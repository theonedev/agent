"""Filesystem traversal, archive handling and change detection inside a sandbox.

Cache archives may preserve sandbox-local links; checkout and artifact paths keep
stricter boundaries. All callers use these functions to apply the same rules.
"""
import fnmatch
import os
from pathlib import Path
import shutil
import tarfile


def safe_path(root, relative, allow_leaf_symlink=False):
    if not isinstance(relative, str) or ".." in relative or Path(relative).is_absolute() or "\\" in relative:
        raise ValueError("Path must be relative and must not contain '..': " + str(relative))
    path = root
    if path.is_symlink():
        raise ValueError("Symbolic link is not allowed: " + str(path))
    parts = Path(relative).parts
    for index, part in enumerate(parts):
        path = path / part
        if path.is_symlink() and not (allow_leaf_symlink and index == len(parts) - 1):
            raise ValueError("Symbolic link is not allowed: " + str(path))
    return path



def matches(path, pattern):
    """Ant-style path matching: ** spans directories, * and ? do not.

    Accepted difference from Ant: fnmatchcase treats bracket expressions as
    character classes, so file[1].txt matches file1.txt instead of file[1].txt.
    Exact Ant compatibility for bracket expressions is intentionally not required.
    """
    parts, patterns = path.split("/"), pattern.rstrip("/").split("/")
    if pattern.endswith("/"):
        patterns.append("**")

    def match(i, j):
        if j == len(patterns):
            return i == len(parts)
        if patterns[j] == "**":
            return match(i, j + 1) or (i < len(parts) and match(i + 1, j))
        return i < len(parts) and fnmatch.fnmatchcase(parts[i], patterns[j]) and match(i + 1, j + 1)

    return match(0, 0)



def walk_directory(root, follow_links=False):
    """Keep missing paths harmless, but never silently omit unreadable directories."""
    def on_error(error):
        if not isinstance(error, FileNotFoundError):
            raise error
    return os.walk(root, followlinks=follow_links, onerror=on_error)



def selected_files(root, includes, excludes, follow_links=False):
    if root.is_symlink():
        raise ValueError("Source directory must not be a symbolic link: " + str(root))
    files = []
    seen = set()
    resolved_root = root.resolve()
    for parent, dirs, names in walk_directory(root, follow_links):
        resolved = Path(parent).resolve()
        if not resolved.is_relative_to(resolved_root):
            raise ValueError("Source directory resolves outside work directory: " + str(parent))
        if resolved in seen:
            dirs[:] = []
            continue
        seen.add(resolved)
        dirs[:] = sorted(d for d in dirs if (follow_links or not (Path(parent) / d).is_symlink())
                         and not any(matches((Path(parent) / d).relative_to(root).as_posix(), p) for p in excludes))
        for name in names:
            path = Path(parent) / name
            relative = path.relative_to(root).as_posix()
            if (path.is_file() and (follow_links or not path.is_symlink())
                    and any(matches(relative, p) for p in includes)
                    and not any(matches(relative, p) for p in excludes)):
                if not path.resolve().is_relative_to(resolved_root):
                    raise ValueError("Source file resolves outside work directory: " + str(path))
                files.append(path)
    return sorted(files)



def unpack(stream, target, allow_external_symlinks=False):
    """Keep extraction writes inside target, optionally preserving VM-local cache links."""
    target.mkdir(parents=True, exist_ok=True)
    with tarfile.open(fileobj=stream, mode="r|*") as archive:
        for member in archive:
            name = member.name.removeprefix("./").rstrip("/")
            if name in ("", "."):
                continue
            dest = safe_path(target, name, allow_leaf_symlink=allow_external_symlinks)
            dest.parent.mkdir(parents=True, exist_ok=True)
            if allow_external_symlinks and dest.is_symlink():
                dest.unlink()
            if member.isdir():
                dest.mkdir(exist_ok=True)
            elif member.isfile():
                with archive.extractfile(member) as source, dest.open("wb") as output:
                    shutil.copyfileobj(source, output)
                dest.chmod(member.mode & 0o777)
                os.utime(dest, (member.mtime, member.mtime))
            elif member.issym():
                if not allow_external_symlinks:
                    link = (dest.parent / member.linkname).resolve()
                    if not link.is_relative_to(target.resolve()):
                        raise ValueError("Archive link escapes target: " + member.name)
                if dest.exists():
                    dest.unlink()
                dest.symlink_to(member.linkname)
            elif member.islnk():
                source = safe_path(target, member.linkname)
                shutil.copyfile(source, dest)
                dest.chmod(member.mode & 0o777)
            else:
                raise ValueError("Unsupported archive entry: " + member.name)



def cache_paths(root, excludes, include_root=False):
    """Include link entries without traversing their targets."""
    for parent, dirs, names in walk_directory(root):
        if include_root and Path(parent) == root:
            yield root
        dirs[:] = sorted(d for d in dirs if not any(
            matches((Path(parent) / d).relative_to(root).as_posix(), p) for p in excludes))
        for name in dirs + sorted(names):
            path = Path(parent) / name
            if not any(matches(path.relative_to(root).as_posix(), p) for p in excludes):
                yield path



def paths_changed(paths, since):
    """Check modification times without following symbolic links to their targets."""
    return any(path.lstat().st_mtime > since for path in paths)



def pack(stream, root, includes, excludes, cache=False):
    with tarfile.open(fileobj=stream, mode="w|", dereference=False) as archive:
        paths = cache_paths(root, excludes) if cache else selected_files(root, includes, excludes)
        for path in paths:
            if not cache and path.is_symlink() and not path.resolve().is_relative_to(root.resolve()):
                raise ValueError("Source link escapes source directory: " + str(path))
            archive.add(path, arcname=path.relative_to(root).as_posix(), recursive=False)

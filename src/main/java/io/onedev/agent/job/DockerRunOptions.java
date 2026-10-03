package io.onedev.agent.job;

import static io.onedev.k8shelper.JobHelper.resolveBuildPath;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.apache.commons.io.FilenameUtils;
import org.jspecify.annotations.Nullable;

import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.StringUtils;

public class DockerRunOptions {

	private static final Set<String> MOUNT_OPTIONS = Set.of("--volume", "--mount", "--volumes-from",
			"--volume-driver", "--device", "--gpus", "--use-api-socket");

	private static final Set<String> FILE_OPTIONS = Set.of("--env-file", "--label-file", "--cidfile");

	// Consume other option values as values, even if they look like a file option.
	private static final Set<String> VALUE_OPTIONS = Set.of(
			"--add-host", "--annotation", "--attach", "--blkio-weight", "--blkio-weight-device",
			"--cap-add", "--cap-drop", "--cgroup-parent", "--cgroupns", "--cpu-count", "--cpu-percent",
			"--cpu-period", "--cpu-quota", "--cpu-rt-period", "--cpu-rt-runtime", "--cpu-shares", "--cpus",
			"--cpuset-cpus", "--cpuset-mems", "--detach-keys", "--device-cgroup-rule",
			"--device-read-bps", "--device-read-iops", "--device-write-bps", "--device-write-iops",
			"--dns", "--dns-opt", "--dns-option", "--dns-search", "--domainname", "--entrypoint", "--env",
			"--expose", "--group-add", "--health-cmd", "--health-interval", "--health-retries",
			"--health-start-interval", "--health-start-period", "--health-timeout", "--hostname",
			"--io-maxbandwidth", "--io-maxiops", "--ip", "--ip6", "--ipc", "--isolation", "--label",
			"--link", "--link-local-ip", "--log-driver", "--log-opt", "--mac-address", "--memory",
			"--memory-reservation", "--memory-swap", "--memory-swappiness", "--name", "--net", "--network",
			"--network-alias", "--oom-score-adj", "--pid", "--pids-limit", "--platform", "--publish",
			"--pull", "--restart", "--runtime", "--shm-size", "--stop-signal", "--stop-timeout",
			"--storage-opt", "--sysctl", "--tmpfs", "--ulimit", "--user", "--userns", "--uts",
			"--workdir");

	/**
	 * Resolve file paths immediately before each job container is run.
	 * Mounts are managed by the executor and cannot be supplied via run options.
	 * These checks reject planted links; they are not atomic with Docker opening the paths.
	 */
	public static List<String> parse(@Nullable String options, File buildDir) {
		var args = new ArrayList<>(Arrays.asList(StringUtils.parseQuoteTokens(options)));
		for (int i = 0; i < args.size(); i++) {
			var arg = args.get(i);
			if (arg.equals("--"))
				throw new ExplicitException("Docker run options must not contain '--'");
			if (arg.startsWith("--")) {
				int equals = arg.indexOf('=');
				var option = equals != -1 ? arg.substring(0, equals) : arg;
				if (MOUNT_OPTIONS.contains(option))
					throw new ExplicitException("Docker run option '" + option + "' is not allowed: volume and device mounts are managed by the executor");
				if (FILE_OPTIONS.contains(option) || option.equals("--security-opt")) {
					var value = equals != -1 ? arg.substring(equals + 1) : nextValue(args, ++i, option);
					value = resolveValue(option, value, buildDir);
					args.set(i, equals != -1 ? option + "=" + value : value);
				} else if (equals == -1 && VALUE_OPTIONS.contains(option)) {
					nextValue(args, ++i, option);
				}
			} else if (arg.startsWith("-")) {
				// Docker permits attached short values and boolean clusters, such as -v./data:/data and -iv... .
				for (int j = 1; j < arg.length(); j++) {
					char option = arg.charAt(j);
					if (option == 'v')
						throw new ExplicitException("Docker run option '-v' is not allowed: volume mounts are managed by the executor");
					if ("acehlmpuw".indexOf(option) != -1) {
						if (j + 1 == arg.length())
							nextValue(args, ++i, "-" + option);
						break;
					}
					if ("ditPq".indexOf(option) == -1 || j + 1 < arg.length() && arg.charAt(j + 1) == '=')
						break;
				}
			}
		}
		return args;
	}

	private static String nextValue(List<String> args, int index, String option) {
		if (index == args.size())
			throw new ExplicitException("Missing value for Docker run option '" + option + "'");
		return args.get(index);
	}

	private static String resolveValue(String option, String value, File buildDir) {
		if (FILE_OPTIONS.contains(option))
			return resolvePath(buildDir, value);
		// Docker accepts both seccomp=path and the older seccomp:path syntax.
		int separator = value.indexOf('=');
		if (separator == -1)
			separator = value.indexOf(':');
		if (separator != -1 && value.substring(0, separator).equals("seccomp")) {
			var profile = value.substring(separator + 1);
			if (!profile.equals("unconfined") && !profile.equals("builtin"))
				return "seccomp=" + resolvePath(buildDir, profile);
		}
		return value;
	}

	private static String resolvePath(File buildDir, String path) {
		// Validate before adding "work/", which would otherwise hide an absolute path.
		if (path.isBlank() || FilenameUtils.getPrefixLength(path) != 0 || path.contains("..")
				|| path.contains(":") || path.contains("\\") || path.indexOf('\0') != -1)
			throw new ExplicitException("Docker run option paths must be relative to the job working directory "
					+ "and must not contain '..': " + path);
		return resolveBuildPath(buildDir, "work/" + path).toPath().toAbsolutePath().normalize().toString();
	}

}

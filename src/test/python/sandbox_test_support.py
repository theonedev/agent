"""Fixtures shared by sandbox unit and Linux permission tests."""
import base64
import importlib.util
import io
import json
from pathlib import Path
import struct
import sys
import tarfile
from http.server import BaseHTTPRequestHandler
from urllib.parse import parse_qs, urlsplit

AGENT = Path(__file__).resolve().parents[2] / 'main/java/io/onedev/agent'
sys.path.insert(0, str(AGENT))
import sandbox


def load_runtime(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def command(text, condition='SUCCESSFUL', optional=False, **extra):
    return dict(type='command', commands=text, condition=condition, optional=optional,
                executable='sh', options=[], extension='.sh', env={},
                pauseCommand='sh "$ONEDEV_WORKDIR/../pause.sh"', **extra)


def archive(files):
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode='w') as tar:
        for name, data in files.items():
            info = tarfile.TarInfo(name)
            info.size = len(data)
            tar.addfile(info, io.BytesIO(data))
    return output.getvalue()


class JobWorker(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def record(self):
        parsed = urlsplit(self.path)
        params = parse_qs(parsed.query)
        assert params['token'] == ['job-secret']
        self.server.requests.append((self.command, parsed.path, params))
        return parsed.path, params

    def reply(self, data=b''):
        self.send_response(200)
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        path, params = self.record()
        if path.endswith('/dependencies'):
            self.reply(archive({'dependency.txt': b'dependency'}))
        elif path.endswith('/test'):
            if getattr(self.server, 'test_failure', False):
                self.send_error(503)
            else:
                self.reply()
        elif path.endswith('/job-cache'):
            self.reply(self.server.cache)
        else:
            self.send_error(404)

    def do_PUT(self):
        self.record()
        self.reply()

    def do_HEAD(self):
        _, params = self.record()
        # OneDev rejects query parameters not declared by the endpoint. The
        # permission check accepts only the token and optional target project.
        if set(params) - {'token', 'projectPath'}:
            self.send_error(406)
            return
        if getattr(self.server, 'deny_cache_upload', False):
            self.send_error(401)
            return
        self.reply()

    def do_POST(self):
        path, params = self.record()
        body = io.BytesIO(self.rfile.read(int(self.headers['Content-Length'])))
        if path.endswith('/run-server-step'):
            assert params['resultFormat'] == ['json']
            position = [sandbox.read_int(body) for _ in range(sandbox.read_int(body))]
            placeholders = {}
            for _ in range(sandbox.read_int(body)):
                key = sandbox.read_exact(body, sandbox.read_int(body)).decode()
                placeholders[key] = sandbox.read_exact(body, sandbox.read_int(body)).decode()
            with tarfile.open(fileobj=body) as tar:
                files = {member.name: tar.extractfile(member).read() for member in tar if member.isfile()}
            self.server.steps.append((position, placeholders, files))
            result = json.dumps({'successful': True, 'outputFiles': {'build-version': base64.b64encode(b'2.0').decode()}}).encode()
            message = b'Published artifacts'
            self.reply(struct.pack('>ii', 1, len(message)) + message + struct.pack('>ii', 2, len(result)) + result)
        else:
            with tarfile.open(fileobj=body) as tar:
                self.server.uploads.append({member.name: member.linkname if member.issym() else
                                           tar.extractfile(member).read() if member.isfile() else None for member in tar})
            self.reply()

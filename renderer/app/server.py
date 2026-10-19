"""Minimal render service: POST /render with a LaTeX document as the body.

200 application/pdf (+ X-Page-Count) on success; 422 JSON {log} on a compile error;
413 over RENDER_MAX_BYTES; 504 when the hard timeout kills pdflatex; GET /health.
Stateless: each request compiles in a fresh temp directory that is always removed.
"""
import json
import os
import re
import shutil
import signal
import subprocess
import tempfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TIMEOUT = int(os.environ.get("RENDER_TIMEOUT_SECONDS", "20"))
MAX_BYTES = int(os.environ.get("RENDER_MAX_BYTES", "1000000"))
PORT = int(os.environ.get("PORT", "8080"))
WORK_ROOT = os.environ.get("RENDER_WORK_ROOT", "/work")
MAX_LOG = 20000


def compile_tex(source: bytes):
    """Returns (status, pdf_or_None, log, pages)."""
    workdir = tempfile.mkdtemp(prefix="job-", dir=WORK_ROOT)
    try:
        with open(os.path.join(workdir, "doc.tex"), "wb") as f:
            f.write(source)
        cmd = ["pdflatex", "-no-shell-escape", "-interaction=nonstopmode", "-halt-on-error",
               "-output-directory", workdir, "doc.tex"]
        proc = subprocess.Popen(cmd, cwd=workdir, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, start_new_session=True)
        try:
            out, _ = proc.communicate(timeout=TIMEOUT)
        except subprocess.TimeoutExpired:
            os.killpg(proc.pid, signal.SIGKILL)
            proc.communicate()
            return 504, None, "render timed out", None
        log = out.decode("utf-8", "replace")[-MAX_LOG:]
        pdf_path = os.path.join(workdir, "doc.pdf")
        if proc.returncode != 0 or not os.path.exists(pdf_path):
            return 422, None, log, None
        with open(pdf_path, "rb") as f:
            pdf = f.read()
        m = re.search(r"Output written on .*? \((\d+) pages?", log)
        return 200, pdf, log, int(m.group(1)) if m else None
    finally:
        shutil.rmtree(workdir, ignore_errors=True)


class Handler(BaseHTTPRequestHandler):
    def _json(self, status, obj):
        body = json.dumps(obj).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/health":
            self._json(200, {"status": "UP"})
        else:
            self._json(404, {"error": "not found"})

    def do_POST(self):
        if self.path != "/render":
            return self._json(404, {"error": "not found"})
        try:
            length = int(self.headers.get("Content-Length", ""))
        except ValueError:
            return self._json(411, {"error": "Content-Length required"})
        if length > MAX_BYTES:
            return self._json(413, {"error": "document too large"})
        status, pdf, log, pages = compile_tex(self.rfile.read(length))
        if status == 200:
            self.send_response(200)
            self.send_header("Content-Type", "application/pdf")
            self.send_header("Content-Length", str(len(pdf)))
            if pages is not None:
                self.send_header("X-Page-Count", str(pages))
            self.end_headers()
            self.wfile.write(pdf)
        else:
            self._json(status, {"log": log})

    def log_message(self, fmt, *args):  # keep document content out of logs
        pass


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()

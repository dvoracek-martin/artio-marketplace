"""Small executable implementation of the pinned Artio extension controller protocol."""

import hmac
import json
import os
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


MAX_REQUEST_BYTES = 1024 * 1024
IDEMPOTENCY_KEY = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")
_results = {}
_results_lock = threading.Lock()


class ExtensionHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/health":
            self._respond(200, {"status": "UP"})
            return
        if self.path == "/diagnostics":
            self._respond(200, {"runtimeVersion": os.environ["ARTIO_REFERENCE_VERSION"]})
            return
        self._respond(404, {"error": "not_found"})

    def do_POST(self):
        if self.path != "/v1/invoke":
            self._respond(404, {"error": "not_found"})
            return
        expected = "Bearer " + os.environ.get("ARTIO_EXTENSION_TOKEN", "")
        if not hmac.compare_digest(self.headers.get("Authorization", ""), expected):
            self._respond(401, {"error": "unauthorized"})
            return
        if self.headers.get("X-Artio-Extension-Api") != "1":
            self._respond(400, {"error": "unsupported_protocol"})
            return
        key = self.headers.get("Idempotency-Key", "")
        if not IDEMPOTENCY_KEY.fullmatch(key):
            self._respond(400, {"error": "invalid_idempotency_key"})
            return
        try:
            length = int(self.headers.get("Content-Length", "-1"))
        except ValueError:
            length = -1
        if length < 0 or length > MAX_REQUEST_BYTES:
            self._respond(413, {"error": "invalid_request_size"})
            return
        try:
            envelope = json.loads(self.rfile.read(length))
        except (json.JSONDecodeError, UnicodeDecodeError):
            self._respond(400, {"error": "invalid_json"})
            return
        if (envelope.get("protocolVersion") != 1 or envelope.get("idempotencyKey") != key
                or not isinstance(envelope.get("extensionPoint"), str)
                or not isinstance(envelope.get("operation"), str)
                or not isinstance(envelope.get("configuration"), dict)
                or not isinstance(envelope.get("payload"), dict)):
            self._respond(400, {"error": "invalid_envelope"})
            return
        if envelope["operation"] == "slow":
            time.sleep(1)

        # This fixture has no side effects; recording the response makes retries deterministic.
        with _results_lock:
            response = _results.setdefault(key, {
                "runtimeVersion": os.environ["ARTIO_REFERENCE_VERSION"],
                "extensionPoint": envelope["extensionPoint"],
                "operation": envelope["operation"],
                "payload": envelope["payload"],
            })
        self._respond(200, response)

    def _respond(self, status, value):
        body = json.dumps(value, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_):
        return


ThreadingHTTPServer(("0.0.0.0", 8080), ExtensionHandler).serve_forever()

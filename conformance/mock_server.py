#!/usr/bin/env python3
"""Local mock of the Blindrelay API for the SDK conformance suite. Standard library only.

Each SDK runner sets its base URL to `<server>/case/<case name>`, so a request selects its case
with nothing but the base URL. The wire checks live here, once, so eight runners cannot disagree
about what a correct request is. After each call the runner reads `GET /__verdict/<case name>`:
`{"received": <requests since the last read>, "mismatches": [...]}`.

Binds to 127.0.0.1 only and prints `listening on http://127.0.0.1:<port>` when ready.
"""

import argparse
import json
import pathlib
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qsl, urlsplit

CASES_FILE = pathlib.Path(__file__).parent / "cases" / "http.json"
CASE_PREFIX = "/case/"
VERDICT_PREFIX = "/__verdict/"
USER_AGENT_PATTERN = re.compile(r"^blindrelay-(node|python|go|php|ruby|java|dotnet|rust)/0\.1\.0")
JSON_MEDIA_TYPE = "application/json"

suite = json.loads(CASES_FILE.read_text(encoding="utf-8"))
cases_by_name = {case["name"]: case for case in suite["cases"]}
expected_authorization = f"Bearer {suite['api_key']}"

verdict_lock = threading.Lock()
received_counts: dict[str, int] = {}
recorded_mismatches: dict[str, list[str]] = {}


def record(name: str, mismatches: list[str]) -> None:
    with verdict_lock:
        received_counts[name] = received_counts.get(name, 0) + 1
        recorded_mismatches.setdefault(name, []).extend(mismatches)


def take_verdict(name: str) -> dict:
    with verdict_lock:
        return {
            "received": received_counts.pop(name, 0),
            "mismatches": recorded_mismatches.pop(name, []),
        }


def media_type(value: str | None) -> str | None:
    return value.split(";")[0].strip().lower() if value else None


def common_header_mismatches(headers, has_body: bool) -> list[str]:
    mismatches = []
    if headers.get("Authorization") != expected_authorization:
        mismatches.append("authorization header is not the expected bearer key")
    if not USER_AGENT_PATTERN.match(headers.get("User-Agent", "")):
        mismatches.append("user-agent is not blindrelay-<language>/0.1.0")
    if media_type(headers.get("Accept")) != JSON_MEDIA_TYPE:
        mismatches.append("accept is not application/json")
    if has_body and media_type(headers.get("Content-Type")) != JSON_MEDIA_TYPE:
        mismatches.append("content-type is not application/json")
    return mismatches


def request_mismatches(case: dict, method: str, target: str, headers, raw_body: bytes) -> list[str]:
    expected = case["expect_request"]
    split = urlsplit(target)
    mismatches = common_header_mismatches(headers, "body" in expected)
    if method != expected["method"]:
        mismatches.append(f"method {method}, expected {expected['method']}")
    if split.path != expected["path"]:
        mismatches.append(f"path {split.path}, expected {expected['path']}")
    query = dict(parse_qsl(split.query, keep_blank_values=True))
    if query != expected.get("query", {}):
        mismatches.append(f"query {query}, expected {expected.get('query', {})}")
    for name, value in expected.get("headers", {}).items():
        if headers.get(name) != value:
            mismatches.append(f"header {name} is {headers.get(name)!r}, expected {value!r}")
    for name in expected.get("absent_headers", []):
        if headers.get(name) is not None:
            mismatches.append(f"header {name} must be absent")
    mismatches.extend(body_mismatches(expected, raw_body))
    return mismatches


def body_mismatches(expected: dict, raw_body: bytes) -> list[str]:
    if "body" not in expected:
        return ["unexpected request body"] if raw_body else []
    try:
        body = json.loads(raw_body)
    except ValueError:
        return ["request body is not JSON"]
    return [] if body == expected["body"] else [f"body {body}, expected {expected['body']}"]


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args) -> None:
        pass

    def read_body(self) -> bytes:
        if "chunked" in self.headers.get("Transfer-Encoding", "").lower():
            chunks = []
            while (size := int(self.rfile.readline().strip() or b"0", 16)) > 0:
                chunks.append(self.rfile.read(size))
                self.rfile.readline()
            self.rfile.readline()
            return b"".join(chunks)
        return self.rfile.read(int(self.headers.get("Content-Length") or 0))

    def respond(self, status: int, payload: bytes, headers: dict[str, str]) -> None:
        self.send_response(status)
        for name, value in headers.items():
            self.send_header(name, value)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def respond_json(self, status: int, value) -> None:
        self.respond(status, json.dumps(value).encode(), {"Content-Type": JSON_MEDIA_TYPE})

    def serve_case(self, method: str) -> None:
        name, _, rest = self.path[len(CASE_PREFIX):].partition("/")
        case = cases_by_name.get(name)
        raw_body = self.read_body()
        if case is None:
            self.respond_json(404, {"error": {"code": "unknown_case", "message": name}})
            return
        record(name, request_mismatches(case, method, "/" + rest, self.headers, raw_body))
        response = case["response"]
        time.sleep(response.get("delay_ms", 0) / 1000)
        headers = {"Content-Type": JSON_MEDIA_TYPE, **response.get("headers", {})}
        payload = (
            response["body_text"].encode()
            if "body_text" in response
            else json.dumps(response["body"]).encode()
        )
        try:
            self.respond(response["status"], payload, headers)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def dispatch(self, method: str) -> None:
        if method == "GET" and self.path.startswith(VERDICT_PREFIX):
            self.respond_json(200, take_verdict(self.path[len(VERDICT_PREFIX):]))
        elif self.path.startswith(CASE_PREFIX):
            self.serve_case(method)
        else:
            self.respond_json(404, {"error": {"code": "not_a_case", "message": self.path}})

    def do_GET(self) -> None:
        self.dispatch("GET")

    def do_POST(self) -> None:
        self.dispatch("POST")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=0, help="0 picks a free port")
    arguments = parser.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", arguments.port), Handler)
    print(f"listening on http://127.0.0.1:{server.server_address[1]}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()

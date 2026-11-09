import base64
import dataclasses
import json
import os
import pathlib
import sys
import unittest
import urllib.request

sys.path.insert(0, str(pathlib.Path(__file__).parent.parent / "src"))

import blindrelay  # noqa: E402

SERVER_URL = os.environ.get("BLINDRELAY_CONFORMANCE_URL")
if not SERVER_URL:
    raise SystemExit("Set BLINDRELAY_CONFORMANCE_URL (see sdks/conformance/run-all.sh)")

CASES_DIR = pathlib.Path(__file__).parent.parent.parent / "conformance" / "cases"
HTTP = json.loads((CASES_DIR / "http.json").read_text(encoding="utf-8"))
WEBHOOKS = json.loads((CASES_DIR / "webhooks.json").read_text(encoding="utf-8"))
UNREACHABLE_URL = "http://127.0.0.1:1"
API_KEY = HTTP["api_key"]


def client_for(case):
    options = case.get("client", {})
    base_url = UNREACHABLE_URL if options.get("no_server") else f"{SERVER_URL}/case/{case['name']}"
    if options.get("trailing_slash"):
        base_url += "/"
    timeout = options.get("timeout_ms")
    kwargs = {} if timeout is None else {"timeout": timeout / 1000}
    return blindrelay.Client(API_KEY, base_url=base_url, **kwargs)


def to_attachment(raw):
    if "from_bytes_base64" in raw:
        return blindrelay.Attachment.from_bytes(
            raw["filename"], raw["content_type"], base64.b64decode(raw["from_bytes_base64"])
        )
    return blindrelay.Attachment(raw["filename"], raw["content_type"], raw["content_base64"])


def invoke(client, case):
    inputs = dict(case["input"])
    operation = case["operation"]
    if operation == "get_email":
        return client.get_email(inputs["id"])
    if operation == "list_emails":
        return client.list_emails(**inputs)
    inputs["from_"] = inputs.pop("from")
    if "attachments" in inputs:
        inputs["attachments"] = [to_attachment(raw) for raw in inputs["attachments"]]
    if "tags" in inputs:
        inputs["tags"] = [blindrelay.Tag(**raw) for raw in inputs["tags"]]
    return client.send(**inputs)


class Conformance(unittest.TestCase):
    def assert_error(self, error, expected):
        if expected["kind"] == "timeout":
            self.assertIsInstance(error, blindrelay.BlindrelayTimeoutError)
            return
        if expected["kind"] == "connection":
            self.assertIsInstance(error, blindrelay.BlindrelayConnectionError)
            return
        self.assertIsInstance(error, blindrelay.BlindrelayApiError)
        self.assertEqual(error.status, expected["status"])
        self.assertEqual(error.code, expected["code"])
        self.assertEqual(error.message, expected["message"])
        self.assertEqual(error.retry_after, expected["retry_after"])
        self.assertNotIn(API_KEY, repr(error) + str(error))

    def assert_wire(self, case):
        if case.get("client", {}).get("no_server"):
            return
        with urllib.request.urlopen(f"{SERVER_URL}/__verdict/{case['name']}") as response:
            self.assertEqual(json.loads(response.read()), {"received": 1, "mismatches": []})

    def run_http_case(self, case):
        try:
            result = invoke(client_for(case), case)
        except blindrelay.BlindrelayError as error:
            if "expect_error" not in case:
                raise
            self.assert_error(error, case["expect_error"])
        else:
            self.assertNotIn("expect_error", case)
            self.assertEqual(dataclasses.asdict(result), case["expect_result"])
        self.assert_wire(case)

    def test_http_cases(self):
        for case in HTTP["cases"]:
            with self.subTest(case["name"]):
                self.run_http_case(case)

    def test_webhook_cases(self):
        for case in WEBHOOKS["cases"]:
            with self.subTest(case["name"]):
                payload = (
                    base64.b64decode(case["body_base64"]) if "body_base64" in case else case["body"]
                )
                extra = {}
                if "tolerance_seconds" in case:
                    extra["tolerance_seconds"] = case["tolerance_seconds"]
                self.assertEqual(
                    blindrelay.verify_webhook(
                        payload=payload,
                        signature_header=case["signature_header"],
                        delivery_id=case["delivery_id"],
                        secret=case["secret"],
                        now=case["now"],
                        **extra,
                    ),
                    case["valid"],
                )

    def test_default_base_url(self):
        self.assertEqual(blindrelay.DEFAULT_BASE_URL, "https://api.blindrelay.app")

    def test_client_string_form_hides_api_key(self):
        client = blindrelay.Client(API_KEY)
        self.assertNotIn(API_KEY, repr(client) + str(client))


if __name__ == "__main__":
    unittest.main()

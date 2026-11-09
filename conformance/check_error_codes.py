#!/usr/bin/env python3
"""Every API error code the conformance cases use must be a named constant in every SDK."""

import json
import pathlib
import sys

SDKS_DIR = pathlib.Path(__file__).parent.parent
PASS_THROUGH_CODE = "some_future_code"
SOURCES_BY_LANGUAGE = {
    "node": "node/src/index.ts",
    "python": "python/src/blindrelay/_errors.py",
    "go": "go/errors.go",
    "php": "php/src/ErrorCode.php",
    "ruby": "ruby/lib/blindrelay/errors.rb",
    "java": "java/src/main/java/app/blindrelay/ErrorCode.java",
    "dotnet": "dotnet/Blindrelay/Errors.cs",
    "rust": "rust/src/error.rs",
}

cases = json.loads((SDKS_DIR / "conformance/cases/http.json").read_text(encoding="utf-8"))["cases"]
codes = {
    case["expect_error"]["code"]
    for case in cases
    if case.get("expect_error", {}).get("kind") == "api"
} - {PASS_THROUGH_CODE}

missing = [
    f"{language}: {code}"
    for language, source in SOURCES_BY_LANGUAGE.items()
    for code in sorted(codes)
    if f'"{code}"' not in (text := (SDKS_DIR / source).read_text(encoding="utf-8")) and f"'{code}'" not in text
]
if missing:
    sys.exit("Error codes missing from SDK constants:\n" + "\n".join(missing))
print(f"{len(codes)} error codes are named in all {len(SOURCES_BY_LANGUAGE)} SDKs")

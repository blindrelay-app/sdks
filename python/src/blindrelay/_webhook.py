import hashlib
import hmac
import re
import time
from typing import Optional, Tuple, Union

DEFAULT_TOLERANCE_SECONDS = 300
_TIMESTAMP_PATTERN = re.compile(r"(0|[1-9][0-9]*)")


def _parse_signature_header(header: str) -> Optional[Tuple[str, list]]:
    timestamp = None
    signatures = []
    for part in header.split(","):
        key, separator, value = part.partition("=")
        if not separator:
            continue
        key, value = key.strip(), value.strip()
        if key == "t" and timestamp is None:
            timestamp = value
        elif key == "v1":
            signatures.append(value)
    return (timestamp, signatures) if timestamp is not None and signatures else None


def verify_webhook(
    *,
    payload: Union[bytes, str],
    signature_header: str,
    delivery_id: str,
    secret: str,
    tolerance_seconds: int = DEFAULT_TOLERANCE_SECONDS,
    now: Optional[int] = None,
) -> bool:
    """Verify a delivery webhook: ``v1`` is hex HMAC-SHA256 over ``<t>.<delivery_id>.`` + raw body.

    ``payload`` is the request body exactly as received. A header can carry several ``v1`` values
    during a secret rotation; one match is enough. Inbound endpoint deliveries sign a different
    string and do not verify here.
    """
    parsed = _parse_signature_header(signature_header)
    if parsed is None:
        return False
    timestamp, signatures = parsed
    if _TIMESTAMP_PATTERN.fullmatch(timestamp) is None:
        return False
    current = int(time.time()) if now is None else now
    if abs(current - int(timestamp)) > tolerance_seconds:
        return False
    body = payload.encode("utf-8") if isinstance(payload, str) else payload
    signed = f"{timestamp}.{delivery_id}.".encode("utf-8") + body
    expected = hmac.new(secret.encode("utf-8"), signed, hashlib.sha256).hexdigest()
    return any(hmac.compare_digest(signature.encode(), expected.encode()) for signature in signatures)

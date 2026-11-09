import base64
import json
import socket
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from typing import Any, Dict, List, Mapping, Optional, Sequence, Union

from ._errors import (
    BlindrelayApiError,
    BlindrelayConnectionError,
    BlindrelayError,
    BlindrelayTimeoutError,
    ErrorCode,
)

SDK_VERSION = "0.1.0"
DEFAULT_BASE_URL = "https://api.blindrelay.app"
DEFAULT_TIMEOUT_SECONDS = 30.0
_USER_AGENT = f"blindrelay-python/{SDK_VERSION}"
_JSON_MEDIA_TYPE = "application/json"
_TIMEOUT_ERRORS = (TimeoutError, socket.timeout)

Recipients = Union[str, Sequence[str]]


@dataclass(frozen=True)
class Attachment:
    filename: str
    content_type: str
    content_base64: str = field(repr=False)

    @classmethod
    def from_bytes(cls, filename: str, content_type: str, data: bytes) -> "Attachment":
        return cls(filename, content_type, base64.b64encode(data).decode("ascii"))


@dataclass(frozen=True)
class Tag:
    key: str
    value: str


@dataclass(frozen=True)
class SendResult:
    id: str
    ids: List[str]
    status: str
    warnings: List[str]


@dataclass(frozen=True)
class EmailStatus:
    id: str
    status: str
    summary: str
    final: bool
    accepted_at: Optional[str]
    updated_at: str
    lookup_window_hours: int
    sandbox: bool


@dataclass(frozen=True)
class EmailListItem:
    id: str
    status: str
    summary: str
    final: bool
    domain: Optional[str]
    api_key_id: Optional[str]
    accepted_at: Optional[str]
    updated_at: str
    sandbox: bool


@dataclass(frozen=True)
class EmailList:
    emails: List[EmailListItem]
    next_cursor: Optional[str]
    lookup_window_hours: int


def _recipients_to_wire(recipients: Recipients) -> Union[str, List[str]]:
    return recipients if isinstance(recipients, str) else list(recipients)


def _email_status(body: Mapping[str, Any]) -> EmailStatus:
    return EmailStatus(
        id=body["id"],
        status=body["status"],
        summary=body["summary"],
        final=body["final"],
        accepted_at=body.get("accepted_at"),
        updated_at=body["updated_at"],
        lookup_window_hours=body["lookup_window_hours"],
        sandbox=body["sandbox"],
    )


def _email_list_item(item: Mapping[str, Any]) -> EmailListItem:
    return EmailListItem(
        id=item["id"],
        status=item["status"],
        summary=item["summary"],
        final=item["final"],
        domain=item.get("domain"),
        api_key_id=item.get("api_key_id"),
        accepted_at=item.get("accepted_at"),
        updated_at=item["updated_at"],
        sandbox=item["sandbox"],
    )


def _parse_retry_after(header: Optional[str], envelope: Mapping[str, Any]) -> Optional[int]:
    if header is not None and header.strip().isdigit():
        return int(header.strip())
    seconds = envelope.get("retry_after_secs")
    return seconds if isinstance(seconds, int) else None


def _api_error(status: int, retry_after: Optional[str], raw: bytes) -> BlindrelayApiError:
    try:
        parsed = json.loads(raw)
    except ValueError:
        parsed = None
    envelope = parsed.get("error") if isinstance(parsed, dict) else None
    envelope = envelope if isinstance(envelope, dict) else {}
    code = envelope.get("code")
    fallback_message = f"HTTP {status}"
    known = isinstance(code, str)
    return BlindrelayApiError(
        status,
        code if known else ErrorCode.UNKNOWN_ERROR,
        envelope.get("message", fallback_message) if known else fallback_message,
        _parse_retry_after(retry_after, envelope),
    )


class Client:
    def __init__(
        self,
        api_key: str,
        *,
        base_url: str = DEFAULT_BASE_URL,
        timeout: float = DEFAULT_TIMEOUT_SECONDS,
    ):
        self._api_key = api_key
        self._base_url = base_url.rstrip("/")
        self._timeout = timeout

    def __repr__(self) -> str:
        return f"Client(base_url={self._base_url!r})"

    def send(
        self,
        *,
        from_: str,
        to: Recipients,
        subject: str,
        html: str,
        text: Optional[str] = None,
        headers: Optional[Mapping[str, str]] = None,
        cc: Optional[Recipients] = None,
        bcc: Optional[Recipients] = None,
        attachments: Optional[Sequence[Attachment]] = None,
        tags: Optional[Sequence[Tag]] = None,
        idempotency_key: Optional[str] = None,
    ) -> SendResult:
        body: Dict[str, Any] = {
            "from": from_,
            "to": _recipients_to_wire(to),
            "subject": subject,
            "html": html,
        }
        if text is not None:
            body["text"] = text
        if headers is not None:
            body["headers"] = dict(headers)
        if cc is not None:
            body["cc"] = _recipients_to_wire(cc)
        if bcc is not None:
            body["bcc"] = _recipients_to_wire(bcc)
        if attachments is not None:
            body["attachments"] = [
                {
                    "filename": attachment.filename,
                    "content_type": attachment.content_type,
                    "content_base64": attachment.content_base64,
                }
                for attachment in attachments
            ]
        if tags is not None:
            body["tags"] = [{"key": tag.key, "value": tag.value} for tag in tags]
        answer = self._request("POST", "/v1/send", body=body, idempotency_key=idempotency_key)
        return SendResult(
            id=answer["id"],
            ids=answer.get("ids", []),
            status=answer["status"],
            warnings=answer.get("warnings", []),
        )

    def get_email(self, email_id: str) -> EmailStatus:
        path = "/v1/emails/" + urllib.parse.quote(email_id, safe="")
        return _email_status(self._request("GET", path))

    def list_emails(
        self,
        *,
        status: Optional[str] = None,
        domain: Optional[str] = None,
        page_size: Optional[int] = None,
        cursor: Optional[str] = None,
    ) -> EmailList:
        filters = {"status": status, "domain": domain, "page_size": page_size, "cursor": cursor}
        query = urllib.parse.urlencode({k: v for k, v in filters.items() if v is not None})
        answer = self._request("GET", "/v1/emails" + (f"?{query}" if query else ""))
        return EmailList(
            emails=[_email_list_item(item) for item in answer["emails"]],
            next_cursor=answer.get("next_cursor"),
            lookup_window_hours=answer["lookup_window_hours"],
        )

    def _request(
        self,
        method: str,
        path: str,
        body: Optional[Mapping[str, Any]] = None,
        idempotency_key: Optional[str] = None,
    ) -> Dict[str, Any]:
        request = urllib.request.Request(
            self._base_url + path,
            method=method,
            headers=self._headers(body is not None, idempotency_key),
            data=None if body is None else json.dumps(body).encode("utf-8"),
        )
        try:
            with urllib.request.urlopen(request, timeout=self._timeout) as response:
                return json.loads(response.read())
        except urllib.error.HTTPError as error:
            raise self._read_api_error(error) from None
        except urllib.error.URLError as error:
            raise self._transport_error(error.reason) from None
        except (*_TIMEOUT_ERRORS, OSError) as error:
            raise self._transport_error(error) from None

    def _headers(self, has_body: bool, idempotency_key: Optional[str]) -> Dict[str, str]:
        headers = {
            "Authorization": f"Bearer {self._api_key}",
            "Accept": _JSON_MEDIA_TYPE,
            "User-Agent": _USER_AGENT,
        }
        if has_body:
            headers["Content-Type"] = _JSON_MEDIA_TYPE
        if idempotency_key is not None:
            headers["Idempotency-Key"] = idempotency_key
        return headers

    @staticmethod
    def _read_api_error(error: urllib.error.HTTPError) -> BlindrelayError:
        with error:
            try:
                raw = error.read()
            except _TIMEOUT_ERRORS:
                return BlindrelayTimeoutError("The request to Blindrelay timed out")
        return _api_error(error.code, error.headers.get("Retry-After"), raw)

    @staticmethod
    def _transport_error(reason: object) -> BlindrelayError:
        if isinstance(reason, _TIMEOUT_ERRORS):
            return BlindrelayTimeoutError("The request to Blindrelay timed out")
        return BlindrelayConnectionError("Could not reach Blindrelay")

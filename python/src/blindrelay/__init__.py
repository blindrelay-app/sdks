"""Official Python SDK for the Blindrelay transactional email API."""

from ._client import (
    DEFAULT_BASE_URL,
    Attachment,
    Client,
    EmailList,
    EmailListItem,
    EmailStatus,
    SendResult,
    Tag,
)
from ._errors import (
    BlindrelayApiError,
    BlindrelayConnectionError,
    BlindrelayError,
    BlindrelayTimeoutError,
    ErrorCode,
)
from ._webhook import verify_webhook

__version__ = "0.1.0"

__all__ = [
    "DEFAULT_BASE_URL",
    "Attachment",
    "BlindrelayApiError",
    "BlindrelayConnectionError",
    "BlindrelayError",
    "BlindrelayTimeoutError",
    "Client",
    "EmailList",
    "EmailListItem",
    "EmailStatus",
    "ErrorCode",
    "SendResult",
    "Tag",
    "verify_webhook",
]

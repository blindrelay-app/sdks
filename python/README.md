# Blindrelay SDK for Python

`pip install blindrelay`. Needs Python 3.9+; no dependencies.

## Send

```python
import os
import blindrelay

client = blindrelay.Client(os.environ["BLINDRELAY_API_KEY"])

result = client.send(
    from_="noreply@example.com",
    to=["user@example.com"],
    subject="Reset your password",
    html='<p>Click <a href="https://example.com/reset">here</a>.</p>',
    tags=[blindrelay.Tag("flow", "password_reset")],
    idempotency_key="reset-user-42",
)
print(result.id, result.status)
```

Options: `Client(key, base_url=..., timeout=30.0)`. Attachments: `blindrelay.Attachment.from_bytes("report.pdf", "application/pdf", data)`.

## Status and paging

```python
email = client.get_email(result.id)
page = client.list_emails(status="delivered", page_size=25, cursor=previous.next_cursor)
```

## Errors

API failures raise `BlindrelayApiError` with `status`, `code`, `message` and `retry_after` (seconds or `None`). Compare `code` with `ErrorCode`, for example `ErrorCode.RATE_LIMITED`; new codes may appear. A body without an error envelope gives `unknown_error`. Timeouts raise `BlindrelayTimeoutError` and network failures `BlindrelayConnectionError`. The SDK does not retry on its own.

## Webhook verification

Use the raw body, before any JSON parsing:

```python
ok = blindrelay.verify_webhook(
    payload=request.body,
    signature_header=request.headers["Blindrelay-Signature"],
    delivery_id=request.headers["Blindrelay-Delivery-Id"],
    secret=os.environ["BLINDRELAY_WEBHOOK_SECRET"],
)
```

`tolerance_seconds` defaults to 300.

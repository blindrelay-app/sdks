# Blindrelay SDK for Ruby

**Not published yet.** Once it is: `gem install blindrelay`. Needs Ruby 3.0+; no dependencies.

## Send

The method is `send_email` because `send` is `Object#send` in Ruby.

```ruby
require "blindrelay"

client = Blindrelay::Client.new(ENV.fetch("BLINDRELAY_API_KEY"))

result = client.send_email(
  from: "noreply@example.com",
  to: ["user@example.com"],
  subject: "Reset your password",
  html: '<p>Click <a href="https://example.com/reset">here</a>.</p>',
  tags: [{ key: "flow", value: "password_reset" }],
  idempotency_key: "reset-user-42"
)
puts result.id
```

Options: `Client.new(key, base_url: url, timeout: 30)`. Attachments: `Blindrelay::Attachment.from_bytes("report.pdf", "application/pdf", bytes)`.

## Status and paging

```ruby
email = client.get_email(result.id)
page = client.list_emails(status: "delivered", page_size: 25, cursor: previous.next_cursor)
```

## Errors

API failures raise `Blindrelay::ApiError` with `status`, `code`, `message` and `retry_after` (seconds or `nil`). Compare `code` with `Blindrelay::ErrorCode`, for example `ErrorCode::RATE_LIMITED`; new codes may appear. A body without an error envelope gives `unknown_error`. Timeouts raise `Blindrelay::TimeoutError` and network failures `Blindrelay::ConnectionError`. The SDK does not retry on its own.

## Webhook verification

Use the raw body:

```ruby
ok = Blindrelay::Webhook.verify(
  payload: request.raw_post,
  signature_header: request.headers["Blindrelay-Signature"],
  delivery_id: request.headers["Blindrelay-Delivery-Id"],
  secret: ENV.fetch("BLINDRELAY_WEBHOOK_SECRET")
)
```

`tolerance_seconds:` defaults to 300.

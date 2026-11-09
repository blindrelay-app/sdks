# Blindrelay SDK for Rust

**Not published yet** (`publish = false` in `Cargo.toml`). Once it is: `cargo add blindrelay`. Async, built on reqwest with rustls.

## Send

```rust
use blindrelay::{Client, SendRequest, Tag};

let client = Client::new(std::env::var("BLINDRELAY_API_KEY")?)?;

let request = SendRequest::new(
    "noreply@example.com",
    "user@example.com",
    "Reset your password",
    r#"<p>Click <a href="https://example.com/reset">here</a>.</p>"#,
)
.tags(vec![Tag { key: "flow".into(), value: "password_reset".into() }])
.idempotency_key("reset-user-42");

let result = client.send(&request).await?;
```

`to`, `cc` and `bcc` take a string or a `Vec`. Options: `Client::builder(key).base_url(url).timeout(duration).build()?`. Attachments: `Attachment::from_bytes("report.pdf", "application/pdf", &bytes)`.

## Status and paging

```rust
let email = client.get_email(&result.id).await?;
let page = client
    .list_emails(&ListEmailsParams { status: Some("delivered".into()), page_size: Some(25), ..Default::default() })
    .await?;
```

`page.next_cursor` is `None` on the last page.

## Errors

```rust
match client.send(&request).await {
    Err(blindrelay::Error::Api(error)) if error.code == blindrelay::code::RATE_LIMITED => {
        // error.retry_after is seconds to wait, or None
    }
    other => { /* ... */ }
}
```

`Error::Timeout` and `Error::Connection` cover transport failures. New codes may appear; a body without an error envelope gives `code::UNKNOWN_ERROR`. The SDK does not retry on its own.

## Webhook verification

Use the raw body:

```rust
let ok = blindrelay::verify_webhook(&blindrelay::VerifyWebhookParams {
    payload: &raw_body,
    signature_header: &signature_header,
    delivery_id: &delivery_id,
    secret: &secret,
    tolerance_seconds: None, // 300
    now: None,
});
```

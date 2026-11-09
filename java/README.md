# Blindrelay SDK for Java

**Not published yet.** Once it is, the Maven coordinate will be `app.blindrelay:blindrelay:0.1.0`. Needs Java 17+; one dependency, Gson.

## Send

```java
Blindrelay client = Blindrelay.create(System.getenv("BLINDRELAY_API_KEY"));

SendResult result = client.send(SendRequest.builder()
    .from("noreply@example.com")
    .to(List.of("user@example.com"))
    .subject("Reset your password")
    .html("<p>Click <a href=\"https://example.com/reset\">here</a>.</p>")
    .tags(List.of(new Tag("flow", "password_reset")))
    .idempotencyKey("reset-user-42")
    .build());
```

Options: `Blindrelay.builder(key).baseUrl(url).timeout(Duration.ofSeconds(30)).build()`. Attachments: `Attachment.fromBytes("report.pdf", "application/pdf", bytes)`.

## Status and paging

```java
EmailStatus email = client.getEmail(result.id());
EmailList page = client.listEmails(new ListEmailsParams("delivered", null, 25, null));
```

`page.nextCursor()` is null on the last page.

## Errors

API failures throw `BlindrelayApiException` with `status()`, `code()`, `getMessage()` and `retryAfter()` (seconds or null). Compare `code()` with `ErrorCode`, for example `ErrorCode.RATE_LIMITED`; new codes may appear. A body without an error envelope gives `unknown_error`. Timeouts throw `BlindrelayTimeoutException` and network failures `BlindrelayConnectionException`. The SDK does not retry on its own.

## Webhook verification

Use the raw body:

```java
boolean ok = Webhooks.verify(
    rawBodyBytes,
    request.getHeader("Blindrelay-Signature"),
    request.getHeader("Blindrelay-Delivery-Id"),
    System.getenv("BLINDRELAY_WEBHOOK_SECRET"));
```

An overload takes the tolerance in seconds (default 300) and the current time.

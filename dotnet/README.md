# Blindrelay SDK for .NET

**Not published yet.** Once it is: `dotnet add package Blindrelay`. Needs .NET 8; no dependencies.

## Send

```csharp
using Blindrelay;

using var client = new BlindrelayClient(Environment.GetEnvironmentVariable("BLINDRELAY_API_KEY")!);

var result = await client.SendAsync(new SendRequest
{
    From = "noreply@example.com",
    To = new[] { "user@example.com" },
    Subject = "Reset your password",
    Html = "<p>Click <a href=\"https://example.com/reset\">here</a>.</p>",
    Tags = new[] { new Tag("flow", "password_reset") },
    IdempotencyKey = "reset-user-42",
});
```

`To`, `Cc` and `Bcc` take a string or an array. Options: `new BlindrelayClient(key, new BlindrelayClientOptions { BaseUrl = url, Timeout = TimeSpan.FromSeconds(30) })`. Attachments: `Attachment.FromBytes("report.pdf", "application/pdf", bytes)`.

## Status and paging

```csharp
var email = await client.GetEmailAsync(result.Id);
var page = await client.ListEmailsAsync(new ListEmailsRequest { Status = "delivered", PageSize = 25 });
```

`page.NextCursor` is null on the last page.

## Errors

API failures throw `BlindrelayApiException` with `Status`, `Code`, `Message` and `RetryAfter` (seconds or null). Compare `Code` with `ErrorCode`, for example `ErrorCode.RateLimited`; new codes may appear. A body without an error envelope gives `unknown_error`. Timeouts throw `BlindrelayTimeoutException` and network failures `BlindrelayConnectionException`. The SDK does not retry on its own.

## Webhook verification

Use the raw body:

```csharp
var ok = Webhook.Verify(
    rawBodyBytes,
    request.Headers["Blindrelay-Signature"]!,
    request.Headers["Blindrelay-Delivery-Id"]!,
    Environment.GetEnvironmentVariable("BLINDRELAY_WEBHOOK_SECRET")!);
```

`toleranceSeconds` defaults to 300.

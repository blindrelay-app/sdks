# Blindrelay SDK for Go

**Not published yet.** The module path `blindrelay.app/sdk/go` is a placeholder until the module is hosted. Needs Go 1.22+; no dependencies.

## Send

```go
client := blindrelay.New(os.Getenv("BLINDRELAY_API_KEY"))

result, err := client.Send(ctx, &blindrelay.SendParams{
	From:           "noreply@example.com",
	To:             []string{"user@example.com"},
	Subject:        "Reset your password",
	HTML:           `<p>Click <a href="https://example.com/reset">here</a>.</p>`,
	Tags:           []blindrelay.Tag{{Key: "flow", Value: "password_reset"}},
	IdempotencyKey: "reset-user-42",
})
```

Options: `blindrelay.New(key, blindrelay.WithBaseURL(url), blindrelay.WithTimeout(d))`. Attachments: `blindrelay.AttachmentFromBytes("report.pdf", "application/pdf", data)`.

## Status and paging

```go
email, err := client.GetEmail(ctx, result.ID)
page, err := client.ListEmails(ctx, blindrelay.ListEmailsParams{Status: "delivered", PageSize: 25})
```

`page.NextCursor` is nil on the last page.

## Errors

```go
var apiErr *blindrelay.APIError
if errors.As(err, &apiErr) && apiErr.Code == blindrelay.CodeRateLimited {
	// apiErr.RetryAfter is seconds to wait, or nil
}
```

`*TimeoutError` and `*ConnectionError` cover transport failures. New codes may appear; a body without an error envelope gives `CodeUnknownError`. The SDK does not retry on its own.

## Webhook verification

Use the raw body:

```go
ok := blindrelay.VerifyWebhook(blindrelay.VerifyWebhookParams{
	Payload:         rawBody,
	SignatureHeader: r.Header.Get("Blindrelay-Signature"),
	DeliveryID:      r.Header.Get("Blindrelay-Delivery-Id"),
	Secret:          os.Getenv("BLINDRELAY_WEBHOOK_SECRET"),
})
```

`Tolerance` of zero means five minutes.

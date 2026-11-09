# Blindrelay SDK for Node.js and TypeScript

`npm install blindrelay`. Needs Node 20.19+; ESM only.

## Send

```ts
import { Blindrelay } from 'blindrelay'

const client = new Blindrelay(process.env.BLINDRELAY_API_KEY!)

const result = await client.send(
  {
    from: 'noreply@example.com',
    to: ['user@example.com'],
    subject: 'Reset your password',
    html: '<p>Click <a href="https://example.com/reset">here</a>.</p>',
    tags: [{ key: 'flow', value: 'password_reset' }],
  },
  { idempotencyKey: 'reset-user-42' },
)
console.log(result.id, result.status)
```

Options: `new Blindrelay(key, { baseUrl, timeoutMs })`. Attachments: `attachmentFromBytes('report.pdf', 'application/pdf', bytes)`.

## React Email

Install the optional peer, then pass an element as `react` instead of `html`:

```sh
npm install @react-email/render react react-dom
```

```tsx
await client.send({
  from: 'noreply@example.com',
  to: 'user@example.com',
  subject: 'Welcome',
  react: <Welcome name="Ada" />,
})
```

## Status and paging

```ts
const email = await client.getEmail(result.id)
const page = await client.listEmails({ status: 'delivered', pageSize: 25, cursor: page1.nextCursor ?? undefined })
```

## Errors

API failures throw `BlindrelayApiError` with `status`, `code`, `message` and `retryAfter` (seconds, or null). Compare `code` with `ErrorCode`, for example `ErrorCode.RateLimited` or `ErrorCode.QuotaExceeded`; new codes may appear, so keep a default branch. A body without an error envelope gives `unknown_error`. Timeouts throw `BlindrelayTimeoutError` and network failures `BlindrelayConnectionError`. The SDK does not retry on its own.

## Webhook verification

Use the raw body, before any JSON parsing:

```ts
import { verifyWebhook } from 'blindrelay'

const ok = verifyWebhook({
  payload: rawBodyBuffer,
  signatureHeader: req.headers['blindrelay-signature'],
  deliveryId: req.headers['blindrelay-delivery-id'],
  secret: process.env.BLINDRELAY_WEBHOOK_SECRET!,
  // toleranceSeconds: 300 (default)
})
```

# Blindrelay SDK for PHP

**Not published yet.** Once it is: `composer require blindrelay/blindrelay`. Needs PHP 8.1+ with `ext-curl`.

## Send

`send` takes the API's field names:

```php
use Blindrelay\Client;

$client = new Client(getenv('BLINDRELAY_API_KEY'));

$result = $client->send([
    'from' => 'noreply@example.com',
    'to' => ['user@example.com'],
    'subject' => 'Reset your password',
    'html' => '<p>Click <a href="https://example.com/reset">here</a>.</p>',
    'tags' => [['key' => 'flow', 'value' => 'password_reset']],
], idempotencyKey: 'reset-user-42');

echo $result['id'];
```

Options: `new Client($key, baseUrl: $url, timeout: 30.0)`. Attachments: `Blindrelay\Attachment::fromBytes('report.pdf', 'application/pdf', $bytes)` returns one entry of `attachments`.

## Status and paging

```php
$email = $client->getEmail($result['id']);
$page = $client->listEmails(['status' => 'delivered', 'page_size' => 25]);
// $page['next_cursor'] is null on the last page.
```

## Errors

API failures throw `Blindrelay\ApiException` with `status`, `errorCode`, `retryAfter` and `getMessage()`. Compare `errorCode` with `Blindrelay\ErrorCode`, for example `ErrorCode::RATE_LIMITED`; new codes may appear. A body without an error envelope gives `unknown_error`. Timeouts throw `TimeoutException` and network failures `ConnectionException`. The SDK does not retry on its own.

## Webhook verification

Use the raw body:

```php
$ok = Blindrelay\Webhook::verify(
    file_get_contents('php://input'),
    $_SERVER['HTTP_BLINDRELAY_SIGNATURE'],
    $_SERVER['HTTP_BLINDRELAY_DELIVERY_ID'],
    getenv('BLINDRELAY_WEBHOOK_SECRET'),
);
```

The tolerance defaults to 300 seconds.

<?php

declare(strict_types=1);

use Blindrelay\ApiException;
use Blindrelay\Attachment;
use Blindrelay\BlindrelayException;
use Blindrelay\Client;
use Blindrelay\ConnectionException;
use Blindrelay\TimeoutException;
use Blindrelay\Webhook;

spl_autoload_register(static function (string $class): void {
    $file = __DIR__ . '/../src/' . substr($class, strlen('Blindrelay\\')) . '.php';
    if (str_starts_with($class, 'Blindrelay\\') && is_file($file)) {
        require $file;
    }
});

const UNREACHABLE_URL = 'http://127.0.0.1:1';

$serverUrl = getenv('BLINDRELAY_CONFORMANCE_URL');
if ($serverUrl === false || $serverUrl === '') {
    fwrite(STDERR, "Set BLINDRELAY_CONFORMANCE_URL (see sdks/conformance/run-all.sh)\n");
    exit(2);
}

function readCases(string $file): array
{
    return json_decode(file_get_contents(__DIR__ . '/../../conformance/cases/' . $file), true, 512, JSON_THROW_ON_ERROR);
}

function fail(string $message): never
{
    throw new AssertionError($message);
}

function assertSame_(mixed $actual, mixed $expected, string $label): void
{
    if ($actual !== $expected) {
        fail("{$label}: got " . json_encode($actual) . ', expected ' . json_encode($expected));
    }
}

function assertEqualJson(mixed $actual, mixed $expected): void
{
    $normalize = static function (mixed $value) use (&$normalize): mixed {
        if (!is_array($value)) {
            return $value;
        }
        ksort($value);
        return array_map($normalize, $value);
    };
    assertSame_($normalize($actual), $normalize($expected), 'result');
}

function clientFor(array $case, string $apiKey, string $serverUrl): Client
{
    $options = $case['client'] ?? [];
    $baseUrl = !empty($options['no_server']) ? UNREACHABLE_URL : "{$serverUrl}/case/{$case['name']}";
    if (!empty($options['trailing_slash'])) {
        $baseUrl .= '/';
    }
    $timeout = isset($options['timeout_ms']) ? $options['timeout_ms'] / 1000 : Client::DEFAULT_TIMEOUT_SECONDS;
    return new Client($apiKey, $baseUrl, $timeout);
}

function invoke(Client $client, array $case): array
{
    $input = $case['input'];
    switch ($case['operation']) {
        case 'send':
            $idempotencyKey = $input['idempotency_key'] ?? null;
            unset($input['idempotency_key']);
            if (isset($input['attachments'])) {
                $input['attachments'] = array_map(
                    static fn (array $raw): array => isset($raw['from_bytes_base64'])
                        ? Attachment::fromBytes($raw['filename'], $raw['content_type'], base64_decode($raw['from_bytes_base64']))
                        : $raw,
                    $input['attachments'],
                );
            }
            return $client->send($input, $idempotencyKey);
        case 'get_email':
            return $client->getEmail($input['id']);
        case 'list_emails':
            return $client->listEmails($input);
    }
    fail("unknown operation {$case['operation']}");
}

function assertExpectedError(BlindrelayException $error, array $expected, string $apiKey): void
{
    if ($expected['kind'] === 'timeout') {
        $error instanceof TimeoutException || fail('expected a timeout, got ' . get_class($error));
        return;
    }
    if ($expected['kind'] === 'connection') {
        $error instanceof ConnectionException || fail('expected a connection error, got ' . get_class($error));
        return;
    }
    $error instanceof ApiException || fail('expected an API error, got ' . get_class($error));
    assertSame_($error->status, $expected['status'], 'status');
    assertSame_($error->errorCode, $expected['code'], 'code');
    assertSame_($error->getMessage(), $expected['message'], 'message');
    assertSame_($error->retryAfter, $expected['retry_after'], 'retry after');
    str_contains((string) $error, $apiKey) && fail('error leaks the API key');
}

function assertWire(string $serverUrl, string $name): void
{
    $verdict = json_decode(file_get_contents("{$serverUrl}/__verdict/{$name}"), true, 512, JSON_THROW_ON_ERROR);
    assertSame_($verdict['received'], 1, 'requests received');
    assertSame_($verdict['mismatches'], [], 'wire mismatches');
}

function runHttpCase(array $case, string $apiKey, string $serverUrl): void
{
    try {
        $result = invoke(clientFor($case, $apiKey, $serverUrl), $case);
        isset($case['expect_error']) && fail('expected an error');
        assertEqualJson($result, $case['expect_result']);
    } catch (BlindrelayException $error) {
        isset($case['expect_error']) || fail('unexpected ' . get_class($error) . ': ' . $error->getMessage());
        assertExpectedError($error, $case['expect_error'], $apiKey);
    }
    if (empty($case['client']['no_server'])) {
        assertWire($serverUrl, $case['name']);
    }
}

function runWebhookCase(array $case): void
{
    $payload = isset($case['body_base64']) ? base64_decode($case['body_base64']) : $case['body'];
    $tolerance = $case['tolerance_seconds'] ?? Webhook::DEFAULT_TOLERANCE_SECONDS;
    $valid = Webhook::verify($payload, $case['signature_header'], $case['delivery_id'], $case['secret'], $tolerance, $case['now']);
    assertSame_($valid, $case['valid'], 'valid');
}

$failures = 0;
$total = 0;
$run = static function (string $name, callable $body) use (&$failures, &$total): void {
    $total++;
    try {
        $body();
    } catch (Throwable $error) {
        $failures++;
        echo "FAIL {$name}: {$error->getMessage()}\n";
    }
};

$http = readCases('http.json');
foreach ($http['cases'] as $case) {
    $run("http {$case['name']}", static fn () => runHttpCase($case, $http['api_key'], $serverUrl));
}
foreach (readCases('webhooks.json')['cases'] as $case) {
    $run("webhook {$case['name']}", static fn () => runWebhookCase($case));
}
$run('default base url', static fn () => assertSame_(Client::DEFAULT_BASE_URL, 'https://api.blindrelay.app', 'base url'));
$run('client string form hides the api key', static function () use ($http): void {
    $client = new Client($http['api_key']);
    str_contains(print_r($client, true) . var_export($client->__debugInfo(), true), $http['api_key']) && fail('client leaks the API key');
    ob_start();
    var_dump($client);
    str_contains((string) ob_get_clean(), $http['api_key']) && fail('var_dump leaks the API key');
});

echo ($total - $failures) . "/{$total} passed\n";
exit($failures === 0 ? 0 : 1);

<?php

declare(strict_types=1);

namespace Blindrelay;

final class Client
{
    public const VERSION = '0.1.0';
    public const DEFAULT_BASE_URL = 'https://api.blindrelay.app';
    public const DEFAULT_TIMEOUT_SECONDS = 30.0;

    private const USER_AGENT = 'blindrelay-php/' . self::VERSION;
    private const JSON_MEDIA_TYPE = 'application/json';
    private const MS_PER_SECOND = 1000;
    private const JSON_ENCODE_FLAGS = JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE;

    private readonly string $baseUrl;

    public function __construct(
        private readonly string $apiKey,
        string $baseUrl = self::DEFAULT_BASE_URL,
        private readonly float $timeout = self::DEFAULT_TIMEOUT_SECONDS,
    ) {
        $this->baseUrl = rtrim($baseUrl, '/');
    }

    /** @return array{baseUrl: string} */
    public function __debugInfo(): array
    {
        return ['baseUrl' => $this->baseUrl];
    }

    /**
     * Queues a message: POST /v1/send. `$params` uses the API's field names.
     *
     * @param array<string, mixed> $params
     * @return array{id: string, ids: list<string>, status: string, warnings: list<string>}
     */
    public function send(array $params, ?string $idempotencyKey = null): array
    {
        // json_encode turns an empty PHP array into a list, which the API refuses as headers.
        if (($params['headers'] ?? null) === []) {
            unset($params['headers']);
        }
        $answer = $this->request('POST', '/v1/send', $params, $idempotencyKey);
        return [
            'id' => $answer['id'],
            'ids' => $answer['ids'] ?? [],
            'status' => $answer['status'],
            'warnings' => $answer['warnings'] ?? [],
        ];
    }

    /** @return array<string, mixed> The delivery status: GET /v1/emails/{id}. */
    public function getEmail(string $id): array
    {
        return self::onlyFields($this->request('GET', '/v1/emails/' . rawurlencode($id)), [
            'id', 'status', 'summary', 'final', 'accepted_at', 'updated_at', 'lookup_window_hours', 'sandbox',
        ]);
    }

    /**
     * One page of recent messages: GET /v1/emails.
     *
     * @param array{status?: string, domain?: string, page_size?: int, cursor?: string} $filters
     * @return array{emails: list<array<string, mixed>>, next_cursor: ?string, lookup_window_hours: int}
     */
    public function listEmails(array $filters = []): array
    {
        $query = http_build_query($filters, '', '&', PHP_QUERY_RFC3986);
        $answer = $this->request('GET', '/v1/emails' . ($query === '' ? '' : "?{$query}"));
        return [
            'emails' => $answer['emails'],
            'next_cursor' => $answer['next_cursor'] ?? null,
            'lookup_window_hours' => $answer['lookup_window_hours'],
        ];
    }

    /**
     * @param array<string, mixed> $answer
     * @param list<string> $fields
     * @return array<string, mixed>
     */
    private static function onlyFields(array $answer, array $fields): array
    {
        return array_intersect_key($answer, array_flip($fields));
    }

    /**
     * @param array<string, mixed>|null $body
     * @return array<string, mixed>
     */
    private function request(string $method, string $path, ?array $body = null, ?string $idempotencyKey = null): array
    {
        $responseHeaders = [];
        $handle = curl_init($this->baseUrl . $path);
        curl_setopt_array($handle, [
            CURLOPT_CUSTOMREQUEST => $method,
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_TIMEOUT_MS => (int) ($this->timeout * self::MS_PER_SECOND),
            CURLOPT_NOSIGNAL => true,
            CURLOPT_HTTPHEADER => $this->headers($body !== null, $idempotencyKey),
            CURLOPT_HEADERFUNCTION => static function ($curl, string $line) use (&$responseHeaders): int {
                $pieces = explode(':', $line, 2);
                if (count($pieces) === 2) {
                    $responseHeaders[strtolower(trim($pieces[0]))] = trim($pieces[1]);
                }
                return strlen($line);
            },
        ]);
        if ($body !== null) {
            curl_setopt($handle, CURLOPT_POSTFIELDS, json_encode($body, self::JSON_ENCODE_FLAGS));
        }
        $raw = curl_exec($handle);
        if ($raw === false) {
            throw self::transportError(curl_errno($handle));
        }
        $status = (int) curl_getinfo($handle, CURLINFO_RESPONSE_CODE);
        if ($status < 200 || $status >= 300) {
            throw self::apiError($status, $responseHeaders['retry-after'] ?? null, (string) $raw);
        }
        return json_decode((string) $raw, true, 512, JSON_THROW_ON_ERROR);
    }

    /** @return list<string> */
    private function headers(bool $hasBody, ?string $idempotencyKey): array
    {
        $headers = [
            'Authorization: Bearer ' . $this->apiKey,
            'Accept: ' . self::JSON_MEDIA_TYPE,
            'User-Agent: ' . self::USER_AGENT,
        ];
        if ($hasBody) {
            $headers[] = 'Content-Type: ' . self::JSON_MEDIA_TYPE;
        }
        if ($idempotencyKey !== null) {
            $headers[] = 'Idempotency-Key: ' . $idempotencyKey;
        }
        return $headers;
    }

    private static function transportError(int $curlError): BlindrelayException
    {
        return $curlError === CURLE_OPERATION_TIMEDOUT
            ? new TimeoutException('The request to Blindrelay timed out')
            : new ConnectionException('Could not reach Blindrelay');
    }

    private static function apiError(int $status, ?string $retryAfterHeader, string $raw): ApiException
    {
        $parsed = json_decode($raw, true);
        $envelope = is_array($parsed) && is_array($parsed['error'] ?? null) ? $parsed['error'] : [];
        $code = $envelope['code'] ?? null;
        $fallback = "HTTP {$status}";
        return new ApiException(
            $status,
            is_string($code) ? $code : ErrorCode::UNKNOWN_ERROR,
            is_string($code) ? (string) ($envelope['message'] ?? $fallback) : $fallback,
            self::retryAfter($retryAfterHeader, $envelope),
        );
    }

    /** @param array<string, mixed> $envelope */
    private static function retryAfter(?string $header, array $envelope): ?int
    {
        if ($header !== null && ctype_digit($header)) {
            return (int) $header;
        }
        $seconds = $envelope['retry_after_secs'] ?? null;
        return is_int($seconds) ? $seconds : null;
    }
}

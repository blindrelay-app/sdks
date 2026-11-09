<?php

declare(strict_types=1);

namespace Blindrelay;

final class Webhook
{
    public const DEFAULT_TOLERANCE_SECONDS = 300;
    private const TIMESTAMP_PATTERN = '/\A(0|[1-9][0-9]*)\z/';

    /**
     * Verifies a delivery webhook: `v1` is hex HMAC-SHA256 over `<t>.<delivery_id>.` + raw body.
     * A header can carry several `v1` values during a secret rotation; one match is enough.
     * Inbound endpoint deliveries sign a different string and do not verify here.
     *
     * @param string $payload The request body exactly as received.
     */
    public static function verify(
        string $payload,
        string $signatureHeader,
        string $deliveryId,
        string $secret,
        int $toleranceSeconds = self::DEFAULT_TOLERANCE_SECONDS,
        ?int $now = null,
    ): bool {
        [$timestamp, $signatures] = self::parseHeader($signatureHeader);
        if ($timestamp === null || $signatures === [] || preg_match(self::TIMESTAMP_PATTERN, $timestamp) !== 1) {
            return false;
        }
        if (abs(($now ?? time()) - (float) $timestamp) > $toleranceSeconds) {
            return false;
        }
        $expected = hash_hmac('sha256', "{$timestamp}.{$deliveryId}.{$payload}", $secret);
        foreach ($signatures as $signature) {
            if (hash_equals($expected, $signature)) {
                return true;
            }
        }
        return false;
    }

    /** @return array{0: ?string, 1: list<string>} */
    private static function parseHeader(string $header): array
    {
        $timestamp = null;
        $signatures = [];
        foreach (explode(',', $header) as $part) {
            $pieces = explode('=', $part, 2);
            if (count($pieces) !== 2) {
                continue;
            }
            [$key, $value] = array_map('trim', $pieces);
            if ($key === 't' && $timestamp === null) {
                $timestamp = $value;
            } elseif ($key === 'v1') {
                $signatures[] = $value;
            }
        }
        return [$timestamp, $signatures];
    }
}

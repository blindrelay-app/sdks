<?php

declare(strict_types=1);

namespace Blindrelay;

/** The API answered with an error. `code` is `unknown_error` without an error envelope. */
final class ApiException extends BlindrelayException
{
    public function __construct(
        public readonly int $status,
        public readonly string $errorCode,
        string $message,
        public readonly ?int $retryAfter,
    ) {
        parent::__construct($message);
    }
}

<?php

declare(strict_types=1);

namespace Blindrelay;

final class Attachment
{
    /** @return array{filename: string, content_type: string, content_base64: string} */
    public static function fromBytes(string $filename, string $contentType, string $bytes): array
    {
        return [
            'filename' => $filename,
            'content_type' => $contentType,
            'content_base64' => base64_encode($bytes),
        ];
    }
}

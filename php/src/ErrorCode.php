<?php

declare(strict_types=1);

namespace Blindrelay;

/** API error codes the send and email routes document. New codes may appear. */
final class ErrorCode
{
    public const INVALID_PAYLOAD = 'invalid_payload';
    public const UNAUTHORIZED = 'unauthorized';
    public const SCOPE_MISSING = 'scope_missing';
    public const DOMAIN_NOT_VERIFIED = 'domain_not_verified';
    public const RECIPIENT_SUPPRESSED = 'recipient_suppressed';
    public const TENANT_SUSPENDED = 'tenant_suspended';
    public const PLAN_RESTRICTED = 'plan_restricted';
    public const UNKNOWN_TENANT = 'unknown_tenant';
    public const PAYLOAD_TOO_LARGE = 'payload_too_large';
    public const INVALID_TAG = 'invalid_tag';
    public const TOO_MANY_ATTACHMENTS = 'too_many_attachments';
    public const ATTACHMENT_TOO_LARGE = 'attachment_too_large';
    public const INVALID_ATTACHMENT_BASE64 = 'invalid_attachment_base64';
    public const INVALID_ATTACHMENT_FILENAME = 'invalid_attachment_filename';
    public const INVALID_ATTACHMENT_CONTENT_TYPE = 'invalid_attachment_content_type';
    public const QUOTA_EXCEEDED = 'quota_exceeded';
    public const SPENDING_CAP_REACHED = 'spending_cap_reached';
    public const RATE_LIMIT_EXCEEDED = 'rate_limit_exceeded';
    public const WARMUP_LIMIT_EXCEEDED = 'warmup_limit_exceeded';
    public const RATE_LIMITED = 'rate_limited';
    public const DRAINING = 'draining';
    public const QUEUE_AT_CAPACITY = 'queue_at_capacity';
    public const INTERNAL = 'internal';
    public const EMAIL_NOT_FOUND = 'email_not_found';
    public const INVALID_STATUS = 'invalid_status';
    public const INVALID_DOMAIN = 'invalid_domain';
    public const INVALID_CURSOR = 'invalid_cursor';
    public const UNKNOWN_ERROR = 'unknown_error';
}

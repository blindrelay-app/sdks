namespace Blindrelay;

/// <summary>API error codes the send and email routes document. New codes may appear.</summary>
public static class ErrorCode
{
    public const string InvalidPayload = "invalid_payload";
    public const string Unauthorized = "unauthorized";
    public const string ScopeMissing = "scope_missing";
    public const string DomainNotVerified = "domain_not_verified";
    public const string RecipientSuppressed = "recipient_suppressed";
    public const string TenantSuspended = "tenant_suspended";
    public const string PlanRestricted = "plan_restricted";
    public const string UnknownTenant = "unknown_tenant";
    public const string PayloadTooLarge = "payload_too_large";
    public const string InvalidTag = "invalid_tag";
    public const string TooManyAttachments = "too_many_attachments";
    public const string AttachmentTooLarge = "attachment_too_large";
    public const string InvalidAttachmentBase64 = "invalid_attachment_base64";
    public const string InvalidAttachmentFilename = "invalid_attachment_filename";
    public const string InvalidAttachmentContentType = "invalid_attachment_content_type";
    public const string QuotaExceeded = "quota_exceeded";
    public const string SpendingCapReached = "spending_cap_reached";
    public const string RateLimitExceeded = "rate_limit_exceeded";
    public const string WarmupLimitExceeded = "warmup_limit_exceeded";
    public const string RateLimited = "rate_limited";
    public const string Draining = "draining";
    public const string QueueAtCapacity = "queue_at_capacity";
    public const string Internal = "internal";
    public const string EmailNotFound = "email_not_found";
    public const string InvalidStatus = "invalid_status";
    public const string InvalidDomain = "invalid_domain";
    public const string InvalidCursor = "invalid_cursor";
    public const string UnknownError = "unknown_error";
}

public class BlindrelayException : Exception
{
    public BlindrelayException(string message) : base(message) { }
}

/// <summary>The API answered with an error. Code is unknown_error without an error envelope.</summary>
public sealed class BlindrelayApiException : BlindrelayException
{
    public BlindrelayApiException(int status, string code, string message, int? retryAfter) : base(message)
    {
        Status = status;
        Code = code;
        RetryAfter = retryAfter;
    }

    public int Status { get; }

    public string Code { get; }

    /// <summary>Seconds to wait, from Retry-After or error.retry_after_secs; null when not given.</summary>
    public int? RetryAfter { get; }
}

public sealed class BlindrelayTimeoutException : BlindrelayException
{
    public BlindrelayTimeoutException() : base("The request to Blindrelay timed out") { }
}

public sealed class BlindrelayConnectionException : BlindrelayException
{
    public BlindrelayConnectionException() : base("Could not reach Blindrelay") { }
}

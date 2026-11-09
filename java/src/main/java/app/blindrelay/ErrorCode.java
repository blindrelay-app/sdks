package app.blindrelay;

/** API error codes the send and email routes document. New codes may appear. */
public final class ErrorCode {
  public static final String INVALID_PAYLOAD = "invalid_payload";
  public static final String UNAUTHORIZED = "unauthorized";
  public static final String SCOPE_MISSING = "scope_missing";
  public static final String DOMAIN_NOT_VERIFIED = "domain_not_verified";
  public static final String RECIPIENT_SUPPRESSED = "recipient_suppressed";
  public static final String TENANT_SUSPENDED = "tenant_suspended";
  public static final String PLAN_RESTRICTED = "plan_restricted";
  public static final String UNKNOWN_TENANT = "unknown_tenant";
  public static final String PAYLOAD_TOO_LARGE = "payload_too_large";
  public static final String INVALID_TAG = "invalid_tag";
  public static final String TOO_MANY_ATTACHMENTS = "too_many_attachments";
  public static final String ATTACHMENT_TOO_LARGE = "attachment_too_large";
  public static final String INVALID_ATTACHMENT_BASE64 = "invalid_attachment_base64";
  public static final String INVALID_ATTACHMENT_FILENAME = "invalid_attachment_filename";
  public static final String INVALID_ATTACHMENT_CONTENT_TYPE = "invalid_attachment_content_type";
  public static final String QUOTA_EXCEEDED = "quota_exceeded";
  public static final String SPENDING_CAP_REACHED = "spending_cap_reached";
  public static final String RATE_LIMIT_EXCEEDED = "rate_limit_exceeded";
  public static final String WARMUP_LIMIT_EXCEEDED = "warmup_limit_exceeded";
  public static final String RATE_LIMITED = "rate_limited";
  public static final String DRAINING = "draining";
  public static final String QUEUE_AT_CAPACITY = "queue_at_capacity";
  public static final String INTERNAL = "internal";
  public static final String EMAIL_NOT_FOUND = "email_not_found";
  public static final String INVALID_STATUS = "invalid_status";
  public static final String INVALID_DOMAIN = "invalid_domain";
  public static final String INVALID_CURSOR = "invalid_cursor";
  public static final String UNKNOWN_ERROR = "unknown_error";

  private ErrorCode() {}
}

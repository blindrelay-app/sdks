use std::fmt;

/// API error codes the send and email routes document. New codes may appear.
pub mod code {
    pub const INVALID_PAYLOAD: &str = "invalid_payload";
    pub const UNAUTHORIZED: &str = "unauthorized";
    pub const SCOPE_MISSING: &str = "scope_missing";
    pub const DOMAIN_NOT_VERIFIED: &str = "domain_not_verified";
    pub const RECIPIENT_SUPPRESSED: &str = "recipient_suppressed";
    pub const TENANT_SUSPENDED: &str = "tenant_suspended";
    pub const PLAN_RESTRICTED: &str = "plan_restricted";
    pub const UNKNOWN_TENANT: &str = "unknown_tenant";
    pub const PAYLOAD_TOO_LARGE: &str = "payload_too_large";
    pub const INVALID_TAG: &str = "invalid_tag";
    pub const TOO_MANY_ATTACHMENTS: &str = "too_many_attachments";
    pub const ATTACHMENT_TOO_LARGE: &str = "attachment_too_large";
    pub const INVALID_ATTACHMENT_BASE64: &str = "invalid_attachment_base64";
    pub const INVALID_ATTACHMENT_FILENAME: &str = "invalid_attachment_filename";
    pub const INVALID_ATTACHMENT_CONTENT_TYPE: &str = "invalid_attachment_content_type";
    pub const QUOTA_EXCEEDED: &str = "quota_exceeded";
    pub const SPENDING_CAP_REACHED: &str = "spending_cap_reached";
    pub const RATE_LIMIT_EXCEEDED: &str = "rate_limit_exceeded";
    pub const WARMUP_LIMIT_EXCEEDED: &str = "warmup_limit_exceeded";
    pub const RATE_LIMITED: &str = "rate_limited";
    pub const DRAINING: &str = "draining";
    pub const QUEUE_AT_CAPACITY: &str = "queue_at_capacity";
    pub const INTERNAL: &str = "internal";
    pub const EMAIL_NOT_FOUND: &str = "email_not_found";
    pub const INVALID_STATUS: &str = "invalid_status";
    pub const INVALID_DOMAIN: &str = "invalid_domain";
    pub const INVALID_CURSOR: &str = "invalid_cursor";
    pub const UNKNOWN_ERROR: &str = "unknown_error";
}

/// An error answer from the API. `code` is [`code::UNKNOWN_ERROR`] without an error envelope.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ApiError {
    pub status: u16,
    pub code: String,
    pub message: String,
    /// Seconds to wait, from `Retry-After` or `error.retry_after_secs`.
    pub retry_after: Option<u64>,
}

#[derive(Debug)]
pub enum Error {
    Api(ApiError),
    Timeout,
    Connection,
    InvalidBaseUrl,
    InvalidResponse,
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Api(error) => write!(
                f,
                "blindrelay: {} (HTTP {}): {}",
                error.code, error.status, error.message
            ),
            Self::Timeout => f.write_str("blindrelay: the request timed out"),
            Self::Connection => f.write_str("blindrelay: could not reach the API"),
            Self::InvalidBaseUrl => f.write_str("blindrelay: the base URL is not a valid http(s) URL"),
            Self::InvalidResponse => f.write_str("blindrelay: the API answered with an unreadable body"),
        }
    }
}

impl std::error::Error for Error {}

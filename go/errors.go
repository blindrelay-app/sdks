package blindrelay

import "fmt"

// API error codes the send and email routes document. New codes may appear.
const (
	CodeInvalidPayload               = "invalid_payload"
	CodeUnauthorized                 = "unauthorized"
	CodeScopeMissing                 = "scope_missing"
	CodeDomainNotVerified            = "domain_not_verified"
	CodeRecipientSuppressed          = "recipient_suppressed"
	CodeTenantSuspended              = "tenant_suspended"
	CodePlanRestricted               = "plan_restricted"
	CodeUnknownTenant                = "unknown_tenant"
	CodePayloadTooLarge              = "payload_too_large"
	CodeInvalidTag                   = "invalid_tag"
	CodeTooManyAttachments           = "too_many_attachments"
	CodeAttachmentTooLarge           = "attachment_too_large"
	CodeInvalidAttachmentBase64      = "invalid_attachment_base64"
	CodeInvalidAttachmentFilename    = "invalid_attachment_filename"
	CodeInvalidAttachmentContentType = "invalid_attachment_content_type"
	CodeQuotaExceeded                = "quota_exceeded"
	CodeSpendingCapReached           = "spending_cap_reached"
	CodeRateLimitExceeded            = "rate_limit_exceeded"
	CodeWarmupLimitExceeded          = "warmup_limit_exceeded"
	CodeRateLimited                  = "rate_limited"
	CodeDraining                     = "draining"
	CodeQueueAtCapacity              = "queue_at_capacity"
	CodeInternal                     = "internal"
	CodeEmailNotFound                = "email_not_found"
	CodeInvalidStatus                = "invalid_status"
	CodeInvalidDomain                = "invalid_domain"
	CodeInvalidCursor                = "invalid_cursor"
	CodeUnknownError                 = "unknown_error"
)

// APIError is an error answer from the API. Code is CodeUnknownError without an error envelope.
type APIError struct {
	Status  int
	Code    string
	Message string
	// RetryAfter is seconds to wait, from Retry-After or error.retry_after_secs; nil when not given.
	RetryAfter *int
}

func (e *APIError) Error() string {
	return fmt.Sprintf("blindrelay: %s (HTTP %d): %s", e.Code, e.Status, e.Message)
}

// TimeoutError means the request did not finish within the client timeout.
type TimeoutError struct{}

func (*TimeoutError) Error() string { return "blindrelay: the request timed out" }

// ConnectionError means the request could not reach the API.
type ConnectionError struct{}

func (*ConnectionError) Error() string { return "blindrelay: could not reach the API" }

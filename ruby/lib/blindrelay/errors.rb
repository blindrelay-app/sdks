module Blindrelay
  # API error codes the send and email routes document. New codes may appear.
  module ErrorCode
    INVALID_PAYLOAD = "invalid_payload"
    UNAUTHORIZED = "unauthorized"
    SCOPE_MISSING = "scope_missing"
    DOMAIN_NOT_VERIFIED = "domain_not_verified"
    RECIPIENT_SUPPRESSED = "recipient_suppressed"
    TENANT_SUSPENDED = "tenant_suspended"
    PLAN_RESTRICTED = "plan_restricted"
    UNKNOWN_TENANT = "unknown_tenant"
    PAYLOAD_TOO_LARGE = "payload_too_large"
    INVALID_TAG = "invalid_tag"
    TOO_MANY_ATTACHMENTS = "too_many_attachments"
    ATTACHMENT_TOO_LARGE = "attachment_too_large"
    INVALID_ATTACHMENT_BASE64 = "invalid_attachment_base64"
    INVALID_ATTACHMENT_FILENAME = "invalid_attachment_filename"
    INVALID_ATTACHMENT_CONTENT_TYPE = "invalid_attachment_content_type"
    QUOTA_EXCEEDED = "quota_exceeded"
    SPENDING_CAP_REACHED = "spending_cap_reached"
    RATE_LIMIT_EXCEEDED = "rate_limit_exceeded"
    WARMUP_LIMIT_EXCEEDED = "warmup_limit_exceeded"
    RATE_LIMITED = "rate_limited"
    DRAINING = "draining"
    QUEUE_AT_CAPACITY = "queue_at_capacity"
    INTERNAL = "internal"
    EMAIL_NOT_FOUND = "email_not_found"
    INVALID_STATUS = "invalid_status"
    INVALID_DOMAIN = "invalid_domain"
    INVALID_CURSOR = "invalid_cursor"
    UNKNOWN_ERROR = "unknown_error"
  end

  class Error < StandardError; end

  # The API answered with an error. +code+ is +unknown_error+ without an error envelope.
  class ApiError < Error
    attr_reader :status, :code, :retry_after

    def initialize(status:, code:, message:, retry_after:)
      super(message)
      @status = status
      @code = code
      @retry_after = retry_after
    end
  end

  class TimeoutError < Error; end

  class ConnectionError < Error; end
end

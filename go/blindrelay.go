// Package blindrelay is the official Go SDK for the Blindrelay transactional email API.
package blindrelay

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

const (
	// Version is the SDK version.
	Version = "0.1.0"
	// DefaultBaseURL is the production API.
	DefaultBaseURL = "https://api.blindrelay.app"
	// DefaultTimeout applies when WithTimeout is not used.
	DefaultTimeout = 30 * time.Second

	userAgent     = "blindrelay-go/" + Version
	jsonMediaType = "application/json"
)

// Client calls the Blindrelay API. Safe for concurrent use.
type Client struct {
	apiKey     string
	baseURL    string
	httpClient *http.Client
}

// Option configures a Client.
type Option func(*Client)

// WithBaseURL sets the API origin, for example the staging API.
func WithBaseURL(baseURL string) Option {
	return func(c *Client) { c.baseURL = strings.TrimRight(baseURL, "/") }
}

// WithTimeout sets the timeout of one request.
func WithTimeout(timeout time.Duration) Option {
	return func(c *Client) { c.httpClient.Timeout = timeout }
}

// New returns a client for the given API key.
func New(apiKey string, options ...Option) *Client {
	client := &Client{
		apiKey:     apiKey,
		baseURL:    DefaultBaseURL,
		httpClient: &http.Client{Timeout: DefaultTimeout},
	}
	for _, option := range options {
		option(client)
	}
	return client
}

// String hides the API key from %v and %+v.
func (c *Client) String() string { return "blindrelay.Client{baseURL: " + c.baseURL + "}" }

// GoString hides the API key from %#v.
func (c *Client) GoString() string { return c.String() }

// Attachment is one file. AttachmentFromBytes builds it from file content.
type Attachment struct {
	Filename      string `json:"filename"`
	ContentType   string `json:"content_type"`
	ContentBase64 string `json:"content_base64"`
}

// AttachmentFromBytes base64-encodes data into an Attachment.
func AttachmentFromBytes(filename, contentType string, data []byte) Attachment {
	return Attachment{
		Filename:      filename,
		ContentType:   contentType,
		ContentBase64: base64.StdEncoding.EncodeToString(data),
	}
}

// Tag is one label returned on the message's webhook events.
type Tag struct {
	Key   string `json:"key"`
	Value string `json:"value"`
}

// SendParams is one message. From, To, Subject and HTML are required.
type SendParams struct {
	From        string
	To          []string
	Subject     string
	HTML        string
	Text        string
	Headers     map[string]string
	Cc          []string
	Bcc         []string
	Attachments []Attachment
	Tags        []Tag
	// IdempotencyKey makes retries of the same logical send safe.
	IdempotencyKey string
}

// SendResult is the 202 answer of Send.
type SendResult struct {
	ID       string   `json:"id"`
	IDs      []string `json:"ids"`
	Status   string   `json:"status"`
	Warnings []string `json:"warnings"`
}

// EmailStatus is the current delivery status of one message.
type EmailStatus struct {
	ID                string  `json:"id"`
	Status            string  `json:"status"`
	Summary           string  `json:"summary"`
	Final             bool    `json:"final"`
	AcceptedAt        *string `json:"accepted_at"`
	UpdatedAt         string  `json:"updated_at"`
	LookupWindowHours int     `json:"lookup_window_hours"`
	Sandbox           bool    `json:"sandbox"`
}

// EmailListItem is one message of a list page.
type EmailListItem struct {
	ID         string  `json:"id"`
	Status     string  `json:"status"`
	Summary    string  `json:"summary"`
	Final      bool    `json:"final"`
	Domain     *string `json:"domain"`
	APIKeyID   *string `json:"api_key_id"`
	AcceptedAt *string `json:"accepted_at"`
	UpdatedAt  string  `json:"updated_at"`
	Sandbox    bool    `json:"sandbox"`
}

// EmailList is one page of ListEmails.
type EmailList struct {
	Emails []EmailListItem `json:"emails"`
	// NextCursor is nil on the last page.
	NextCursor        *string `json:"next_cursor"`
	LookupWindowHours int     `json:"lookup_window_hours"`
}

// ListEmailsParams filters ListEmails. Zero values are left out of the query.
type ListEmailsParams struct {
	Status   string
	Domain   string
	PageSize int
	Cursor   string
}

// recipientsToWire sends one address as a JSON string, the shape the other SDKs send for a single
// address; the API treats a one-element array the same.
func recipientsToWire(addresses []string) any {
	if len(addresses) == 1 {
		return addresses[0]
	}
	return addresses
}

func sendBody(params *SendParams) map[string]any {
	body := map[string]any{
		"from":    params.From,
		"to":      recipientsToWire(params.To),
		"subject": params.Subject,
		"html":    params.HTML,
	}
	if params.Text != "" {
		body["text"] = params.Text
	}
	if len(params.Headers) > 0 {
		body["headers"] = params.Headers
	}
	if len(params.Cc) > 0 {
		body["cc"] = recipientsToWire(params.Cc)
	}
	if len(params.Bcc) > 0 {
		body["bcc"] = recipientsToWire(params.Bcc)
	}
	if len(params.Attachments) > 0 {
		body["attachments"] = params.Attachments
	}
	if len(params.Tags) > 0 {
		body["tags"] = params.Tags
	}
	return body
}

// Send queues a message for delivery: POST /v1/send.
func (c *Client) Send(ctx context.Context, params *SendParams) (*SendResult, error) {
	var result SendResult
	err := c.do(ctx, http.MethodPost, "/v1/send", sendBody(params), params.IdempotencyKey, &result)
	if err != nil {
		return nil, err
	}
	if result.IDs == nil {
		result.IDs = []string{}
	}
	if result.Warnings == nil {
		result.Warnings = []string{}
	}
	return &result, nil
}

// GetEmail returns the delivery status of one message: GET /v1/emails/{id}.
func (c *Client) GetEmail(ctx context.Context, id string) (*EmailStatus, error) {
	var status EmailStatus
	if err := c.do(ctx, http.MethodGet, "/v1/emails/"+url.PathEscape(id), nil, "", &status); err != nil {
		return nil, err
	}
	return &status, nil
}

// ListEmails returns one page of recent messages: GET /v1/emails.
func (c *Client) ListEmails(ctx context.Context, params ListEmailsParams) (*EmailList, error) {
	query := url.Values{}
	if params.Status != "" {
		query.Set("status", params.Status)
	}
	if params.Domain != "" {
		query.Set("domain", params.Domain)
	}
	if params.PageSize != 0 {
		query.Set("page_size", strconv.Itoa(params.PageSize))
	}
	if params.Cursor != "" {
		query.Set("cursor", params.Cursor)
	}
	path := "/v1/emails"
	if len(query) > 0 {
		path += "?" + query.Encode()
	}
	var list EmailList
	if err := c.do(ctx, http.MethodGet, path, nil, "", &list); err != nil {
		return nil, err
	}
	return &list, nil
}

func (c *Client) do(ctx context.Context, method, path string, body any, idempotencyKey string, out any) error {
	var reader io.Reader
	if body != nil {
		encoded, err := json.Marshal(body)
		if err != nil {
			return fmt.Errorf("blindrelay: encode request: %w", err)
		}
		reader = bytes.NewReader(encoded)
	}
	request, err := http.NewRequestWithContext(ctx, method, c.baseURL+path, reader)
	if err != nil {
		return &ConnectionError{}
	}
	request.Header.Set("Authorization", "Bearer "+c.apiKey)
	request.Header.Set("Accept", jsonMediaType)
	request.Header.Set("User-Agent", userAgent)
	if body != nil {
		request.Header.Set("Content-Type", jsonMediaType)
	}
	if idempotencyKey != "" {
		request.Header.Set("Idempotency-Key", idempotencyKey)
	}
	response, err := c.httpClient.Do(request)
	if err != nil {
		return transportError(err)
	}
	defer response.Body.Close()
	raw, err := io.ReadAll(response.Body)
	if err != nil {
		return transportError(err)
	}
	if response.StatusCode >= http.StatusMultipleChoices {
		return apiError(response.StatusCode, response.Header.Get("Retry-After"), raw)
	}
	if err := json.Unmarshal(raw, out); err != nil {
		return fmt.Errorf("blindrelay: decode response: %w", err)
	}
	return nil
}

func transportError(err error) error {
	var network net.Error
	if errors.Is(err, context.DeadlineExceeded) || (errors.As(err, &network) && network.Timeout()) {
		return &TimeoutError{}
	}
	return &ConnectionError{}
}

func apiError(status int, retryAfterHeader string, raw []byte) *APIError {
	var parsed struct {
		Error *struct {
			Code           *string `json:"code"`
			Message        string  `json:"message"`
			RetryAfterSecs *int    `json:"retry_after_secs"`
		} `json:"error"`
	}
	result := &APIError{Status: status, Code: CodeUnknownError, Message: fmt.Sprintf("HTTP %d", status)}
	if json.Unmarshal(raw, &parsed) == nil && parsed.Error != nil && parsed.Error.Code != nil {
		result.Code = *parsed.Error.Code
		result.Message = parsed.Error.Message
		result.RetryAfter = parsed.Error.RetryAfterSecs
	}
	if seconds, err := strconv.Atoi(strings.TrimSpace(retryAfterHeader)); err == nil && seconds >= 0 {
		result.RetryAfter = &seconds
	}
	return result
}

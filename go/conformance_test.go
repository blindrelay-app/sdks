package blindrelay

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os"
	"reflect"
	"strings"
	"testing"
	"time"
)

const unreachableURL = "http://127.0.0.1:1"

type httpCase struct {
	Name      string          `json:"name"`
	Operation string          `json:"operation"`
	Input     json.RawMessage `json:"input"`
	Client    struct {
		TimeoutMs     int  `json:"timeout_ms"`
		NoServer      bool `json:"no_server"`
		TrailingSlash bool `json:"trailing_slash"`
	} `json:"client"`
	ExpectResult any `json:"expect_result"`
	ExpectError  *struct {
		Kind       string `json:"kind"`
		Status     int    `json:"status"`
		Code       string `json:"code"`
		Message    string `json:"message"`
		RetryAfter *int   `json:"retry_after"`
	} `json:"expect_error"`
}

type sendInput struct {
	From        string            `json:"from"`
	To          stringOrList      `json:"to"`
	Subject     string            `json:"subject"`
	HTML        string            `json:"html"`
	Text        string            `json:"text"`
	Headers     map[string]string `json:"headers"`
	Cc          stringOrList      `json:"cc"`
	Bcc         stringOrList      `json:"bcc"`
	Tags        []Tag             `json:"tags"`
	Idempotency string            `json:"idempotency_key"`
	Attachments []struct {
		Filename      string `json:"filename"`
		ContentType   string `json:"content_type"`
		ContentBase64 string `json:"content_base64"`
		FromBytes     string `json:"from_bytes_base64"`
	} `json:"attachments"`
}

type stringOrList []string

func (s *stringOrList) UnmarshalJSON(raw []byte) error {
	var single string
	if json.Unmarshal(raw, &single) == nil {
		*s = []string{single}
		return nil
	}
	return json.Unmarshal(raw, (*[]string)(s))
}

func serverURL(t *testing.T) string {
	url := os.Getenv("BLINDRELAY_CONFORMANCE_URL")
	if url == "" {
		t.Fatal("Set BLINDRELAY_CONFORMANCE_URL (see sdks/conformance/run-all.sh)")
	}
	return url
}

func readCases(t *testing.T, file string, target any) {
	raw, err := os.ReadFile("../conformance/cases/" + file)
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(raw, target); err != nil {
		t.Fatal(err)
	}
}

func clientFor(server, apiKey string, c *httpCase) *Client {
	base := server + "/case/" + c.Name
	if c.Client.NoServer {
		base = unreachableURL
	}
	if c.Client.TrailingSlash {
		base += "/"
	}
	options := []Option{WithBaseURL(base)}
	if c.Client.TimeoutMs > 0 {
		options = append(options, WithTimeout(time.Duration(c.Client.TimeoutMs)*time.Millisecond))
	}
	return New(apiKey, options...)
}

func buildSendParams(t *testing.T, raw json.RawMessage) *SendParams {
	var input sendInput
	if err := json.Unmarshal(raw, &input); err != nil {
		t.Fatal(err)
	}
	params := &SendParams{
		From: input.From, To: input.To, Subject: input.Subject, HTML: input.HTML, Text: input.Text,
		Headers: input.Headers, Cc: input.Cc, Bcc: input.Bcc, Tags: input.Tags, IdempotencyKey: input.Idempotency,
	}
	for _, attachment := range input.Attachments {
		if attachment.FromBytes != "" {
			data, err := base64.StdEncoding.DecodeString(attachment.FromBytes)
			if err != nil {
				t.Fatal(err)
			}
			params.Attachments = append(params.Attachments, AttachmentFromBytes(attachment.Filename, attachment.ContentType, data))
			continue
		}
		params.Attachments = append(params.Attachments, Attachment{attachment.Filename, attachment.ContentType, attachment.ContentBase64})
	}
	return params
}

func invoke(t *testing.T, client *Client, c *httpCase) (any, error) {
	ctx := context.Background()
	switch c.Operation {
	case "send":
		return client.Send(ctx, buildSendParams(t, c.Input))
	case "get_email":
		var input struct {
			ID string `json:"id"`
		}
		json.Unmarshal(c.Input, &input)
		return client.GetEmail(ctx, input.ID)
	case "list_emails":
		var input struct {
			Status   string `json:"status"`
			Domain   string `json:"domain"`
			PageSize int    `json:"page_size"`
			Cursor   string `json:"cursor"`
		}
		json.Unmarshal(c.Input, &input)
		return client.ListEmails(ctx, ListEmailsParams(input))
	}
	t.Fatalf("unknown operation %s", c.Operation)
	return nil, nil
}

func asGeneric(t *testing.T, value any) any {
	raw, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	var generic any
	if err := json.Unmarshal(raw, &generic); err != nil {
		t.Fatal(err)
	}
	return generic
}

func assertError(t *testing.T, c *httpCase, apiKey string, err error) {
	expected := c.ExpectError
	if err == nil {
		t.Fatal("expected an error")
	}
	var timeout *TimeoutError
	var connection *ConnectionError
	var api *APIError
	switch expected.Kind {
	case "timeout":
		if !errors.As(err, &timeout) {
			t.Fatalf("expected timeout, got %v", err)
		}
	case "connection":
		if !errors.As(err, &connection) {
			t.Fatalf("expected connection error, got %v", err)
		}
	default:
		if !errors.As(err, &api) {
			t.Fatalf("expected API error, got %v", err)
		}
		if api.Status != expected.Status || api.Code != expected.Code || api.Message != expected.Message {
			t.Fatalf("got %+v, expected %+v", api, expected)
		}
		if !reflect.DeepEqual(api.RetryAfter, expected.RetryAfter) {
			t.Fatalf("retry after %v, expected %v", api.RetryAfter, expected.RetryAfter)
		}
		if strings.Contains(fmt.Sprintf("%v %+v %#v", err, err, err), apiKey) {
			t.Fatal("error leaks the API key")
		}
	}
}

func assertWire(t *testing.T, server, name string) {
	response, err := http.Get(server + "/__verdict/" + name)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	var verdict struct {
		Received   int      `json:"received"`
		Mismatches []string `json:"mismatches"`
	}
	if err := json.NewDecoder(response.Body).Decode(&verdict); err != nil {
		t.Fatal(err)
	}
	if verdict.Received != 1 || len(verdict.Mismatches) != 0 {
		t.Fatalf("wire verdict: %+v", verdict)
	}
}

func TestHTTPCases(t *testing.T) {
	server := serverURL(t)
	var suite struct {
		APIKey string     `json:"api_key"`
		Cases  []httpCase `json:"cases"`
	}
	readCases(t, "http.json", &suite)
	for index := range suite.Cases {
		c := &suite.Cases[index]
		t.Run(c.Name, func(t *testing.T) {
			result, err := invoke(t, clientFor(server, suite.APIKey, c), c)
			if c.ExpectError != nil {
				assertError(t, c, suite.APIKey, err)
			} else {
				if err != nil {
					t.Fatal(err)
				}
				if got := asGeneric(t, result); !reflect.DeepEqual(got, c.ExpectResult) {
					t.Fatalf("got %v, expected %v", got, c.ExpectResult)
				}
			}
			if !c.Client.NoServer {
				assertWire(t, server, c.Name)
			}
		})
	}
}

func TestWebhookCases(t *testing.T) {
	var suite struct {
		Cases []struct {
			Name      string  `json:"name"`
			Secret    string  `json:"secret"`
			Header    string  `json:"signature_header"`
			Delivery  string  `json:"delivery_id"`
			Body      string  `json:"body"`
			Base64    *string `json:"body_base64"`
			Now       int64   `json:"now"`
			Tolerance int     `json:"tolerance_seconds"`
			Valid     bool    `json:"valid"`
		} `json:"cases"`
	}
	readCases(t, "webhooks.json", &suite)
	for _, c := range suite.Cases {
		t.Run(c.Name, func(t *testing.T) {
			payload := []byte(c.Body)
			if c.Base64 != nil {
				decoded, err := base64.StdEncoding.DecodeString(*c.Base64)
				if err != nil {
					t.Fatal(err)
				}
				payload = decoded
			}
			got := VerifyWebhook(VerifyWebhookParams{
				Payload: payload, SignatureHeader: c.Header, DeliveryID: c.Delivery, Secret: c.Secret,
				Tolerance: time.Duration(c.Tolerance) * time.Second, Now: time.Unix(c.Now, 0),
			})
			if got != c.Valid {
				t.Fatalf("got %v, expected %v", got, c.Valid)
			}
		})
	}
}

func TestDefaultBaseURL(t *testing.T) {
	if DefaultBaseURL != "https://api.blindrelay.app" {
		t.Fatal(DefaultBaseURL)
	}
}

func TestClientStringFormHidesAPIKey(t *testing.T) {
	const key = "conformance-test-key-not-a-real-secret"
	client := New(key)
	if strings.Contains(fmt.Sprintf("%v %+v %#v %s", client, client, client, client), key) {
		t.Fatal("client leaks the API key")
	}
}

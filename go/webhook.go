package blindrelay

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// DefaultWebhookTolerance is how far a signature timestamp may be from the current time.
const DefaultWebhookTolerance = 300 * time.Second

var signatureTimestampPattern = regexp.MustCompile(`^(0|[1-9][0-9]*)$`)

// VerifyWebhookParams are the inputs of VerifyWebhook.
type VerifyWebhookParams struct {
	// Payload is the request body exactly as received.
	Payload []byte
	// SignatureHeader is the value of the Blindrelay-Signature header.
	SignatureHeader string
	// DeliveryID is the value of the Blindrelay-Delivery-Id header.
	DeliveryID string
	Secret     string
	// Tolerance of zero means DefaultWebhookTolerance.
	Tolerance time.Duration
	// Now of the zero time means the current time.
	Now time.Time
}

func parseSignatureHeader(header string) (timestamp string, signatures []string) {
	for _, part := range strings.Split(header, ",") {
		key, value, found := strings.Cut(part, "=")
		if !found {
			continue
		}
		key, value = strings.TrimSpace(key), strings.TrimSpace(value)
		if key == "t" && timestamp == "" {
			timestamp = value
		} else if key == "v1" {
			signatures = append(signatures, value)
		}
	}
	return timestamp, signatures
}

func withinTolerance(timestamp string, now time.Time, tolerance time.Duration) bool {
	signedAt, err := strconv.ParseInt(timestamp, 10, 64)
	if err != nil {
		return false
	}
	difference := now.Unix() - signedAt
	if difference < 0 {
		difference = -difference
	}
	return difference <= int64(tolerance/time.Second)
}

// VerifyWebhook verifies a delivery webhook: v1 is hex HMAC-SHA256 over "<t>.<delivery_id>." plus
// the raw body. A header can carry several v1 values during a secret rotation; one match is
// enough. Inbound endpoint deliveries sign a different string and do not verify here.
func VerifyWebhook(params VerifyWebhookParams) bool {
	timestamp, signatures := parseSignatureHeader(params.SignatureHeader)
	if !signatureTimestampPattern.MatchString(timestamp) || len(signatures) == 0 {
		return false
	}
	now := params.Now
	if now.IsZero() {
		now = time.Now()
	}
	tolerance := params.Tolerance
	if tolerance == 0 {
		tolerance = DefaultWebhookTolerance
	}
	if !withinTolerance(timestamp, now, tolerance) {
		return false
	}
	mac := hmac.New(sha256.New, []byte(params.Secret))
	mac.Write([]byte(timestamp + "." + params.DeliveryID + "."))
	mac.Write(params.Payload)
	expected := []byte(hex.EncodeToString(mac.Sum(nil)))
	for _, signature := range signatures {
		if hmac.Equal([]byte(signature), expected) {
			return true
		}
	}
	return false
}

use hmac::{Hmac, Mac};
use sha2::Sha256;
use std::time::{SystemTime, UNIX_EPOCH};

pub const DEFAULT_WEBHOOK_TOLERANCE_SECONDS: i64 = 300;

/// The inputs of [`verify_webhook`].
pub struct VerifyWebhookParams<'a> {
    /// The request body exactly as received.
    pub payload: &'a [u8],
    /// The value of the `Blindrelay-Signature` header.
    pub signature_header: &'a str,
    /// The value of the `Blindrelay-Delivery-Id` header.
    pub delivery_id: &'a str,
    pub secret: &'a str,
    pub tolerance_seconds: Option<i64>,
    /// Unix seconds; defaults to the current time.
    pub now: Option<i64>,
}

fn parse_signature_header(header: &str) -> (Option<&str>, Vec<&str>) {
    let mut timestamp = None;
    let mut signatures = Vec::new();
    for part in header.split(',') {
        let Some((key, value)) = part.split_once('=') else {
            continue;
        };
        match key.trim() {
            "t" if timestamp.is_none() => timestamp = Some(value.trim()),
            "v1" => signatures.push(value.trim()),
            _ => {}
        }
    }
    (timestamp, signatures)
}

fn parse_timestamp(raw: &str) -> Option<i64> {
    let digits_only = !raw.is_empty() && raw.bytes().all(|byte| byte.is_ascii_digit());
    let leading_zero = raw.len() > 1 && raw.starts_with('0');
    if digits_only && !leading_zero {
        raw.parse().ok()
    } else {
        None
    }
}

fn current_unix_seconds() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |elapsed| elapsed.as_secs() as i64)
}

fn is_lowercase_hex(value: &str) -> bool {
    value
        .bytes()
        .all(|byte| matches!(byte, b'0'..=b'9' | b'a'..=b'f'))
}

fn matches_signature(signed: &Hmac<Sha256>, signature: &str) -> bool {
    if !is_lowercase_hex(signature) {
        return false;
    }
    hex::decode(signature)
        .is_ok_and(|bytes| signed.clone().verify_slice(&bytes).is_ok())
}

/// Verifies a delivery webhook: `v1` is hex HMAC-SHA256 over `<t>.<delivery_id>.` + raw body.
/// A header can carry several `v1` values during a secret rotation; one match is enough.
/// Inbound endpoint deliveries sign a different string and do not verify here.
pub fn verify_webhook(params: &VerifyWebhookParams<'_>) -> bool {
    let (Some(raw_timestamp), signatures) = parse_signature_header(params.signature_header) else {
        return false;
    };
    let Some(timestamp) = parse_timestamp(raw_timestamp) else {
        return false;
    };
    let now = params.now.unwrap_or_else(current_unix_seconds);
    let tolerance = params
        .tolerance_seconds
        .unwrap_or(DEFAULT_WEBHOOK_TOLERANCE_SECONDS);
    if (now - timestamp).abs() > tolerance {
        return false;
    }
    let Ok(mut mac) = Hmac::<Sha256>::new_from_slice(params.secret.as_bytes()) else {
        return false;
    };
    mac.update(raw_timestamp.as_bytes());
    mac.update(b".");
    mac.update(params.delivery_id.as_bytes());
    mac.update(b".");
    mac.update(params.payload);
    signatures
        .iter()
        .any(|signature| matches_signature(&mac, signature))
}

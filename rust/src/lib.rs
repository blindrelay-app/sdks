//! Official Rust SDK for the Blindrelay transactional email API.

mod error;
mod webhook;

pub use error::{code, ApiError, Error};
pub use webhook::{verify_webhook, VerifyWebhookParams, DEFAULT_WEBHOOK_TOLERANCE_SECONDS};

use base64::{engine::general_purpose::STANDARD, Engine};
use reqwest::{
    header::{ACCEPT, AUTHORIZATION, CONTENT_TYPE, RETRY_AFTER, USER_AGENT},
    Method, Url,
};
use serde::{de::DeserializeOwned, Deserialize, Serialize};
use std::{collections::HashMap, fmt, time::Duration};

pub const DEFAULT_BASE_URL: &str = "https://api.blindrelay.app";
pub const DEFAULT_TIMEOUT: Duration = Duration::from_secs(30);

const SDK_USER_AGENT: &str = concat!("blindrelay-rust/", env!("CARGO_PKG_VERSION"));
const JSON_MEDIA_TYPE: &str = "application/json";
const IDEMPOTENCY_KEY_HEADER: &str = "Idempotency-Key";

/// One address or several; `From` conversions cover `&str`, `String`, and vectors of either.
#[derive(Clone, Serialize)]
#[serde(untagged)]
pub enum Recipients {
    One(String),
    Many(Vec<String>),
}

impl fmt::Debug for Recipients {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let count = match self {
            Self::One(_) => 1,
            Self::Many(addresses) => addresses.len(),
        };
        write!(f, "Recipients({count})")
    }
}

impl From<&str> for Recipients {
    fn from(address: &str) -> Self {
        Self::One(address.to_string())
    }
}

impl From<String> for Recipients {
    fn from(address: String) -> Self {
        Self::One(address)
    }
}

impl From<Vec<String>> for Recipients {
    fn from(addresses: Vec<String>) -> Self {
        Self::Many(addresses)
    }
}

impl From<Vec<&str>> for Recipients {
    fn from(addresses: Vec<&str>) -> Self {
        Self::Many(addresses.into_iter().map(str::to_string).collect())
    }
}

/// One file. [`Attachment::from_bytes`] builds it from file content.
#[derive(Clone, Serialize)]
pub struct Attachment {
    pub filename: String,
    pub content_type: String,
    pub content_base64: String,
}

impl Attachment {
    pub fn from_bytes(
        filename: impl Into<String>,
        content_type: impl Into<String>,
        bytes: &[u8],
    ) -> Self {
        Self {
            filename: filename.into(),
            content_type: content_type.into(),
            content_base64: STANDARD.encode(bytes),
        }
    }
}

impl fmt::Debug for Attachment {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Attachment")
            .field("filename", &self.filename)
            .field("content_type", &self.content_type)
            .finish_non_exhaustive()
    }
}

#[derive(Debug, Clone, Serialize)]
pub struct Tag {
    pub key: String,
    pub value: String,
}

/// One message: `SendRequest::new(from, to, subject, html)` plus the optional setters.
#[derive(Clone, Serialize)]
pub struct SendRequest {
    from: String,
    to: Recipients,
    subject: String,
    html: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    text: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    headers: Option<HashMap<String, String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    cc: Option<Recipients>,
    #[serde(skip_serializing_if = "Option::is_none")]
    bcc: Option<Recipients>,
    #[serde(skip_serializing_if = "Option::is_none")]
    attachments: Option<Vec<Attachment>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    tags: Option<Vec<Tag>>,
    #[serde(skip)]
    idempotency_key: Option<String>,
}

impl fmt::Debug for SendRequest {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("SendRequest")
            .field("to", &self.to)
            .field("cc", &self.cc)
            .field("bcc", &self.bcc)
            .field("attachments", &self.attachments)
            .finish_non_exhaustive()
    }
}

impl SendRequest {
    pub fn new(
        from: impl Into<String>,
        to: impl Into<Recipients>,
        subject: impl Into<String>,
        html: impl Into<String>,
    ) -> Self {
        Self {
            from: from.into(),
            to: to.into(),
            subject: subject.into(),
            html: html.into(),
            text: None,
            headers: None,
            cc: None,
            bcc: None,
            attachments: None,
            tags: None,
            idempotency_key: None,
        }
    }

    pub fn text(mut self, text: impl Into<String>) -> Self {
        self.text = Some(text.into());
        self
    }

    pub fn headers(mut self, headers: HashMap<String, String>) -> Self {
        self.headers = Some(headers);
        self
    }

    pub fn cc(mut self, cc: impl Into<Recipients>) -> Self {
        self.cc = Some(cc.into());
        self
    }

    pub fn bcc(mut self, bcc: impl Into<Recipients>) -> Self {
        self.bcc = Some(bcc.into());
        self
    }

    pub fn attachments(mut self, attachments: Vec<Attachment>) -> Self {
        self.attachments = Some(attachments);
        self
    }

    pub fn tags(mut self, tags: Vec<Tag>) -> Self {
        self.tags = Some(tags);
        self
    }

    pub fn idempotency_key(mut self, key: impl Into<String>) -> Self {
        self.idempotency_key = Some(key.into());
        self
    }
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct SendResult {
    pub id: String,
    #[serde(default)]
    pub ids: Vec<String>,
    pub status: String,
    #[serde(default)]
    pub warnings: Vec<String>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct EmailStatus {
    pub id: String,
    pub status: String,
    pub summary: String,
    #[serde(rename = "final")]
    pub is_final: bool,
    pub accepted_at: Option<String>,
    pub updated_at: String,
    pub lookup_window_hours: u32,
    pub sandbox: bool,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct EmailListItem {
    pub id: String,
    pub status: String,
    pub summary: String,
    #[serde(rename = "final")]
    pub is_final: bool,
    pub domain: Option<String>,
    pub api_key_id: Option<String>,
    pub accepted_at: Option<String>,
    pub updated_at: String,
    pub sandbox: bool,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct EmailList {
    pub emails: Vec<EmailListItem>,
    pub next_cursor: Option<String>,
    pub lookup_window_hours: u32,
}

/// Filters for [`Client::list_emails`]. `None` is left out of the query.
#[derive(Debug, Clone, Default)]
pub struct ListEmailsParams {
    pub status: Option<String>,
    pub domain: Option<String>,
    pub page_size: Option<u32>,
    pub cursor: Option<String>,
}

pub struct ClientBuilder {
    api_key: String,
    base_url: String,
    timeout: Duration,
}

impl ClientBuilder {
    pub fn base_url(mut self, base_url: impl Into<String>) -> Self {
        self.base_url = base_url.into();
        self
    }

    pub fn timeout(mut self, timeout: Duration) -> Self {
        self.timeout = timeout;
        self
    }

    pub fn build(self) -> Result<Client, Error> {
        let base_url = Url::parse(&self.base_url).map_err(|_| Error::InvalidBaseUrl)?;
        if !matches!(base_url.scheme(), "http" | "https") {
            return Err(Error::InvalidBaseUrl);
        }
        let http = reqwest::Client::builder()
            .timeout(self.timeout)
            .build()
            .map_err(|_| Error::Connection)?;
        Ok(Client {
            api_key: self.api_key,
            base_url,
            http,
        })
    }
}

pub struct Client {
    api_key: String,
    base_url: Url,
    http: reqwest::Client,
}

impl fmt::Debug for Client {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Client")
            .field("base_url", &self.base_url.as_str())
            .finish_non_exhaustive()
    }
}

#[derive(Deserialize)]
struct ErrorResponse {
    error: ErrorEnvelope,
}

#[derive(Deserialize)]
struct ErrorEnvelope {
    code: String,
    #[serde(default)]
    message: Option<String>,
    #[serde(default)]
    retry_after_secs: Option<u64>,
}

fn transport_error(error: reqwest::Error) -> Error {
    if error.is_timeout() {
        Error::Timeout
    } else {
        Error::Connection
    }
}

fn api_error(status: u16, retry_after_header: Option<u64>, text: &str) -> Error {
    let parsed = serde_json::from_str::<ErrorResponse>(text).ok();
    let fallback_message = format!("HTTP {status}");
    let (code, message, body_retry_after) = match parsed {
        Some(ErrorResponse { error }) => (
            error.code,
            error.message.unwrap_or(fallback_message),
            error.retry_after_secs,
        ),
        None => (code::UNKNOWN_ERROR.to_string(), fallback_message, None),
    };
    Error::Api(ApiError {
        status,
        code,
        message,
        retry_after: retry_after_header.or(body_retry_after),
    })
}

impl Client {
    pub fn new(api_key: impl Into<String>) -> Result<Self, Error> {
        Self::builder(api_key).build()
    }

    pub fn builder(api_key: impl Into<String>) -> ClientBuilder {
        ClientBuilder {
            api_key: api_key.into(),
            base_url: DEFAULT_BASE_URL.to_string(),
            timeout: DEFAULT_TIMEOUT,
        }
    }

    /// Queues a message for delivery: `POST /v1/send`.
    pub async fn send(&self, request: &SendRequest) -> Result<SendResult, Error> {
        let body = serde_json::to_string(request).map_err(|_| Error::InvalidResponse)?;
        let builder = self
            .http
            .request(Method::POST, self.url(&["v1", "send"]))
            .header(CONTENT_TYPE, JSON_MEDIA_TYPE)
            .body(body);
        let builder = match &request.idempotency_key {
            Some(key) => builder.header(IDEMPOTENCY_KEY_HEADER, key),
            None => builder,
        };
        self.execute(builder).await
    }

    /// The delivery status of one message: `GET /v1/emails/{id}`.
    pub async fn get_email(&self, id: &str) -> Result<EmailStatus, Error> {
        let url = self.url(&["v1", "emails", id]);
        self.execute(self.http.request(Method::GET, url)).await
    }

    /// One page of recent messages: `GET /v1/emails`.
    pub async fn list_emails(&self, params: &ListEmailsParams) -> Result<EmailList, Error> {
        let mut url = self.url(&["v1", "emails"]);
        let page_size = params.page_size.map(|size| size.to_string());
        let pairs = [
            ("status", params.status.as_deref()),
            ("domain", params.domain.as_deref()),
            ("page_size", page_size.as_deref()),
            ("cursor", params.cursor.as_deref()),
        ];
        for (name, value) in pairs {
            if let Some(value) = value {
                url.query_pairs_mut().append_pair(name, value);
            }
        }
        self.execute(self.http.request(Method::GET, url)).await
    }

    fn url(&self, segments: &[&str]) -> Url {
        let mut url = self.base_url.clone();
        url.path_segments_mut()
            .expect("build() accepts only http(s) URLs")
            .pop_if_empty()
            .extend(segments);
        url
    }

    async fn execute<T: DeserializeOwned>(
        &self,
        builder: reqwest::RequestBuilder,
    ) -> Result<T, Error> {
        let response = builder
            .header(AUTHORIZATION, format!("Bearer {}", self.api_key))
            .header(ACCEPT, JSON_MEDIA_TYPE)
            .header(USER_AGENT, SDK_USER_AGENT)
            .send()
            .await
            .map_err(transport_error)?;
        let status = response.status();
        let retry_after = response
            .headers()
            .get(RETRY_AFTER)
            .and_then(|value| value.to_str().ok())
            .and_then(|value| value.trim().parse().ok());
        let text = response.text().await.map_err(transport_error)?;
        if !status.is_success() {
            return Err(api_error(status.as_u16(), retry_after, &text));
        }
        serde_json::from_str(&text).map_err(|_| Error::InvalidResponse)
    }
}

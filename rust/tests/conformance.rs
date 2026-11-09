use base64::{engine::general_purpose::STANDARD, Engine};
use blindrelay::{
    verify_webhook, Attachment, Client, Error, ListEmailsParams, Recipients, SendRequest, Tag,
    VerifyWebhookParams, DEFAULT_BASE_URL,
};
use serde_json::{json, Value};
use std::{collections::HashMap, time::Duration};

const UNREACHABLE_URL: &str = "http://127.0.0.1:1";

fn read_cases(file: &str) -> Value {
    let path = format!("{}/../conformance/cases/{file}", env!("CARGO_MANIFEST_DIR"));
    serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap()
}

fn string_of(value: &Value, key: &str) -> Option<String> {
    value.get(key).and_then(Value::as_str).map(str::to_string)
}

fn recipients(value: &Value) -> Recipients {
    match value {
        Value::Array(items) => {
            let addresses: Vec<String> = items
                .iter()
                .map(|item| item.as_str().unwrap().to_string())
                .collect();
            addresses.into()
        }
        other => other.as_str().unwrap().into(),
    }
}

fn attachment(raw: &Value) -> Attachment {
    let filename = raw["filename"].as_str().unwrap();
    let content_type = raw["content_type"].as_str().unwrap();
    match raw.get("from_bytes_base64") {
        Some(encoded) => Attachment::from_bytes(
            filename,
            content_type,
            &STANDARD.decode(encoded.as_str().unwrap()).unwrap(),
        ),
        None => Attachment {
            filename: filename.to_string(),
            content_type: content_type.to_string(),
            content_base64: raw["content_base64"].as_str().unwrap().to_string(),
        },
    }
}

fn send_request(input: &Value) -> SendRequest {
    let mut request = SendRequest::new(
        input["from"].as_str().unwrap(),
        recipients(&input["to"]),
        input["subject"].as_str().unwrap(),
        input["html"].as_str().unwrap(),
    );
    if let Some(text) = string_of(input, "text") {
        request = request.text(text);
    }
    if let Some(headers) = input.get("headers") {
        let map: HashMap<String, String> = serde_json::from_value(headers.clone()).unwrap();
        request = request.headers(map);
    }
    if let Some(cc) = input.get("cc") {
        request = request.cc(recipients(cc));
    }
    if let Some(bcc) = input.get("bcc") {
        request = request.bcc(recipients(bcc));
    }
    if let Some(attachments) = input.get("attachments") {
        request = request.attachments(attachments.as_array().unwrap().iter().map(attachment).collect());
    }
    if let Some(tags) = input.get("tags") {
        let tags: Vec<Tag> = tags
            .as_array()
            .unwrap()
            .iter()
            .map(|raw| Tag {
                key: raw["key"].as_str().unwrap().to_string(),
                value: raw["value"].as_str().unwrap().to_string(),
            })
            .collect();
        request = request.tags(tags);
    }
    if let Some(key) = string_of(input, "idempotency_key") {
        request = request.idempotency_key(key);
    }
    request
}

async fn invoke(client: &Client, case: &Value) -> Result<Value, Error> {
    let input = &case["input"];
    match case["operation"].as_str().unwrap() {
        "send" => client
            .send(&send_request(input))
            .await
            .map(|result| serde_json::to_value(result).unwrap()),
        "get_email" => client
            .get_email(input["id"].as_str().unwrap())
            .await
            .map(|result| serde_json::to_value(result).unwrap()),
        "list_emails" => {
            let params = ListEmailsParams {
                status: string_of(input, "status"),
                domain: string_of(input, "domain"),
                page_size: input.get("page_size").and_then(Value::as_u64).map(|size| size as u32),
                cursor: string_of(input, "cursor"),
            };
            client
                .list_emails(&params)
                .await
                .map(|result| serde_json::to_value(result).unwrap())
        }
        other => panic!("unknown operation {other}"),
    }
}

fn client_for(case: &Value, server: &str, api_key: &str) -> Client {
    let empty = json!({});
    let options = case.get("client").unwrap_or(&empty);
    let no_server = options.get("no_server").is_some();
    let mut base_url = if no_server {
        UNREACHABLE_URL.to_string()
    } else {
        format!("{server}/case/{}", case["name"].as_str().unwrap())
    };
    if options.get("trailing_slash").is_some() {
        base_url.push('/');
    }
    let mut builder = Client::builder(api_key).base_url(base_url);
    if let Some(timeout) = options.get("timeout_ms").and_then(Value::as_u64) {
        builder = builder.timeout(Duration::from_millis(timeout));
    }
    builder.build().unwrap()
}

fn check_error(error: &Error, expected: &Value, api_key: &str) -> Result<(), String> {
    match (expected["kind"].as_str().unwrap(), error) {
        ("timeout", Error::Timeout) | ("connection", Error::Connection) => Ok(()),
        ("api", Error::Api(api)) => {
            let actual = json!({
                "kind": "api",
                "status": api.status,
                "code": api.code,
                "message": api.message,
                "retry_after": api.retry_after,
            });
            if &actual != expected {
                return Err(format!("got {actual}, expected {expected}"));
            }
            if format!("{error:?} {error}").contains(api_key) {
                return Err("error leaks the API key".into());
            }
            Ok(())
        }
        (kind, other) => Err(format!("expected {kind}, got {other:?}")),
    }
}

async fn check_wire(server: &str, name: &str) -> Result<(), String> {
    let verdict: Value = reqwest::get(format!("{server}/__verdict/{name}"))
        .await
        .unwrap()
        .text()
        .await
        .map(|text| serde_json::from_str(&text).unwrap())
        .unwrap();
    if verdict == json!({ "received": 1, "mismatches": [] }) {
        Ok(())
    } else {
        Err(format!("wire verdict {verdict}"))
    }
}

async fn run_http_case(case: &Value, server: &str, api_key: &str) -> Result<(), String> {
    let outcome = invoke(&client_for(case, server, api_key), case).await;
    match (case.get("expect_error"), outcome) {
        (Some(expected), Err(error)) => check_error(&error, expected, api_key)?,
        (Some(_), Ok(result)) => return Err(format!("expected an error, got {result}")),
        (None, Err(error)) => return Err(format!("unexpected error {error:?}")),
        (None, Ok(result)) => {
            if result != case["expect_result"] {
                return Err(format!("got {result}, expected {}", case["expect_result"]));
            }
        }
    }
    if case.pointer("/client/no_server").is_none() {
        check_wire(server, case["name"].as_str().unwrap()).await?;
    }
    Ok(())
}

fn run_webhook_case(case: &Value) -> Result<(), String> {
    let payload = match case.get("body_base64") {
        Some(encoded) => STANDARD.decode(encoded.as_str().unwrap()).unwrap(),
        None => case["body"].as_str().unwrap().as_bytes().to_vec(),
    };
    let valid = verify_webhook(&VerifyWebhookParams {
        payload: &payload,
        signature_header: case["signature_header"].as_str().unwrap(),
        delivery_id: case["delivery_id"].as_str().unwrap(),
        secret: case["secret"].as_str().unwrap(),
        tolerance_seconds: case.get("tolerance_seconds").and_then(Value::as_i64),
        now: case["now"].as_i64(),
    });
    if valid == case["valid"].as_bool().unwrap() {
        Ok(())
    } else {
        Err(format!("got {valid}, expected {}", case["valid"]))
    }
}

#[tokio::test]
async fn conformance() {
    let server = std::env::var("BLINDRELAY_CONFORMANCE_URL")
        .expect("Set BLINDRELAY_CONFORMANCE_URL (see sdks/conformance/run-all.sh)");
    let http = read_cases("http.json");
    let api_key = http["api_key"].as_str().unwrap();
    let mut failures = Vec::new();
    let mut total = 0;
    for case in http["cases"].as_array().unwrap() {
        total += 1;
        if let Err(reason) = run_http_case(case, &server, api_key).await {
            failures.push(format!("http {}: {reason}", case["name"]));
        }
    }
    for case in read_cases("webhooks.json")["cases"].as_array().unwrap() {
        total += 1;
        if let Err(reason) = run_webhook_case(case) {
            failures.push(format!("webhook {}: {reason}", case["name"]));
        }
    }
    println!("{}/{total} cases passed", total - failures.len());
    assert!(failures.is_empty(), "{}", failures.join("\n"));
}

#[test]
fn default_base_url() {
    assert_eq!(DEFAULT_BASE_URL, "https://api.blindrelay.app");
}

#[test]
fn debug_output_hides_the_key_and_the_message() {
    let client = Client::new("conformance-test-key-not-a-real-secret").unwrap();
    assert!(!format!("{client:?}").contains("conformance-test-key"));
    let request = SendRequest::new("a@example.com", "secret-recipient@example.com", "Hi", "<p>body-text</p>");
    let debug = format!("{request:?}");
    assert!(!debug.contains("secret-recipient") && !debug.contains("body-text"));
}

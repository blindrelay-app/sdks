require "json"
require "net/http"
require "uri"
require_relative "../lib/blindrelay"

SERVER_URL = ENV["BLINDRELAY_CONFORMANCE_URL"].to_s
abort "Set BLINDRELAY_CONFORMANCE_URL (see sdks/conformance/run-all.sh)" if SERVER_URL.empty?

UNREACHABLE_URL = "http://127.0.0.1:1"
CASES_DIR = File.expand_path("../../conformance/cases", __dir__)
HTTP = JSON.parse(File.read(File.join(CASES_DIR, "http.json"), encoding: "UTF-8"))
WEBHOOKS = JSON.parse(File.read(File.join(CASES_DIR, "webhooks.json"), encoding: "UTF-8"))
API_KEY = HTTP["api_key"]

class AssertionFailed < StandardError; end

def assert_equal(actual, expected, label)
  return if actual == expected

  raise AssertionFailed, "#{label}: got #{actual.inspect}, expected #{expected.inspect}"
end

def client_for(test_case)
  options = test_case["client"] || {}
  base_url = options["no_server"] ? UNREACHABLE_URL : "#{SERVER_URL}/case/#{test_case["name"]}"
  base_url += "/" if options["trailing_slash"]
  timeout = options["timeout_ms"] ? options["timeout_ms"] / 1000.0 : Blindrelay::Client::DEFAULT_TIMEOUT_SECONDS
  Blindrelay::Client.new(API_KEY, base_url: base_url, timeout: timeout)
end

def to_attachment(raw)
  if raw["from_bytes_base64"]
    return Blindrelay::Attachment.from_bytes(raw["filename"], raw["content_type"], raw["from_bytes_base64"].unpack1("m0"))
  end

  Blindrelay::Attachment.new(filename: raw["filename"], content_type: raw["content_type"], content_base64: raw["content_base64"])
end

def invoke(client, test_case)
  input = test_case["input"].transform_keys(&:to_sym)
  case test_case["operation"]
  when "send"
    input[:attachments] = input[:attachments].map { |raw| to_attachment(raw) } if input[:attachments]
    client.send_email(**input)
  when "get_email" then client.get_email(input[:id])
  when "list_emails" then client.list_emails(**input)
  else raise AssertionFailed, "unknown operation"
  end
end

def to_wire(value)
  case value
  when Struct then value.members.to_h { |member| [member.to_s, to_wire(value[member])] }
  when Array then value.map { |item| to_wire(item) }
  else value
  end
end

def assert_expected_error(error, expected)
  case expected["kind"]
  when "timeout" then assert_equal(error.class, Blindrelay::TimeoutError, "error class")
  when "connection" then assert_equal(error.class, Blindrelay::ConnectionError, "error class")
  else
    assert_equal(error.class, Blindrelay::ApiError, "error class")
    assert_equal(error.status, expected["status"], "status")
    assert_equal(error.code, expected["code"], "code")
    assert_equal(error.message, expected["message"], "message")
    assert_equal(error.retry_after, expected["retry_after"], "retry after")
    raise AssertionFailed, "error leaks the API key" if error.inspect.include?(API_KEY)
  end
end

def assert_wire(name)
  verdict = JSON.parse(Net::HTTP.get(URI("#{SERVER_URL}/__verdict/#{name}")))
  assert_equal(verdict, { "received" => 1, "mismatches" => [] }, "wire verdict")
end

def run_http_case(test_case)
  begin
    result = invoke(client_for(test_case), test_case)
    raise AssertionFailed, "expected an error" if test_case["expect_error"]

    assert_equal(to_wire(result), test_case["expect_result"], "result")
  rescue Blindrelay::Error => e
    raise unless test_case["expect_error"]

    assert_expected_error(e, test_case["expect_error"])
  end
  assert_wire(test_case["name"]) unless test_case.dig("client", "no_server")
end

def run_webhook_case(test_case)
  payload = test_case["body_base64"] ? test_case["body_base64"].unpack1("m0") : test_case["body"]
  options = {}
  options[:tolerance_seconds] = test_case["tolerance_seconds"] if test_case["tolerance_seconds"]
  valid = Blindrelay::Webhook.verify(payload: payload, signature_header: test_case["signature_header"],
                                     delivery_id: test_case["delivery_id"], secret: test_case["secret"],
                                     now: test_case["now"], **options)
  assert_equal(valid, test_case["valid"], "valid")
end

$total = 0
$failures = 0

def check(name)
  $total += 1
  yield
rescue StandardError => e
  $failures += 1
  puts "FAIL #{name}: #{e.class}: #{e.message}"
end

HTTP["cases"].each { |test_case| check("http #{test_case["name"]}") { run_http_case(test_case) } }
WEBHOOKS["cases"].each { |test_case| check("webhook #{test_case["name"]}") { run_webhook_case(test_case) } }
check("default base url") { assert_equal(Blindrelay::Client::DEFAULT_BASE_URL, "https://api.blindrelay.app", "base url") }
check("client string form hides the api key") do
  client = Blindrelay::Client.new(API_KEY)
  raise AssertionFailed, "client leaks the API key" if client.inspect.include?(API_KEY) || client.to_s.include?(API_KEY)
end

puts "#{$total - $failures}/#{$total} passed"
exit($failures.zero? ? 0 : 1)

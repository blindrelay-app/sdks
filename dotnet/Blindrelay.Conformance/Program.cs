using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.Json.Serialization;
using Blindrelay;

const string UnreachableUrl = "http://127.0.0.1:1";

var serverUrl = Environment.GetEnvironmentVariable("BLINDRELAY_CONFORMANCE_URL");
if (string.IsNullOrEmpty(serverUrl))
{
    Console.Error.WriteLine("Set BLINDRELAY_CONFORMANCE_URL (see sdks/conformance/run-all.sh)");
    return 2;
}

var casesDir = Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "..", "..", "conformance", "cases");
var http = JsonNode.Parse(File.ReadAllText(Path.Combine(casesDir, "http.json")))!;
var webhooks = JsonNode.Parse(File.ReadAllText(Path.Combine(casesDir, "webhooks.json")))!;
var apiKey = http["api_key"]!.GetValue<string>();
var wireJson = new JsonSerializerOptions { PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower };
using var verdicts = new HttpClient();

var total = 0;
var failures = 0;

async Task Check(string name, Func<Task> body)
{
    total++;
    try
    {
        await body();
    }
    catch (Exception error)
    {
        failures++;
        Console.WriteLine($"FAIL {name}: {error.GetType().Name}: {error.Message}");
    }
}

static void Require(bool condition, string message)
{
    if (!condition)
    {
        throw new InvalidOperationException(message);
    }
}

static string? Text(JsonNode? node, string name) => node?[name]?.GetValue<string>();

static Recipients ToRecipients(JsonNode node) =>
    node is JsonArray array ? array.Select(item => item!.GetValue<string>()).ToArray() : node.GetValue<string>();

static Attachment ToAttachment(JsonNode raw)
{
    var filename = Text(raw, "filename")!;
    var contentType = Text(raw, "content_type")!;
    var fromBytes = Text(raw, "from_bytes_base64");
    return fromBytes is null
        ? new Attachment(filename, contentType, Text(raw, "content_base64")!)
        : Attachment.FromBytes(filename, contentType, Convert.FromBase64String(fromBytes));
}

static SendRequest ToSendRequest(JsonNode input) => new()
{
    From = Text(input, "from")!,
    To = ToRecipients(input["to"]!),
    Subject = Text(input, "subject")!,
    Html = Text(input, "html")!,
    Text = Text(input, "text"),
    Headers = input["headers"]?.AsObject().ToDictionary(pair => pair.Key, pair => pair.Value!.GetValue<string>()),
    Cc = input["cc"] is { } cc ? ToRecipients(cc) : null,
    Bcc = input["bcc"] is { } bcc ? ToRecipients(bcc) : null,
    Attachments = input["attachments"]?.AsArray().Select(raw => ToAttachment(raw!)).ToArray(),
    Tags = input["tags"]?.AsArray().Select(raw => new Tag(Text(raw, "key")!, Text(raw, "value")!)).ToArray(),
    IdempotencyKey = Text(input, "idempotency_key"),
};

BlindrelayClient ClientFor(JsonNode testCase)
{
    var options = testCase["client"];
    var noServer = options?["no_server"] is not null;
    var baseUrl = noServer ? UnreachableUrl : $"{serverUrl}/case/{Text(testCase, "name")}";
    if (options?["trailing_slash"] is not null)
    {
        baseUrl += "/";
    }
    var timeoutMs = options?["timeout_ms"]?.GetValue<int>();
    return new BlindrelayClient(apiKey, new BlindrelayClientOptions
    {
        BaseUrl = baseUrl,
        Timeout = timeoutMs is null ? BlindrelayClient.DefaultTimeout : TimeSpan.FromMilliseconds(timeoutMs.Value),
    });
}

async Task<JsonNode?> Invoke(BlindrelayClient client, JsonNode testCase)
{
    var input = testCase["input"]!;
    switch (Text(testCase, "operation"))
    {
        case "send":
            return JsonSerializer.SerializeToNode(await client.SendAsync(ToSendRequest(input)), wireJson);
        case "get_email":
            return JsonSerializer.SerializeToNode(await client.GetEmailAsync(Text(input, "id")!), wireJson);
        default:
            var request = new ListEmailsRequest
            {
                Status = Text(input, "status"),
                Domain = Text(input, "domain"),
                PageSize = input["page_size"]?.GetValue<int>(),
                Cursor = Text(input, "cursor"),
            };
            return JsonSerializer.SerializeToNode(await client.ListEmailsAsync(request), wireJson);
    }
}

void CheckError(BlindrelayException error, JsonNode expected)
{
    switch (Text(expected, "kind"))
    {
        case "timeout":
            Require(error is BlindrelayTimeoutException, $"expected timeout, got {error.GetType().Name}");
            return;
        case "connection":
            Require(error is BlindrelayConnectionException, $"expected connection error, got {error.GetType().Name}");
            return;
    }
    var api = error as BlindrelayApiException ?? throw new InvalidOperationException($"expected API error, got {error.GetType().Name}");
    Require(api.Status == expected["status"]!.GetValue<int>(), $"status {api.Status}");
    Require(api.Code == Text(expected, "code"), $"code {api.Code}");
    Require(api.Message == Text(expected, "message"), $"message {api.Message}");
    Require(api.RetryAfter == expected["retry_after"]?.GetValue<int>(), $"retry after {api.RetryAfter}");
    Require(!api.ToString().Contains(apiKey), "error leaks the API key");
}

async Task CheckWire(JsonNode testCase)
{
    if (testCase["client"]?["no_server"] is not null)
    {
        return;
    }
    var verdict = JsonNode.Parse(await verdicts.GetStringAsync($"{serverUrl}/__verdict/{Text(testCase, "name")}"));
    Require(JsonNode.DeepEquals(verdict, JsonNode.Parse("{\"received\":1,\"mismatches\":[]}")), $"wire verdict {verdict}");
}

async Task RunHttpCase(JsonNode testCase)
{
    using var client = ClientFor(testCase);
    var expectError = testCase["expect_error"];
    try
    {
        var result = await Invoke(client, testCase);
        Require(expectError is null, "expected an error");
        Require(JsonNode.DeepEquals(result, testCase["expect_result"]), $"got {result}, expected {testCase["expect_result"]}");
    }
    catch (BlindrelayException error) when (expectError is not null)
    {
        CheckError(error, expectError);
    }
    await CheckWire(testCase);
}

void RunWebhookCase(JsonNode testCase)
{
    var payload = Text(testCase, "body_base64") is { } encoded
        ? Convert.FromBase64String(encoded)
        : System.Text.Encoding.UTF8.GetBytes(Text(testCase, "body")!);
    var valid = Webhook.Verify(
        payload,
        Text(testCase, "signature_header")!,
        Text(testCase, "delivery_id")!,
        Text(testCase, "secret")!,
        testCase["tolerance_seconds"]?.GetValue<long>() ?? Webhook.DefaultToleranceSeconds,
        testCase["now"]!.GetValue<long>());
    Require(valid == testCase["valid"]!.GetValue<bool>(), $"got {valid}");
}

foreach (var testCase in http["cases"]!.AsArray())
{
    await Check($"http {Text(testCase, "name")}", () => RunHttpCase(testCase!));
}
foreach (var testCase in webhooks["cases"]!.AsArray())
{
    await Check($"webhook {Text(testCase, "name")}", () => { RunWebhookCase(testCase!); return Task.CompletedTask; });
}
await Check("default base url", () => { Require(BlindrelayClient.DefaultBaseUrl == "https://api.blindrelay.app", "base url"); return Task.CompletedTask; });
await Check("client string form hides the api key", () =>
{
    using var client = new BlindrelayClient(apiKey);
    Require(!client.ToString().Contains(apiKey), "client leaks the API key");
    return Task.CompletedTask;
});

Console.WriteLine($"{total - failures}/{total} passed");
return failures == 0 ? 0 : 1;

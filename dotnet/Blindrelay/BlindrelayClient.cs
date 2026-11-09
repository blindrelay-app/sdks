using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Blindrelay;

public sealed class BlindrelayClientOptions
{
    public string BaseUrl { get; init; } = BlindrelayClient.DefaultBaseUrl;

    public TimeSpan Timeout { get; init; } = BlindrelayClient.DefaultTimeout;
}

public sealed class BlindrelayClient : IDisposable
{
    public const string Version = "0.1.0";
    public const string DefaultBaseUrl = "https://api.blindrelay.app";
    public static readonly TimeSpan DefaultTimeout = TimeSpan.FromSeconds(30);

    private const string UserAgent = "blindrelay-dotnet/" + Version;
    private const string JsonMediaType = "application/json";

    internal static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };

    private readonly string _apiKey;
    private readonly string _baseUrl;
    private readonly TimeSpan _timeout;
    private readonly HttpClient _http = new() { Timeout = System.Threading.Timeout.InfiniteTimeSpan };

    public BlindrelayClient(string apiKey, BlindrelayClientOptions? options = null)
    {
        options ??= new BlindrelayClientOptions();
        _apiKey = apiKey;
        _baseUrl = options.BaseUrl.TrimEnd('/');
        _timeout = options.Timeout;
    }

    public void Dispose() => _http.Dispose();

    public override string ToString() => $"BlindrelayClient {{ BaseUrl = {_baseUrl} }}";

    /// <summary>Queues a message for delivery: POST /v1/send.</summary>
    public Task<SendResult> SendAsync(SendRequest request, CancellationToken cancellationToken = default) =>
        RequestAsync<SendResult>(HttpMethod.Post, "/v1/send", request, request.IdempotencyKey, cancellationToken);

    /// <summary>The delivery status of one message: GET /v1/emails/{id}.</summary>
    public Task<EmailStatus> GetEmailAsync(string id, CancellationToken cancellationToken = default) =>
        RequestAsync<EmailStatus>(HttpMethod.Get, "/v1/emails/" + Uri.EscapeDataString(id), null, null, cancellationToken);

    /// <summary>One page of recent messages: GET /v1/emails.</summary>
    public Task<EmailList> ListEmailsAsync(ListEmailsRequest? request = null, CancellationToken cancellationToken = default) =>
        RequestAsync<EmailList>(HttpMethod.Get, "/v1/emails" + QueryString(request), null, null, cancellationToken);

    private static string QueryString(ListEmailsRequest? request)
    {
        if (request is null)
        {
            return string.Empty;
        }
        var parts = new (string Name, string? Value)[]
        {
            ("status", request.Status),
            ("domain", request.Domain),
            ("page_size", request.PageSize?.ToString()),
            ("cursor", request.Cursor),
        }
            .Where(part => part.Value is not null)
            .Select(part => $"{part.Name}={Uri.EscapeDataString(part.Value!)}")
            .ToArray();
        return parts.Length == 0 ? string.Empty : "?" + string.Join('&', parts);
    }

    private async Task<T> RequestAsync<T>(
        HttpMethod method,
        string path,
        SendRequest? body,
        string? idempotencyKey,
        CancellationToken cancellationToken)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(_timeout);
        using var request = BuildRequest(method, path, body, idempotencyKey);
        try
        {
            using var response = await _http.SendAsync(request, timeout.Token).ConfigureAwait(false);
            var text = await response.Content.ReadAsStringAsync(timeout.Token).ConfigureAwait(false);
            if (!response.IsSuccessStatusCode)
            {
                throw ToApiError((int)response.StatusCode, RetryAfterHeader(response), text);
            }
            return JsonSerializer.Deserialize<T>(text, JsonOptions)!;
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            throw new BlindrelayTimeoutException();
        }
        catch (HttpRequestException)
        {
            throw new BlindrelayConnectionException();
        }
    }

    private HttpRequestMessage BuildRequest(HttpMethod method, string path, SendRequest? body, string? idempotencyKey)
    {
        var request = new HttpRequestMessage(method, _baseUrl + path);
        request.Headers.TryAddWithoutValidation("Authorization", "Bearer " + _apiKey);
        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue(JsonMediaType));
        request.Headers.TryAddWithoutValidation("User-Agent", UserAgent);
        if (idempotencyKey is not null)
        {
            request.Headers.TryAddWithoutValidation("Idempotency-Key", idempotencyKey);
        }
        if (body is not null)
        {
            var json = JsonSerializer.Serialize(body, JsonOptions);
            request.Content = new StringContent(json, Encoding.UTF8, JsonMediaType);
        }
        return request;
    }

    private static string? RetryAfterHeader(HttpResponseMessage response) =>
        response.Headers.TryGetValues("Retry-After", out var values) ? values.FirstOrDefault() : null;

    private static BlindrelayApiException ToApiError(int status, string? retryAfterHeader, string text)
    {
        var fallbackMessage = $"HTTP {status}";
        var envelope = ParseErrorEnvelope(text);
        if (envelope is { } error && error.TryGetProperty("code", out var code) && code.ValueKind == JsonValueKind.String)
        {
            var message = error.TryGetProperty("message", out var raw) && raw.ValueKind == JsonValueKind.String
                ? raw.GetString()!
                : fallbackMessage;
            return new BlindrelayApiException(status, code.GetString()!, message, RetryAfter(retryAfterHeader, error));
        }
        return new BlindrelayApiException(status, ErrorCode.UnknownError, fallbackMessage, RetryAfter(retryAfterHeader, null));
    }

    private static JsonElement? ParseErrorEnvelope(string text)
    {
        try
        {
            using var document = JsonDocument.Parse(text);
            if (document.RootElement.ValueKind == JsonValueKind.Object
                && document.RootElement.TryGetProperty("error", out var error)
                && error.ValueKind == JsonValueKind.Object)
            {
                return error.Clone();
            }
            return null;
        }
        catch (JsonException)
        {
            return null;
        }
    }

    private static int? RetryAfter(string? header, JsonElement? error)
    {
        if (int.TryParse(header, out var seconds) && seconds >= 0)
        {
            return seconds;
        }
        if (error is { } envelope
            && envelope.TryGetProperty("retry_after_secs", out var value)
            && value.TryGetInt32(out var fromBody))
        {
            return fromBody;
        }
        return null;
    }
}

package app.blindrelay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Client for the Blindrelay API. Safe for concurrent use. */
public final class Blindrelay {
  public static final String VERSION = "0.1.0";
  public static final String DEFAULT_BASE_URL = "https://api.blindrelay.app";
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

  private static final String USER_AGENT = "blindrelay-java/" + VERSION;
  private static final String JSON_MEDIA_TYPE = "application/json";

  private final String apiKey;
  private final String baseUrl;
  private final Duration timeout;
  private final HttpClient http;

  private Blindrelay(Builder builder) {
    this.apiKey = builder.apiKey;
    this.baseUrl = builder.baseUrl.replaceAll("/+$", "");
    this.timeout = builder.timeout;
    this.http = HttpClient.newBuilder().connectTimeout(builder.timeout).build();
  }

  public static Blindrelay create(String apiKey) {
    return builder(apiKey).build();
  }

  public static Builder builder(String apiKey) {
    return new Builder(apiKey);
  }

  public static final class Builder {
    private final String apiKey;
    private String baseUrl = DEFAULT_BASE_URL;
    private Duration timeout = DEFAULT_TIMEOUT;

    private Builder(String apiKey) {
      this.apiKey = apiKey;
    }

    public Builder baseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
      return this;
    }

    public Builder timeout(Duration timeout) {
      this.timeout = timeout;
      return this;
    }

    public Blindrelay build() {
      return new Blindrelay(this);
    }
  }

  @Override
  public String toString() {
    return "Blindrelay[baseUrl=" + baseUrl + "]";
  }

  /** Queues a message for delivery: {@code POST /v1/send}. */
  public SendResult send(SendRequest request) {
    JsonObject answer =
        execute("POST", "/v1/send", request.body().toString(), request.idempotencyKey());
    return new SendResult(
        requiredString(answer, "id"),
        stringList(answer, "ids"),
        requiredString(answer, "status"),
        stringList(answer, "warnings"));
  }

  /** The delivery status of one message: {@code GET /v1/emails/{id}}. */
  public EmailStatus getEmail(String id) {
    JsonObject answer = execute("GET", "/v1/emails/" + encode(id), null, null);
    return new EmailStatus(
        requiredString(answer, "id"),
        requiredString(answer, "status"),
        requiredString(answer, "summary"),
        answer.get("final").getAsBoolean(),
        nullableString(answer, "accepted_at"),
        requiredString(answer, "updated_at"),
        answer.get("lookup_window_hours").getAsInt(),
        answer.get("sandbox").getAsBoolean());
  }

  public EmailList listEmails() {
    return listEmails(ListEmailsParams.none());
  }

  /** One page of recent messages: {@code GET /v1/emails}. */
  public EmailList listEmails(ListEmailsParams params) {
    JsonObject answer = execute("GET", "/v1/emails" + queryString(params), null, null);
    List<EmailListItem> emails = new ArrayList<>();
    for (JsonElement element : answer.getAsJsonArray("emails")) {
      emails.add(toListItem(element.getAsJsonObject()));
    }
    return new EmailList(
        emails, nullableString(answer, "next_cursor"), answer.get("lookup_window_hours").getAsInt());
  }

  private static EmailListItem toListItem(JsonObject item) {
    return new EmailListItem(
        requiredString(item, "id"),
        requiredString(item, "status"),
        requiredString(item, "summary"),
        item.get("final").getAsBoolean(),
        nullableString(item, "domain"),
        nullableString(item, "api_key_id"),
        nullableString(item, "accepted_at"),
        requiredString(item, "updated_at"),
        item.get("sandbox").getAsBoolean());
  }

  private static String queryString(ListEmailsParams params) {
    List<String> parts = new ArrayList<>();
    addQueryPart(parts, "status", params.status());
    addQueryPart(parts, "domain", params.domain());
    addQueryPart(parts, "page_size", params.pageSize() == null ? null : params.pageSize().toString());
    addQueryPart(parts, "cursor", params.cursor());
    return parts.isEmpty() ? "" : "?" + String.join("&", parts);
  }

  private static void addQueryPart(List<String> parts, String name, String value) {
    if (value != null) {
      parts.add(name + "=" + encode(value));
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private JsonObject execute(String method, String path, String body, String idempotencyKey) {
    HttpResponse<String> response = perform(buildRequest(method, path, body, idempotencyKey));
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw toApiError(response);
    }
    return JsonParser.parseString(response.body()).getAsJsonObject();
  }

  private HttpRequest buildRequest(String method, String path, String body, String idempotencyKey) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(timeout)
            .header("Authorization", "Bearer " + apiKey)
            .header("Accept", JSON_MEDIA_TYPE)
            .header("User-Agent", USER_AGENT);
    if (idempotencyKey != null) {
      builder.header("Idempotency-Key", idempotencyKey);
    }
    if (body == null) {
      return builder.method(method, HttpRequest.BodyPublishers.noBody()).build();
    }
    return builder
        .header("Content-Type", JSON_MEDIA_TYPE)
        .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        .build();
  }

  private HttpResponse<String> perform(HttpRequest request) {
    try {
      return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (HttpTimeoutException timedOut) {
      throw new BlindrelayTimeoutException("The request to Blindrelay timed out");
    } catch (IOException failed) {
      throw new BlindrelayConnectionException("Could not reach Blindrelay");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new BlindrelayConnectionException("Interrupted while calling Blindrelay");
    }
  }

  private static BlindrelayApiException toApiError(HttpResponse<String> response) {
    int status = response.statusCode();
    Optional<JsonObject> envelope = errorEnvelope(response.body());
    Optional<String> code = envelope.map(e -> nullableString(e, "code"));
    String fallback = "HTTP " + status;
    String message =
        code.isPresent() ? envelope.map(e -> nullableString(e, "message")).orElse(fallback) : fallback;
    return new BlindrelayApiException(
        status,
        code.orElse(ErrorCode.UNKNOWN_ERROR),
        message,
        retryAfter(response.headers().firstValue("Retry-After"), envelope));
  }

  private static Optional<JsonObject> errorEnvelope(String text) {
    try {
      JsonElement parsed = JsonParser.parseString(text);
      if (parsed.isJsonObject() && parsed.getAsJsonObject().get("error") instanceof JsonObject error) {
        return Optional.of(error);
      }
    } catch (JsonParseException notJson) {
      return Optional.empty();
    }
    return Optional.empty();
  }

  private static Integer retryAfter(Optional<String> header, Optional<JsonObject> envelope) {
    if (header.isPresent() && header.get().trim().matches("[0-9]+")) {
      return Integer.valueOf(header.get().trim());
    }
    return envelope
        .map(e -> e.get("retry_after_secs"))
        .filter(value -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber())
        .map(JsonElement::getAsInt)
        .orElse(null);
  }

  private static String requiredString(JsonObject object, String name) {
    return object.get(name).getAsString();
  }

  private static String nullableString(JsonObject object, String name) {
    JsonElement value = object.get(name);
    return value == null || value.isJsonNull() ? null : value.getAsString();
  }

  private static List<String> stringList(JsonObject object, String name) {
    List<String> values = new ArrayList<>();
    JsonArray array = object.getAsJsonArray(name);
    if (array != null) {
      array.forEach(element -> values.add(element.getAsString()));
    }
    return values;
  }
}

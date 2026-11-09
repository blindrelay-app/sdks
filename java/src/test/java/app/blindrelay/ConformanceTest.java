package app.blindrelay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class ConformanceTest {
  private static final String UNREACHABLE_URL = "http://127.0.0.1:1";
  private static final String SERVER_URL = System.getenv("BLINDRELAY_CONFORMANCE_URL");
  private static final Path CASES_DIR = Path.of("..", "conformance", "cases");
  private static final JsonObject HTTP_SUITE = readCases("http.json");
  private static final String API_KEY = HTTP_SUITE.get("api_key").getAsString();

  private static JsonObject readCases(String file) {
    try {
      return JsonParser.parseString(Files.readString(CASES_DIR.resolve(file), StandardCharsets.UTF_8)).getAsJsonObject();
    } catch (IOException failed) {
      throw new IllegalStateException(failed);
    }
  }

  private static Stream<JsonObject> cases(JsonObject suite) {
    List<JsonObject> cases = new ArrayList<>();
    suite.getAsJsonArray("cases").forEach(element -> cases.add(element.getAsJsonObject()));
    return cases.stream();
  }

  private static boolean flag(JsonObject options, String name) {
    return options.has(name) && options.get(name).getAsBoolean();
  }

  private static JsonObject clientOptions(JsonObject testCase) {
    return testCase.has("client") ? testCase.getAsJsonObject("client") : new JsonObject();
  }

  private static Blindrelay clientFor(JsonObject testCase) {
    JsonObject options = clientOptions(testCase);
    String base = flag(options, "no_server") ? UNREACHABLE_URL : SERVER_URL + "/case/" + testCase.get("name").getAsString();
    if (flag(options, "trailing_slash")) {
      base += "/";
    }
    Blindrelay.Builder builder = Blindrelay.builder(API_KEY).baseUrl(base);
    if (options.has("timeout_ms")) {
      builder.timeout(Duration.ofMillis(options.get("timeout_ms").getAsLong()));
    }
    return builder.build();
  }

  private static List<String> strings(JsonElement element) {
    List<String> values = new ArrayList<>();
    element.getAsJsonArray().forEach(item -> values.add(item.getAsString()));
    return values;
  }

  private static String optional(JsonObject object, String name) {
    return object.has(name) ? object.get(name).getAsString() : null;
  }

  private static Attachment toAttachment(JsonObject raw) {
    String filename = raw.get("filename").getAsString();
    String contentType = raw.get("content_type").getAsString();
    if (raw.has("from_bytes_base64")) {
      return Attachment.fromBytes(filename, contentType, Base64.getDecoder().decode(raw.get("from_bytes_base64").getAsString()));
    }
    return new Attachment(filename, contentType, raw.get("content_base64").getAsString());
  }

  private static SendRequest toSendRequest(JsonObject input) {
    SendRequest.Builder builder =
        SendRequest.builder()
            .from(input.get("from").getAsString())
            .subject(input.get("subject").getAsString())
            .html(input.get("html").getAsString());
    applyRecipients(input.get("to"), builder::to, builder::to);
    if (input.has("cc")) {
      applyRecipients(input.get("cc"), builder::cc, builder::cc);
    }
    if (input.has("bcc")) {
      applyRecipients(input.get("bcc"), builder::bcc, builder::bcc);
    }
    if (input.has("text")) {
      builder.text(input.get("text").getAsString());
    }
    if (input.has("headers")) {
      Map<String, String> headers = new java.util.LinkedHashMap<>();
      input.getAsJsonObject("headers").entrySet().forEach(entry -> headers.put(entry.getKey(), entry.getValue().getAsString()));
      builder.headers(headers);
    }
    if (input.has("attachments")) {
      List<Attachment> attachments = new ArrayList<>();
      input.getAsJsonArray("attachments").forEach(raw -> attachments.add(toAttachment(raw.getAsJsonObject())));
      builder.attachments(attachments);
    }
    if (input.has("tags")) {
      List<Tag> tags = new ArrayList<>();
      input.getAsJsonArray("tags").forEach(raw -> tags.add(new Tag(raw.getAsJsonObject().get("key").getAsString(), raw.getAsJsonObject().get("value").getAsString())));
      builder.tags(tags);
    }
    if (input.has("idempotency_key")) {
      builder.idempotencyKey(input.get("idempotency_key").getAsString());
    }
    return builder.build();
  }

  private static void applyRecipients(
      JsonElement value,
      java.util.function.Consumer<String> single,
      java.util.function.Consumer<List<String>> many) {
    if (value.isJsonArray()) {
      many.accept(strings(value));
    } else {
      single.accept(value.getAsString());
    }
  }

  private static JsonElement nullable(String value) {
    return value == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(value);
  }

  private static JsonObject statusFields(String id, String status, String summary, boolean isFinal, String acceptedAt, String updatedAt, boolean sandbox) {
    JsonObject object = new JsonObject();
    object.addProperty("id", id);
    object.addProperty("status", status);
    object.addProperty("summary", summary);
    object.addProperty("final", isFinal);
    object.add("accepted_at", nullable(acceptedAt));
    object.addProperty("updated_at", updatedAt);
    object.addProperty("sandbox", sandbox);
    return object;
  }

  private static JsonElement toWire(Object result) {
    if (result instanceof SendResult send) {
      JsonObject object = new JsonObject();
      object.addProperty("id", send.id());
      object.add("ids", new com.google.gson.Gson().toJsonTree(send.ids()));
      object.addProperty("status", send.status());
      object.add("warnings", new com.google.gson.Gson().toJsonTree(send.warnings()));
      return object;
    }
    if (result instanceof EmailStatus status) {
      JsonObject object = statusFields(status.id(), status.status(), status.summary(), status.isFinal(), status.acceptedAt(), status.updatedAt(), status.sandbox());
      object.addProperty("lookup_window_hours", status.lookupWindowHours());
      return object;
    }
    EmailList list = (EmailList) result;
    com.google.gson.JsonArray emails = new com.google.gson.JsonArray();
    for (EmailListItem item : list.emails()) {
      JsonObject object = statusFields(item.id(), item.status(), item.summary(), item.isFinal(), item.acceptedAt(), item.updatedAt(), item.sandbox());
      object.add("domain", nullable(item.domain()));
      object.add("api_key_id", nullable(item.apiKeyId()));
      emails.add(object);
    }
    JsonObject object = new JsonObject();
    object.add("emails", emails);
    object.add("next_cursor", nullable(list.nextCursor()));
    object.addProperty("lookup_window_hours", list.lookupWindowHours());
    return object;
  }

  private static Object invoke(Blindrelay client, JsonObject testCase) {
    JsonObject input = testCase.getAsJsonObject("input");
    switch (testCase.get("operation").getAsString()) {
      case "send":
        return client.send(toSendRequest(input));
      case "get_email":
        return client.getEmail(input.get("id").getAsString());
      default:
        return client.listEmails(new ListEmailsParams(optional(input, "status"), optional(input, "domain"), input.has("page_size") ? input.get("page_size").getAsInt() : null, optional(input, "cursor")));
    }
  }

  private static void assertExpectedError(Throwable error, JsonObject expected) {
    switch (expected.get("kind").getAsString()) {
      case "timeout":
        assertInstanceOf(BlindrelayTimeoutException.class, error);
        return;
      case "connection":
        assertInstanceOf(BlindrelayConnectionException.class, error);
        return;
      default:
        BlindrelayApiException api = assertInstanceOf(BlindrelayApiException.class, error);
        assertEquals(expected.get("status").getAsInt(), api.status());
        assertEquals(expected.get("code").getAsString(), api.code());
        assertEquals(expected.get("message").getAsString(), api.getMessage());
        Integer retryAfter = expected.get("retry_after").isJsonNull() ? null : expected.get("retry_after").getAsInt();
        assertEquals(retryAfter, api.retryAfter());
        assertFalse(error.toString().contains(API_KEY));
    }
  }

  private static void assertWire(JsonObject testCase) throws Exception {
    if (flag(clientOptions(testCase), "no_server")) {
      return;
    }
    HttpRequest request = HttpRequest.newBuilder(URI.create(SERVER_URL + "/__verdict/" + testCase.get("name").getAsString())).build();
    String text = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body();
    assertEquals(JsonParser.parseString("{\"received\":1,\"mismatches\":[]}"), JsonParser.parseString(text));
  }

  private static void runHttpCase(JsonObject testCase) throws Exception {
    Blindrelay client = clientFor(testCase);
    if (testCase.has("expect_error")) {
      BlindrelayException error = assertThrows(BlindrelayException.class, () -> invoke(client, testCase));
      assertExpectedError(error, testCase.getAsJsonObject("expect_error"));
    } else {
      assertEquals(testCase.get("expect_result"), toWire(invoke(client, testCase)));
    }
    assertWire(testCase);
  }

  private static void runWebhookCase(JsonObject testCase) {
    byte[] payload = testCase.has("body_base64")
        ? Base64.getDecoder().decode(testCase.get("body_base64").getAsString())
        : testCase.get("body").getAsString().getBytes(StandardCharsets.UTF_8);
    long tolerance = testCase.has("tolerance_seconds") ? testCase.get("tolerance_seconds").getAsLong() : Webhooks.DEFAULT_TOLERANCE_SECONDS;
    boolean valid = Webhooks.verify(payload, testCase.get("signature_header").getAsString(), testCase.get("delivery_id").getAsString(), testCase.get("secret").getAsString(), tolerance, testCase.get("now").getAsLong());
    assertEquals(testCase.get("valid").getAsBoolean(), valid);
  }

  @TestFactory
  Stream<DynamicTest> httpCases() {
    return cases(HTTP_SUITE).map(testCase -> DynamicTest.dynamicTest(testCase.get("name").getAsString(), () -> runHttpCase(testCase)));
  }

  @TestFactory
  Stream<DynamicTest> webhookCases() {
    return cases(readCases("webhooks.json")).map(testCase -> DynamicTest.dynamicTest(testCase.get("name").getAsString(), () -> runWebhookCase(testCase)));
  }

  @Test
  void defaultBaseUrl() {
    assertEquals("https://api.blindrelay.app", Blindrelay.DEFAULT_BASE_URL);
  }

  @Test
  void clientStringFormHidesApiKey() {
    assertFalse(Blindrelay.create(API_KEY).toString().contains(API_KEY));
  }
}

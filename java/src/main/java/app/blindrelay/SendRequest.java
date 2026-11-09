package app.blindrelay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.List;
import java.util.Map;

/** One message. {@code from}, {@code to}, {@code subject} and {@code html} are required. */
public final class SendRequest {
  private final JsonObject body;
  private final String idempotencyKey;

  private SendRequest(JsonObject body, String idempotencyKey) {
    this.body = body;
    this.idempotencyKey = idempotencyKey;
  }

  public static Builder builder() {
    return new Builder();
  }

  JsonObject body() {
    return body;
  }

  String idempotencyKey() {
    return idempotencyKey;
  }

  public static final class Builder {
    private final JsonObject body = new JsonObject();
    private String idempotencyKey;

    private Builder() {}

    public Builder from(String from) {
      body.addProperty("from", from);
      return this;
    }

    public Builder to(String address) {
      body.addProperty("to", address);
      return this;
    }

    public Builder to(List<String> addresses) {
      body.add("to", stringArray(addresses));
      return this;
    }

    public Builder subject(String subject) {
      body.addProperty("subject", subject);
      return this;
    }

    public Builder html(String html) {
      body.addProperty("html", html);
      return this;
    }

    public Builder text(String text) {
      body.addProperty("text", text);
      return this;
    }

    public Builder headers(Map<String, String> headers) {
      JsonObject object = new JsonObject();
      headers.forEach(object::addProperty);
      body.add("headers", object);
      return this;
    }

    public Builder cc(String address) {
      body.add("cc", new JsonPrimitive(address));
      return this;
    }

    public Builder cc(List<String> addresses) {
      body.add("cc", stringArray(addresses));
      return this;
    }

    public Builder bcc(String address) {
      body.add("bcc", new JsonPrimitive(address));
      return this;
    }

    public Builder bcc(List<String> addresses) {
      body.add("bcc", stringArray(addresses));
      return this;
    }

    public Builder attachments(List<Attachment> attachments) {
      JsonArray array = new JsonArray();
      attachments.forEach(attachment -> array.add(attachmentToJson(attachment)));
      body.add("attachments", array);
      return this;
    }

    public Builder tags(List<Tag> tags) {
      JsonArray array = new JsonArray();
      tags.forEach(tag -> array.add(tagToJson(tag)));
      body.add("tags", array);
      return this;
    }

    public Builder idempotencyKey(String key) {
      this.idempotencyKey = key;
      return this;
    }

    public SendRequest build() {
      return new SendRequest(body, idempotencyKey);
    }

    private static JsonElement stringArray(List<String> values) {
      JsonArray array = new JsonArray();
      values.forEach(array::add);
      return array;
    }

    private static JsonObject attachmentToJson(Attachment attachment) {
      JsonObject object = new JsonObject();
      object.addProperty("filename", attachment.filename());
      object.addProperty("content_type", attachment.contentType());
      object.addProperty("content_base64", attachment.contentBase64());
      return object;
    }

    private static JsonObject tagToJson(Tag tag) {
      JsonObject object = new JsonObject();
      object.addProperty("key", tag.key());
      object.addProperty("value", tag.value());
      return object;
    }
  }
}

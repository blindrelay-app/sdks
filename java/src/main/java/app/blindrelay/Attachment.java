package app.blindrelay;

import java.util.Base64;

/** One file. {@code contentBase64} is standard base64 without line breaks. */
public record Attachment(String filename, String contentType, String contentBase64) {
  public static Attachment fromBytes(String filename, String contentType, byte[] bytes) {
    return new Attachment(filename, contentType, Base64.getEncoder().encodeToString(bytes));
  }

  @Override
  public String toString() {
    return "Attachment[filename=" + filename + ", contentType=" + contentType + "]";
  }
}

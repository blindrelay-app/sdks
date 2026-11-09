package app.blindrelay;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class Webhooks {
  public static final long DEFAULT_TOLERANCE_SECONDS = 300;

  private static final String HMAC_ALGORITHM = "HmacSHA256";
  private static final Pattern TIMESTAMP_PATTERN = Pattern.compile("0|[1-9][0-9]*");

  private Webhooks() {}

  public static boolean verify(byte[] payload, String signatureHeader, String deliveryId, String secret) {
    return verify(
        payload, signatureHeader, deliveryId, secret, DEFAULT_TOLERANCE_SECONDS, Instant.now().getEpochSecond());
  }

  /**
   * Verifies a delivery webhook: {@code v1} is hex HMAC-SHA256 over {@code <t>.<delivery_id>.} + raw
   * body. A header can carry several {@code v1} values during a secret rotation; one match is
   * enough. Inbound endpoint deliveries sign a different string and do not verify here.
   *
   * @param payload the request body exactly as received
   * @param nowEpochSeconds the current time in Unix seconds
   */
  public static boolean verify(
      byte[] payload,
      String signatureHeader,
      String deliveryId,
      String secret,
      long toleranceSeconds,
      long nowEpochSeconds) {
    String timestamp = null;
    List<String> signatures = new ArrayList<>();
    for (String part : signatureHeader.split(",")) {
      int separator = part.indexOf('=');
      if (separator < 0) {
        continue;
      }
      String key = part.substring(0, separator).trim();
      String value = part.substring(separator + 1).trim();
      if (key.equals("t") && timestamp == null) {
        timestamp = value;
      } else if (key.equals("v1")) {
        signatures.add(value);
      }
    }
    if (timestamp == null || signatures.isEmpty() || !isWithinTolerance(timestamp, toleranceSeconds, nowEpochSeconds)) {
      return false;
    }
    byte[] expected = expectedSignature(payload, timestamp, deliveryId, secret);
    return signatures.stream()
        .anyMatch(signature -> MessageDigest.isEqual(signature.getBytes(StandardCharsets.UTF_8), expected));
  }

  private static boolean isWithinTolerance(String timestamp, long toleranceSeconds, long now) {
    if (!TIMESTAMP_PATTERN.matcher(timestamp).matches()) {
      return false;
    }
    try {
      return Math.abs(now - Long.parseLong(timestamp)) <= toleranceSeconds;
    } catch (NumberFormatException tooLarge) {
      return false;
    }
  }

  private static byte[] expectedSignature(byte[] payload, String timestamp, String deliveryId, String secret) {
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
      mac.update((timestamp + "." + deliveryId + ".").getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(mac.doFinal(payload)).getBytes(StandardCharsets.UTF_8);
    } catch (GeneralSecurityException impossible) {
      throw new IllegalStateException("HmacSHA256 is required on every Java platform", impossible);
    }
  }
}

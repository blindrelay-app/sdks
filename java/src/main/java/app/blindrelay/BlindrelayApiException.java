package app.blindrelay;

/** The API answered with an error. {@code code} is {@code unknown_error} without an error envelope. */
public final class BlindrelayApiException extends BlindrelayException {
  private final int status;
  private final String code;
  private final Integer retryAfter;

  BlindrelayApiException(int status, String code, String message, Integer retryAfter) {
    super(message);
    this.status = status;
    this.code = code;
    this.retryAfter = retryAfter;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }

  /** Seconds to wait, from {@code Retry-After} or {@code error.retry_after_secs}; null when not given. */
  public Integer retryAfter() {
    return retryAfter;
  }
}

package app.blindrelay;

/** Filters for {@link Blindrelay#listEmails}. Null fields are left out of the query. */
public record ListEmailsParams(String status, String domain, Integer pageSize, String cursor) {
  public static ListEmailsParams none() {
    return new ListEmailsParams(null, null, null, null);
  }
}

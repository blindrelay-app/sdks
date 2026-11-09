package app.blindrelay;

public record EmailListItem(
    String id,
    String status,
    String summary,
    boolean isFinal,
    String domain,
    String apiKeyId,
    String acceptedAt,
    String updatedAt,
    boolean sandbox) {}

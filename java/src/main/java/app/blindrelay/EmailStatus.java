package app.blindrelay;

public record EmailStatus(
    String id,
    String status,
    String summary,
    boolean isFinal,
    String acceptedAt,
    String updatedAt,
    int lookupWindowHours,
    boolean sandbox) {}

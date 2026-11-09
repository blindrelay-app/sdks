package app.blindrelay;

import java.util.List;

public record EmailList(List<EmailListItem> emails, String nextCursor, int lookupWindowHours) {}

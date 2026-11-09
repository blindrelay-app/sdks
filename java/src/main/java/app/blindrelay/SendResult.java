package app.blindrelay;

import java.util.List;

public record SendResult(String id, List<String> ids, String status, List<String> warnings) {}

using System.Text.Json;
using System.Text.Json.Serialization;

namespace Blindrelay;

/// <summary>One address or several; assign a string or a string array.</summary>
[JsonConverter(typeof(RecipientsConverter))]
public sealed class Recipients
{
    private Recipients(object value) => Value = value;

    internal object Value { get; }

    public static implicit operator Recipients(string address) => new(address);

    public static implicit operator Recipients(string[] addresses) => new(addresses);

    public static implicit operator Recipients(List<string> addresses) => new(addresses.ToArray());
}

internal sealed class RecipientsConverter : JsonConverter<Recipients>
{
    public override Recipients Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options) =>
        throw new NotSupportedException();

    public override void Write(Utf8JsonWriter writer, Recipients value, JsonSerializerOptions options) =>
        JsonSerializer.Serialize(writer, value.Value, value.Value.GetType(), options);
}

public sealed record Attachment(string Filename, string ContentType, string ContentBase64)
{
    public static Attachment FromBytes(string filename, string contentType, ReadOnlySpan<byte> bytes) =>
        new(filename, contentType, Convert.ToBase64String(bytes));

    public override string ToString() => $"Attachment {{ Filename = {Filename}, ContentType = {ContentType} }}";
}

public sealed record Tag(string Key, string Value);

/// <summary>One message. From, To, Subject and Html are required.</summary>
public sealed class SendRequest
{
    public required string From { get; init; }

    public required Recipients To { get; init; }

    public required string Subject { get; init; }

    public required string Html { get; init; }

    public string? Text { get; init; }

    public IReadOnlyDictionary<string, string>? Headers { get; init; }

    public Recipients? Cc { get; init; }

    public Recipients? Bcc { get; init; }

    public IReadOnlyList<Attachment>? Attachments { get; init; }

    public IReadOnlyList<Tag>? Tags { get; init; }

    [JsonIgnore]
    public string? IdempotencyKey { get; init; }
}

public sealed class SendResult
{
    public required string Id { get; init; }

    public IReadOnlyList<string> Ids { get; init; } = Array.Empty<string>();

    public required string Status { get; init; }

    public IReadOnlyList<string> Warnings { get; init; } = Array.Empty<string>();
}

public sealed class EmailStatus
{
    public required string Id { get; init; }

    public required string Status { get; init; }

    public required string Summary { get; init; }

    public required bool Final { get; init; }

    public string? AcceptedAt { get; init; }

    public required string UpdatedAt { get; init; }

    public required int LookupWindowHours { get; init; }

    public required bool Sandbox { get; init; }
}

public sealed class EmailListItem
{
    public required string Id { get; init; }

    public required string Status { get; init; }

    public required string Summary { get; init; }

    public required bool Final { get; init; }

    public string? Domain { get; init; }

    public string? ApiKeyId { get; init; }

    public string? AcceptedAt { get; init; }

    public required string UpdatedAt { get; init; }

    public required bool Sandbox { get; init; }
}

public sealed class EmailList
{
    public required IReadOnlyList<EmailListItem> Emails { get; init; }

    public string? NextCursor { get; init; }

    public required int LookupWindowHours { get; init; }
}

/// <summary>Filters for ListEmailsAsync. Null values are left out of the query.</summary>
public sealed class ListEmailsRequest
{
    public string? Status { get; init; }

    public string? Domain { get; init; }

    public int? PageSize { get; init; }

    public string? Cursor { get; init; }
}

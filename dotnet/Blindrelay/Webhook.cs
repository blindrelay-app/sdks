using System.Globalization;
using System.Security.Cryptography;
using System.Text;

namespace Blindrelay;

public static class Webhook
{
    public const long DefaultToleranceSeconds = 300;

    /// <summary>
    /// Verifies a delivery webhook: v1 is hex HMAC-SHA256 over "&lt;t&gt;.&lt;delivery_id&gt;." + raw body.
    /// A header can carry several v1 values during a secret rotation; one match is enough.
    /// Inbound endpoint deliveries sign a different string and do not verify here.
    /// </summary>
    /// <param name="payload">The request body exactly as received.</param>
    /// <param name="now">Unix seconds; defaults to the current time.</param>
    public static bool Verify(
        ReadOnlySpan<byte> payload,
        string signatureHeader,
        string deliveryId,
        string secret,
        long toleranceSeconds = DefaultToleranceSeconds,
        long? now = null)
    {
        var (timestamp, signatures) = ParseHeader(signatureHeader);
        if (timestamp is null || signatures.Count == 0 || !IsWithinTolerance(timestamp, toleranceSeconds, now))
        {
            return false;
        }
        var expected = ComputeSignature(payload, timestamp, deliveryId, secret);
        foreach (var signature in signatures)
        {
            if (CryptographicOperations.FixedTimeEquals(Encoding.UTF8.GetBytes(signature), expected))
            {
                return true;
            }
        }
        return false;
    }

    private static (string? Timestamp, List<string> Signatures) ParseHeader(string header)
    {
        string? timestamp = null;
        var signatures = new List<string>();
        foreach (var part in header.Split(','))
        {
            var separator = part.IndexOf('=');
            if (separator < 0)
            {
                continue;
            }
            var key = part[..separator].Trim();
            var value = part[(separator + 1)..].Trim();
            if (key == "t" && timestamp is null)
            {
                timestamp = value;
            }
            else if (key == "v1")
            {
                signatures.Add(value);
            }
        }
        return (timestamp, signatures);
    }

    private static bool IsWithinTolerance(string timestamp, long toleranceSeconds, long? now)
    {
        var digitsOnly = timestamp.Length > 0 && timestamp.All(char.IsAsciiDigit);
        var leadingZero = timestamp.Length > 1 && timestamp[0] == '0';
        if (!digitsOnly || leadingZero
            || !long.TryParse(timestamp, NumberStyles.None, CultureInfo.InvariantCulture, out var signedAt))
        {
            return false;
        }
        var current = now ?? DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        return Math.Abs(current - signedAt) <= toleranceSeconds;
    }

    private static byte[] ComputeSignature(ReadOnlySpan<byte> payload, string timestamp, string deliveryId, string secret)
    {
        using var hmac = IncrementalHash.CreateHMAC(HashAlgorithmName.SHA256, Encoding.UTF8.GetBytes(secret));
        hmac.AppendData(Encoding.UTF8.GetBytes($"{timestamp}.{deliveryId}."));
        hmac.AppendData(payload);
        var hex = Convert.ToHexString(hmac.GetHashAndReset()).ToLowerInvariant();
        return Encoding.UTF8.GetBytes(hex);
    }
}

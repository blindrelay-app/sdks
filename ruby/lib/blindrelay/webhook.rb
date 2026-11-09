require "openssl"

module Blindrelay
  module Webhook
    DEFAULT_TOLERANCE_SECONDS = 300
    TIMESTAMP_PATTERN = /\A(0|[1-9][0-9]*)\z/.freeze

    # Verifies a delivery webhook: v1 is hex HMAC-SHA256 over "<t>.<delivery_id>." + raw body.
    # A header can carry several v1 values during a secret rotation; one match is enough.
    # Inbound endpoint deliveries sign a different string and do not verify here.
    #
    # +payload+ is the request body exactly as received.
    def self.verify(payload:, signature_header:, delivery_id:, secret:,
                    tolerance_seconds: DEFAULT_TOLERANCE_SECONDS, now: nil)
      timestamp, signatures = parse_header(signature_header)
      return false if timestamp.nil? || signatures.empty? || !TIMESTAMP_PATTERN.match?(timestamp)
      return false if ((now || Time.now.to_i) - timestamp.to_i).abs > tolerance_seconds

      signed = "#{timestamp}.#{delivery_id}.".b + payload.b
      expected = OpenSSL::HMAC.hexdigest("SHA256", secret, signed)
      signatures.any? { |signature| constant_time_equal?(signature, expected) }
    end

    def self.parse_header(header)
      timestamp = nil
      signatures = []
      header.split(",").each do |part|
        key, value = part.split("=", 2)
        next if value.nil?

        key = key.strip
        if key == "t"
          timestamp ||= value.strip
        elsif key == "v1"
          signatures << value.strip
        end
      end
      [timestamp, signatures]
    end
    private_class_method :parse_header

    def self.constant_time_equal?(left, right)
      left.bytesize == right.bytesize && OpenSSL.fixed_length_secure_compare(left, right)
    end
    private_class_method :constant_time_equal?
  end
end

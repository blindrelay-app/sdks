require "json"
require "net/http"
require "uri"
require "erb"
require "openssl"

module Blindrelay
  SendResult = Struct.new(:id, :ids, :status, :warnings, keyword_init: true)
  EmailStatus = Struct.new(:id, :status, :summary, :final, :accepted_at, :updated_at,
                           :lookup_window_hours, :sandbox, keyword_init: true)
  EmailListItem = Struct.new(:id, :status, :summary, :final, :domain, :api_key_id, :accepted_at,
                             :updated_at, :sandbox, keyword_init: true)
  EmailList = Struct.new(:emails, :next_cursor, :lookup_window_hours, keyword_init: true)

  # One file. +content_base64+ is standard base64 without line breaks.
  Attachment = Struct.new(:filename, :content_type, :content_base64, keyword_init: true) do
    def self.from_bytes(filename, content_type, bytes)
      new(filename: filename, content_type: content_type, content_base64: [bytes].pack("m0"))
    end

    def inspect
      "#<Blindrelay::Attachment filename=#{filename.inspect}>"
    end
  end

  class Client
    DEFAULT_BASE_URL = "https://api.blindrelay.app"
    DEFAULT_TIMEOUT_SECONDS = 30
    USER_AGENT = "blindrelay-ruby/#{VERSION}"
    JSON_MEDIA_TYPE = "application/json"
    TIMEOUT_ERRORS = [Net::OpenTimeout, Net::ReadTimeout, Net::WriteTimeout].freeze
    CONNECTION_ERRORS = [SocketError, SystemCallError, IOError, OpenSSL::SSL::SSLError].freeze
    UNSUCCESSFUL_FROM = 300

    def initialize(api_key, base_url: DEFAULT_BASE_URL, timeout: DEFAULT_TIMEOUT_SECONDS)
      @api_key = api_key
      @base_url = base_url.sub(%r{/+\z}, "")
      @timeout = timeout
    end

    def inspect
      "#<Blindrelay::Client base_url=#{@base_url.inspect}>"
    end

    # Named send_email because +send+ is Object#send in Ruby.
    # +to+, +cc+ and +bcc+ take a string or an array. +tags+ are hashes with :key and :value.
    def send_email(from:, to:, subject:, html:, text: nil, headers: nil, cc: nil, bcc: nil,
                   attachments: nil, tags: nil, idempotency_key: nil)
      body = { from: from, to: to, subject: subject, html: html, text: text, headers: headers,
               cc: cc, bcc: bcc, tags: tags }.compact
      body[:attachments] = attachments.map(&:to_h) if attachments
      answer = request(Net::HTTP::Post, "/v1/send", body: body, idempotency_key: idempotency_key)
      SendResult.new(id: answer["id"], ids: answer["ids"] || [], status: answer["status"],
                     warnings: answer["warnings"] || [])
    end

    def get_email(id)
      answer = request(Net::HTTP::Get, "/v1/emails/#{ERB::Util.url_encode(id)}")
      EmailStatus.new(**pick(answer, EmailStatus.members))
    end

    def list_emails(status: nil, domain: nil, page_size: nil, cursor: nil)
      query = URI.encode_www_form({ status: status, domain: domain, page_size: page_size,
                                    cursor: cursor }.compact)
      answer = request(Net::HTTP::Get, query.empty? ? "/v1/emails" : "/v1/emails?#{query}")
      emails = answer["emails"].map { |item| EmailListItem.new(**pick(item, EmailListItem.members)) }
      EmailList.new(emails: emails, next_cursor: answer["next_cursor"],
                    lookup_window_hours: answer["lookup_window_hours"])
    end

    private

    def pick(hash, members)
      members.to_h { |member| [member, hash[member.to_s]] }
    end

    def request(request_class, path, body: nil, idempotency_key: nil)
      uri = URI.parse(@base_url + path)
      http_request = request_class.new(uri.request_uri, headers(!body.nil?, idempotency_key))
      http_request.body = JSON.generate(body) unless body.nil?
      response = perform(uri, http_request)
      raise api_error(response) if response.code.to_i >= UNSUCCESSFUL_FROM

      JSON.parse(utf8_body(response))
    end

    def perform(uri, http_request)
      Net::HTTP.start(uri.host, uri.port, use_ssl: uri.scheme == "https", open_timeout: @timeout,
                      read_timeout: @timeout, write_timeout: @timeout, max_retries: 0) do |http|
        http.request(http_request)
      end
    rescue *TIMEOUT_ERRORS
      raise TimeoutError, "The request to Blindrelay timed out"
    rescue *CONNECTION_ERRORS
      raise ConnectionError, "Could not reach Blindrelay"
    end

    def headers(has_body, idempotency_key)
      headers = { "Authorization" => "Bearer #{@api_key}", "Accept" => JSON_MEDIA_TYPE,
                  "User-Agent" => USER_AGENT }
      headers["Content-Type"] = JSON_MEDIA_TYPE if has_body
      headers["Idempotency-Key"] = idempotency_key if idempotency_key
      headers
    end

    def utf8_body(response)
      response.body.to_s.dup.force_encoding(Encoding::UTF_8)
    end

    def api_error(response)
      status = response.code.to_i
      envelope = error_envelope(response)
      code = envelope["code"]
      known = code.is_a?(String)
      ApiError.new(status: status, code: known ? code : ErrorCode::UNKNOWN_ERROR,
                   message: known ? (envelope["message"] || "HTTP #{status}") : "HTTP #{status}",
                   retry_after: retry_after(response["Retry-After"], envelope))
    end

    def error_envelope(response)
      parsed = JSON.parse(utf8_body(response))
      parsed.is_a?(Hash) && parsed["error"].is_a?(Hash) ? parsed["error"] : {}
    rescue JSON::ParserError
      {}
    end

    def retry_after(header, envelope)
      return header.strip.to_i if header && header.strip.match?(/\A\d+\z/)

      seconds = envelope["retry_after_secs"]
      seconds.is_a?(Integer) ? seconds : nil
    end
  end
end

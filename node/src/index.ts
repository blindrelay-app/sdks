import { createHmac, timingSafeEqual } from 'node:crypto'

const SDK_VERSION = '0.1.0'
const USER_AGENT = `blindrelay-node/${SDK_VERSION}`
const DEFAULT_TIMEOUT_MS = 30_000
const DEFAULT_WEBHOOK_TOLERANCE_SECONDS = 300
const UNKNOWN_ERROR_CODE = 'unknown_error'
const REACT_RENDER_PACKAGE = '@react-email/render'
const MS_PER_SECOND = 1000

export const DEFAULT_BASE_URL = 'https://api.blindrelay.app'

/** API error codes the send and email routes document. New codes may appear: compare as strings. */
export const ErrorCode = {
  InvalidPayload: 'invalid_payload',
  Unauthorized: 'unauthorized',
  ScopeMissing: 'scope_missing',
  DomainNotVerified: 'domain_not_verified',
  RecipientSuppressed: 'recipient_suppressed',
  TenantSuspended: 'tenant_suspended',
  PlanRestricted: 'plan_restricted',
  UnknownTenant: 'unknown_tenant',
  PayloadTooLarge: 'payload_too_large',
  InvalidTag: 'invalid_tag',
  TooManyAttachments: 'too_many_attachments',
  AttachmentTooLarge: 'attachment_too_large',
  InvalidAttachmentBase64: 'invalid_attachment_base64',
  InvalidAttachmentFilename: 'invalid_attachment_filename',
  InvalidAttachmentContentType: 'invalid_attachment_content_type',
  QuotaExceeded: 'quota_exceeded',
  SpendingCapReached: 'spending_cap_reached',
  RateLimitExceeded: 'rate_limit_exceeded',
  WarmupLimitExceeded: 'warmup_limit_exceeded',
  RateLimited: 'rate_limited',
  Draining: 'draining',
  QueueAtCapacity: 'queue_at_capacity',
  Internal: 'internal',
  EmailNotFound: 'email_not_found',
  InvalidStatus: 'invalid_status',
  InvalidDomain: 'invalid_domain',
  InvalidCursor: 'invalid_cursor',
  UnknownError: UNKNOWN_ERROR_CODE,
} as const

export class BlindrelayError extends Error {
  override name = 'BlindrelayError'
}

/** The API answered with an error. `code` is `unknown_error` when the answer had no error envelope. */
export class BlindrelayApiError extends BlindrelayError {
  override name = 'BlindrelayApiError'

  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    /** Seconds to wait, from `Retry-After` or `error.retry_after_secs`; null when not given. */
    readonly retryAfter: number | null,
  ) {
    super(message)
  }
}

export class BlindrelayTimeoutError extends BlindrelayError {
  override name = 'BlindrelayTimeoutError'
}

export class BlindrelayConnectionError extends BlindrelayError {
  override name = 'BlindrelayConnectionError'
}

export interface ClientOptions {
  baseUrl?: string
  timeoutMs?: number
}

export type Recipients = string | string[]

export interface Attachment {
  filename: string
  contentType: string
  /** Standard base64 without line breaks. {@link attachmentFromBytes} builds it. */
  contentBase64: string
}

export interface Tag {
  key: string
  value: string
}

export interface SendParams {
  from: string
  to: Recipients
  subject: string
  html?: string
  /** A React element rendered to `html` with the optional peer `@react-email/render`. */
  react?: unknown
  text?: string
  headers?: Record<string, string>
  cc?: Recipients
  bcc?: Recipients
  attachments?: Attachment[]
  tags?: Tag[]
}

export interface SendOptions {
  idempotencyKey?: string
}

export interface SendResult {
  id: string
  ids: string[]
  status: string
  warnings: string[]
}

export interface EmailStatus {
  id: string
  status: string
  summary: string
  final: boolean
  acceptedAt: string | null
  updatedAt: string
  lookupWindowHours: number
  sandbox: boolean
}

export interface EmailListItem {
  id: string
  status: string
  summary: string
  final: boolean
  domain: string | null
  apiKeyId: string | null
  acceptedAt: string | null
  updatedAt: string
  sandbox: boolean
}

export interface EmailList {
  emails: EmailListItem[]
  nextCursor: string | null
  lookupWindowHours: number
}

export interface ListEmailsParams {
  status?: string
  domain?: string
  pageSize?: number
  cursor?: string
}

export interface VerifyWebhookParams {
  /** The request body exactly as received, before any JSON parsing. */
  payload: Uint8Array | string
  /** The value of the `Blindrelay-Signature` header. */
  signatureHeader: string
  /** The value of the `Blindrelay-Delivery-Id` header. */
  deliveryId: string
  secret: string
  toleranceSeconds?: number
  /** Unix seconds; defaults to the current time. */
  now?: number
}

export function attachmentFromBytes(
  filename: string,
  contentType: string,
  bytes: Uint8Array,
): Attachment {
  return { filename, contentType, contentBase64: Buffer.from(bytes).toString('base64') }
}

interface RequestOptions {
  query?: URLSearchParams
  body?: unknown
  idempotencyKey?: string
}

function toSendResult(body: any): SendResult {
  return { id: body.id, ids: body.ids ?? [], status: body.status, warnings: body.warnings ?? [] }
}

function toEmailStatus(body: any): EmailStatus {
  return {
    id: body.id,
    status: body.status,
    summary: body.summary,
    final: body.final,
    acceptedAt: body.accepted_at ?? null,
    updatedAt: body.updated_at,
    lookupWindowHours: body.lookup_window_hours,
    sandbox: body.sandbox,
  }
}

function toEmailListItem(item: any): EmailListItem {
  return {
    id: item.id,
    status: item.status,
    summary: item.summary,
    final: item.final,
    domain: item.domain ?? null,
    apiKeyId: item.api_key_id ?? null,
    acceptedAt: item.accepted_at ?? null,
    updatedAt: item.updated_at,
    sandbox: item.sandbox,
  }
}

function toEmailList(body: any): EmailList {
  return {
    emails: body.emails.map(toEmailListItem),
    nextCursor: body.next_cursor ?? null,
    lookupWindowHours: body.lookup_window_hours,
  }
}

function parseRetryAfter(header: string | null, errorBody: any): number | null {
  if (header !== null && /^\d+$/.test(header.trim())) return Number(header.trim())
  return typeof errorBody?.retry_after_secs === 'number' ? errorBody.retry_after_secs : null
}

function parseJson(text: string): any {
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}

function toApiError(status: number, retryAfterHeader: string | null, text: string): BlindrelayApiError {
  const envelope = parseJson(text)?.error
  const hasCode = typeof envelope?.code === 'string'
  return new BlindrelayApiError(
    status,
    hasCode ? envelope.code : UNKNOWN_ERROR_CODE,
    hasCode && typeof envelope.message === 'string' ? envelope.message : `HTTP ${status}`,
    parseRetryAfter(retryAfterHeader, envelope),
  )
}

function toTransportError(error: unknown): BlindrelayError {
  if (error instanceof Error && error.name === 'TimeoutError') {
    return new BlindrelayTimeoutError('The request to Blindrelay timed out')
  }
  return new BlindrelayConnectionError('Could not reach Blindrelay')
}

async function renderReact(element: unknown): Promise<string> {
  let render: (element: never) => Promise<string>
  try {
    // The name is a variable so bundlers do not fail on an optional peer that is not installed.
    ;({ render } = (await import(REACT_RENDER_PACKAGE)) as { render: typeof render })
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === 'ERR_MODULE_NOT_FOUND') {
      throw new BlindrelayError(
        `The react option needs the optional peer dependency ${REACT_RENDER_PACKAGE}. ` +
          `Install it with: npm install ${REACT_RENDER_PACKAGE} react react-dom`,
      )
    }
    throw error
  }
  return render(element as never)
}

async function resolveHtml(params: SendParams): Promise<string | undefined> {
  if (params.react === undefined) return params.html
  if (params.html !== undefined) throw new BlindrelayError('Pass either html or react, not both')
  return renderReact(params.react)
}

function attachmentToWire(attachment: Attachment) {
  return {
    filename: attachment.filename,
    content_type: attachment.contentType,
    content_base64: attachment.contentBase64,
  }
}

export class Blindrelay {
  readonly #apiKey: string
  readonly #baseUrl: string
  readonly #timeoutMs: number

  constructor(apiKey: string, options: ClientOptions = {}) {
    this.#apiKey = apiKey
    this.#baseUrl = (options.baseUrl ?? DEFAULT_BASE_URL).replace(/\/+$/, '')
    this.#timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS
  }

  async send(params: SendParams, options: SendOptions = {}): Promise<SendResult> {
    const body = {
      from: params.from,
      to: params.to,
      subject: params.subject,
      html: await resolveHtml(params),
      text: params.text,
      headers: params.headers,
      cc: params.cc,
      bcc: params.bcc,
      attachments: params.attachments?.map(attachmentToWire),
      tags: params.tags,
    }
    return toSendResult(
      await this.#request('POST', '/v1/send', { body, idempotencyKey: options.idempotencyKey }),
    )
  }

  async getEmail(id: string): Promise<EmailStatus> {
    return toEmailStatus(await this.#request('GET', `/v1/emails/${encodeURIComponent(id)}`))
  }

  async listEmails(params: ListEmailsParams = {}): Promise<EmailList> {
    const query = new URLSearchParams()
    if (params.status !== undefined) query.set('status', params.status)
    if (params.domain !== undefined) query.set('domain', params.domain)
    if (params.pageSize !== undefined) query.set('page_size', String(params.pageSize))
    if (params.cursor !== undefined) query.set('cursor', params.cursor)
    return toEmailList(await this.#request('GET', '/v1/emails', { query }))
  }

  async #request(method: string, path: string, options: RequestOptions = {}): Promise<unknown> {
    const queryString = options.query?.size ? `?${options.query}` : ''
    let response: Response
    let text: string
    try {
      response = await fetch(`${this.#baseUrl}${path}${queryString}`, {
        method,
        headers: this.#headers(options),
        body: options.body === undefined ? undefined : JSON.stringify(options.body),
        signal: AbortSignal.timeout(this.#timeoutMs),
      })
      text = await response.text()
    } catch (error) {
      throw toTransportError(error)
    }
    if (!response.ok) throw toApiError(response.status, response.headers.get('retry-after'), text)
    return JSON.parse(text)
  }

  #headers(options: RequestOptions): Record<string, string> {
    return {
      Authorization: `Bearer ${this.#apiKey}`,
      Accept: 'application/json',
      'User-Agent': USER_AGENT,
      ...(options.body === undefined ? {} : { 'Content-Type': 'application/json' }),
      ...(options.idempotencyKey === undefined ? {} : { 'Idempotency-Key': options.idempotencyKey }),
    }
  }
}

function parseSignatureHeader(header: string): { timestamp: string; signatures: string[] } | null {
  const pairs = header.split(',').map((part) => {
    const separator = part.indexOf('=')
    return separator < 0
      ? ['', '']
      : [part.slice(0, separator).trim(), part.slice(separator + 1).trim()]
  })
  const timestamp = pairs.find(([key]) => key === 't')?.[1]
  const signatures = pairs.filter(([key]) => key === 'v1').map(([, value]) => value)
  return timestamp !== undefined && signatures.length > 0 ? { timestamp, signatures } : null
}

function constantTimeEquals(a: string, b: string): boolean {
  const left = Buffer.from(a)
  const right = Buffer.from(b)
  return left.length === right.length && timingSafeEqual(left, right)
}

/**
 * Verifies a delivery webhook: `v1` is hex HMAC-SHA256 over `<t>.<delivery_id>.` + raw body.
 * A header can carry several `v1` values during a secret rotation; one match is enough.
 * Inbound endpoint deliveries sign a different string and do not verify here.
 */
export function verifyWebhook(params: VerifyWebhookParams): boolean {
  const parsed = parseSignatureHeader(params.signatureHeader)
  if (parsed === null || !/^(0|[1-9]\d*)$/.test(parsed.timestamp)) return false
  const now = params.now ?? Math.floor(Date.now() / MS_PER_SECOND)
  const tolerance = params.toleranceSeconds ?? DEFAULT_WEBHOOK_TOLERANCE_SECONDS
  if (Math.abs(now - Number(parsed.timestamp)) > tolerance) return false
  const expected = createHmac('sha256', params.secret)
    .update(`${parsed.timestamp}.${params.deliveryId}.`)
    .update(params.payload)
    .digest('hex')
  return parsed.signatures.some((signature) => constantTimeEquals(signature, expected))
}

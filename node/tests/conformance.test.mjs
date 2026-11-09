import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { inspect } from 'node:util'
import { test } from 'node:test'
import {
  Blindrelay,
  BlindrelayApiError,
  BlindrelayConnectionError,
  BlindrelayTimeoutError,
  DEFAULT_BASE_URL,
  attachmentFromBytes,
  verifyWebhook,
} from '../dist/index.js'

const serverUrl = process.env.BLINDRELAY_CONFORMANCE_URL
if (!serverUrl) throw new Error('Set BLINDRELAY_CONFORMANCE_URL (see sdks/conformance/run-all.sh)')

const readCases = (file) =>
  JSON.parse(readFileSync(new URL(`../../conformance/cases/${file}`, import.meta.url), 'utf8'))
const http = readCases('http.json')
const webhooks = readCases('webhooks.json')
const UNREACHABLE_URL = 'http://127.0.0.1:1'

function clientFor(testCase) {
  const options = testCase.client ?? {}
  let baseUrl = options.no_server ? UNREACHABLE_URL : `${serverUrl}/case/${testCase.name}`
  if (options.trailing_slash) baseUrl += '/'
  return new Blindrelay(http.api_key, { baseUrl, timeoutMs: options.timeout_ms })
}

function toAttachment(raw) {
  return raw.from_bytes_base64 === undefined
    ? { filename: raw.filename, contentType: raw.content_type, contentBase64: raw.content_base64 }
    : attachmentFromBytes(raw.filename, raw.content_type, Buffer.from(raw.from_bytes_base64, 'base64'))
}

function invoke(client, testCase) {
  const input = testCase.input
  switch (testCase.operation) {
    case 'send': {
      const { idempotency_key: idempotencyKey, attachments, ...params } = input
      return client.send(
        { ...params, attachments: attachments?.map(toAttachment) },
        idempotencyKey === undefined ? {} : { idempotencyKey },
      )
    }
    case 'get_email':
      return client.getEmail(input.id)
    case 'list_emails':
      return client.listEmails({ ...input, pageSize: input.page_size, page_size: undefined })
    default:
      throw new Error(`unknown operation ${testCase.operation}`)
  }
}

const statusToWire = (result) => ({
  id: result.id,
  status: result.status,
  summary: result.summary,
  final: result.final,
  accepted_at: result.acceptedAt,
  updated_at: result.updatedAt,
  lookup_window_hours: result.lookupWindowHours,
  sandbox: result.sandbox,
})

const itemToWire = (item) => ({
  id: item.id,
  status: item.status,
  summary: item.summary,
  final: item.final,
  domain: item.domain,
  api_key_id: item.apiKeyId,
  accepted_at: item.acceptedAt,
  updated_at: item.updatedAt,
  sandbox: item.sandbox,
})

function resultToWire(operation, result) {
  if (operation === 'get_email') return statusToWire(result)
  if (operation === 'list_emails') {
    return {
      emails: result.emails.map(itemToWire),
      next_cursor: result.nextCursor,
      lookup_window_hours: result.lookupWindowHours,
    }
  }
  return result
}

function assertError(error, expected) {
  if (expected.kind === 'timeout') return assert.ok(error instanceof BlindrelayTimeoutError, String(error))
  if (expected.kind === 'connection') return assert.ok(error instanceof BlindrelayConnectionError, String(error))
  assert.ok(error instanceof BlindrelayApiError, String(error))
  assert.equal(error.status, expected.status)
  assert.equal(error.code, expected.code)
  assert.equal(error.message, expected.message)
  assert.equal(error.retryAfter, expected.retry_after)
  assert.ok(!inspect(error).includes(http.api_key))
}

async function assertWire(testCase) {
  if (testCase.client?.no_server) return
  const verdict = await (await fetch(`${serverUrl}/__verdict/${testCase.name}`)).json()
  assert.deepEqual(verdict, { received: 1, mismatches: [] })
}

for (const testCase of http.cases) {
  test(`http ${testCase.name}`, async () => {
    let outcome
    try {
      outcome = { result: await invoke(clientFor(testCase), testCase) }
    } catch (error) {
      outcome = { error }
    }
    if (testCase.expect_error) {
      assert.ok(outcome.error, 'expected an error')
      assertError(outcome.error, testCase.expect_error)
    } else {
      if (outcome.error) throw outcome.error
      assert.deepEqual(resultToWire(testCase.operation, outcome.result), testCase.expect_result)
    }
    await assertWire(testCase)
  })
}

for (const testCase of webhooks.cases) {
  test(`webhook ${testCase.name}`, () => {
    const payload =
      testCase.body_base64 === undefined ? testCase.body : Buffer.from(testCase.body_base64, 'base64')
    const valid = verifyWebhook({
      payload,
      signatureHeader: testCase.signature_header,
      deliveryId: testCase.delivery_id,
      secret: testCase.secret,
      toleranceSeconds: testCase.tolerance_seconds,
      now: testCase.now,
    })
    assert.equal(valid, testCase.valid)
  })
}

test('default base url', () => assert.equal(DEFAULT_BASE_URL, 'https://api.blindrelay.app'))

test('client string form hides the api key', () => {
  const client = new Blindrelay(http.api_key)
  assert.ok(!inspect(client, { showHidden: true, depth: 5 }).includes(http.api_key))
  assert.ok(!JSON.stringify(client).includes(http.api_key))
})

import assert from 'node:assert/strict'
import { cpSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { createServer } from 'node:http'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'
import { after, before, test } from 'node:test'
import { createElement } from 'react'
import { Blindrelay, BlindrelayError } from '../dist/index.js'

const ACCEPTED = JSON.stringify({ id: 'id-1', ids: ['id-1'], status: 'queued' })
const SEND = { from: 'noreply@example.com', to: 'user@example.com', subject: 'Hi' }
let server
let baseUrl
let received

before(async () => {
  server = createServer((request, response) => {
    let body = ''
    request.on('data', (chunk) => (body += chunk))
    request.on('end', () => {
      received = JSON.parse(body)
      response.writeHead(202, { 'Content-Type': 'application/json' })
      response.end(ACCEPTED)
    })
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  baseUrl = `http://127.0.0.1:${server.address().port}`
})

after(() => server.close())

test('react renders to html on the wire', async () => {
  const client = new Blindrelay('key', { baseUrl })
  const element = createElement('p', null, 'Welcome, Ada')
  await client.send({ ...SEND, react: element })
  assert.match(received.html, /<p[^>]*>Welcome, Ada<\/p>/)
  assert.equal(received.react, undefined)
})

test('html and react together are refused before any request', async () => {
  const client = new Blindrelay('key', { baseUrl: 'http://127.0.0.1:1' })
  await assert.rejects(
    client.send({ ...SEND, html: '<p>x</p>', react: createElement('p') }),
    (error) => error instanceof BlindrelayError && /either html or react/.test(error.message),
  )
})

test('a missing peer dependency gives an install hint', async () => {
  const directory = mkdtempSync(join(tmpdir(), 'blindrelay-sdk-no-peer-'))
  try {
    cpSync(new URL('../dist', import.meta.url), join(directory, 'dist'), { recursive: true })
    writeFileSync(join(directory, 'package.json'), '{"type":"module"}')
    const isolated = await import(pathToFileURL(join(directory, 'dist/index.js')).href)
    const client = new isolated.Blindrelay('key', { baseUrl: 'http://127.0.0.1:1' })
    await assert.rejects(
      client.send({ ...SEND, react: {} }),
      (error) =>
        error instanceof isolated.BlindrelayError &&
        /npm install @react-email\/render react react-dom/.test(error.message),
    )
  } finally {
    rmSync(directory, { recursive: true, force: true })
  }
})

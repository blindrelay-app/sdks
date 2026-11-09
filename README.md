# Blindrelay SDKs

Thin official SDKs for the Blindrelay machine API in eight languages. They share one conformance suite so their behaviour cannot drift. The API reference is [openapi.yaml](https://blindrelay.app/openapi.yaml).

Node and Python install from npm and PyPI. The other SDKs are not published yet; each README says what to expect. Their manifests are guarded against an accidental upload where the ecosystem allows it. MIT licensed.

## Coverage

Every SDK covers: client construction (API key, base URL, timeout), `send`, `getEmail`, `listEmails`, typed errors and webhook verification. The method names follow each language.

| Language | Path | Send call | Dependencies | Runtime |
|----------|------|-----------|--------------|---------|
| Node / TypeScript | `node/` | `client.send(...)` | none (optional peer `@react-email/render`) | Node 20.19+ |
| Python | `python/` | `client.send(from_=..., ...)` | none | Python 3.9+ |
| Go | `go/` | `client.Send(ctx, ...)` | none | Go 1.22+ |
| PHP | `php/` | `$client->send([...])` | `ext-curl`, `ext-json` | PHP 8.1+ |
| Ruby | `ruby/` | `client.send_email(...)` | none | Ruby 3.0+ |
| Java | `java/` | `client.send(request)` | Gson | Java 17+ |
| .NET (C#) | `dotnet/` | `client.SendAsync(request)` | none | .NET 8 |
| Rust | `rust/` | `client.send(&request).await` | reqwest, serde, hmac, sha2, hex, base64 | Rust 2021 edition |

`react` on `send` is Node only.

The webhook helpers verify delivery webhooks (`Blindrelay-Signature` over `<t>.<delivery_id>.` + raw body). Inbound endpoint deliveries sign a different string and are not covered.

## Conformance

`conformance/cases/http.json` holds one case per request/response shape: the request expected on the wire, the canned response, and the expected result or error. `conformance/cases/webhooks.json` holds the signature vectors, starting with the published test vector from `backend/src/domain/delivery/signing.rs`.

`conformance/mock_server.py` is a local HTTP server (Python standard library, loopback only). A runner points the client at `<server>/case/<case name>`; the server checks the request and answers with the case's canned response, and the runner reads the wire verdict from `/__verdict/<case name>`. All wire rules live in the server, so eight runners cannot disagree about a correct request.

Run everything:

```sh
bash conformance/run-all.sh            # every language
bash conformance/run-all.sh go rust    # only these
```

A language uses its local toolchain when that is new enough, otherwise its official image through Podman if Podman is up (`podman machine start`), otherwise it is reported as skipped.

To run one SDK by hand, start `python3 conformance/mock_server.py`, set `BLINDRELAY_CONFORMANCE_URL` to the printed URL and run that SDK's test command (see `test_command` in `conformance/run-all.sh`).

Adding a case: edit the JSON; every runner consumes it. A new field in a request or response also needs mapping in each runner.

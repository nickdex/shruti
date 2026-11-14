# Smriti command API contract

Shruti's Smriti adapter submits a JSON command to `POST /api/command` over
HTTPS with a Bearer API key. The generic transport accepts a caller-supplied
JSON payload; it does not choose a command or interpret its fields.

```json
{
  "command/name": "journal/create-entry",
  "text": "Remember that we need 20kg potatoes tomorrow.",
  "created-at": "2026-07-27T12:30:00.000Z",
  "tags": ["shruti"]
}
```

The final transcript becomes `text`. When available, the capture time is
sent as an offset-bearing `created-at`; Smriti derives its Day in its own
zone. If no capture time is available the command uses its normal current-time
default. Shruti adds the `shruti` Tag and does not upload audio.

Smriti validates and authorizes commands as the API key's User. The key is
not restricted to a command allowlist: protect it as a full User credential.
Other callers can use the same transport to send other command payloads.

## Responses and queue behavior

| Response or condition | Shruti action |
| --- | --- |
| `2xx` | Persist the local acceptance receipt and mark accepted |
| `401`, `403` | Keep the capture and mark it blocked on credentials |
| `408`, `425`, `429`, `5xx`, timeout, offline | Keep the capture and retry with capped full-jitter backoff; honor `Retry-After` |
| `409` | Keep the capture and mark it rejected; it may be a command conflict |
| Other `4xx` | Keep the capture and mark it rejected with the server error |

## Current idempotency limitation

Smriti's command API does not deduplicate captures. A network failure after
Smriti commits an Entry can therefore be retried and create a second Entry.
Delivery is at-least-once, not exactly-once.

## Credential boundary

On Android, the API key is entered in the setup screen and stored encrypted
with an AES-GCM key in Android Keystore. The native transport retains the key
and performs delivery itself; it never returns the key to JavaScript storage,
logs, URLs, or APK assets. Existing Pebble prototype connections are cleared
and must be configured again. The browser shell is a local-development
fallback and cannot securely configure this cross-origin connection.

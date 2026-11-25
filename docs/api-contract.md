# Smriti API contract

Shruti uses one configured Smriti connection for two authenticated operations:

1. `POST /api/command` submits a reviewed capture as a journal command.
2. `GET /api/groups` lists the User's current Groups and ordered quick picks
   for the destination pills.

Both use HTTPS with a Bearer API key. The generic transport accepts a
caller-supplied JSON payload; it does not choose a command or interpret its
fields.

## Submitting a capture

```json
{
  "command/name": "journal/create-entry",
  "text": "Remember that we need 20kg potatoes tomorrow.",
  "created-at": "2026-07-27T12:30:00.000Z",
  "group-id": "6f1d0a2e-7c4b-4a8e-9f60-2d3c1b0a9e11",
  "client-request-id": "a7e9f2c4-1b2d-4e6f-8a90-123456789abc-1750000000000",
  "tags": ["shruti", "todo"]
}
```

- `text` is the reviewed transcript, trimmed. It is frozen when the message
  is submitted; later edits cannot change a submitted message. Submission
  happens at Send, which is either the User's press or the automatic
  submission of the three-second auto-send experiment for a newly finalized,
  non-empty transcript.
- `created-at` is the original occurred-at instant when available. Smriti
  derives its Day in its own zone. If no capture time is available the
  command uses its normal current-time default.
- `group-id` is the destination Group's stable id, or `""` for Private. One
  Entry has at most one Group. At Send, the selection is snapshotted with the
  text, so changing the session's active destination cannot redirect an
  already submitted message.
- `client-request-id` is Shruti's capture id. It is the idempotency key and
  is scoped by Smriti to the authenticated User. Two separate captures with
  identical text still carry different ids and remain separate Entries.
- Shruti adds the `shruti` and `todo` Tags and does not upload audio.

Smriti validates and authorizes commands as the API key's User. The key is
not restricted to a command allowlist: protect it as a full User credential.
Other callers can use the same transport to send other command payloads.

## Group discovery

```json
{
  "groups": [{"id": "uuid", "name": "Family"}],
  "quickPicks": [{"id": "uuid", "name": "Family", "position": 1}],
  "quickPicksConfigured": true
}
```

- `groups` lists every Group the key's User currently participates in,
  sorted by name then id. It is the full choice set, so a User can pick a
  Group outside the pills.
- `quickPicks` is the ordered destination list, at most three, already
  filtered to current membership. Order is significant. An empty list is a
  real answer (explicitly no picks), and Shruti never pads or replaces it
  locally.
- `quickPicksConfigured` distinguishes that explicit list from Smriti's own
  never-configured fallback, which derives up to three alphabetical current
  Groups. Either way, Shruti displays exactly the list it receives.
- Stable Group ids are the only destination identity; a rename changes only
  the display name.

Shruti caches the last successful payload on the device so the pills work
offline. A cached payload is always surfaced as stale (`:stale? true`) and
never authorizes a server write: Smriti rechecks membership during delivery,
and a rejected Group delivery stays visible for reassignment or Private plus
retry. There is no silent fallback to a different Group or to Private.
Without a cached payload, discovery fails visibly instead of inventing a
destination list.

## Responses and queue behavior

| Response or condition | Shruti action |
| --- | --- |
| `2xx` (first acceptance or replayed idempotent retry) | Persist the local acceptance receipt and mark accepted |
| `409` with `code` `capture-conflict` and `accepted` true | Keep the message and mark it conflicted; show that the original was already sent; only an explicit Send as a new entry creates another Entry |
| Other `409` | Keep the capture and mark it rejected |
| `401`, `403` | Keep the capture and mark it blocked on credentials |
| `408`, `425`, `429`, `5xx`, timeout, offline | Keep the capture and retry with capped full-jitter backoff; honor `Retry-After` |
| Other `4xx` | Keep the capture and mark it rejected with the server error |

Queued and in-flight messages never block a new recording. A rejected or
conflicted message keeps its text and destination so it can be inspected,
reassigned, retried, or sent as a new entry. Authentication and terminal
errors remain visible instead of disappearing into a retry loop.

## Idempotent delivery

Smriti records the capture id and the accepted payload identity atomically
with Entry creation, under the authenticated User:

- Repeating the same capture id with an equivalent payload returns success
  and the original acceptance; it does not create another Entry. A lost
  response can therefore be retried without duplicating the Journal.
- Equivalent means the same normalized text, destination, supplied
  `created-at` (or `target-date`), canonical Tags, and references.
- A changed text, destination, or other meaningfully changed payload after
  acceptance returns `409` with `code` `capture-conflict` and
  `accepted: true`, indicating the original was accepted.
- A failed or rejected attempt that created no Entry may be corrected and
  retried under the same capture id.
- A delayed retry after the Entry was deleted still deduplicates: it does
  not recreate the Entry.
- Callers that omit `client-request-id` keep the unkeyed behavior and are
  not deduplicated.
- Concurrent identical requests produce one Entry and the same acceptance
  for both.

## Credential boundary

On Android, the API key is entered in the setup screen and stored encrypted
with an AES-GCM key in Android Keystore. The native transport retains the key
and performs both command delivery and Group discovery itself; it never
returns the key to JavaScript storage, logs, URLs, or APK assets. Discovery
is restricted to an absolute path on the configured endpoint's origin, and
redirects are refused, so the key cannot be sent elsewhere. The offline Group
cache is scoped to a digest of the current credential and holds only Group
ids and names; it is cleared when the connection is reconfigured or cleared.

Existing Pebble prototype connections are cleared and must be configured
again. The browser shell is a local-development fallback: it cannot securely
configure this cross-origin connection, so Group selection is unavailable
there and command setup stays in the Android app.

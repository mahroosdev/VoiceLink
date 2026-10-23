# VoiceLink HTTP and WebSocket reference

This documents the current integrated Standard candidate. Protected routes use the authenticated server session; mutating HTTP requests require Spring Security CSRF protection. The server derives account identity, room membership, language direction, and stored service profile. Browser-supplied identity or profile values do not authorize a room. Private review, glossary, turn, and generated-audio responses explicitly use `Cache-Control: no-store`; a room is limited to two members. This is a controlled demo API, not a public third-party integration contract.

## Pages and form actions

| Method and path | Purpose |
| --- | --- |
| `GET /`, `GET /about` | Public homepage and current-scope information. The homepage preview is illustrative. |
| `GET /register`, `POST /register`, `GET /login`, `POST /login`, `POST /logout` | Account registration and session authentication. Login/logout POST handlers are Spring Security form actions. |
| `GET/POST /forgot-password`, `GET/POST /reset-password` | Optional recovery flow; disabled unless explicitly configured. |
| `GET /app`, `GET /rooms` | Authenticated dashboard and room setup. |
| `POST /rooms` | Create room with conversation display name, creator speaking language, and server-validated Standard profile. |
| `POST /rooms/join/preview`, `POST /rooms/join/setup` | Validate a private invite, then confirm the complementary speaking language and participant name. `POST /rooms/join` also accepts the preview form. |
| `GET /rooms/{id}`, `POST /rooms/{id}/close`, `POST /rooms/{id}/remove` | Member room view, explicit close, and removal of a closed room from the list. |
| `GET /account`, `GET/POST /account/delete` | Account settings and protected account deletion. |
| `GET /actuator/health` | Status-only health check; it does not prove provider quality or browser operation. |

## Authenticated JSON and audio endpoints

| Method and path | Purpose and important input |
| --- | --- |
| `GET /api/rooms/{id}/live-state` | Current authorized room state for a member. |
| `GET/POST /api/rooms/{roomId}/glossary` | List/create room glossary entries. Create supplies source/target language and source/preferred terms. |
| `PUT/DELETE /api/rooms/{roomId}/glossary/{entryId}` | Edit/delete a term with `expectedVersion` for stale-write protection. Closed-room entries are deleted. |
| `POST /api/rooms/{roomId}/text/reviews` | Translate current `sourceText` at a client `revision`; optional `sharingEpoch` is used only for explicit Shared Live. Returns a short-lived private review. |
| `POST /api/rooms/{roomId}/text/send` | Explicitly send a valid stored review with `reviewId`, original `sourceText`, and `revision`; duplicate retry returns the same final ID. |
| `POST /api/rooms/{roomId}/text/sharing` | Explicit Shared Live opt-in; returns a sharing epoch. |
| `DELETE /api/rooms/{roomId}/text/sharing/{epoch}` | Stop sharing. |
| `POST /api/rooms/{roomId}/text/sharing/{epoch}/clear` or `/invalidate` | Clear or invalidate a provisional draft using its revision. |
| `POST /api/rooms/{roomId}/turns` | Multipart WebM/Opus `audio`, `clientRequestId`, `durationMillis`, and `workflow` (`LIVE_SEND` or `FINISH_AND_SEND`). Maximum 1 MiB and 15 seconds. |
| `GET /api/rooms/{roomId}/turns/{turnId}/review` | Sender-only private speech review for Finish & Send. |
| `POST /api/rooms/{roomId}/turns/{turnId}/send` | Explicit final speech Send after private review. |
| `DELETE /api/rooms/{roomId}/turns/{turnId}/review` | Cancel a private speech review. |
| `GET /api/rooms/{roomId}/turns/recent` | Bounded, temporary recent speech state for reconnect; not permanent history. |
| `GET /api/rooms/{roomId}/turns/{turnId}/audio` | Active-member-only temporary WAV; no-store and normally expires after five minutes. |

Current utterances and WebSocket text are limited to 1,000 Unicode code points. Validation, member checks, provider configuration, rate limits, and expiry may return safe 4xx/5xx statuses; clients must not treat an uncertain send as permission to create a new final ID. Provider-disabled local QA should expect a safe error, not translated content.

## Raw WebSocket v1

Connect to `ws(s)://<same-origin>/ws/rooms/{roomId}` with the authenticated HTTP session. Same-origin policy and a server-side room check apply at handshake; a waiting member receives room state but cannot send text. The only client text command is a bounded JSON object with `protocolVersion: 1`, `type: "SEND_TEXT"`, a UUID `clientMessageId`, and `text` (1–1,000 code points). Binary frames are rejected. Reusing `clientMessageId` within the temporary deduplication window does not deliver a second message.

Server events contain `protocolVersion`, `eventId`, `streamId`, `roomId`, `sequence`, `type`, `senderParticipantId`, `turnId`, `createdAt`, and `payload`. Relevant types include `ROOM_STATE`, `ROOM_CLOSED`, `TEXT_MESSAGE`, `TEXT_DRAFT_UPDATED`, `TEXT_DRAFT_CLEARED`, `TEXT_TRANSLATION_SENT`, `TURN_ACCEPTED`, `TRANSCRIPT_READY`, `TRANSLATION_READY`, `AUDIO_READY`, `TURN_FAILED`, and safe `ERROR`. The client uses server participant IDs for You/Partner attribution and suppresses duplicate event/final IDs. `sequence` reflects publication order in one process, not persistent creation-time history. Refresh/reconnect receives current room state and bounded recent speech where available; missed text is not replayed, and restart clears in-memory streams.

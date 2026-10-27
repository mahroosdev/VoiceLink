# VoiceLink HTTP and WebSocket reference

This describes the current VoiceLink HTTP and WebSocket behavior; it is a maintainer reference, not a stable third-party integration contract. Protected routes use the authenticated server session; mutating HTTP requests require Spring Security CSRF protection. The server derives account identity, room membership, language direction, and stored service profile. Browser-supplied identity or profile values do not authorize a room. Private review, glossary, turn, and generated-audio responses explicitly use `Cache-Control: no-store`; a room is limited to two members.

## Pages and form actions

| Method and path | Purpose |
| --- | --- |
| `GET /`, `GET /about` | Public homepage and current-scope information. The homepage preview is illustrative. |
| `GET /register`, `POST /register`, `GET /login`, `POST /login`, `POST /logout` | Account registration and session authentication. Login/logout POST handlers are Spring Security form actions. |
| `GET/POST /forgot-password`, `GET/POST /reset-password` | Optional recovery flow; disabled unless explicitly configured. |
| `GET /app`, `GET /rooms` | Authenticated dashboard and room setup. |
| `POST /rooms` | Create room with conversation display name, creator speaking language, and a server-validated immutable service profile. Premium creation is rejected unless the server has Premium enabled and its credentials configured. |
| `POST /rooms/join/preview`, `POST /rooms/join/setup` | Validate a private invite, then confirm the complementary speaking language and participant name. `POST /rooms/join` also accepts the preview form. |
| `GET /rooms/{id}`, `POST /rooms/{id}/close`, `POST /rooms/{id}/remove` | Member room view, explicit close, and removal of a closed room from the list. |
| `GET /account`, `GET/POST /account/delete` | Account settings and protected account deletion. |
| `GET /actuator/health` | Status-only health check; it does not prove provider quality or browser operation. |

## Authenticated JSON and audio endpoints

| Method and path | Purpose and important input |
| --- | --- |
| `GET /api/rooms/{id}/live-state` | Current authorized room state for a member. |
| `POST /api/rooms/{id}/languages` | Owner-only JSON `{"ownerSpeaking":"en"}` or `{"ownerSpeaking":"ta"}`; success returns 204. Requires an authenticated session and CSRF token. While the room is open, updates both participants to complementary directions and broadcasts the new room state; the partner does not need to rejoin. Missing/blank or unsupported language returns 400, a non-owner receives 404, and a closed room returns 409. |
| `GET/POST /api/rooms/{roomId}/glossary` | List/create room glossary entries. Create supplies source/target language and source/preferred terms. |
| `PUT/DELETE /api/rooms/{roomId}/glossary/{entryId}` | Edit/delete a term with `expectedVersion` for stale-write protection. Closed-room entries are deleted. |
| `POST /api/rooms/{roomId}/text/reviews` | Translate current `sourceText` at a client `revision`; optional `romanizedTamil` marks Tamil-to-English input typed with Latin characters. Returns a short-lived private review. |
| `POST /api/rooms/{roomId}/text/send` | Explicitly send a valid stored review with `reviewId`, original `sourceText`, and `revision`; duplicate retry returns the same final ID. |
| `POST /api/rooms/{roomId}/turns/{turnId}/retry-translation` | Sender-only retry from a recognized speech turn's temporary transcript; checks room membership, turn ownership, session, current room direction and language-version, queue capacity, and rate limits. |
| `POST /api/rooms/{roomId}/turns` | Multipart WebM/Opus `audio`, `clientRequestId`, `durationMillis`, and `workflow` (`LIVE_SEND` or `FINISH_AND_SEND`). Maximum 1 MiB and 15 seconds. |
| `GET /api/rooms/{roomId}/turns/{turnId}/review` | Sender-only private speech review for Finish & Send. |
| `POST /api/rooms/{roomId}/turns/{turnId}/send` | Explicit final speech Send after private review. |
| `DELETE /api/rooms/{roomId}/turns/{turnId}/review` | Cancel a private speech review. |
| `GET /api/rooms/{roomId}/turns/recent` | Bounded, temporary recent speech state for reconnect; not permanent history. |
| `GET /api/rooms/{roomId}/turns/{turnId}/audio` | Active-member-only temporary WAV; no-store and normally expires after five minutes. |

There is no text-sharing endpoint. Live translation previews are always private to their sender. A partner receives original and translated text only after the sender explicitly selects Send.

Current utterances and WebSocket text are limited to 1,000 Unicode code points. Validation, member checks, provider configuration, rate limits, and expiry may return safe 4xx/5xx statuses; clients must not treat an uncertain send as permission to create a new final ID. Provider-disabled local QA should expect a safe error, not translated content.

## Raw WebSocket v1

Connect to `ws(s)://<same-origin>/ws/rooms/{roomId}` with the authenticated HTTP session. Same-origin policy and a server-side room check apply at handshake; a waiting member receives room state but cannot send text. The only client text command is a bounded JSON object with `protocolVersion: 1`, `type: "SEND_TEXT"`, a UUID `clientMessageId`, and `text` (1–1,000 code points). Binary frames are rejected. Quick chat retries on reconnect reuse the same ID for 30 minutes from the first send attempt. The server retains processed-message receipts for at least 35 minutes and returns the original message for a duplicate ID. Each participant may process up to 60 unique messages per rolling 35-minute window (64 receipt hard cap); additional new IDs receive a `RATE_LIMITED` error. After 30 minutes without confirmation, the client stops message retries, reports delivery unconfirmed, and requires explicit confirmation before a manual resend with a new ID. These receipts are in-memory and are lost on application restart. A room stream remains while anyone is connected and is removed 35 minutes after all participants disconnect; explicit room closure removes it immediately.

When speech recognition succeeds but translation fails, the transcript and retry action are sent only to the speaker. `POST /api/rooms/{roomId}/turns/{turnId}/retry-translation` retries translation from that turn's in-memory transcript, with the existing user, room, session, current language-direction and language-version, queue, and rate-limit checks. The same turn ID is used. The partner receives the turn only after successful translation. Retriable turn state expires after ten minutes or room closure, whichever comes first; it is not restored after refresh/restart unless the sender's same room page can recover its in-memory view from the recent-turn endpoint.

Server events contain `protocolVersion`, `eventId`, `streamId`, `roomId`, `sequence`, `type`, `senderParticipantId`, `turnId`, `createdAt`, and `payload`. Relevant types include `ROOM_STATE`, `ROOM_CLOSED`, `TEXT_MESSAGE`, `TEXT_TRANSLATION_SENT`, `TURN_ACCEPTED`, `TRANSCRIPT_READY`, `TRANSLATION_READY`, `AUDIO_READY`, sender-only translation-stage `TURN_FAILED`, and safe `ERROR`. Provisional text draft events are not emitted. The client uses server participant IDs for You/Partner attribution and suppresses duplicate event/final IDs. `sequence` reflects publication order in one process, not persistent creation-time history. Refresh/reconnect receives current room state and bounded recent speech where available; missed text is not replayed, and restart clears in-memory streams.

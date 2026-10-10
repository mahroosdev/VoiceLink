# VoiceLink architecture

## Approved direction

The browser captures short microphone turns and displays captions and playback. Java/Spring Boot owns application logic, validation, authorization, room membership, bounded translation context, and room glossary behavior. Provider interfaces separate speech-to-text, translation, and text-to-speech services from orchestration. PostgreSQL persists accounts, rooms, and room glossary entries. WebSocket version 1 carries room and speech-turn events.

Current path: browser microphone → Java/Spring Boot → speech-to-text → bounded recent context and applicable room glossary → translation → captions → text-to-speech → recipient.

## Implemented in Gate 1

- Spring Boot entry point and package boundaries.
- Temporary Thymeleaf foundation page and Actuator health endpoint.
- Spring Security allowlist for those two endpoints and deny-all fallback.
- PostgreSQL local Compose definition, environment-based configuration, JPA validation mode, and Flyway enabled.
- In-memory database foundation tests.

## Gate 2B account foundation

Flyway V1 creates `users` and `user_preferences` in one migration. `users.id` is a UUID primary key; `email` is normalized in application code and protected by a named unique constraint. `user_preferences.user_id` shares the account UUID and has a foreign key with `ON DELETE CASCADE`. Language tag columns are nullable strings so registration begins with unset preferences and later gates can support languages beyond the initial planned English (`en`) and Tamil (`ta`) pair. Hibernate validates the migration-created schema; it does not generate it.

Registration uses a dedicated form, central email normalization, Bean Validation, a transactional service, and one preferences row per new account. Password length: 10–128 characters. Passwords are encoded before persistence; the database stores only the encoded value. The selected encoder is Spring Security 7.1.1's `Argon2Password4jPasswordEncoder`, using Password4j 1.8.4 because the supported adapter requires it. Configuration is Argon2id, 65,536 KiB memory, three iterations, one lane, 32-byte output, and a 16-byte random salt. No custom hashing algorithm is implemented.

Spring Security uses a database-backed `UserDetailsService`, form login with `email` as the username field, and server-side sessions. The trusted principal carries the user UUID. Login failure messages are generic; disabled accounts cannot authenticate. Session fixation protection changes the session ID on authentication. POST logout invalidates the session and deletes its cookie. CSRF protection remains enabled, including login, registration, and logout forms. At Gate 2B, `/app` and future `/rooms/**` and `/api/**` paths required authentication, while `/ws/**` was denied. Gate 4B's exact live endpoint exception is described below; all other unlisted routes remain denied. The public root and health routes remain for foundation checks.

This is a controlled development/demo identity boundary. Email verification, password recovery, request throttling, production session cookie/TLS deployment configuration, and account administration are future security work before public identity use.

## Planned, not implemented

Persisted messages and history, broader conversational context, Premium providers, and the final user interface.

## Gate 3B two-person room foundation

Flyway V2 creates `conversation_rooms` and `room_participants`. A room has one creator in participant slot 1 while `WAITING`, and becomes `ACTIVE` only when a second authenticated user joins slot 2. A unique `(room_id, participant_slot)` constraint limits membership to two; a pessimistic room-row lock serializes competing joins. A separate unique `(room_id, user_id)` constraint prevents duplicate membership. The application transaction maintains the `ACTIVE`-means-two invariant. Room and participant rows remain after closure for later history support.

The creator chooses `en` → `ta` or `ta` → `en`; the joiner's reverse direction is stored in their own participant row. These room-specific values are snapshots independent of later preference changes. The database does not hardcode the current language pair. Authenticated principal UUIDs establish identity, and a central membership service checks private room access. Direct unknown and non-member room UUIDs receive the same not-found response.

A 16-character random code is manually shared and entered by an authenticated joiner. It is an invitation rather than an authentication credential, and is never placed in a URL. The code expires 24 hours after room creation while `WAITING` and becomes unusable once the room is `ACTIVE` or `CLOSED`. Invite age does not close an `ACTIVE` room. The creator can cancel a waiting room; either active member can explicitly leave, which closes it for both. Refresh, logout, browser closure, and network loss do not close rooms. Closed rooms cannot reopen. A join-attempt limiter is deferred for the controlled demo; public deployment requires abuse-rate controls.

Flyway owns schema changes; Hibernate validates rather than generating schema. The health endpoint includes database connectivity when the application is started with a working database connection. H2 tests alone do not establish PostgreSQL compatibility; the separate Testcontainers path must be run against PostgreSQL 17.

## Gate 4A approved; Gate 4B live transport

Gate 4A approved a raw Spring WebSocket endpoint at `/ws/rooms/{roomId}`. The browser reuses its authenticated HTTP session. The handshake derives the user UUID from `AccountPrincipal`, checks PostgreSQL room membership and status, and applies Spring's same-origin policy. Other `/ws/**` paths remain denied. A member of a `WAITING` room may connect to receive `ROOM_STATE` but cannot send text. An `ACTIVE` member may send temporary text. A `CLOSED` room rejects new sockets.

Each live room has an in-memory stream UUID and increasing sequence. Version 1 server events are `ROOM_STATE`, `TEXT_MESSAGE`, `ROOM_CLOSED`, and private `ERROR`. The server assigns event IDs, trusted participant identity, room sequence, and turn IDs. A verified connection is bound to one room; at most one current socket exists per member per room. An old socket's close callback cannot remove its replacement. Browser text is rendered as text, never HTML.

Room activation and closure notifications are delivered only after their database transactions commit. Room publication is serialized so a concurrently accepted text event may precede `ROOM_CLOSED`, but no text event is published afterward. Logout and reported servlet session destruction close associated sockets; live sends also check the existing HTTP session. Exact timeout notification timing depends on the servlet container.

Events and temporary text are transient. Reconnect performs a fresh authenticated handshake and receives current `ROOM_STATE`; it does not replay missed text or automatically resend uncertain text. A restart resets in-memory streams and sequence values, distinguished by a new stream UUID. There is no message, event, presence, or WebSocket-session table and no Flyway V3. Gate 5A approved bounded authenticated HTTP upload for push-to-talk turns with WebSocket result events.

## Gate 5A approval and Gate 5B Standard speech foundation

The owner approved the 2026-10-08 provider research in [AI_PROVIDER_EVALUATION.md](AI_PROVIDER_EVALUATION.md). Gate 5B originally used provisional Azure translation/TTS adapters. After repeated owner Azure account/payment-card verification failures, the Standard mapping was revised before live validation. It now uses Groq Free `whisper-large-v3` STT, Gemini API Free Tier `gemini-3.1-flash-lite` translation, and Gemini API Free Tier `gemini-3.8-flash-lite-tts` with documented `Kore` voice and WAV output. The profile is fixed server-side. It has no paid fallback, no Premium runtime path, and no provider credentials in the database or browser. The operator must explicitly confirm Groq Free and Gemini API Free Tier before enabling calls; the flag does not itself verify billing status. Gemini Free Tier content may be used by Google to improve its products, so development use is limited to non-sensitive, non-confidential speech.

An ACTIVE member sends a maximum 1 MiB WebM/Opus turn through CSRF-protected `POST /api/rooms/{roomId}/turns`. The server derives identity and English/Tamil direction from the authenticated principal and stored room participant. It ignores browser-supplied identity and language fields. A single-instance bounded worker processes turns serially per room: STT, source caption, translation, target caption, TTS, authenticated audio reference. Provider calls occur outside database transactions and WebSocket publication locks. `turnIndex` is acceptance/playback order; WebSocket `sequence` remains publication order. New version 1 event types are `TURN_ACCEPTED`, `TRANSCRIPT_READY`, `TRANSLATION_READY`, `AUDIO_READY`, and `TURN_FAILED`.

Raw uploaded bytes are dropped after STT. Audio is available to ACTIVE room members from a no-store endpoint for five minutes, with a 16 MiB global audio cap; recent in-memory turn state expires after ten minutes. One runtime keeps at most 32 room states, four pending turns per room, 16 recent turns per room, and 128 bounded idempotency IDs per room. Closed rooms discard state. The idempotency window and temporary state do not survive restart. A recent-turn endpoint helps a reconnecting browser recover current captions, but this is not durable conversation history.

Browser capture has a 15-second stop timer and checks `MediaRecorder.isTypeSupported` before recording. The server checks size, declared duration, WebM signature, and WebM/Opus markers, and rejects excessive embedded duration when present. Full media decoding is outside this gate, so malicious low-bitrate overlong input remains a residual risk. At Gate 5B, the Standard pipeline used no context or glossary and reserved empty translation inputs. The owner has not yet reported live English/Tamil provider or integrated microphone quality evidence.

Default translation style: **Natural Conversational**. Designed behavior: the Gemini system instruction names the source and target language explicitly; asks for natural spoken English or Tamil while preserving actual meaning, intent, tone, politeness, question/statement intent, and appropriate technical or proper terms; and forbids forced literal word order, added or omitted meaning, explanations, commentary, markdown, and answering instead of translating. The adapter still accepts only the structured `translation` field. Unverified quality: actual English/Tamil naturalness and meaning preservation still require live human review; offline request tests verify the instruction contract, not Gemini output quality.

## Gate 6A approval and Gate 6B bounded context and room glossary

The owner approved memory-only context and a PostgreSQL room glossary at Gate 6A. The per-room serial speech worker snapshots prior source transcripts after STT and before translation. Only turns with a valid completed translation qualify, including a turn whose TTS later failed. It uses at most the newest three prior turns, no older than five minutes, at most 400 Unicode code points per turn and 1,200 combined, in oldest-to-newest turn-index order. Oversized turns are omitted whole. STT and translation failures, the current turn, and other rooms are excluded. Room closure clears in-memory state after commit; restart loses it. There is no conversation transcript/history table.

Flyway V3 adds `room_glossary_entries`, scoped to `conversation_rooms`, with a unique normalized source per room and direction, creator foreign key, `row_version`, and cascade on room deletion. Service validation limits a room to 12 entries and source/preferred terms to 48/64 Unicode code points. Terms are NFC-normalized, trimmed, and whitespace-collapsed; source uniqueness uses locale-independent lowercase. A blank preferred input stores the display source term as an explicit preserve-source preference. The waiting creator may edit while the invite is valid; either active member may edit; expired waiting rooms are read-only and closed rooms deny access. Room closure deletes glossary rows in the same transaction. Mutations lock the room row for capacity/lifecycle ordering; entity versioning and expected versions reject stale edits.

The member-only `/api/rooms/{roomId}/glossary` GET/POST and `/{entryId}` PUT/DELETE endpoints derive identity from the authenticated principal, enforce CSRF for mutations, cap JSON mutation bodies at 4 KiB, and resolve entries by both room and entry ID. Responses use no-store; the browser renders terms through `textContent` and refreshes from the server. The room page has only a minimal terminology control, not transcript history. Direction-matching terms are sent to translation only when they match the current utterance as whole phrases; Latin matching is case-insensitive and longer overlapping terms win. Tamil morphology may require conservative exact phrase boundaries, so some inflected occurrences may not match.

The immutable translation request carries the current utterance, explicit language direction, bounded context, and applicable glossary terms, never persistence entities or user identity. Gemini receives these as separate structured data fields. The system instruction treats all speech and glossary text as untrusted reference data, translates only the current utterance, and retains Natural Conversational style and structured output. This reduces prompt-injection risk but does not guarantee immunity. The worker holds no database transaction or broad state lock during provider calls. A provider request already in flight at closure may complete; its result is not published to the closed room. Closing a room cannot retract data already sent to providers.

Gemini Free Tier may receive more user data than before: up to three prior source transcripts and applicable room glossary terms as well as the current utterance. Standard/free development remains limited to non-sensitive, non-confidential conversations. Do not log context, glossary contents, raw prompts, provider bodies, transcripts, translations, audio, or keys. Actual English/Tamil quality and provider behavior remain unverified by live human review.

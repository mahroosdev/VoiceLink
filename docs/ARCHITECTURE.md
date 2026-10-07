# VoiceLink architecture

## Approved direction

The browser will capture microphone input and display captions and playback in later gates. Java/Spring Boot owns application logic, validation, authorization, room membership, and future conversation and glossary behavior. Future provider abstractions will separate speech-to-text, translation, and text-to-speech services from the core application. PostgreSQL persists accounts and rooms. WebSocket support is included for later real-time communication; no message protocol is defined yet.

Planned path: browser microphone → Java/Spring Boot → speech-to-text → conversation context and glossary → translation → captions → text-to-speech → recipient.

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

Persisted messages and history, glossary behavior, speech/translation providers, captions, audio delivery, and the final user interface.

## Gate 3B two-person room foundation

Flyway V2 creates `conversation_rooms` and `room_participants`. A room has one creator in participant slot 1 while `WAITING`, and becomes `ACTIVE` only when a second authenticated user joins slot 2. A unique `(room_id, participant_slot)` constraint limits membership to two; a pessimistic room-row lock serializes competing joins. A separate unique `(room_id, user_id)` constraint prevents duplicate membership. The application transaction maintains the `ACTIVE`-means-two invariant. Room and participant rows remain after closure for later history support.

The creator chooses `en` → `ta` or `ta` → `en`; the joiner's reverse direction is stored in their own participant row. These room-specific values are snapshots independent of later preference changes. The database does not hardcode the current language pair. Authenticated principal UUIDs establish identity, and a central membership service checks private room access. Direct unknown and non-member room UUIDs receive the same not-found response.

A 16-character random code is manually shared and entered by an authenticated joiner. It is an invitation rather than an authentication credential, and is never placed in a URL. The code expires 24 hours after room creation while `WAITING` and becomes unusable once the room is `ACTIVE` or `CLOSED`. Invite age does not close an `ACTIVE` room. The creator can cancel a waiting room; either active member can explicitly leave, which closes it for both. Refresh, logout, browser closure, and network loss do not close rooms. Closed rooms cannot reopen. A join-attempt limiter is deferred for the controlled demo; public deployment requires abuse-rate controls.

Flyway owns schema changes; Hibernate validates rather than generating schema. The health endpoint includes database connectivity when the application is started with a working database connection. H2 tests alone do not establish PostgreSQL compatibility; the separate Testcontainers path must be run against PostgreSQL 17.

## Gate 4A approved; Gate 4B live transport

Gate 4A approved a raw Spring WebSocket endpoint at `/ws/rooms/{roomId}`. The browser reuses its authenticated HTTP session. The handshake derives the user UUID from `AccountPrincipal`, checks PostgreSQL room membership and status, and applies Spring's same-origin policy. Other `/ws/**` paths remain denied. A member of a `WAITING` room may connect to receive `ROOM_STATE` but cannot send text. An `ACTIVE` member may send temporary text. A `CLOSED` room rejects new sockets.

Each live room has an in-memory stream UUID and increasing sequence. Version 1 server events are `ROOM_STATE`, `TEXT_MESSAGE`, `ROOM_CLOSED`, and private `ERROR`. The server assigns event IDs, trusted participant identity, room sequence, and turn IDs. A verified connection is bound to one room; at most one current socket exists per member per room. An old socket's close callback cannot remove its replacement. Browser text is rendered as text, never HTML.

Room activation and closure notifications are delivered only after their database transactions commit. Room publication is serialized so a concurrently accepted text event may precede `ROOM_CLOSED`, but no text event is published afterward. Logout and reported servlet session destruction close associated sockets; live sends also check the existing HTTP session. Exact timeout notification timing depends on the servlet container.

Events and temporary text are transient. Reconnect performs a fresh authenticated handshake and receives current `ROOM_STATE`; it does not replay missed text or automatically resend uncertain text. A restart resets in-memory streams and sequence values, distinguished by a new stream UUID. There is no message, event, presence, or WebSocket-session table and no Flyway V3. Later audio design provisionally favors bounded authenticated HTTP upload for push-to-talk turns with WebSocket result/progress events; that choice requires Gate 5A validation.

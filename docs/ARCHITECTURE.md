# VoiceLink architecture

## Approved direction

The browser captures microphone input and displays captions and playback. Java/Spring Boot owns application logic, validation, authorization, room membership, conversation context, glossary behavior, and orchestration. Future provider abstractions will separate speech-to-text, translation, and text-to-speech services from the core application. PostgreSQL will persist the domain model once that model is designed. WebSocket support is included for later real-time communication; no message protocol is defined yet.

Planned path: browser microphone → Java/Spring Boot → speech-to-text → conversation context and glossary → translation → captions → text-to-speech → recipient.

## Implemented in Gate 1

- Spring Boot entry point and package boundaries.
- Temporary Thymeleaf foundation page and Actuator health endpoint.
- Spring Security allowlist for those two endpoints and deny-all fallback.
- PostgreSQL local Compose definition, environment-based configuration, JPA validation mode, and Flyway enabled.
- In-memory database foundation tests.

## Gate 2B account foundation

Flyway V1 creates `users` and `user_preferences` in one migration. `users.id` is a UUID primary key; `email` is normalized in application code and protected by a named unique constraint. `user_preferences.user_id` shares the account UUID and has a foreign key with `ON DELETE CASCADE`. Language tag columns are nullable strings so registration begins with unset preferences and later gates can support languages beyond the initial planned English (`en`) and Tamil (`ta`) pair. Hibernate validates the migration-created schema; it does not generate it.

Registration uses a dedicated form, central email normalization, Bean Validation, a transactional service, and one preferences row per new account. Passwords are encoded before persistence; the database stores only the encoded value. The selected encoder is Spring Security 7.1.1's `Argon2Password4jPasswordEncoder`, using Password4j 1.8.4 because the supported adapter requires it. Configuration is Argon2id, 65,536 KiB memory, three iterations, one lane, 32-byte output, and a 16-byte random salt. No custom hashing algorithm is implemented.

Spring Security uses a database-backed `UserDetailsService`, form login with `email` as the username field, and server-side sessions. The trusted principal carries the user UUID. Login failure messages are generic; disabled accounts cannot authenticate. Session fixation protection changes the session ID on authentication. POST logout invalidates the session and deletes its cookie. CSRF protection remains enabled, including login, registration, and logout forms. `/app` and future `/rooms/**` and `/api/**` paths require authentication; `/ws/**` is denied; all unlisted routes are denied. The public root and health routes remain for foundation checks.

This is a controlled development/demo identity boundary. Email verification, password recovery, request throttling, production session cookie/TLS deployment configuration, and account administration are future security work before public identity use.

## Planned, not implemented

Private two-participant rooms, membership, messages and history, glossary behavior, WebSocket protocol, speech/translation providers, captions, audio delivery, and the final user interface.

Flyway owns schema changes; Hibernate validates rather than generating schema. The health endpoint includes database connectivity when the application is started with a working database connection. H2 tests alone do not establish PostgreSQL compatibility; the separate Testcontainers path must be run against PostgreSQL 17.

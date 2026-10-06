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

## Planned, not implemented

Accounts and authentication, private two-participant rooms, membership, messages and history, glossary behavior, WebSocket protocol, speech/translation providers, captions, audio delivery, and the final user interface.

The Flyway migration directory is intentionally empty. No domain table is justified before the database-design gate. Flyway will own future schema changes; Hibernate is set to validate rather than generate schema. The health endpoint includes database connectivity when the application is started with a working database connection. H2 tests do not establish PostgreSQL integration behavior.

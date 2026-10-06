# VoiceLink Testing and Evidence

## Automated evidence — Codex-executed

On the Gate 1 foundation, `mvnw.cmd test` passed four tests with zero failures, errors, or skips. `mvnw.cmd clean package` passed and produced the application JAR. The tests cover application context startup, public Actuator health, the public temporary root page, and denial of an example anonymous application route. They use in-memory H2 and do not establish PostgreSQL compatibility.

Gate 1.5 reran `.\mvnw.cmd -ntp test` and `.\mvnw.cmd -ntp clean package` on 2026-10-07. Both passed; each test run reported four tests, zero failures, zero errors, and zero skipped tests. The clean package run produced the application JAR again.

## Manual evidence — project owner

The owner reported verification on Windows 11 after Gate 1: Docker Desktop running; PostgreSQL 17.11 container healthy and bound to `127.0.0.1:5432`; `pg_isready` accepting connections; `SELECT 1` returning 1; Spring Boot starting with the `local` profile and connecting through HikariCP; Flyway and JPA/Hibernate initializing; Actuator health returning `UP`; and the temporary root page loading in a browser. Codex did not execute these host-side checks.

## Unverified and future

- Real authentication and authorization flows.
- Domain schema, migrations, and application data persistence behavior.
- WebSocket room and media flow.
- AI speech-to-text, translation, and text-to-speech integrations.
- English ↔ Tamil translation quality, latency, and glossary effectiveness.
- Deployment, operational resilience, and production security behavior.

The empty Gate 1 migration directory is intentional. The test run emitted non-failing warnings about the managed H2 version relative to Flyway's verified range and Mockito's dynamic Java agent; those warnings do not establish future compatibility.

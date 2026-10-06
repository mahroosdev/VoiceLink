# VoiceLink Testing and Evidence

## Automated evidence — Codex-executed

On the Gate 1 foundation, `mvnw.cmd test` passed four tests with zero failures, errors, or skips. `mvnw.cmd clean package` passed and produced the application JAR. The tests cover application context startup, public Actuator health, the public temporary root page, and denial of an example anonymous application route. They use in-memory H2 and do not establish PostgreSQL compatibility.

Gate 1.5 reran `.\mvnw.cmd -ntp test` and `.\mvnw.cmd -ntp clean package` on 2026-10-07. Both passed; each test run reported four tests, zero failures, zero errors, and zero skipped tests. The clean package run produced the application JAR again.

## Manual evidence — project owner

The owner reported verification on Windows 11 after Gate 1: Docker Desktop running; PostgreSQL 17.11 container healthy and bound to `127.0.0.1:5432`; `pg_isready` accepting connections; `SELECT 1` returning 1; Spring Boot starting with the `local` profile and connecting through HikariCP; Flyway and JPA/Hibernate initializing; Actuator health returning `UP`; and the temporary root page loading in a browser. Codex did not execute these host-side checks.

## Gate 1 limitations and future work

- At Gate 1, authentication and domain schema were not yet implemented; Gate 2B evidence is recorded below.
- WebSocket room and media flow.
- AI speech-to-text, translation, and text-to-speech integrations.
- English ↔ Tamil translation quality, latency, and glossary effectiveness.
- Deployment, operational resilience, and production security behavior.

The empty Gate 1 migration directory is intentional. The test run emitted non-failing warnings about the managed H2 version relative to Flyway's verified range and Mockito's dynamic Java agent; those warnings do not establish future compatibility.

## Gate 2B verification paths

The Docker-independent fast suite is `.\mvnw.cmd -ntp test`. It uses H2, applies Flyway V1, and exercises registration, normalized email uniqueness, encoded password storage, browser validation, CSRF, login, protected routes, and logout. The same tests run again during `.\mvnw.cmd -ntp clean package`. Initial Gate 2B verification on 2026-10-07 passed **11 tests, zero failures, zero errors, zero skips**. After the owner found missing registration-success feedback, the suite added two browser-flow tests. The final `.\mvnw.cmd -ntp test` and `.\mvnw.cmd -ntp clean package` each passed **13 tests, zero failures, zero errors, zero skips**; clean package produced `target/voicelink-0.0.1-SNAPSHOT.jar`. The Argon2id timing test logs only encode and verification milliseconds, never a password or hash.

The isolated PostgreSQL 17 integration path is `.\mvnw.cmd -ntp -Ppostgres-it verify`. Maven Failsafe runs `PostgresIntegrationIT` only under that profile, so normal fast tests do not start Docker. On 2026-10-07, Codex ran the path with Docker Desktop 29.8.2 and PostgreSQL 17.11. The final post-fix run passed **13 fast tests plus one PostgreSQL integration test**, with zero failures, errors, or skips. The integration test verified Flyway V1, Hibernate validation, UUID account/preferences persistence, normalized-email uniqueness, foreign-key enforcement, and cascading deletion. This is automated isolated-database evidence, not a claim that the owner's persistent local database was checked.

## Gate 2B manual browser evidence — project owner

Reported on 2026-10-07, with no execution time supplied: the owner registered an account, signed in, reached authenticated `/app`, and completed the logout/sign-in flow in a browser. The owner found that successful registration lacked clear success feedback. Codex then changed the successful registration redirect to carry a one-time flash message, "Account created successfully. You can now sign in.", rendered with an accessible status role on the login page. Automated tests cover its presence after a successful redirect and absence on validation and duplicate-account failures. The owner has not yet reported a browser recheck of the corrected message; do not present that recheck as completed.

For an optional owner recheck, start PostgreSQL with the README Compose commands, set the local profile and database password in the current PowerShell process, run `.\mvnw.cmd spring-boot:run`, register a fresh email at `http://localhost:8080/register`, and confirm the one-time success message on `/login`. The UI is a development placeholder; no room or AI flow is present. Email verification, recovery, rate limiting, production session-cookie/TLS behavior, and deployment remain unverified.

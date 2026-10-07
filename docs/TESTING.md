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

For an optional owner recheck, start PostgreSQL with the README Compose commands, set the local profile and database password in the current PowerShell process, run `.\mvnw.cmd spring-boot:run`, register a fresh email at `http://localhost:8080/register`, and confirm the one-time success message on `/login`. The UI remains a development placeholder; Gate 3B room evidence is recorded below, while AI flow is absent. Email verification, recovery, rate limiting, production session-cookie/TLS behavior, and deployment remain unverified.

## Gate 3B two-person rooms — Codex-executed automated evidence

The final `.\mvnw.cmd -ntp test` run passed **25 fast tests, zero failures, zero errors, zero skips**: 9 account-flow tests, 12 room-flow tests, and 4 foundation tests. The final `.\mvnw.cmd -ntp clean package` run passed the same 25 tests and built the application JAR. Fast tests use H2 and do not require Docker.

The final `.\mvnw.cmd -ntp -Ppostgres-it verify` run passed **25 fast tests plus 3 PostgreSQL 17.11 integration tests**, all with zero failures, errors, or skips. Testcontainers applied Flyway V1 and V2 and Hibernate validated the resulting schema. PostgreSQL tests checked UUID persistence, foreign keys, unique join codes, unique room membership, unique participant slots, slot and language checks, closed-status consistency, preserved membership, and refusal to delete a user referenced by a room.

The concurrency integration test used two worker threads calling separate transactional joins against the last vacant slot in one waiting room. One joined, one received the safe unavailable result, and the final PostgreSQL state was `ACTIVE` with exactly two participants. This is real PostgreSQL transaction evidence, not a mocked race.

Fast tests cover waiting-room creation, 24-hour invite expiry, random-code format and normalization, reverse `en`/`ta` language direction, duplicate and third joins, member-only views and lists, explicit close, active-room persistence beyond invite age and logout, principal-derived identity, route authentication, and CSRF on create/join/close. The browser-facing create/join/close flow was exercised with MockMvc. No owner-performed browser room smoke test has been reported yet.

Non-failing warnings remained: managed H2 2.4.240 is newer than Flyway's stated verified H2 range, and Mockito warns about future JDK dynamic-agent behavior. PostgreSQL 17.11 verification passed. Join-attempt rate limiting is intentionally deferred for this controlled authenticated demo; public deployment abuse controls, WebSocket, AI, audio, and message/history behavior are unverified later-gate work.

## Gate 4B live transport — automated evidence

Gate 4A was approved before implementation. The final Docker-independent H2 suite has **30 tests: 9 account, 4 foundation, 12 room, and 5 real HTTP/WebSocket tests**, with zero failures, errors, or skips. The five real-socket tests run the application on a random port, sign in through the actual HTTP form/session, and upgrade with that cookie. They cover WAITING and ACTIVE access, anonymous/non-member/CLOSED/wrong-Origin rejection, automatic after-commit activation, two-way delivery and trusted participant identity, cross-room isolation, room sequence under concurrent sends, bounded duplicate suppression, malformed/blank/overlong/spoofed requests, socket replacement, logout revocation, reconnect with a new login, binary-frame rejection, and after-commit closure with no later text event. Existing CSRF tests remain in the suite.

The full suite passed using Maven 3.9.16 in a disposable Linux Java 21 container. A final `clean package` using the same Maven version passed all **30 tests** and built `voicelink-0.0.1-SNAPSHOT.jar`. The suite itself does not require Docker; Docker was used as an alternate execution environment for Codex verification.

The separate PostgreSQL profile passed **30 fast tests plus 3 PostgreSQL 17.11 Testcontainers integration tests**, with zero failures, errors, or skips. Flyway applied only V1/V2 and Hibernate validated the schema. The existing transactional final-slot and database constraint checks remained green. No Gate 4B migration was added.

The literal Windows Codex-shell commands `.\mvnw.cmd -ntp test`, `.\mvnw.cmd -ntp clean package`, and `.\mvnw.cmd -ntp -Ppostgres-it verify` were attempted. In each, the 25 non-server tests passed but the five real-server tests could not start Tomcat: Java `Selector.open()` failed in this execution environment with `Unable to establish loopback connection` / `Invalid argument: connect`. The same failure was reproduced directly in JShell outside the application. These Windows-shell command runs therefore failed; the successful Linux runs above are the automated application evidence. Normal Windows PowerShell runtime behavior remains for the owner browser check.

## Gate 4B owner integrated browser verification — pending

The owner intentionally deferred Gate 4B browser verification to a later integrated end-to-end application test. The following two-profile checks are planned for that test, not reported as performed:

1. In profile A, create an `en` → `ta` room and remain on its WAITING page. Confirm its local connection shows Connected.
2. In profile B, join with the displayed invite code. Confirm A changes to ACTIVE without refreshing and B shows ACTIVE and Connected.
3. Send temporary text A → B and B → A; confirm each recipient sees the exact text and that labels/identity are correct. Text is temporary, not saved history.
4. Refresh A. Confirm it reconnects, the room remains ACTIVE, and the page does not claim missed text was replayed.
5. Explicitly close the room. Confirm both pages show CLOSED and further sending is unavailable.
6. In an unsigned browser and then a third non-member account, try the room URL/socket. Neither may gain live access.

Gate 4B automated verification passed; owner integrated browser verification is pending. Session-expiry notification timing and Windows browser/runtime behavior remain to be verified. No audio, AI, or conversation persistence is part of Gate 4B.

## 2026-10-08 password minimum maintenance checkpoint

Password length: 10–128 characters. The owner intentionally approved lowering the registration minimum from 15 to 10 before Gate 5A. Automated account tests reject 9 and 129 characters, accept 10 and 128, and verify that a 9-character browser submission stays on registration with accessible field feedback and no success message. Existing registration, login, session, and Argon2id checks remained in the suite.

The requested `.\mvnw.cmd -ntp test` and `.\mvnw.cmd -ntp clean package` commands were run from the Windows Codex shell. Each ran 32 tests: 27 passed and the five actual WebSocket tests errored when Tomcat startup reached the previously documented Java loopback selector failure. The equivalent full Maven 3.9.16 `test` and `clean package` runs in an isolated Linux Java 21 container each passed **32 tests, zero failures, zero errors, zero skips**. Clean package built `voicelink-0.0.1-SNAPSHOT.jar`.

The PostgreSQL integration profile was not rerun for this validation-only edit because persistence code and schema were unchanged. Gate 4B automated verification passed; owner integrated browser verification remains pending for the later end-to-end application test.

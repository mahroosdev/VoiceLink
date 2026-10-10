# VoiceLink

**VoiceLink – Java-Based Real-Time Multilingual Speech Translation and Communication Platform**

Status: **Gate 6C context/glossary hardening passed automated verification; live-provider/Tamil validation pending**. Gate 4B automated verification passed; owner integrated browser verification remains pending. Gate 1 included owner-performed local PostgreSQL and runtime verification.

VoiceLink aims to help two people communicate across languages while preserving conversation context and session terminology. The current application supports accounts, session sign-in, private two-person rooms, temporary live text, and a Standard short-turn speech pipeline. Live English/Tamil provider quality is pending owner validation. It does not persist conversation messages.

## Planned flow

Browser microphone → bounded authenticated upload → Java/Spring Boot → Groq STT → bounded in-memory context and room glossary → Gemini translation → captions → Gemini TTS → authenticated audio playback.

Java and Spring Boot own the application, authorization, and pipeline orchestration. Gate 5B includes Standard adapters only; Premium is a later provisional target.

## Technology

Java 21, Spring Boot 4.1.1, Maven Wrapper, Spring Web MVC, Security, Data JPA, Validation, WebSocket, Actuator, Thymeleaf, Flyway, PostgreSQL 17 for local development, and JUnit. Fast tests use H2; the separate PostgreSQL integration path uses Testcontainers.

## Current capabilities

- Temporary foundation page at `/`.
- Actuator health at `/actuator/health`, including database health when the application is connected to a database.
- Registration at `/register`, sign-in at `/login`, and a protected placeholder at `/app`.
- Create, join, list, view, and explicitly close two-person rooms at `/rooms`. The creator chooses English ↔ Tamil direction; the second participant receives the reverse direction. A private manual invite code expires after 24 hours while the room is waiting. Active rooms persist across refresh and logout.
- An authenticated, same-origin raw WebSocket for room-state updates and temporary text. A waiting creator sees activation without refreshing; active members exchange text. Live text is not saved or replayed.
- ACTIVE members can record up to 15 seconds of WebM/Opus speech and upload at most 1 MiB through a CSRF-protected HTTP endpoint. Standard uses Groq Free `whisper-large-v3`, Gemini API Free Tier `gemini-3.1-flash-lite` translation, and `gemini-3.8-flash-lite-tts`. The source transcript and translated caption arrive as WebSocket events; generated WAV is served only to ACTIVE room members from temporary memory. These providers have free quotas, not unlimited use. No paid fallback exists.
- Server-side sessions with CSRF protection, member-only room views, and deny-by-default authorization.
- Recent translated source turns supply memory-only translation context: at most three prior turns, no older than five minutes, 400 Unicode code points each and 1,200 combined. Restart and room closure erase this context; no transcript history is stored in PostgreSQL.
- Each open room has up to 12 saved terminology entries in PostgreSQL through Flyway V3. The waiting creator can prepare terms while the invite is valid; active members can view and edit them. Explicit room closure deletes entries. The room page has a small list and add/edit/delete form.
- Local PostgreSQL Compose definition and Flyway V1/V2/V3 for accounts, rooms, and glossary entries.

## Prerequisites

- JDK 21; no global Maven installation is needed.
- Docker with Compose for local PostgreSQL.
- PowerShell on Windows for the commands below.

## Local setup

From the repository root:

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
notepad .env
```

Replace the example password with a unique local-only password. Keep `.env` out of Git. The Docker Compose environment file is separate from the application process environment. Load its two values into the current PowerShell session, without printing them:

```powershell
$settings = Get-Content .env | Where-Object { $_ -match '^(POSTGRES_USER|POSTGRES_PASSWORD|VOICELINK_[A-Z_]+)=' }
foreach ($setting in $settings) {
    $name, $value = $setting -split '=', 2
    [Environment]::SetEnvironmentVariable($name, $value, 'Process')
}
$env:SPRING_PROFILES_ACTIVE = 'local'
```

The local profile connects to `jdbc:postgresql://localhost:5432/voicelink`. `SERVER_PORT` can override port 8080; `DB_URL` and `DB_USERNAME` can override local connection defaults. The default profile requires explicit `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` environment variables. Never commit production secrets.

Speech remains disabled until you configure `VOICELINK_GROQ_API_KEY` and `VOICELINK_GEMINI_API_KEY`, verify in the provider consoles that Groq is **Free** and the Gemini API project is on the **Free Tier**, then set `VOICELINK_STANDARD_FREE_TIER_CONFIRMED=true`. Keep the flag false otherwise; the application cannot infer billing status from a key. Provider keys stay in the server process; never enter them in the browser. A key or quota failure produces a safe turn failure, with no switch to a paid service. [Gemini Free Tier pricing disclosures](https://ai.google.dev/gemini-api/docs/pricing) say content may be used to improve Google's products. Use only non-sensitive demo speech and non-confidential conversations during development. A current transcript, up to three prior source transcripts, and matching room glossary terms may now be sent to Gemini. Closing a room removes local context and glossary data but cannot retract anything already sent to a provider. No live provider or Tamil quality test is claimed yet.

## PostgreSQL with Docker Compose

```powershell
docker compose --env-file .env config --quiet
docker compose --env-file .env up -d postgres
docker compose --env-file .env ps
docker compose --env-file .env exec postgres pg_isready -U voicelink_dev -d voicelink
docker compose --env-file .env exec postgres psql -U voicelink_dev -d voicelink -c 'SELECT 1;'
```

If you change `POSTGRES_USER`, use that name in the last two commands. The database has a named persistent volume; changing initialization credentials in `.env` does not update an already initialized database.

## Test and run

```powershell
.\mvnw.cmd test
.\mvnw.cmd clean package
.\mvnw.cmd spring-boot:run
```

With the application running, request `http://localhost:8080/`, `http://localhost:8080/actuator/health`, and `http://localhost:8080/register`. Register with a display name, email, and a 10–128 character password, then sign in and visit `/app`. Open **Your rooms** to create a room, share its displayed code directly with another signed-in user, and have that user enter it at `/rooms`. The waiting creator should see the room turn active when the second user joins. The room terminology section lets the waiting creator prepare terms while the invite is valid and lets either active member add, edit, delete, or refresh them. Active members can exchange temporary live text. With validated Standard credentials, they can Record/Stop short turns and see source/translated captions and audio playback. Speech results and recent translation context are temporary; the room glossary is saved until explicit closure. Either member can explicitly close an active room. A database outage should make Actuator report unhealthy. Fast tests use H2 and fake providers; they need no Docker or provider key. PostgreSQL integration runs separately with `.\mvnw.cmd -ntp -Ppostgres-it verify` when Docker is available.

The explicit `-Pprovider-live` profile (`.\mvnw.cmd -ntp -Pprovider-live verify`) is the only provided automated path that calls real speech providers. It needs Groq Free and Gemini API Free Tier credentials, `VOICELINK_STANDARD_FREE_TIER_CONFIRMED=true`, and non-sensitive WebM/Opus clip paths in `VOICELINK_LIVE_AUDIO_EN` and `VOICELINK_LIVE_AUDIO_TA`. It makes two calls to each provider and consumes free-tier quota. It is not part of normal `test`, `clean package`, or CI. See [provider evaluation](docs/AI_PROVIDER_EVALUATION.md) and [testing evidence](docs/TESTING.md) before invoking it.

## Security and scope

The root page, health endpoint, login, and registration are public. `/app`, room routes, `/api/rooms/{roomId}/turns`, generated audio, `/api/rooms/{roomId}/glossary`, the room scripts, and `/ws/rooms/{roomId}` require an authenticated server-side session. Turn upload and audio retrieval also require ACTIVE room membership; glossary access requires membership in an open room. Other `/ws/**` paths and unlisted paths are denied by default. CSRF remains enabled for browser forms, audio upload, and glossary mutations. Passwords use Argon2id through Spring Security's PasswordEncoder abstraction. This is a controlled development/demo application: email verification, password recovery, request throttling, production deployment controls, strong media decoding, owner live Tamil verification, Premium, and the final UI remain future work. Do not expose account registration publicly as a production identity service.

See [architecture](docs/ARCHITECTURE.md), [development plan](docs/DEVELOPMENT_PLAN.md), [provider evaluation](docs/AI_PROVIDER_EVALUATION.md), [project log](docs/PROJECT_LOG.md), and [testing evidence](docs/TESTING.md).

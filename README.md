# VoiceLink

**VoiceLink – Java-Based Real-Time Multilingual Speech Translation and Communication Platform**

Status: **Gate 3B two-person room foundation / active development**. Gate 1 passed, including owner-performed local PostgreSQL and runtime verification. Gate 1.5 published the [public foundation checkpoint](https://github.com/mahroosdev/voicelink).

VoiceLink aims to help two people communicate across languages while preserving conversation context and session terminology. The current application supports accounts, session sign-in, and private two-person room membership. It does not yet process speech, translate, synthesize audio, or exchange conversation messages.

## Planned flow

Browser microphone → Java/Spring Boot → Speech-to-Text → conversation context + glossary → translation → captions → Text-to-Speech → recipient.

Java and Spring Boot will own the application and business logic. AI providers will be selected and integrated in later gates.

## Technology

Java 21, Spring Boot 4.1.1, Maven Wrapper, Spring Web MVC, Security, Data JPA, Validation, WebSocket, Actuator, Thymeleaf, Flyway, PostgreSQL 17 for local development, and JUnit. Fast tests use H2; the separate PostgreSQL integration path uses Testcontainers.

## Current capabilities

- Temporary foundation page at `/`.
- Actuator health at `/actuator/health`, including database health when the application is connected to a database.
- Registration at `/register`, sign-in at `/login`, and a protected placeholder at `/app`.
- Create, join, list, view, and explicitly close two-person rooms at `/rooms`. The creator chooses English ↔ Tamil direction; the second participant receives the reverse direction. A private manual invite code expires after 24 hours while the room is waiting. Active rooms persist across refresh and logout.
- Server-side sessions with CSRF protection, member-only room views, and deny-by-default authorization. AI and WebSocket behavior remain outside this gate.
- Local PostgreSQL Compose definition and Flyway V1/V2 for accounts and rooms.

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
$settings = Get-Content .env | Where-Object { $_ -match '^(POSTGRES_USER|POSTGRES_PASSWORD)=' }
foreach ($setting in $settings) {
    $name, $value = $setting -split '=', 2
    [Environment]::SetEnvironmentVariable($name, $value, 'Process')
}
$env:SPRING_PROFILES_ACTIVE = 'local'
```

The local profile connects to `jdbc:postgresql://localhost:5432/voicelink`. `SERVER_PORT` can override port 8080; `DB_URL` and `DB_USERNAME` can override local connection defaults. The default profile requires explicit `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` environment variables. Never commit production secrets.

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

With the application running, request `http://localhost:8080/`, `http://localhost:8080/actuator/health`, and `http://localhost:8080/register`. Register with a display name, email, and a 15–128 character password, then sign in and visit `/app`. Open **Your rooms** to create a room, share its displayed code directly with another signed-in user, and have that user enter it at `/rooms`. Either member can explicitly leave and close an active room. A database outage should make Actuator report unhealthy. The fast test profile uses H2 so `.\mvnw.cmd test` runs without Docker. PostgreSQL integration tests run separately with `.\mvnw.cmd -ntp -Ppostgres-it verify` when Docker is available.

## Security and scope

The root page, health endpoint, login, and registration are public. `/app` and room routes require an authenticated server-side session, with room details limited to members; `/ws/**` is denied, and other paths are denied by default. CSRF remains enabled for browser forms. Passwords use Argon2id through Spring Security's PasswordEncoder abstraction. This is a controlled development/demo application: email verification, password recovery, request throttling, production deployment controls, real-time media, AI integrations, and the final UI remain future work. Do not expose account registration publicly as a production identity service.

See [architecture](docs/ARCHITECTURE.md), [development plan](docs/DEVELOPMENT_PLAN.md), [project log](docs/PROJECT_LOG.md), and [testing evidence](docs/TESTING.md).

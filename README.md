# VoiceLink

**VoiceLink – Java-Based Real-Time Multilingual Speech Translation and Communication Platform**

Status: **Foundation / active development**. Gate 1 passed, including owner-performed local PostgreSQL and runtime verification. Gate 1.5 is documenting that evidence and preparing the public GitHub checkpoint.

VoiceLink aims to help two people communicate across languages while preserving conversation context and session terminology. The current application is a foundation only; it does not yet process speech, translate, synthesize audio, create rooms, or authenticate users.

## Planned flow

Browser microphone → Java/Spring Boot → Speech-to-Text → conversation context + glossary → translation → captions → Text-to-Speech → recipient.

Java and Spring Boot will own the application and business logic. AI providers will be selected and integrated in later gates.

## Technology

Java 21, Spring Boot 4.1.1, Maven Wrapper, Spring Web MVC, Security, Data JPA, Validation, WebSocket, Actuator, Thymeleaf, Flyway, PostgreSQL 17 for local development, and JUnit. Tests use an in-memory H2 database and do not verify PostgreSQL compatibility.

## Current capabilities

- Temporary foundation page at `/`.
- Actuator health at `/actuator/health`, including database health when the application is connected to a database.
- All other application routes denied by default; no login or users exist yet.
- Local PostgreSQL Compose definition and Flyway configuration. Domain tables and migrations are deferred to database design.

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

With the application running, request `http://localhost:8080/` and `http://localhost:8080/actuator/health`. A database outage should make Actuator report unhealthy. The test profile uses H2 so tests run without Docker.

## Security and scope

The root page and health endpoint are public for foundation verification. Every other route is denied. HTTP Basic and form login are disabled, and the security configuration supplies no accounts, so there is no intended generated login password. CSRF protection remains at its framework default. Authentication, authorization policy, domain APIs, room membership, real-time media, AI integrations, and the final UI are outside Gate 1.

See [architecture](docs/ARCHITECTURE.md), [development plan](docs/DEVELOPMENT_PLAN.md), [project log](docs/PROJECT_LOG.md), and [testing evidence](docs/TESTING.md).

# Deployment and operations

VoiceLink runs as a single Java 21 Spring Boot service with a PostgreSQL database. Its current in-memory sessions, WebSocket streams, recent context, temporary captions/audio, idempotency state, and rate counters are not shared across replicas. Use a single app instance unless the state model and rate limiting are deliberately redesigned.

The public service URL recorded in the latest release evidence is [voicelink-production-85f2.up.railway.app](https://voicelink-production-85f2.up.railway.app). Railway source was observed as `mahroosdev/VoiceLink` / `main` on 2026-10-25; verify current source and deployment status in Railway before relying on it.

## Build and start

Railway's Railpack can detect `pom.xml` and build the Spring Boot JAR. Use Java 21, the built JAR, and Railway's injected `PORT`; do not hardcode a public port or run Maven dev mode as the production process. Confirm the actual JAR name from build output. The app's production profile requires a valid JDBC URL, database username, and database password.

## PostgreSQL and migrations

Use a private PostgreSQL service with persistent storage. Configure application variables through Railway references to the intended database service rather than copying credential values:

```text
SPRING_PROFILES_ACTIVE=prod
RAILPACK_JDK_VERSION=21
DB_URL=jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}?sslmode=require
DB_USERNAME=${{Postgres.PGUSER}}
DB_PASSWORD=${{Postgres.PGPASSWORD}}
```

Replace `Postgres` with the actual service name when needed. Check connection-string format and private TLS without displaying resolved credentials. Flyway applies versioned migrations at startup; Hibernate validates the mapped schema. Back up and verify restore readiness before any migration with material data impact. Never delete a database volume as a troubleshooting shortcut.

## Provider variables

Standard speech requires `VOICELINK_GROQ_API_KEY`, `VOICELINK_GEMINI_API_KEY`, and `VOICELINK_STANDARD_FREE_TIER_CONFIRMED=true` after the operator verifies the account/project tiers and current quotas. Premium is disabled by default using `VOICELINK_PREMIUM_ENABLED=false`. If separately approved later, Premium credentials belong to a distinct billed Gemini project. Missing or failed provider configuration must not trigger a paid fallback.

Password reset is disabled by default. Enabling it requires `VOICELINK_PASSWORD_RESET_ENABLED`, `VOICELINK_PUBLIC_BASE_URL`, `VOICELINK_RESET_MAIL_API_KEY`, and `VOICELINK_RESET_MAIL_FROM_ADDRESS`; configure and verify these only through the intended secret manager. Do not put secret values in build arguments, logs, source control, or screenshots. `.env` is for local use only and is Git-ignored.

## Release and health checks

Before a release, confirm the exact Git commit, source connection, clean intended diff, active database service and volume, verified backup/restore plan, and absence of another pending deployment. Use a normal fast-forward/reviewed merge and push only with explicit release authorization. Allow the connected Railway service to create its deployment once; do not trigger a duplicate deploy.

After deployment, check the deployed commit, build/deploy result, `/actuator/health`, database connection, Flyway state, homepage/auth/static assets, and safe logs. A health response does not verify login workflows, room membership, translation quality, microphone capture, or audio playback. If rollback is needed, inspect which environment variables a rollback restores; an application rollback does not reverse database migrations. Restore data only from a verified backup with separate authorization.

## Scaling and cost

Process-local rate limits are not distributed billing limits. Before multiple replicas or paid-provider operation, design shared request accounting, provider budgets/alerts, session/state behavior, and tested rollback/restore procedures. Check current Railway and provider plans in their official consoles; service prices, quotas, trial conditions, and runtime limits change over time.

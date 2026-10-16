# Railway deployment readiness

Gate 10A prepared the repository for a **single-instance, controlled campus demonstration**; the steps below record that gate's original deployment procedure. Do not treat the public URL as an unrestricted production identity service: registration and room joining still lack request throttling and email verification. Password recovery is implemented but disabled until its HTTPS email configuration is supplied and verified. Use only non-sensitive, non-confidential demo conversations.

## Build and runtime

Connect the `main` branch of `mahroosdev/voicelink` to one Railway application service. Railway Railpack detects the Maven `pom.xml`, builds a Spring Boot JAR, and supplies Java 21 by default; set `RAILPACK_JDK_VERSION=21` to pin that choice. Prefer its inferred build and start commands. If the start command is not inferred correctly, use `java -jar target/voicelink-0.0.1-SNAPSHOT.jar` after confirming the built JAR path in the build output. Do not start Maven dev mode in production. No Dockerfile or Procfile is required by the current design.

Railway supplies `PORT`; `application.yml` selects it before the existing `SERVER_PORT` local override and otherwise defaults to 8080. Do not hardcode a Railway port. Keep exactly one app replica: HTTP sessions, WebSocket streams, recent context, captions, and temporary audio are in memory and cannot be shared across replicas.

## Owner setup sequence for Gate 10B

1. Confirm the intended `main` commit and Railway account status. A verified Full Trial removes the Limited Trial's outbound-network restrictions; a Limited Trial may block Groq/Gemini access. If Full Trial is unavailable, assess Hobby billing before enabling provider validation. Actual provider reachability remains unverified until a separately authorized live check.
2. Create one Railway project and connect the GitHub repository to an application service on `main`.
3. Add one Railway PostgreSQL service to the same project and region. Keep the database on private networking with persistent storage; do not give it a public TCP proxy for this app.
4. Name the database service `Postgres` or replace `Postgres` in the references below with its actual service name. Set these **application-service** variables as Railway references, not copied credential literals:

   ```text
   SPRING_PROFILES_ACTIVE=prod
   RAILPACK_JDK_VERSION=21
   DB_URL=jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}?sslmode=require
   DB_USERNAME=${{Postgres.PGUSER}}
   DB_PASSWORD=${{Postgres.PGPASSWORD}}
   VOICELINK_PREMIUM_ENABLED=false
   VOICELINK_STANDARD_FREE_TIER_CONFIRMED=false
   ```

   `DB_URL` must begin with `jdbc:postgresql://`; Railway's raw `DATABASE_URL` normally uses `postgresql://` and is not a JDBC URL. Check the resolved URL format without displaying its password. Confirm the private PostgreSQL connection accepts `sslmode=require`; do not silently disable TLS to bypass a connection error. The local `application-local.yml`, Compose `.env`, and `POSTGRES_PASSWORD` are not production app settings.
5. Initially leave both Standard keys unset. After the owner confirms Groq Free and Gemini API Free Tier status and authorizes live use, add `VOICELINK_GROQ_API_KEY` and `VOICELINK_GEMINI_API_KEY` as Railway secrets and set `VOICELINK_STANDARD_FREE_TIER_CONFIRMED=true`. Keep Premium off; a separate paid Premium project/key is not required for the campus demo. Never paste keys into Git, documentation, build arguments, a public browser page, or logs.
6. Deploy the application using Railpack. Confirm Java 21 and the Spring Boot JAR in build/start logs. Startup should connect to private PostgreSQL, apply Flyway V1–V5, and pass Hibernate `ddl-auto=validate`. A failure here blocks the deployment; do not use the local profile or manually modify the schema.
7. Configure Railway's health-check path to `/actuator/health`. It exposes only status, including database health. A healthy status does **not** verify provider credentials, outbound access, microphone, or translation quality.
8. Generate a Railway public domain for the app, then verify its `https://` URL. Railway terminates TLS at its proxy. The `prod` profile processes trusted forwarded headers and marks the session cookie `Secure`, `HttpOnly`, and `SameSite=Lax`; confirm those attributes, HTTPS redirects/links, CSRF, and same-origin `wss://` handshakes on the actual domain. Do not make WebSocket origins permissive.
9. Review deployment logs for accidental secrets or private content. Keep normal INFO-level logging; do not enable request/response-body, provider-body, SQL-parameter, transcript, glossary, or audio logging.
10. Perform the deferred final integrated QA as one later authorized stage: desktop/tablet/mobile appearance, keyboard accessibility, two-user accounts and rooms, microphone, WebSocket reconnect/errors, captions/audio, context/glossary, both English→Tamil and Tamil→English Standard provider paths, human quality review, and deployed smoke checks. Gate 10A performs none of this QA.

## Variables and persistence

**Required for application startup:** `SPRING_PROFILES_ACTIVE=prod`, `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD`. Railway supplies `PORT`; pin `RAILPACK_JDK_VERSION=21`. The PostgreSQL references above avoid duplicate manually entered credentials.

**Required only for working Standard speech:** `VOICELINK_GROQ_API_KEY`, `VOICELINK_GEMINI_API_KEY`, and `VOICELINK_STANDARD_FREE_TIER_CONFIRMED=true` after owner verification. Without this combination, provider execution remains blocked. There is no automatic paid fallback.

**Optional:** `SERVER_PORT` for local/non-Railway hosting when `PORT` is absent; `VOICELINK_PREMIUM_ENABLED=false` explicitly confirms the default; `VOICELINK_PREMIUM_GEMINI_API_KEY` is only for a separately authorized paid Premium launch. A JVM memory option should be chosen only after observing actual memory use and the selected Railway plan. Do not add storage infrastructure for temporary audio.

**Test/live-validation only:** `VOICELINK_LIVE_AUDIO_EN` and `VOICELINK_LIVE_AUDIO_TA`; neither belongs in the production app service.

**Password recovery:** `VOICELINK_PASSWORD_RESET_ENABLED` defaults to false. Enabling it requires `VOICELINK_PUBLIC_BASE_URL`, `VOICELINK_RESET_MAIL_API_KEY`, and `VOICELINK_RESET_MAIL_FROM_ADDRESS`; the application rejects incomplete or non-HTTPS configuration. The sender uses Resend's HTTPS API, not SMTP. Configure secrets only in the application environment after provider and sender verification. Do not claim live email delivery until it has been tested separately. Recovery links expire after 30 minutes, are single-use, and invalidate existing authenticated access after a successful password change.

Only the production profile can use the real sender. The local profile rejects enabled recovery, and automated tests use an offline capturing sender.

PostgreSQL retains accounts, rooms, and open-room glossary entries across app restarts. App memory does not: sessions, sockets, recent captions/context, idempotency state, and generated WAV may disappear on restart or redeploy. Audio is available only temporarily to active members, with a five-minute expiry and bounded memory; no app filesystem persistence or object storage is needed. Browser users may need to sign in again after a restart.

## Cost and account limits

Railway currently advertises a 30-day/$5 new-user trial, followed by a Free plan with $1 monthly credit and lower memory limits; Hobby has a $5 monthly minimum plus any excess usage. Trial credit, network access, memory, and billing eligibility depend on the owner's actual account. Do not create paid resources or enable providers based only on this document. Check current [Railway pricing](https://railway.com/pricing), [trial limits](https://docs.railway.com/pricing/free-trial), [Spring Boot deployment](https://docs.railway.com/guides/spring-boot), and [PostgreSQL variables](https://docs.railway.com/databases/postgresql) before Gate 10B actions.

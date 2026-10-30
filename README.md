# VoiceLink

VoiceLink is a Java web application for temporary, two-person English↔Tamil conversations. It combines speech recognition, translation, translated audio, reviewed text translation, and short-lived captions in an authenticated room.

**Production:** [voicelink-production-85f2.up.railway.app](https://voicelink-production-85f2.up.railway.app) · **Source:** [mahroosdev/VoiceLink](https://github.com/mahroosdev/VoiceLink)

The homepage translation example is illustrative and makes no provider requests. Automated tests and a production health check have passed, but human-reviewed translation quality, microphone/audio behavior, and the complete two-person graphical workflow have not been verified in this release evidence.

## Capabilities

- Session-authenticated accounts, room membership, invitation codes, and a two-participant limit.
- English↔Tamil speaking directions assigned and checked by the server.
- Short WebM/Opus speech turns: Groq `whisper-large-v3` transcription, Gemini `gemini-3.1-flash-lite` translation, and Gemini `gemini-3.8-flash-lite-tts` speech using Kore.
- Text translation offers private Translate Message reviews that are shared only after explicit Send. In Live Translation, one provisional draft is shared with the other participant, replaced as the text changes, and cleared when the draft is edited or sharing stops; Finish explicitly finalizes the message.
- Temporary captions and audio, with a single live conversation timeline. Conversation history is not stored in PostgreSQL or generally restored after reconnect; a bounded in-memory message-deduplication cache can echo a retried message.
- Room-scoped glossary entries and bounded, memory-only recent context.
- Premium provider routing is present in source but disabled by default; it is not activated or live-validated. Standard never automatically falls back to Premium.

These are implemented capabilities. They do not establish provider availability, translation accuracy, or behavior in every browser. Review [known limits](docs/ARCHITECTURE.md#limitations) before operating the service.

## Technology and design

Java 21, Spring Boot, Spring Security, Spring Data JPA, Maven Wrapper, PostgreSQL, Flyway, Thymeleaf, CSS, vanilla JavaScript, and authenticated same-origin WebSockets. See the [architecture](docs/ARCHITECTURE.md) and [HTTP/WebSocket reference](docs/API_REFERENCE.md).

## User guide

Read the [VoiceLink User Guide](docs/USER_GUIDE.md) or download the [PDF user guide](docs/VoiceLink-User-Guide.pdf).

## Local setup

Prerequisites: JDK 21, Docker with Compose, and Node.js for JavaScript tests. Maven is supplied by the wrapper.

1. Clone the repository and copy `.env.example` to `.env`.
2. Set unique local PostgreSQL credentials in `.env`. Add provider keys only if you have separately reviewed the providers' current terms, account tier, and quota. Never commit `.env`.
3. Start PostgreSQL and the app from PowerShell:

   ```powershell
   docker compose --env-file .env up -d postgres
   $settings = Get-Content .env | Where-Object { $_ -match '^(POSTGRES_USER|POSTGRES_PASSWORD|VOICELINK_[A-Z_]+)=' }
   foreach ($setting in $settings) {
       $name, $value = $setting -split '=', 2
       [Environment]::SetEnvironmentVariable($name, $value, 'Process')
   }
   $env:SPRING_PROFILES_ACTIVE = 'local'
   .\mvnw.cmd -ntp spring-boot:run
   ```

   The app uses `http://localhost:8080`; health is available at `/actuator/health`. The local profile applies Flyway migrations to the Compose PostgreSQL service. Do not use production credentials for local development.

Standard providers require `VOICELINK_GROQ_API_KEY`, `VOICELINK_GEMINI_API_KEY`, and `VOICELINK_STANDARD_FREE_TIER_CONFIRMED=true`, after the operator independently confirms the account/project tiers. Without this configuration, provider-backed speech/translation is unavailable. Do not send confidential conversations to free-tier providers. Premium is opt-in by server configuration and must remain disabled unless separately reviewed and authorized.

## Verification

```powershell
.\mvnw.cmd -ntp test
.\mvnw.cmd -ntp clean package
.\mvnw.cmd -ntp -Ppostgres-it verify
node --test src/test/js/*.test.cjs
```

For JavaScript syntax checks, run `node --check` on each file in `src/main/resources/static/js`. Normal tests use fake providers; do not use the separate `provider-live` profile as part of routine tests. The [testing guide](docs/TESTING.md) records the latest evidence and its limits.

## Deployment and operations

See [deployment](docs/DEPLOYMENT.md) for Java 21, PostgreSQL, environment-variable names, migrations, health checks, and safe rollout guidance. Do not copy credentials into command output, logs, screenshots, or source control. The [provider evaluation](docs/AI_PROVIDER_EVALUATION.md) explains data handling and distinguishes prompt design from unverified translation quality. The [Standard live QA plan](docs/STANDARD_LIVE_QA_PLAN.md) is an operator procedure, not authorization to make a provider call.

## Privacy and retention

Room access is checked server-side. Audio is sent to Groq for transcription; the current utterance and eligible context/glossary data may be sent to Gemini for translation, and translated text is sent to Gemini TTS. These transmissions are subject to the providers' current terms and account settings. Do not use sensitive or confidential content with free-tier services. VoiceLink does not persist conversation transcripts or generated audio in PostgreSQL. Open-room glossary entries and account/room records are persisted. Recent conversation context, Quick chat retry state, speech, and audio state are held in memory. Quick chat retries reuse a message ID for up to 30 minutes; server receipts are kept for at least 35 minutes, with at most 60 unique messages per participant in that rolling window and a 64-receipt hard cap. A room stream stays while any participant is connected and is removed after 35 minutes with everyone disconnected, or immediately on room closure. Restarting the single application instance loses in-memory receipts. See [architecture limitations](docs/ARCHITECTURE.md#limitations).

## Limitations

- English↔Tamil is the only supported pair. Live recognition, naturalness, pronunciation, latency, and end-to-end browser behavior require human testing.
- Public signup, login, password recovery (when enabled), room creation, invitation attempts, Live draft invalidations, and concurrent memory-hard password operations have configurable process-local safeguards. Before unrestricted public use, verify trusted proxy address handling and configure external ingress controls; these in-process limits are not shared across replicas.
- WebSocket state, rate limits, temporary captions, and generated audio are process-local; the current deployment design is single-instance.
- Premium activation, billing behavior, quality, and paid-provider operation are deferred and unverified.

Do not interpret a successful build, automated test, health response, or homepage load as proof of live translation quality or full browser workflow correctness.

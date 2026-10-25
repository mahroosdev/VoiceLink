# Standard provider validation procedure

This is a preparation guide, not authorization to make provider calls. Live tests consume quota and send input data to external providers. Use only consented, synthetic, non-sensitive, non-confidential examples. Keep keys, audio, transcripts, translations, and detailed linguistic notes out of logs and source control. Premium stays disabled.

## Preconditions

- Independently confirm Groq account tier and Gemini API project tier, available quota, current model access, and data-use terms in the providers' official consoles.
- Confirm the Standard key belongs to the intended project and verify `VOICELINK_STANDARD_FREE_TIER_CONFIRMED=true` only after those checks.
- Use a disposable room and short English and Tamil WebM/Opus clips. Do not use confidential conversations.
- Confirm the application is configured for Standard and `VOICELINK_PREMIUM_ENABLED=false`. Do not switch to paid models if a Standard request fails.
- Obtain explicit authorization for the bounded test before using the `provider-live` profile.

## Suggested checks

1. English speech recognition and English→Tamil translation.
2. Tamil speech recognition and Tamil→English translation.
3. Names, numbers, and common workplace/technical terms.
4. Glossary behavior and a short contextual follow-up.
5. Correct sender/receiver captions and translated WAV playback in both sessions.
6. Safe failure behavior, retry without duplicate send, and temporary audio expiry.

Human reviewers should assess meaning, tone, politeness, natural spoken Tamil, technical terminology, intelligibility, and latency separately for each direction. Do not use exact-match expectations for natural language. A mocked test or homepage example does not establish translation quality. Stop if account tier, quota, billing, privacy, or model behavior is uncertain.

Record only non-sensitive metadata: date, app commit, direction, provider/model identifier, pass/fail, safe error code, and elapsed times. Store transcripts/audio and detailed review notes privately if retention is appropriate; do not put private conversation content in the public repository.

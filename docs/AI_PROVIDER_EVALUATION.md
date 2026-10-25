# AI providers, translation behavior, and data handling

## Configured profiles

| Profile | Speech recognition | Translation | Speech generation | Operating status |
| --- | --- | --- | --- | --- |
| Standard | Groq `whisper-large-v3` | Gemini `gemini-3.1-flash-lite` | Gemini `gemini-3.8-flash-lite-tts`, Kore voice | Implemented; live language quality is not established by offline tests. |
| Premium | Shared Groq `whisper-large-v3` | Paid Gemini `gemini-3.8-flash` | Paid Gemini `gemini-3.8-flash-tts` | Code path exists, disabled by default, not live-validated. |

Standard requires `VOICELINK_GROQ_API_KEY`, `VOICELINK_GEMINI_API_KEY`, and operator confirmation that the actual accounts use the intended free tiers. Public model/pricing listings do not prove an individual account's billing or remaining quota. Premium requires a separate paid Gemini project/key and explicit server-side enablement. There is no automatic paid fallback. Normal automated tests use mocks and make no provider requests.

## Translation contract

The designed default is Natural Conversational translation. The request identifies English→Tamil or Tamil→English explicitly and asks Gemini to preserve meaning, intent, tone, politeness, questions/statements, and appropriate conversational style. It avoids forced literal word order, preserves names and technical terms where translation would distort them, and forbids added information, omissions, explanations, commentary, Markdown, or answering instead of translating. Tamil should suit spoken conversation; English should read naturally rather than mirror Tamil syntax. Structured response validation remains in place.

Room context and glossary are references only; the current utterance remains the translation target. Context is limited to recent eligible turns and held in memory. Room glossary entries are persisted for the open room. The prompt instructions and output contract are covered by offline tests. **Actual naturalness, fidelity, recognition accuracy, glossary adherence, pronunciation, and latency are unverified until human review of live output.**

## Data and privacy

Uploaded audio is sent to Groq. Transcribed text, eligible recent context, and applicable glossary terms may be sent to Gemini translation; translated text may be sent to Gemini TTS. Free-tier terms and data handling can differ from paid offerings and may change. Confirm current provider account settings, privacy terms, limits, and participant consent before sending any real content. Keep free-tier testing to synthetic, non-sensitive, non-confidential examples. Closing a room cannot retract data already sent to a provider.

VoiceLink does not write conversation transcripts or generated audio to PostgreSQL. Recent context and temporary captions/audio remain in memory for bounded lifetimes. Open-room glossary content is stored in PostgreSQL. Operational logs must not contain API keys, audio, transcripts, translations, context, glossary values, raw prompts, or provider response bodies.

## Primary references

- [Groq speech-to-text](https://console.groq.com/docs/speech-to-text) and [rate limits](https://console.groq.com/docs/rate-limits)
- [Gemini models](https://ai.google.dev/gemini-api/docs/models) and [pricing](https://ai.google.dev/gemini-api/docs/pricing)
- [Gemini speech generation](https://ai.google.dev/gemini-api/docs/speech-generation)
- [Groq data controls](https://console.groq.com/docs/your-data)

Model names, availability, terms, and quotas are time-sensitive; check the provider's current official documentation and account console before use. Documentation and mocked tests are not evidence of live service behavior.

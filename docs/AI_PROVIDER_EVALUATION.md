# VoiceLink AI provider evaluation

Initial research date: **2026-10-08**. Gate 5A: **PASS / APPROVED** by the project owner. Standard design revised **2026-10-10**, before live provider validation. Models, prices, quotas, regions, and terms are time-sensitive; published capability is not live VoiceLink quality evidence.

## Current provider mapping

| Profile | STT | Translation | TTS | Status |
| --- | --- | --- | --- | --- |
| Standard | Groq Free, `whisper-large-v3` | Gemini Developer API Free Tier, `gemini-3.1-flash-lite` | Gemini Developer API Free Tier, `gemini-3.8-flash-lite-tts` | Implemented offline; live English/Tamil validation pending |
| Premium | Google Cloud Speech-to-Text | Paid Gemini 3.5 Flash-Lite | Google Cloud Chirp 3 HD | Later provisional target; **not implemented** |

Standard uses quota-based free tiers. The operator must confirm the actual Groq account is Free and the Gemini API project is on the Free Tier before setting `VOICELINK_STANDARD_FREE_TIER_CONFIRMED=true`. A key does not prove the billing tier. Missing keys, rate limits, and quota failures stop the turn; VoiceLink has no paid model or provider fallback. Normal automated tests are offline. Gate 6B adds bounded prior source context and applicable room glossary terms to Gemini translation requests; this increases the data sent to Gemini.

Default translation style: **Natural Conversational**. **Designed behavior:** Gemini receives an explicit English→Tamil or Tamil→English instruction to preserve the speaker's meaning, intent, tone, politeness, question/statement intent, and conversational style; use natural spoken target-language wording rather than forced literal word order; retain proper names and technical terms when translation would distort them; and return only the structured translation without additions, omissions, explanations, commentary, markdown, or an answer to the speaker's question. Tamil should suit spoken TTS without unnecessary literary form; English should not mechanically mirror Tamil syntax. Gate 6B separates current utterance, bounded recent source context, and matching room glossary terms as structured data. Context and glossary are reference-only untrusted data, with explicit instructions not to follow embedded commands or translate prior turns. **Unverified quality:** actual English/Tamil naturalness, fidelity, glossary adherence, and prompt-injection resistance require live human review; offline tests verify only the request policy and response contract.

## Official evidence and remaining uncertainty

| Stage | Official evidence | Remaining uncertainty |
| --- | --- | --- |
| Groq STT | [Groq STT](https://console.groq.com/docs/speech-to-text) lists multilingual `whisper-large-v3`, transcription, source-language hints, and WebM input. [Groq limits](https://console.groq.com/docs/rate-limits) publishes Free limits. | Tamil recognition, account limits, and latency require live validation. No Groq translation endpoint is used. |
| Gemini translation | [Gemini 3.1 Flash-Lite model guide](https://ai.google.dev/gemini-api/docs/models/gemini-3.1-flash-lite) identifies `gemini-3.1-flash-lite`, recommends it for translation, and lists structured output support. [Generate Content API](https://ai.google.dev/api/generate-content) documents the REST request. [Gemini pricing](https://ai.google.dev/gemini-api/docs/pricing) currently lists Free Tier input/output for this model. | English↔Tamil meaning, terminology, output consistency, free quota, and actual project tier need owner validation. |
| Gemini TTS | [Gemini 3.8 Flash-Lite TTS](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash-lite-tts) and the [speech guide](https://ai.google.dev/gemini-api/docs/speech-generation) document the Interactions REST API, prebuilt `Kore` voice, English and Tamil support, and WAV output. [Gemini pricing](https://ai.google.dev/gemini-api/docs/pricing) currently lists Free Tier input/output for this model. | Pronunciation, accent, voice suitability, account limits, latency, and browser playback require live review. |
| Later Premium | [Google STT languages](https://docs.cloud.google.com/speech-to-text/docs/v1/speech-to-text-supported-languages), [Gemini pricing](https://ai.google.dev/gemini-api/docs/pricing), and [Chirp 3 HD](https://docs.cloud.google.com/text-to-speech/docs/chirp3-hd) informed a later provisional option. | Premium quality and cost are unmeasured; no Premium route or credentials exist. |

The original 2026-10-08 Standard selection was **provisional**: Groq Free STT, Azure Translator F0, and Azure Speech F0. The [Azure Translator language documentation](https://learn.microsoft.com/en-us/azure/ai-services/translator/language-support) and [Azure Speech language documentation](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/language-support?tabs=tts) supported that research. Azure account/payment-card verification repeatedly failed for the owner, so Standard translation/TTS changed to Gemini before live validation. Azure adapters and active configuration were removed. No Azure live API call was performed; no Gemini live validation has been performed.

## Data handling

Standard sends uploaded audio to Groq, the current transcript plus up to three eligible prior source transcripts and applicable room glossary terms to Gemini translation, and translated text to Gemini TTS. [Groq's data policy](https://console.groq.com/docs/your-data) describes inference-content handling and possible reliability/abuse retention. The current [Gemini pricing disclosure](https://ai.google.dev/gemini-api/docs/pricing) marks Free Tier input and output as **used to improve Google's products**. Development use should be limited to non-sensitive demo speech and non-confidential conversations. Do not represent Standard Free as an enterprise private-data service. Review actual account controls, current terms, and participant consent before any real conversation; Premium privacy must be evaluated separately later. Room closure cannot retract information already sent to an external provider.

VoiceLink drops uploaded bytes after STT, keeps generated WAV audio in bounded authenticated memory for at most five minutes, and keeps recent captions for at most ten minutes in one runtime. Prior translation context remains memory-only, disappears on restart, and is cleared on room closure. PostgreSQL stores accounts, rooms, and up to 12 glossary entries per open room, but no speech turns or recordings. Glossary entries survive restart and are deleted on explicit closure; a room left open may retain them indefinitely. Production diagnostics must not log raw audio, transcripts, translations, context, glossary contents, raw Gemini prompts, TTS input/output, provider bodies, or keys. Limited WebM header and declared-duration checks do not prove an adversarial file's fully decoded duration; stronger media inspection is later hardening.

## Live validation procedure

No live provider call or Tamil quality score is claimed yet. First verify Groq Free and Gemini API Free Tier in the provider consoles, review quotas and privacy, and use only owner-created, non-sensitive English and Tamil WebM/Opus clips under 15 seconds. The explicitly invoked `-Pprovider-live` profile requires `VOICELINK_GROQ_API_KEY`, `VOICELINK_GEMINI_API_KEY`, `VOICELINK_STANDARD_FREE_TIER_CONFIRMED=true`, and file paths in `VOICELINK_LIVE_AUDIO_EN` and `VOICELINK_LIVE_AUDIO_TA`. Run `.\mvnw.cmd -ntp -Pprovider-live verify` from the project root after loading those values in the process environment. It makes two Groq STT, two Gemini translation, and two Gemini TTS requests; even short clips consume quota. Normal `test`, `clean package`, and `-Ppostgres-it verify` do not select this profile. The live test logs only provider/model, direction, elapsed milliseconds, success, or safe failure code; human review of transcript and translation should remain private.

Suggested non-sensitive owner-spoken samples (record each clip under 15 seconds):

| Language | Sample | Coverage |
| --- | --- | --- |
| English | “Hello, Maya. Can you deploy the Java Spring Boot API to GitHub today?” | Greeting, name, everyday question, and mixed technical vocabulary |
| English | “I have 12 requests and 3 answers.” | Numbers and a short sentence |
| Tamil | “வணக்கம், அருண். இன்று Java API-யை GitHub-ல் பதிவேற்ற முடியுமா?” | Greeting, Tamil question, and mixed technical vocabulary |
| Tamil | “எனக்கு இரண்டு கேள்விகள் உள்ளன.” | Short Tamil sentence and number meaning |

These are prompts for human quality review, not scored test results. A Tamil speaker should compare recognition, meaning, technical terms, voice intelligibility, and elapsed time in both directions. Record actual spoken words, transcript, translation, and listening judgment privately; do not commit recordings or personal transcripts.

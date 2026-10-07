# VoiceLink AI provider evaluation

Research date: **2026-10-08**. Gate 5A: **PASS / APPROVED** by the project owner. Provider features, prices, quotas, regions, and terms may change. Published capability is not live VoiceLink quality evidence.

## Approved provisional profiles

| Profile | STT | Translation | TTS | Gate 5B status |
| --- | --- | --- | --- | --- |
| Standard | Groq Free, `whisper-large-v3` | Azure Translator F0 | Azure Speech F0 Neural | Implemented adapters and bounded pipeline; live Tamil validation pending |
| Premium | Google Cloud Speech-to-Text | Paid Gemini 3.5 Flash-Lite | Google Cloud Chirp 3 HD | Later provisional target; **not implemented** |

Standard uses **quota-based free tiers**, not unlimited free service. The application requires an explicit free-tier confirmation flag and contains no paid fallback. The operator must verify the actual Groq account is Free and both Azure resources are F0; an API key alone cannot prove billing tier. Quota exhaustion or a missing key stops the affected stage. Gate 5B automated tests use fake providers and do not contact Groq or Azure.

## Evidence and limitations

| Stage | Official evidence retrieved 2026-10-08 | Remaining uncertainty |
| --- | --- | --- |
| Groq STT | [Groq STT](https://console.groq.com/docs/speech-to-text) lists multilingual `whisper-large-v3`, transcription endpoint, language hint, WebM input, 25 MB Free upload limit and $0.111/audio hour paid list rate. [Groq limits](https://console.groq.com/docs/rate-limits) lists 20 requests/minute, 2,000/day, 7,200 audio seconds/hour and 28,800/day for the model on its published Free table. | Groq does not enumerate Tamil there. The underlying [Whisper tokenizer](https://github.com/openai/whisper/blob/main/whisper/tokenizer.py) includes Tamil, but actual Tamil recognition and account limits need live validation. |
| Azure Translator | [Language support](https://learn.microsoft.com/en-us/azure/ai-services/translator/language-support) includes English and Tamil. [F0 pricing](https://azure.microsoft.com/en-us/pricing/details/translator/) lists 2 million characters/month. [Translate v3](https://learn.microsoft.com/en-us/azure/ai-services/translator/text-translation/reference/v3/translate) documents explicit `from` and `to`. | Meaning and terminology preservation require Tamil-speaker evaluation; F0 availability is account/region dependent. |
| Azure Speech TTS | [Voice list](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/language-support?tabs=tts) lists `ta-LK-SaranyaNeural` and `en-US-JennyNeural`. [F0 pricing](https://azure.microsoft.com/en-us/pricing/details/speech/) lists 500,000 neural characters/month. [REST TTS](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/rest-text-to-speech) documents SSML and MP3 output. | Voice availability in the configured region, Tamil intelligibility, and browser playback need live validation. |
| Later Premium | [Google STT V1 languages](https://docs.cloud.google.com/speech-to-text/docs/v1/speech-to-text-supported-languages) lists `ta-IN` and `ta-LK`; [Gemini pricing](https://ai.google.dev/gemini-api/docs/pricing) describes Flash-Lite translation use and paid token pricing; [Chirp 3 HD](https://docs.cloud.google.com/text-to-speech/docs/chirp3-hd) lists Tamil voices. | Premium quality and cost are unmeasured; no Premium route or credentials exist in Gate 5B. |

## Data handling

Standard sends uploaded audio to Groq, its transcript to Azure Translator, and translated text to Azure Speech. [Groq's data policy](https://console.groq.com/docs/your-data) says inference content is not retained by default but may be held for reliability or abuse for up to 30 days unless eligible zero-data-retention settings are enabled. Azure's [Translator](https://learn.microsoft.com/en-us/azure/foundry/responsible-ai/translator/data-privacy-security?view=foundry-classic) and [Speech TTS](https://learn.microsoft.com/en-us/azure/foundry/responsible-ai/speech-service/text-to-speech/data-privacy-security?tabs=prebuilt-voice) pages describe their service data handling. Verify actual account controls and participant consent before live personal conversations.

VoiceLink drops uploaded bytes after STT, keeps generated audio in bounded authenticated memory for at most five minutes, and keeps recent captions for at most ten minutes in one runtime. The database stores accounts and rooms, not speech turns or recordings. Production diagnostics must not log raw audio, transcripts, translations, TTS input/output, provider bodies, or keys. Restart loses temporary speech results. Limited WebM header and declared-duration checks do not prove an adversarial file's fully decoded duration; stronger media inspection is later hardening.

## Live validation procedure

No live provider call or Tamil quality score is claimed yet. First verify Groq Free and Azure Translator/Speech F0 in the provider consoles, regional voices, and privacy controls. For the automated live profile, use one small non-sensitive owner-created English WebM/Opus clip and one Tamil clip. The explicitly invoked `-Pprovider-live` Maven profile requires `VOICELINK_LIVE_AUDIO_EN` and `VOICELINK_LIVE_AUDIO_TA` paths, Standard keys, and `VOICELINK_STANDARD_FREE_TIER_CONFIRMED=true`. It makes two Groq STT, two Azure translation, and two Azure TTS requests; short-clip usage still counts against free-tier quotas. It does not run under normal `test`, `clean package`, or `-Ppostgres-it verify`. A Tamil speaker must compare recognition, meaning, technical terms, voice intelligibility, and elapsed time in both directions. Do not commit private recordings.

Suggested non-sensitive owner-spoken samples (record each clip under 15 seconds):

| Language | Sample | Coverage |
| --- | --- | --- |
| English | “Hello, Maya. Can you deploy the Java Spring Boot API to GitHub today?” | Greeting, person's name, everyday question, Java, Spring Boot, API, GitHub, deployment, mixed technical vocabulary |
| English | “I have 12 requests and 3 answers.” | Numbers and a short English sentence |
| Tamil | “வணக்கம், அருண். இன்று Java API-யை GitHub-ல் பதிவேற்ற முடியுமா?” | Greeting, person's name, short Tamil question, mixed technical vocabulary |
| Tamil | “எனக்கு இரண்டு கேள்விகள் உள்ளன.” | Short Tamil sentence and number meaning |

These are prompts for human quality review, not scored test results. Record the actual spoken words, recognized transcript, translation, listening judgment, and elapsed time privately; do not place personal recordings or transcripts in Git.

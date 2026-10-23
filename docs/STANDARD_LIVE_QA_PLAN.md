# Standard English↔Tamil live QA — authorization pending

This plan is **not** permission to use Groq or Gemini. Use only invented, non-sensitive workplace phrases. Do not put keys, audio, transcripts, translations, or glossary content in logs, screenshots, tickets, or this repository. Record direction, pass/fail, provider/model identifiers, safe failure code, and elapsed time; keep any linguistic notes private and paraphrased. Premium and paid-project credentials stay disabled.

## Free-tier and account gate

As checked against public provider pages on 2026-10-24, Groq lists `whisper-large-v3` under its [Free Plan limits](https://console.groq.com/docs/rate-limits), and Google's [pricing page](https://ai.google.dev/gemini-api/docs/pricing) lists free-tier input/output for `gemini-3.1-flash-lite` and `gemini-3.8-flash-lite-tts`. Public listings are **not** evidence that the owner's Groq account and Gemini project currently have Free status, remaining quota, model access, or no billing exposure. Before any call, the owner must inspect those account dashboards, confirm available quota for all three models, confirm that the Standard Gemini key belongs to the intended free project, and explicitly authorize a bounded live pass. If any check fails or is unclear, keep `VOICELINK_STANDARD_FREE_TIER_CONFIRMED=false` and stop. Do not switch models or route to Premium.

## Small synthetic test matrix

| Check | Synthetic input and observation | Pass evidence |
| --- | --- | --- |
| English STT | Short English WebM/Opus utterance: “Could you share the project update?” | Meaningful English transcript; record recognizer model and elapsed time. |
| Tamil STT | Short Tamil WebM/Opus utterance requesting a project update; owner supplies natural phrasing. | Meaningful Tamil transcript; note recognition errors privately. |
| English→Tamil | Translate the English utterance above. | Natural spoken Tamil with correct question intent and politeness; code-switching only where natural. Do not require an exact fixed sentence. |
| Tamil→English | Translate the Tamil utterance. | Natural English preserving actual meaning, names, numbers, and question/statement intent. |
| Technical terms | Short synthetic turn with “REST API”, “Java 21”, and a made-up product name. | Terms preserved where translation would distort them; no invented information. |
| Room glossary | Add one synthetic term in the room; use it in both permitted directions. | Preference applied only when matching and consistent with the current utterance. |
| Two-person flow | English-speaking A and Tamil-speaking B exchange one speech turn each. | Sender/source and partner/target captions, authenticated WAV playback, Kore voice, and correct attribution in each session. |
| Failure and recovery | Use a controlled provider-disabled or network-failure case separately from the live pass. Refresh, reconnect, Retry, and close. | Safe error; existing captions survive TTS failure; no duplicate final delivery; temporary history/expiry and closed-room access behave as documented. |

Record STT, translation, TTS, and end-to-end elapsed time separately for each direction. Check audio MIME, playback, and five-minute expiry without retaining private audio. A bilingual human must judge naturalness, tone, pronunciation, code-switching, and technical-term consistency. Mocked responses and the illustrative homepage preview are not quality evidence. Stop after the agreed small number of calls or if quota/rate-limit, configuration, billing, privacy, or model behavior is uncertain.

# VoiceLink User Guide

**A practical guide to private, two-person English-Tamil conversations**
For the deployed VoiceLink web application · October 2026

[Open VoiceLink](https://voicelink-production-85f2.up.railway.app)

> This guide describes controls and behavior present in the current application source. It is not evidence that every workflow has passed graphical browser testing. Real recognition, translation naturalness, pronunciation, and audio playback depend on provider availability and still need human validation.

## Contents

1. [What VoiceLink does](#1-what-voicelink-does)
2. [Before you begin](#2-before-you-begin)
3. [Create an account and sign in](#3-create-an-account-and-sign-in)
4. [Create a conversation and invite someone](#4-create-a-conversation-and-invite-someone)
5. [Join a conversation](#5-join-a-conversation)
6. [Understand the conversation screen](#6-understand-the-conversation-screen)
7. [Type, review, and send](#7-type-review-and-send)
8. [Speak, review, and send](#8-speak-review-and-send)
9. [Retries, reconnecting, and closing a room](#9-retries-reconnecting-and-closing-a-room)
10. [Troubleshooting](#10-troubleshooting)
11. [Privacy, temporary data, and limitations](#11-privacy-temporary-data-and-limitations)
12. [Quick-start checklist](#12-quick-start-checklist)

## 1. What VoiceLink does

VoiceLink is a web application for a temporary conversation between two authenticated people. One person speaks English and the other speaks Tamil. The app can translate typed messages or short recorded speech turns. Speech turns can also produce translated audio. A temporary conversation timeline helps both people follow shared messages and translated turns.

VoiceLink currently supports **English and Tamil only**. Its translation preview on the public homepage is an illustration; opening that page does not submit content to an AI provider.

## 2. Before you begin

- Use an up-to-date browser and an internet connection. Open the [VoiceLink application](https://voicelink-production-85f2.up.railway.app) over HTTPS.
- For speech, allow microphone access when the browser asks. Use a working microphone. A speaker or headphones are needed to listen to generated audio.
- Both participants need their own VoiceLink accounts and must be signed in to join a room.
- Plan to use only non-sensitive, non-confidential conversation content. Speech and text may be processed by external providers.

If the current deployment does not have the required Standard provider configuration or quota available, speech or translated workflows may report that the service is unavailable. This guide cannot guarantee provider availability.

## 3. Create an account and sign in

1. Open the application and choose **Create an account**.
2. Enter a display name, email address, and password. The registration password must be 10-128 characters.
3. Submit **Create account**. A successful registration returns you to **Sign in** with an account-created confirmation.
4. Enter your email and password and choose **Sign in**.

After sign-in, **Conversations** is your workspace. Use **Rooms** in the application navigation to create or join rooms. The **Account management** menu provides account options and **Sign out**.

Password recovery is configuration-dependent. If **Forgot password?** is not shown on the sign-in page, recovery is not available in that deployment; contact the service owner through an established support channel.

## 4. Create a conversation and invite someone

1. Open **Rooms**, then find **Create Conversation**.
2. Enter **Your display name**. It is used for this conversation; it does not change your account name.
3. Choose **English** or **Tamil** under **Which language will you speak?** The page previews the two directions: each person's speech is translated toward the other person's language.
4. Choose **Standard** for the free-tier service profile. It has provider quotas and is intended for non-sensitive demo conversations. If a **Premium** option is enabled on a server, it uses paid Gemini services; do not select it unless its use has been separately approved.
5. Choose **Create conversation**.
6. In the waiting room, share the displayed **Private invite code** with one person using a channel you trust. VoiceLink displays when the code expires; VoiceLink does not send the invitation for you.

A room supports two participants. While the room is open, the conversation owner can change the language pair from **Room details**. The partner's direction updates automatically. Closed rooms cannot be changed. The service profile remains fixed for the room after creation.

## 5. Join a conversation

1. Sign in to your own account and open **Rooms**.
2. Under **Join a conversation**, enter the invite code and choose **Continue**. The code can be entered with or without hyphens.
3. Review the conversation owner's display name and speaking language.
4. Choose your conversation display name and the available speaking language. The application offers only the language complementary to the owner's language.
5. Choose **Join conversation**.

An expired, invalid, closed, or already-full invitation cannot be used. Ask the owner to create a new conversation and share its new code.

## 6. Understand the conversation screen

- The header shows the room direction, status (**WAITING**, **ACTIVE**, or **CLOSED**), and service profile.
- While waiting, the owner can see the invite code and expiry. The conversation tools become available when the second participant joins.
- Choose **Speak** to record speech or **Type** to write text.
- The **Conversation** timeline has **Translated** and **Quick chat** filters. These are temporary recent activity, not a saved chat archive. Translated turns identify the original and translated content and its participant. Quick chat messages are shown as written.
- Open **Technical glossary** to manage room-specific terms while the room is open. Entries are shared by room members, are limited to 12, and are deleted when the room is explicitly closed.
- **Room details** explains the provider and temporary-data behavior. Read it before sending content.

## 7. Type, review, and send

In **Type**, choose one of the three composer modes:

- **Translate message**: enter a message and choose **Translate**. Review the original and translated text. The other participant does not receive the draft. Choose **Send translated message** only when the translation is ready to share. If translation fails, use **Retry translation** while the same draft is available.
- **Live translation**: as you type, VoiceLink requests an updated translation preview. The preview is private to you. Choose the explicit **Send translated message** action to share the reviewed result; typing alone does not send it.
- **Quick chat**: sends the text as written, without translation. Enter a message and choose **Send**. Do not use Quick chat when you need translated wording.

If you are the Tamil-speaking participant, an optional **Type Tamil using English letters** checkbox is available. When selected, the message and live translation modes interpret your Romanized input as Tamil. This is model interpretation, not a deterministic transliteration tool. Quick chat always sends the text as typed and is unaffected by this option.

## 8. Speak, review, and send

1. Select **Speak** and check that the displayed direction matches your speaking language.
2. Choose either **Live Send** or **Finish & Send**.
3. Choose **Start speaking**, speak clearly, then choose **Stop recording** within 15 seconds. The browser uploads the short recording for processing.

**Live Send** processes and sends the translated turn when the workflow succeeds; it does not provide the separate pre-send review step. If translation fails after recognition, the recognized text and **Retry translation** control are visible only to the sender. Retry translation uses the existing recognized text instead of asking you to record again. The partner does not receive that failed transcript.

**Finish & Send** gives you a private review of **What VoiceLink heard** and the **Translation**. Check both before choosing **Send translated turn**. The review expires after two minutes. Use **Record again** if recognition is wrong, or **Cancel** to discard the review. The partner receives the turn only after you send it.

After a translated turn is shared, generated audio may become available in the timeline. Audio generation and playback depend on provider response, browser support, and connection quality; they are not guaranteed. Captions and audio are temporary.

## 9. Retries, reconnecting, and closing a room

- If a translation fails, follow the visible status and choose **Retry translation** when offered. For speech translation failure, retry uses the transcript already recognized for that turn; it does not repeat speech recognition.
- A pending Quick chat send reuses its message ID for automatic retry for up to 30 minutes after its first send attempt. If delivery is still unconfirmed after that deadline, the app stops automatic retries and shows an unconfirmed status. A manual resend after the deadline requires an explicit confirmation because a duplicate may be possible.
- Quick chat retry state is held in the current page. Refreshing or restarting the browser can lose a pending message ID; the conversation timeline is not a durable message history and may not be restored after reconnect. Do not assume that an unconfirmed message was delivered or not delivered.
- If the connection drops, wait for the room to reconnect and follow the status shown. If the page was refreshed and older text is no longer visible, this is consistent with the temporary timeline design.
- To end a room, open **Room details** and choose **Leave and close room** (or **Cancel room** while waiting). Closing ends the room for both participants, cannot be undone, and deletes its room glossary. A closed room cannot be reopened or rejoined.

## 10. Troubleshooting

| Problem | What to try |
| --- | --- |
| Sign-in fails | Check the email and password. Use **Create an account** if you are new. If password recovery is not shown, contact the service owner. |
| Invite code is unavailable | Check the code, including characters, and ask the owner to verify that it has not expired and the room is not full or closed. |
| Room remains waiting | Confirm that the other person has signed in, entered the current code, selected the complementary language, and joined successfully. |
| Microphone is blocked | Allow microphone access for the HTTPS site in browser permissions, select the intended microphone, and reload if the browser requires it. |
| No transcript or translation | Check the connection and server/provider availability. Try again when the service is available. Do not repeatedly submit sensitive content. |
| Speech translation failed | Use **Retry translation** if shown. It reuses the recognized text; if recognition itself is wrong, use **Record again** in the private review flow. |
| No audio plays | Check device volume, output device, browser playback permissions, and connection. Generated audio depends on provider availability and may expire. |
| Recent messages are missing | Recent activity is temporary and is not a persistent transcript. A refresh, application restart, room closure, or expiry may clear it. |

## 11. Privacy, temporary data, and limitations

VoiceLink uses authenticated accounts and checks room membership on the server. The invitation code should be shared only with the intended participant. A room has two participant slots.

For speech, recorded audio is sent to Groq for speech recognition. The current utterance, eligible recent conversation context, and applicable room glossary terms may be sent to Gemini for translation. Translated text may be sent to Gemini for generated voice. Provider handling depends on the selected account tier, provider settings, and their current terms. Do not submit sensitive or confidential conversations or glossary entries.

VoiceLink stores account, room, participant, and open-room glossary records in PostgreSQL. It does not store a persistent conversation transcript or generated audio there. Recent captions, speech state, audio, context, and Quick chat retry/deduplication state are temporary application memory. Context and in-memory state are lost on application restart; glossary entries survive while a room is open and are deleted when it is explicitly closed. Reconnecting does not turn the timeline into an archive.

VoiceLink is not documented as end-to-end encrypted. Translation can be inaccurate or unnatural; recognition can be wrong; audio may not be generated or playable; and service availability is not guaranteed. Only English and Tamil are supported. Premium is a separate paid profile when enabled and is not a fallback for Standard.

## 12. Quick-start checklist

- [ ] Open the [VoiceLink application](https://voicelink-production-85f2.up.railway.app) and sign in, or create an account.
- [ ] Create a room in **Rooms**, set your display name and speaking language, and choose the approved service profile.
- [ ] Share the private invite code with exactly one intended participant.
- [ ] Confirm the other participant joins using the complementary language.
- [ ] For text, review translations and use the explicit Send action. Use Quick chat only for untranslated text.
- [ ] For speech, permit microphone access, choose **Live Send** or **Finish & Send**, and review before sending when using Finish & Send.
- [ ] Keep content non-sensitive and remember that captions, audio, and room activity are temporary.
- [ ] Close the room only when you intend to end it for both participants and delete its glossary.

## Verification note

The instructions above were cross-checked against the current VoiceLink templates, controllers, services, JavaScript, README, and architecture/privacy documentation. This guide contains no application screenshots: no graphical browser session was used to verify the full user workflow while preparing it. The deployment's HTTP availability is not a test of two-user browser behavior, live translation quality, or audio quality.

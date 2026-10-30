# Two-browser functional QA

This is a manual test procedure, not evidence that the steps have been completed. Use a disposable local database, two separate browser profiles, synthetic accounts, and non-sensitive messages. Do not include invite codes, account details, transcripts, audio, or screenshots with private content in public reports.

## Start a local test environment

Follow the [local setup](../README.md#local-setup), then start the application with provider keys omitted and provider calls disabled. Confirm the health endpoint is UP. Use a disposable PostgreSQL database; never use production data for test cleanup.

## Two participants

1. In browser A, register/sign in and create a Standard room. Choose a display name and speaking language. Keep the invite code private.
2. In browser B, sign in with a separate account. Preview/join the room, confirm the complementary language, and choose a different display name.
3. Confirm both sessions see their own identity as You and the other participant as Partner, with complementary speak/receive labels.
4. Attempt access as a non-member and verify protected room state, glossary, audio, and WebSocket operations are denied. Confirm a third participant cannot join.

## Text and timeline

1. Send a Quick chat message in each direction. Check sender attribution, safe text rendering, and the Translated/Quick chat filters (Translated is the default).
2. With providers disabled, verify translation failures are clear and do not create a false completed translation. This checks the failure path only; it cannot produce a successful Live draft.
3. To verify successful Shared Live behavior in browsers, use synthetic, non-sensitive text and a functioning Standard provider only after checking account tier, quota, and data-use terms. Type in browser A and confirm browser B sees the provisional draft. Edit the text and confirm B sees the replacement; clear the input or stop sharing and confirm the stale draft is cleared. Select Finish and confirm the intended text is finalized once for both participants.
4. Use explicit Send in Translate Message mode. Check Retry after a simulated recoverable failure only when a local mock or failure-injection setup is available; confirm it does not create a duplicate final message. Check composer mode and selected history filter remain independent.
5. Refresh/reconnect and confirm a stale live draft is not restored and the interface does not promise durable message history. Close the room and check access is denied and glossary data follows the documented lifecycle.

The automated client and service tests use mocks for successful translation, revision replacement/clearing, retry, and cleanup-failure behavior. They can verify those flows without provider calls, but they do not replace observing two authenticated browser sessions. Do not use provider credentials or enable Premium for routine browser checks.

## Speech and responsive/accessibility review

After separately authorized provider validation only, test short synthetic speech in both directions. Check recording, recognized text, translated caption, playback, failure recovery, and member attribution. Do not infer live quality from provider-disabled tests.

Use desktop and actual browser viewport sizes 1440, 1024, 700, 390, and 320 CSS pixels. Check overflow, focus visibility, keyboard-only operation, touch targets, reduced motion, modal focus handling, status announcements, and long timeline scrolling. A narrow desktop window is not a substitute for a mobile device/browser check.

## Recording results

Record date, app commit, browser/version, actual device/viewport, and pass/fail by scenario. Keep real account information, invite codes, provider credentials, audio, and conversation content private. Distinguish manual browser observation from automated protocol or rendering tests.

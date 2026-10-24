package com.mahroosdev.voicelink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.regex.Pattern;

import com.mahroosdev.voicelink.auth.AccountPrincipal;
import com.mahroosdev.voicelink.room.RoomService;
import com.mahroosdev.voicelink.user.UserAccount;
import com.mahroosdev.voicelink.user.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UiRenderingTests {
    @Autowired MockMvc mvc;
    @Autowired RoomService rooms;
    @Autowired UserAccountRepository accounts;

    private AccountPrincipal principal(String displayName) {
        UserAccount account = accounts.save(new UserAccount(
                displayName, UUID.randomUUID() + "@example.test", "test-only-hash"));
        return new AccountPrincipal(account.getId(), account.getEmail(),
                account.getPasswordHash(), true);
    }

    @Test
    void publicFaviconServesRealIconWithoutOpeningProtectedRoutes() throws Exception {
        var favicon = mvc.perform(get("/favicon.ico"))
                .andExpect(status().isOk()).andReturn().getResponse();
        byte[] content = favicon.getContentAsByteArray();
        assertThat(content.length).isGreaterThan(100);
        assertThat(content[0]).isZero();
        assertThat(content[1]).isZero();
        assertThat(content[2]).isEqualTo((byte) 1);
        assertThat(content[3]).isZero();
        assertThat(favicon.getContentType()).contains("icon");
        mvc.perform(get("/css/voicelink.css")).andExpect(status().isOk());
        mvc.perform(get("/app")).andExpect(status().isFound());
        mvc.perform(get("/js/room-live.js")).andExpect(status().isFound());
    }

    @Test
    void publicStylesheetAndRealAuthenticationFormsRenderWithoutOpeningRoomScripts() throws Exception {
        String css = mvc.perform(get("/css/voicelink.css"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(css).contains("--vl-primary: #006F73", "prefers-reduced-motion",
                "focus-visible", "@media (max-width: 639px)", "@media (max-width: 799px)",
                "--vl-shell-width: 1120px", ".authenticated-shell {",
                ".application-sidebar {", ".application-bottom-nav {",
                ".room-form-grid { grid-template-columns: 1fr; }",
                "flex-wrap: wrap", ".about-grid { grid-template-columns: 1fr; }",
                ".password-control input { padding-right: 60px; }", "width: 44px;",
                ".password-toggle__show, .password-toggle[data-visible=\"true\"] .password-toggle__hide { display: none; }",
                ".password-toggle[data-visible=\"true\"] .password-toggle__show { display: inline; }");
        assertThat(css).contains("--vl-canvas:", "--vl-depth-3:", "--vl-motion-slow:",
                ".application-nav a.is-current::before", ".recording-card[data-phase=\"listening\"]",
                ".turn-caption--revealed", "@media (prefers-reduced-motion: reduce)")
                .doesNotContain("transition: all");
        assertThat(css).contains("account-menu-out", ".room-state-entered",
                ".recording-card[data-phase=\"uploading\"] .recording-status::before",
                ".recording-card[data-phase=\"error\"] .recording-status",
                ".turn-card[data-phase=\"ready\"] .turn-card__status",
                ".glossary-dialog::backdrop", ".glossary-dialog:not([open])",
                ".recording-card[data-phase=\"uploading\"] .recording-status::before,",
                "animation: none !important");
        assertThat(css).contains("@media (min-width: 900px)",
                ".authenticated-shell--about .workspace--about {",
                "overflow-y: auto;", "overscroll-behavior-y: contain;",
                "@media (min-width: 900px) and (max-height: 359px)",
                ".authenticated-shell--about .application-sidebar { overflow-y: auto; }");
        assertThat(css).contains(".public-shell--home", ".home-hero {",
                ".home-preview {", ".home-benefits__grid {",
                "@media (max-width: 799px)", "@media (max-width: 359px)",
                ".public-shell--home .site-header .header-actions > a:not(.button) { display: inline-flex;",
                "@media (hover: hover) and (pointer: fine)",
                ".home-preview-frame { position: relative; min-width: 0; isolation: isolate; }",
                ".home-preview-frame::before {", "pointer-events: none;",
                ".home-preview:hover { border-color: #B5D2CF;",
                ".home-benefit:hover { border-color: #C8DAD8;",
                ".public-shell--home .header-actions a:focus-visible",
                ".home-hero__action:hover:not(:disabled):not([aria-disabled=\"true\"])",
                "transition-duration: 200ms;", "transition: none !important;")
                .doesNotContain(".home-preview::before");
        assertThat(css).contains(".profile-option:hover:not(:has(input:disabled)):not(:has(input:checked))",
                ".button:active:not(:disabled):not([aria-disabled=\"true\"]) { box-shadow: inset",
                "@keyframes vl-caption-in { from { opacity: 0; } to { opacity: 1; } }")
                .doesNotContain(".button:hover:not(:disabled):not([aria-disabled=\"true\"]) { transform",
                        ".application-nav a:active { transform", ".application-bottom-nav a:active { transform",
                        ".home-preview:hover { transform", ".home-benefit:hover { transform",
                        "@keyframes account-menu-in { from { opacity: 0; transform",
                        "@keyframes vl-caption-in { from { opacity: 0; transform");
        assertThat(Pattern.compile("(?s)\\.home-preview \\{([^}]*)\\}").matcher(css)
                .results().findFirst().orElseThrow().group(1))
                .contains("background: var(--vl-surface);")
                .doesNotContain("var(--vl-teal-wash)");
        assertThat(Pattern.compile("(?s)\\.home-preview:hover \\{([^}]*)\\}").matcher(css)
                .results().findFirst().orElseThrow().group(1))
                .doesNotContain("background:", "background-color:");
        mvc.perform(get("/js/room-live.js")).andExpect(status().isFound());
        String visibilityScript = mvc.perform(get("/js/password-visibility.js"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(visibilityScript).contains("input.type = visible ? \"text\" : \"password\"",
                "visible ? \"Hide password\" : \"Show password\"",
                "button.setAttribute(\"aria-pressed\", String(visible))")
                .doesNotContain("innerHTML", "localStorage", "sessionStorage");

        String login = mvc.perform(get("/login"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String register = mvc.perform(get("/register"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for (String html : new String[] { login, register }) {
            assertThat(html).contains("/css/voicelink.css", "name=\"_csrf\"",
                    "class=\"skip-link\"");
        }
        assertThat(login).contains("action=\"/login\"", "name=\"email\"", "name=\"password\"");
        assertThat(register).contains("action=\"/register\"", "name=\"displayName\"",
                "name=\"email\"", "name=\"password\"", "10–128 characters.");
        assertThat(login).contains("Continue your translated conversations.",
                "src=\"/js/password-visibility.js\"").doesNotContain("English ↔ Tamil", "English and Tamil");
        assertThat(register).contains("Create your account for private translated conversations.",
                "src=\"/js/password-visibility.js\"").doesNotContain("English ↔ Tamil", "English and Tamil");
        assertPasswordControl(login, "password", "current-password");
        assertPasswordControl(register, "password", "new-password");
    }

    private void assertPasswordControl(String html, String id, String autocomplete) {
        var control = Pattern.compile("<div class=\"password-control\">\\s*<input id=\"" + id
                + "\"[^>]*type=\"password\"[^>]*autocomplete=\"" + autocomplete
                + "\"[^>]*>\\s*<button[^>]*type=\"button\"[^>]*aria-label=\"Show password\""
                + "[^>]*aria-pressed=\"false\"[^>]*title=\"Show password\""
                + "[^>]*aria-controls=\"" + id + "\"[^>]*data-password-toggle[^>]*data-visible=\"false\"");
        assertThat(control.matcher(html).find()).as("masked password control for " + id).isTrue();
        assertThat(html).contains("class=\"password-toggle__show\"", "class=\"password-toggle__hide\"");
    }

    @Test
    void dashboardAndRoomsUseRealActionsAndAccurateUnavailableProfileCopy() throws Exception {
        AccountPrincipal owner = principal("Owner");
        String app = mvc.perform(get("/app").with(user(owner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(app).contains("class=\"authenticated-shell\"", "<h1>Conversations</h1>",
                "Continue an active room or start a new conversation.",
                "/rooms#create", "/rooms#join", "href=\"/about\"", "action=\"/logout\"",
                "class=\"application-sidebar\"", "class=\"application-topbar\"",
                "class=\"application-bottom-nav\"", "aria-current=\"page\"", owner.getUsername());
        assertThat(app).contains("Current conversations", "No active conversations",
                "class=\"conversation-empty\"", "<details class=\"application-account\"",
                "aria-label=\"Account options\"", "class=\"application-account__menu-identity\"",
                "class=\"application-account__menu-copy\"", "class=\"application-account__email\"",
                "title=\"" + owner.getUsername() + "\"", "Account management", "Sign out");
        assertThat(app.indexOf("Signed in as")).isLessThan(app.indexOf("Account management"));
        assertThat(app.indexOf("Account management")).isLessThan(app.indexOf("Sign out"));
        assertThat(app).contains("event.key === \"Escape\"", "summary.focus()",
                "!menu.contains(event.target)")
                .doesNotContain("href=\"/account/delete\"");
        assertThat(app).doesNotContain("planned for later gates", "English ↔ Tamil", "English and Tamil",
                "Speak naturally and hear the conversation translated.", "class=\"site-header\"");

        String landing = mvc.perform(get("/"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(landing).contains("class=\"public-shell public-shell--home\"",
                "class=\"home-preview-frame\"",
                "A clearer way to speak across languages.",
                "private, two-person English ↔ Tamil conversations",
                "href=\"/register\">Get started</a>",
                "href=\"/register\">Create account</a>",
                "aria-label=\"Illustrative English to Tamil conversation preview\"",
                "Illustrative preview", "English → Tamil",
                "English · Speech", "Tamil · Translated text",
                "Could you share the project update?",
                "project update-ஐ share பண்ண முடியுமா?",
                "Private two-person rooms", "Speak or type", "Read and listen");
        assertThat(landing).doesNotContain("திட்டத்தின் புதுப்பிப்பைப் பகிர முடியுமா?",
                "தமிழ் · மொழிபெயர்க்கப்பட்ட உரை");
        assertThat(Pattern.compile("href=\"/login\"[^>]*>Sign in</a>")
                .matcher(landing).results().count()).isEqualTo(1);
        assertThat(landing).doesNotContain("simultaneous interpretation", "always listening",
                "real-time interpreter");

        String index = mvc.perform(get("/rooms").with(user(owner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(index).contains("class=\"authenticated-shell\"", "<h1>Rooms</h1>",
                "Closed rooms", "No closed rooms",
                "class=\"application-sidebar\"", "class=\"application-bottom-nav\"",
                "action=\"/rooms\"", "action=\"/rooms/join/preview\"",
                "name=\"displayName\"", "name=\"speaking\"", "name=\"profile\"",
                "Paid Gemini translation and voice; Groq speech recognition is shared.",
                "Premium is not available on this server.", "name=\"_csrf\"",
                "href=\"/about#privacy\"", "Recent captions and generated audio are temporary.",
                "href=\"/about\"", owner.getUsername());
        assertThat(index).contains("id=\"direction-preview\"", "Conversation Owner")
                .doesNotContain("Recent History", "Recovery Key");
        assertThat(index).doesNotContain("<details class=\"disclosure data-disclosure\"",
                "Standard uses Gemini Free Tier for translation and voice.",
                "VOICELINK_PREMIUM_GEMINI_API_KEY");
        assertThat(index.indexOf("Premium is not available on this server.") - index.indexOf("value=\"PREMIUM\""))
                .isBetween(0, 1000);
    }

    @Test
    void roomCreationScriptIsAvailableOnlyToAuthenticatedUsers() throws Exception {
        mvc.perform(get("/js/room-create.js"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login"));

        AccountPrincipal owner = principal("Room creator");
        var script = mvc.perform(get("/js/room-create.js").with(user(owner)))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(script.getContentType()).contains("javascript");
        assertThat(script.getContentAsString()).contains("premium-cost-saver",
                "input[name=\"profile\"]", "premiumControls.hidden = !selected",
                "direction-preview", "textContent = creatorLanguage")
                .doesNotContain("innerHTML", "localStorage", "sessionStorage");

        mvc.perform(get("/app"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login"));
        mvc.perform(get("/js/room-live.js"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void conversationsShowsOnlyCurrentMemberRoomsWhileRoomsManagesClosedRooms() throws Exception {
        AccountPrincipal owner = principal("Owner");
        AccountPrincipal guest = principal("Guest");
        UUID waitingId = rooms.createRoom(owner.getUserId(), "ta", "en");
        UUID activeId = rooms.createRoom(owner.getUserId(), "en", "ta");
        rooms.joinRoom(guest.getUserId(), rooms.getRoomForMember(owner.getUserId(), activeId).inviteCode());
        UUID closedId = rooms.createRoom(owner.getUserId(), "en", "ta");
        rooms.closeRoom(owner.getUserId(), closedId);

        String app = mvc.perform(get("/app").with(user(owner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(app).contains("href=\"/rooms/" + waitingId + "\"", "href=\"/rooms/" + activeId + "\"",
                "Waiting for a guest", "Two people connected", "Tamil → English", "Open room");
        assertThat(app).doesNotContain("href=\"/rooms/" + closedId + "\"", "No active conversations",
                "transcript preview", "conversation history", "last message");

        String index = mvc.perform(get("/rooms").with(user(owner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(index).contains("Closed rooms", "href=\"/rooms/" + closedId + "\"",
                "View closed room", "Remove room", "action=\"/rooms\"", "action=\"/rooms/join/preview\"");
        assertThat(index).doesNotContain("href=\"/rooms/" + waitingId + "\"",
                "href=\"/rooms/" + activeId + "\"");
    }

    @Test
    void aboutIsPublicAndHoldsTheMovedInformationWithoutPrivateAccountData() throws Exception {
        String about = mvc.perform(get("/about"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(about).contains("About VoiceLink", "How VoiceLink works", "Privacy &amp; Data",
                "AI Service Profiles", "Current Scope", "Groq handles speech recognition",
                "Gemini handles translation and generated voice", "no automatic fallback",
                "does not store a persistent transcript history", "glossary entries are stored in PostgreSQL",
                "bounded recent context, and matching room glossary terms may be sent to Gemini",
                "Premium is not available on this server.", "href=\"/login\"",
                "href=\"/about\"", "id=\"privacy\"", "class=\"skip-link\"",
                "VoiceLink supports two-person English ↔ Tamil rooms",
                "Each person speaks English or Tamil and receives the other language.");
        assertThat(about).contains("Understand each other, one turn at a time.");
        assertThat(about).contains("class=\"page-heading about-intro\"", "class=\"about-grid\"",
                "class=\"about-flow\"", "Speak", "Translate", "Hear",
                "Provider processing", "Temporary on this server", "Room glossary",
                "Two-person rooms", "English ↔ Tamil", "Short push-to-talk turns");
        assertThat(about.indexOf("How VoiceLink works")).isLessThan(about.indexOf("AI Service Profiles"));
        assertThat(about.indexOf("AI Service Profiles")).isLessThan(about.indexOf("Privacy &amp; Data"));
        assertThat(about.indexOf("Privacy &amp; Data")).isLessThan(about.indexOf("Current Scope"));
        assertThat(about).contains("class=\"public-shell public-shell--about\"", "class=\"site-header\"")
                .doesNotContain("tabindex=\"0\"")
                .doesNotContain("authenticated-shell--about");
        assertThat(about).doesNotContain("action=\"/logout\"", "Signed-in account",
                "class=\"application-sidebar\"", "class=\"application-bottom-nav\"");

        AccountPrincipal owner = principal("Owner");
        String signedIn = mvc.perform(get("/about").with(user(owner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(signedIn).contains("class=\"authenticated-shell authenticated-shell--about\"",
                "<main id=\"main\" class=\"workspace workspace--about\" tabindex=\"0\"",
                "class=\"application-sidebar\"",
                "class=\"application-bottom-nav\"", "href=\"/rooms\"", "action=\"/logout\"",
                "aria-current=\"page\"", "name=\"_csrf\"", owner.getUsername());
        assertThat(signedIn).containsPattern("(?s)<body class=\"authenticated-shell authenticated-shell--about\">"
                + "\\s*<a class=\"skip-link\"[^>]*>.*?</a>\\s*<div class=\"application-chrome\"");
        assertThat(signedIn).doesNotContain("href=\"/login\"", "class=\"site-header\"");
    }

    @Test
    void deleteAccountPasswordUsesTheSharedMaskedControlWithoutChangingConfirmation() throws Exception {
        AccountPrincipal owner = principal("Owner");
        String page = mvc.perform(get("/account/delete").with(user(owner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertPasswordControl(page, "currentPassword", "current-password");
        assertThat(page).contains("action=\"/account/delete\"", "name=\"confirmation\"",
                "Type DELETE to confirm", "src=\"/js/password-visibility.js\"");
    }

    @Test
    void waitingActiveAndClosedRoomsRenderOnlyTheirRealControls() throws Exception {
        AccountPrincipal owner = principal("Owner");
        AccountPrincipal guest = principal("Guest");
        UUID roomId = rooms.createRoom(owner.getUserId(), "en", "ta");
        String path = "/rooms/" + roomId;

        String waiting = mvc.perform(get(path).with(user(owner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var code = Pattern.compile("<code id=\"invite-code\"[^>]*>([^<]+)</code>")
                .matcher(waiting);
        assertThat(code.find()).isTrue();
        assertThat(code.group(1)).matches("[0-9A-HJKMNP-TV-Z]{4}(?:-[0-9A-HJKMNP-TV-Z]{4}){3}")
                .doesNotContain(roomId.toString());
        assertThat(waiting).contains("class=\"authenticated-shell\"", "id=\"invite-copy\"", "id=\"glossary-section\"",
                "id=\"room-details\"", "id=\"close-room-form\"", "data-room-id=\"" + roomId + "\"", "Standard",
                "class=\"waiting-card__code-row\"", "Back to conversations",
                "id=\"glossary-open\"", "id=\"glossary-add\"", "id=\"glossary-close\"",
                "<dialog id=\"glossary-section\"", "id=\"glossary-form\" class=\"glossary-form\" hidden",
                "Your partner will speak <span>Tamil</span>, and you will receive <span>English</span>.");
        assertThat(waiting).doesNotContain("class=\"room-sidebar\"");

        rooms.joinRoom(guest.getUserId(), code.group(1));
        String active = mvc.perform(get(path).with(user(guest)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(active).contains("Conversation", "id=\"record-start\"",
                "id=\"record-stop\"", "id=\"recording-status\"", "id=\"conversation-events\"",
                "id=\"turn-announcement\"", "id=\"glossary-form\"", "id=\"live-text-form\"",
                "id=\"close-room-form\"",
                "meta name=\"_csrf\"", "room-live.js", "room-glossary.js");
        assertThat(active).contains("id=\"speech-section\" class=\"recording-card\" data-phase=\"idle\"",
                "class=\"turns-card\"", "id=\"glossary-section\" class=\"glossary-dialog\"",
                "id=\"room-details\"", "data-role=\"You\"", "data-role=\"Partner\"",
                "You speak: ", "Partner receives: ", "Partner speaks: ", "You receive: ",
                "You speak: </span><span>Tamil", "Partner receives: </span><span>English",
                "Partner speaks: </span><span>English", "You receive: </span><span>Tamil",
                "Translate message", "Live translation", "Private Live", "Shared Live",
                "id=\"history-all\"", "id=\"history-translated\"", "id=\"history-quick\"",
                "id=\"history-latest\"", "id=\"text-mode-quick\"",
                "Translate and review your message before sending.",
                "Record and send the translated turn.")
                .doesNotContain("You hear: ", "Partner hears: ",
                        "Low latency", "Recent History", "Delete account");
        String ownerActive = mvc.perform(get(path).with(user(owner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(ownerActive).contains("You speak: </span><span>English",
                "Partner receives: </span><span>Tamil", "Partner speaks: </span><span>Tamil",
                "You receive: </span><span>English");
        var ids = Pattern.compile(" id=\"([^\"]+)\"").matcher(active).results()
                .map(match -> match.group(1)).toList();
        assertThat(ids).doesNotHaveDuplicates();
        assertThat(active).containsPattern("(?s)<details id=\"room-details\".*?<form id=\"close-room-form\"");
        assertThat(active.indexOf("id=\"conversation-events\"")).isLessThan(active.indexOf("id=\"record-start\""));
        String roomScript = mvc.perform(get("/js/room-live.js").with(user(guest)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(roomScript).contains("speechSection.dataset.phase = \"listening\"",
                "speechSection.dataset.phase = \"uploading\"",
                "recordingStatus.textContent = \"Requesting microphone access\"",
                "speechSection.dataset.phase = \"error\"",
                "recordStartLabel.textContent = \"Listening\"",
                "recordStartLabel.textContent = \"Start speaking\"",
                "active.classList.add(\"room-state-entered\")",
                "parts.row.dataset.phase = \"translating\"",
                "parts.row.dataset.phase = \"voice\"",
                "parts.row.dataset.phase = \"ready\"")
                .doesNotContain("innerHTML");

        rooms.closeRoom(guest.getUserId(), roomId);
        String closed = mvc.perform(get(path).with(user(owner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(closed).contains("This room is closed");
        assertThat(closed).contains("Back to rooms", "Confirm removal");
        assertThat(closed).doesNotContain("id=\"glossary-section\"", "id=\"close-room-form\"", "room-live.js",
                "room-glossary.js", "id=\"glossary-open\"", "id=\"record-stop\"");
    }
}

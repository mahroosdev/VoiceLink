package com.mahroosdev.voicelink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
                "focus-visible", "@media (max-width: 639px)", "min-width: 960px",
                "flex-wrap: wrap", ".about-grid { grid-template-columns: 1fr; }");
        mvc.perform(get("/js/room-live.js")).andExpect(status().isFound());

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
    }

    @Test
    void dashboardAndRoomsUseRealActionsAndAccurateUnavailableProfileCopy() throws Exception {
        AccountPrincipal owner = principal("Owner");
        String app = mvc.perform(get("/app").with(user(owner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(app).contains("Start an English ↔ Tamil conversation.",
                "/rooms#create", "/rooms#join", "href=\"/about\"", "action=\"/logout\"");
        assertThat(app).doesNotContain("planned for later gates");

        String index = mvc.perform(get("/rooms").with(user(owner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(index).contains("action=\"/rooms\"", "action=\"/rooms/join\"",
                "name=\"speaking\"", "name=\"listening\"", "name=\"profile\"",
                "Paid Gemini translation and voice; Groq speech recognition is shared.",
                "Premium is not available on this server.", "name=\"_csrf\"",
                "href=\"/about#privacy\"", "Recent captions and generated audio are temporary.",
                "href=\"/about\"");
        assertThat(index).doesNotContain("<details class=\"disclosure data-disclosure\"",
                "Standard uses Gemini Free Tier for translation and voice.",
                "VOICELINK_PREMIUM_GEMINI_API_KEY");
        assertThat(index.indexOf("Premium is not available on this server.") - index.indexOf("value=\"PREMIUM\""))
                .isBetween(0, 1000);
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
                "href=\"/about\"", "id=\"privacy\"", "class=\"skip-link\"");
        assertThat(about).doesNotContain("action=\"/logout\"", "Signed-in account");

        AccountPrincipal owner = principal("Owner");
        String signedIn = mvc.perform(get("/about").with(user(owner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(signedIn).contains("href=\"/rooms\"", "action=\"/logout\"",
                "name=\"_csrf\"", owner.getUsername());
        assertThat(signedIn).doesNotContain("href=\"/login\"");
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
        assertThat(waiting).contains("id=\"invite-copy\"", "id=\"glossary-section\"",
                "data-room-id=\"" + roomId + "\"", "Standard");

        rooms.joinRoom(guest.getUserId(), code.group(1));
        String active = mvc.perform(get(path).with(user(guest)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(active).contains("Recent live turns", "id=\"record-start\"",
                "id=\"record-stop\"", "id=\"recording-status\"", "id=\"speech-events\"",
                "id=\"turn-announcement\"", "id=\"glossary-form\"", "id=\"live-text-form\"",
                "meta name=\"_csrf\"", "room-live.js", "room-glossary.js");

        rooms.closeRoom(guest.getUserId(), roomId);
        String closed = mvc.perform(get(path).with(user(owner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(closed).contains("This room is closed");
        assertThat(closed).doesNotContain("id=\"glossary-section\"", "room-live.js",
                "room-glossary.js");
    }
}

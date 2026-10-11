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
    void publicStylesheetAndRealAuthenticationFormsRenderWithoutOpeningRoomScripts() throws Exception {
        String css = mvc.perform(get("/css/voicelink.css"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(css).contains("--vl-primary: #006F73", "prefers-reduced-motion",
                "focus-visible", "@media (max-width: 639px)", "min-width: 960px");
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
        assertThat(app).contains("Speak in English or Tamil and hear the conversation translated.",
                "/rooms#create", "/rooms#join", "action=\"/logout\"");
        assertThat(app).doesNotContain("planned for later gates");

        String index = mvc.perform(get("/rooms").with(user(owner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(index).contains("action=\"/rooms\"", "action=\"/rooms/join\"",
                "name=\"speaking\"", "name=\"listening\"", "name=\"profile\"",
                "Paid Gemini translation and voice; Groq speech recognition is shared.",
                "Premium is not available on this server.", "name=\"_csrf\"");
        assertThat(index).doesNotContain("VOICELINK_PREMIUM_GEMINI_API_KEY");
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

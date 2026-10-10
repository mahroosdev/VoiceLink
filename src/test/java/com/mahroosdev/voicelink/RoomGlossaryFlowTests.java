package com.mahroosdev.voicelink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import com.mahroosdev.voicelink.auth.AccountPrincipal;
import com.mahroosdev.voicelink.glossary.RoomGlossaryRepository;
import com.mahroosdev.voicelink.glossary.RoomGlossaryService;
import com.mahroosdev.voicelink.room.ConversationRoomRepository;
import com.mahroosdev.voicelink.room.RoomService;
import com.mahroosdev.voicelink.user.UserAccount;
import com.mahroosdev.voicelink.user.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoomGlossaryFlowTests {
    @Autowired MockMvc mvc;
    @Autowired RoomService rooms;
    @Autowired RoomGlossaryService glossary;
    @Autowired RoomGlossaryRepository entries;
    @Autowired ConversationRoomRepository roomRepository;
    @Autowired UserAccountRepository accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    private UserAccount account() {
        return accounts.save(new UserAccount("Glossary Tester", UUID.randomUUID() + "@example.test", "test-only-hash"));
    }

    private UserDetails principal(UserAccount account) {
        return new AccountPrincipal(account.getId(), account.getEmail(), account.getPasswordHash(), true);
    }

    private RoomGlossaryService.Change term(String source, String preferred) {
        return new RoomGlossaryService.Change("en", "ta", source, preferred);
    }

    private String body(String source, String preferred) {
        return json.writeValueAsString(Map.of("sourceLanguage", "en", "targetLanguage", "ta",
                "sourceTerm", source, "preferredTerm", preferred));
    }

    @Test void memberOnlyCsrfAndWrongRoomEntryAreEnforced() throws Exception {
        var creator = account(); var other = account(); var outsider = account();
        UUID room = rooms.createRoom(creator.getId(), "en", "ta");
        String path = "/api/rooms/" + room + "/glossary";
        mvc.perform(get(path)).andExpect(status().is3xxRedirection());
        mvc.perform(post(path).with(user(principal(creator))).contentType(MediaType.APPLICATION_JSON)
                .content(body("API", "API"))).andExpect(status().isForbidden());
        mvc.perform(get(path).with(user(principal(outsider)))).andExpect(status().isNotFound());
        mvc.perform(post(path).with(user(principal(outsider))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body("API", "API")))
                .andExpect(status().isNotFound());
        mvc.perform(post(path).with(user(principal(creator))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body("x".repeat(5000), "x")))
                .andExpect(status().isPayloadTooLarge());
        var created = mvc.perform(post(path).with(user(principal(creator))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body("API", "API")))
                .andExpect(status().isCreated()).andReturn();
        UUID entry = UUID.fromString(json.readTree(created.getResponse().getContentAsString()).path("id").asText());
        assertThat(created.getResponse().getHeader("Cache-Control")).contains("no-store");
        mvc.perform(get(path).with(user(principal(creator)))).andExpect(status().isOk());
        mvc.perform(put(path + "/" + entry).with(user(principal(creator)))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete(path + "/" + entry).with(user(principal(creator)))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        rooms.joinRoom(other.getId(), roomRepository.findById(room).orElseThrow().getJoinCode());
        assertThat(glossary.list(other.getId(), room)).hasSize(1);
        UUID anotherRoom = rooms.createRoom(other.getId(), "ta", "en");
        mvc.perform(put("/api/rooms/{roomId}/glossary/{entryId}", anotherRoom, entry)
                .with(user(principal(other))).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("sourceLanguage", "en", "targetLanguage", "ta",
                        "sourceTerm", "API", "preferredTerm", "API", "expectedVersion", 0))))
                .andExpect(status().isNotFound());
        mvc.perform(delete(path + "/" + entry).with(user(principal(outsider))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"))
                .andExpect(status().isNotFound());
    }

    @Test void bothMembersEditVersionAndClosureDeletesTerms() {
        var creator = account(); var member = account();
        UUID room = rooms.createRoom(creator.getId(), "en", "ta");
        var initial = glossary.create(creator.getId(), room, term(" REST   API ", " "));
        assertThat(initial.sourceTerm()).isEqualTo("REST API");
        assertThat(initial.preferredTerm()).isEqualTo("REST API");
        rooms.joinRoom(member.getId(), roomRepository.findById(room).orElseThrow().getJoinCode());
        var updated = glossary.update(member.getId(), room, initial.id(), term("REST API", "ரெஸ்ட் API"),
                initial.rowVersion());
        assertThat(updated.rowVersion()).isGreaterThan(initial.rowVersion());
        assertThatThrownBy(() -> glossary.update(creator.getId(), room, initial.id(),
                term("REST API", "old"), initial.rowVersion())).isInstanceOfSatisfying(ResponseStatusException.class,
                ex -> assertThat(ex.getStatusCode().value()).isEqualTo(409));
        assertThat(glossary.list(creator.getId(), room).getFirst().preferredTerm()).isEqualTo("ரெஸ்ட் API");
        rooms.closeRoom(member.getId(), room);
        assertThat(entries.countByRoom_Id(room)).isZero();
        assertThatThrownBy(() -> glossary.list(creator.getId(), room))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(409));
        assertThatThrownBy(() -> glossary.create(creator.getId(), room, term("new", "new")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(409));
    }

    @Test void restUpdateDeleteRejectStaleVersionsAndRenderSafeRoomPage() throws Exception {
        var creator = account(); var member = account();
        UUID room = rooms.createRoom(creator.getId(), "en", "ta");
        rooms.joinRoom(member.getId(), roomRepository.findById(room).orElseThrow().getJoinCode());
        var entry = glossary.create(creator.getId(), room, term("<img src=x onerror=alert(1)>", "literal"));
        String path = "/api/rooms/" + room + "/glossary/" + entry.id();
        var changed = mvc.perform(put(path).with(user(principal(member))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("sourceLanguage", "en", "targetLanguage", "ta",
                        "sourceTerm", "API", "preferredTerm", "ஏபிஐ", "expectedVersion", entry.rowVersion()))))
                .andExpect(status().isOk()).andReturn();
        long version = json.readTree(changed.getResponse().getContentAsString()).path("rowVersion").longValue();
        assertThat(version).isGreaterThan(entry.rowVersion());
        mvc.perform(put(path).with(user(principal(creator))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("sourceLanguage", "en", "targetLanguage", "ta",
                        "sourceTerm", "API", "preferredTerm", "old", "expectedVersion", entry.rowVersion()))))
                .andExpect(status().isConflict());
        mvc.perform(delete(path).with(user(principal(member))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("expectedVersion", version))))
                .andExpect(status().isNoContent());
        assertThat(entries.findByRoom_IdAndId(room, entry.id())).isEmpty();
        mvc.perform(get("/rooms/{id}", room).with(user(principal(member))))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("Room terminology")));
    }

    @Test void normalizationValidationDirectionAndCapacityAreBounded() {
        var creator = account();
        UUID room = rooms.createRoom(creator.getId(), "en", "ta");
        var nfc = glossary.create(creator.getId(), room, term("Cafe\u0301", "Preferred"));
        assertThat(nfc.sourceTerm()).isEqualTo("Café");
        assertThatThrownBy(() -> glossary.create(creator.getId(), room, term("CAFÉ", "duplicate")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(409));
        assertThat(glossary.create(creator.getId(), room,
                new RoomGlossaryService.Change("ta", "en", "Café", "Cafe")).sourceLanguage())
                .isEqualTo("ta");
        for (String invalid : new String[]{"", " ", "a\nb", "x".repeat(49)}) {
            assertThatThrownBy(() -> glossary.create(creator.getId(), room, term(invalid, "ok")))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            ex -> assertThat(ex.getStatusCode().value()).isEqualTo(400));
        }
        assertThatThrownBy(() -> glossary.create(creator.getId(), room, term("valid", "x".repeat(65))))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(400));
        assertThatThrownBy(() -> glossary.create(creator.getId(), room,
                new RoomGlossaryService.Change("en", "fr", "term", "term")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(400));
        glossary.create(creator.getId(), room, term("s".repeat(48), "p".repeat(64)));
        glossary.create(creator.getId(), room, term("x", "x"));
        for (int i = 0; i < 8; i++) glossary.create(creator.getId(), room, term("term" + i, "preferred" + i));
        assertThat(entries.countByRoom_Id(room)).isEqualTo(12);
        assertThatThrownBy(() -> glossary.create(creator.getId(), room, term("thirteenth", "no")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(409));
    }

    @Test void expiredWaitingRoomCannotMutateButCanRead() {
        var creator = account();
        UUID room = rooms.createRoom(creator.getId(), "en", "ta");
        glossary.create(creator.getId(), room, term("one", "one"));
        jdbc.update("UPDATE conversation_rooms SET created_at = ?, invite_expires_at = ? WHERE id = ?",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(2 * 86400)),
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)), room);
        assertThat(glossary.list(creator.getId(), room)).hasSize(1);
        assertThatThrownBy(() -> glossary.create(creator.getId(), room, term("two", "two")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(409));
    }

    @Test void supplementaryUnicodeUsesCodePointLimitsRatherThanUtf16Units() {
        var creator = account();
        UUID room = rooms.createRoom(creator.getId(), "en", "ta");
        var accepted = glossary.create(creator.getId(), room,
                term("😀".repeat(48), "😀".repeat(64)));
        assertThat(accepted.sourceTerm()).hasSize(96);
        assertThat(accepted.preferredTerm()).hasSize(128);
        assertThatThrownBy(() -> glossary.create(creator.getId(), room,
                term("😀".repeat(49), "valid")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(400));
        assertThatThrownBy(() -> glossary.create(creator.getId(), room,
                term("valid", "😀".repeat(65))))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(400));
    }
}

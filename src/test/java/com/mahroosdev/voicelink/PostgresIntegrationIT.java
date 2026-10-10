package com.mahroosdev.voicelink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.mahroosdev.voicelink.auth.RegistrationForm;
import com.mahroosdev.voicelink.auth.RegistrationService;
import com.mahroosdev.voicelink.glossary.RoomGlossaryRepository;
import com.mahroosdev.voicelink.glossary.RoomGlossaryService;
import com.mahroosdev.voicelink.room.ConversationRoomRepository;
import com.mahroosdev.voicelink.room.RoomInviteUnavailableException;
import com.mahroosdev.voicelink.room.RoomParticipantRepository;
import com.mahroosdev.voicelink.room.RoomService;
import com.mahroosdev.voicelink.room.RoomStatus;
import com.mahroosdev.voicelink.user.UserAccount;
import com.mahroosdev.voicelink.user.UserAccountRepository;
import com.mahroosdev.voicelink.user.UserPreferencesRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers
class PostgresIntegrationIT {
    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-bookworm")
            .withDatabaseName("voicelink_it");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired RegistrationService registration;
    @Autowired UserAccountRepository accounts;
    @Autowired UserPreferencesRepository preferences;
    @Autowired JdbcTemplate jdbc;
    @Autowired RoomService rooms;
    @Autowired ConversationRoomRepository roomRepository;
    @Autowired RoomParticipantRepository participants;
    @Autowired RoomGlossaryService glossary;
    @Autowired RoomGlossaryRepository glossaryEntries;

    @Test
    void flywayAndHibernatePersistUuidAccountAndPreferences() {
        Integer version = jdbc.queryForObject(
                "SELECT max(installed_rank) FROM flyway_schema_history WHERE success = true", Integer.class);
        assertThat(version).isEqualTo(3);
        RegistrationForm form = new RegistrationForm();
        form.setDisplayName("Postgres Test");
        form.setEmail("  " + UUID.randomUUID() + "@EXAMPLE.TEST  ");
        form.setPassword("long integration passphrase");
        UUID id = registration.register(form);
        assertThat(accounts.findById(id)).isPresent();
        assertThat(preferences.findById(id)).isPresent();
        assertThat(accounts.findById(id).orElseThrow().getEmail()).endsWith("@example.test");

        String email = accounts.findById(id).orElseThrow().getEmail();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO users "
                + "(id, display_name, email, password_hash, enabled, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, true, now(), now())",
                UUID.randomUUID(), "Duplicate", email, "irrelevant"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO user_preferences "
                + "(user_id, created_at, updated_at) VALUES (?, now(), now())", UUID.randomUUID()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        jdbc.update("DELETE FROM users WHERE id = ?", id);
        assertThat(preferences.findById(id)).isEmpty();
    }

    private UserAccount account(String name) {
        return accounts.save(new UserAccount(name, UUID.randomUUID() + "@example.test", "test-only-hash"));
    }

    @Test
    void v3GlossaryConstraintsVersionsAndRoomCloseCleanup() {
        UserAccount creator = account("Glossary creator");
        UUID roomId = rooms.createRoom(creator.getId(), "en", "ta");
        var first = glossary.create(creator.getId(), roomId,
                new RoomGlossaryService.Change("en", "ta", "REST API", "REST API"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM room_glossary_entries WHERE room_id = ?",
                Long.class, roomId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT row_version FROM room_glossary_entries WHERE id = ?",
                Long.class, first.id())).isZero();
        var updated = glossary.update(creator.getId(), roomId, first.id(),
                new RoomGlossaryService.Change("en", "ta", "REST API", "ரெஸ்ட் API"), first.rowVersion());
        assertThat(updated.rowVersion()).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO room_glossary_entries "
                + "(id, room_id, source_language_tag, target_language_tag, source_term, "
                + "normalized_source_term, preferred_term, created_by_user_id, created_at, updated_at, row_version) "
                + "VALUES (?, ?, 'en', 'ta', 'REST API', 'rest api', 'duplicate', ?, now(), now(), 0)",
                UUID.randomUUID(), roomId, creator.getId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO room_glossary_entries "
                + "(id, room_id, source_language_tag, target_language_tag, source_term, "
                + "normalized_source_term, preferred_term, created_by_user_id, created_at, updated_at, row_version) "
                + "VALUES (?, ?, 'en', 'ta', 'other', 'other', 'other', ?, now(), now(), 0)",
                UUID.randomUUID(), roomId, UUID.randomUUID()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(glossaryEntries.findByRoom_IdOrderByCreatedAtAsc(roomId)).hasSize(1);
        rooms.closeRoom(creator.getId(), roomId);
        assertThat(glossaryEntries.countByRoom_Id(roomId)).isZero();
        UUID secondRoom = rooms.createRoom(creator.getId(), "en", "ta");
        glossary.create(creator.getId(), secondRoom,
                new RoomGlossaryService.Change("en", "ta", "API", "API"));
        jdbc.update("DELETE FROM room_participants WHERE room_id = ?", secondRoom);
        jdbc.update("DELETE FROM conversation_rooms WHERE id = ?", secondRoom);
        assertThat(glossaryEntries.countByRoom_Id(secondRoom)).isZero();
    }

    private void insertParticipant(UUID roomId, UUID userId, int slot, String speaking, String listening) {
        jdbc.update("INSERT INTO room_participants "
                + "(id, room_id, user_id, participant_slot, speaking_language_tag, listening_language_tag, joined_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, now())",
                UUID.randomUUID(), roomId, userId, slot, speaking, listening);
    }

    @Test
    void roomMigrationAndPostgresConstraintsEnforceTwoSlotsAndHistory() {
        UserAccount creator = account("Creator");
        UserAccount joiner = account("Joiner");
        UserAccount third = account("Third");
        UUID id = rooms.createRoom(creator.getId(), "en", "ta");
        String code = roomRepository.findById(id).orElseThrow().getJoinCode();
        assertThat(participants.findByRoom_IdOrderByParticipantSlotAsc(id)).hasSize(1);

        assertThatThrownBy(() -> jdbc.update("INSERT INTO conversation_rooms "
                + "(id, join_code, status, created_by_user_id, created_at, updated_at, invite_expires_at) "
                + "VALUES (?, ?, 'WAITING', ?, now(), now(), now() + interval '1 day')",
                UUID.randomUUID(), code, creator.getId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO conversation_rooms "
                + "(id, join_code, status, created_by_user_id, created_at, updated_at, invite_expires_at) "
                + "VALUES (?, ?, 'WAITING', ?, now(), now(), now() + interval '1 day')",
                UUID.randomUUID(), UUID.randomUUID().toString().substring(0, 16), UUID.randomUUID()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertParticipant(id, UUID.randomUUID(), 2, "ta", "en"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertParticipant(id, creator.getId(), 2, "ta", "en"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertParticipant(id, joiner.getId(), 3, "ta", "en"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertParticipant(id, joiner.getId(), 2, "en", "en"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        rooms.joinRoom(joiner.getId(), code);
        assertThatThrownBy(() -> insertParticipant(id, third.getId(), 2, "ta", "en"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE conversation_rooms SET status = 'CLOSED' WHERE id = ?", id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        rooms.closeRoom(joiner.getId(), id);
        assertThat(roomRepository.findById(id).orElseThrow().getStatus()).isEqualTo(RoomStatus.CLOSED);
        assertThat(participants.findByRoom_IdOrderByParticipantSlotAsc(id)).hasSize(2);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM users WHERE id = ?", creator.getId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void concurrentFinalSlotJoinHasOneWinnerAndOneSafeLoser() throws Exception {
        UserAccount creator = account("Creator");
        UserAccount first = account("First");
        UserAccount second = account("Second");
        UUID id = rooms.createRoom(creator.getId(), "en", "ta");
        String code = roomRepository.findById(id).orElseThrow().getJoinCode();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Callable<String>> attempts = List.of(
                    () -> tryJoin(start, first.getId(), code),
                    () -> tryJoin(start, second.getId(), code));
            List<Future<String>> results = new ArrayList<>();
            for (var attempt : attempts) results.add(pool.submit(attempt));
            start.countDown();
            List<String> outcomes = List.of(results.get(0).get(60, TimeUnit.SECONDS),
                    results.get(1).get(60, TimeUnit.SECONDS));
            assertThat(outcomes).containsExactlyInAnyOrder("joined", "unavailable");
        }
        assertThat(participants.findByRoom_IdOrderByParticipantSlotAsc(id)).hasSize(2);
        assertThat(roomRepository.findById(id).orElseThrow().getStatus()).isEqualTo(RoomStatus.ACTIVE);
    }

    private String tryJoin(CountDownLatch start, UUID userId, String code) throws InterruptedException {
        start.await();
        try {
            rooms.joinRoom(userId, code);
            return "joined";
        } catch (RoomInviteUnavailableException ex) {
            return "unavailable";
        }
    }
}

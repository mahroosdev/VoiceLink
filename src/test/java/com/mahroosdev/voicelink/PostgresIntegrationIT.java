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
import com.mahroosdev.voicelink.ai.AiServiceProfile;
import com.mahroosdev.voicelink.auth.RegistrationService;
import com.mahroosdev.voicelink.auth.AccountDeletionService;
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

@SpringBootTest(properties = "voicelink.password-reset.enabled=false")
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
    @Autowired AccountDeletionService accountDeletion;
    @Autowired UserAccountRepository accounts;
    @Autowired UserPreferencesRepository preferences;
    @Autowired JdbcTemplate jdbc;
    @Autowired RoomService rooms;
    @Autowired ConversationRoomRepository roomRepository;
    @Autowired RoomParticipantRepository participants;
    @Autowired RoomGlossaryService glossary;
    @Autowired RoomGlossaryRepository glossaryEntries;

    @Test
    void accountDeletionRespectsRealForeignKeysAndRemovesResetToken() {
        RegistrationForm deletionForm = new RegistrationForm();
        deletionForm.setDisplayName("Account deletion owner");
        deletionForm.setEmail(UUID.randomUUID() + "@example.test");
        deletionForm.setPassword("long integration passphrase");
        UserAccount departing = accounts.findById(registration.register(deletionForm)).orElseThrow();
        UserAccount counterpart = account("Account deletion counterpart");
        UUID roomId = rooms.createRoom(departing.getId(), "en", "ta");
        rooms.joinRoom(counterpart.getId(), roomRepository.findById(roomId).orElseThrow().getJoinCode());
        jdbc.update("INSERT INTO password_reset_tokens (user_id, token_hash, created_at, expires_at) "
                + "VALUES (?, ?, now(), now() + interval '30 minutes')", departing.getId(), "c".repeat(64));
        assertThat(accountDeletion.delete(departing.getId(), "long integration passphrase", "DELETE")).isTrue();
        assertThat(accounts.findById(departing.getId())).isEmpty();
        assertThat(accounts.findById(counterpart.getId())).isPresent();
        assertThat(roomRepository.findById(roomId)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM password_reset_tokens WHERE user_id = ?",
                Long.class, departing.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM room_participants WHERE user_id = ?",
                Long.class, departing.getId())).isZero();
    }

    @Test
    void flywayAndHibernatePersistUuidAccountAndPreferences() {
        Integer version = jdbc.queryForObject(
                "SELECT max(installed_rank) FROM flyway_schema_history WHERE success = true", Integer.class);
        assertThat(version).isEqualTo(8);
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

    @Test
    void participantConversationNamesPersistWhileLegacyRowsRemainCompatible() {
        UserAccount creator = account("Legacy fallback");
        UserAccount joiner = account("Joiner account");
        UUID named = rooms.createRoomForSpeaker(creator.getId(), "உரிமையாளர்", "ta",
                AiServiceProfile.STANDARD, true);
        String code = roomRepository.findById(named).orElseThrow().getJoinCode();
        assertThatThrownBy(() -> rooms.joinRoomWithSetup(joiner.getId(), code, "<Guest>", "ta"))
                .isInstanceOf(com.mahroosdev.voicelink.room.JoinLanguageMismatchException.class);
        rooms.joinRoomWithSetup(joiner.getId(), code, "<Guest>", "en");
        assertThat(jdbc.queryForList("SELECT conversation_display_name FROM room_participants "
                + "WHERE room_id = ? ORDER BY participant_slot", String.class, named))
                .containsExactly("உரிமையாளர்", "<Guest>");
        assertThat(rooms.getRoomForMember(joiner.getId(), named).currentParticipant().displayName())
                .isEqualTo("<Guest>");

        UUID legacy = rooms.createRoom(creator.getId(), "en", "ta");
        assertThat(jdbc.queryForObject("SELECT conversation_display_name IS NULL FROM room_participants "
                + "WHERE room_id = ?", Boolean.class, legacy)).isTrue();
        assertThat(rooms.getRoomForMember(creator.getId(), legacy).currentParticipant().displayName())
                .isEqualTo("Legacy fallback");
        assertThatThrownBy(() -> jdbc.update("UPDATE room_participants "
                + "SET conversation_display_name = '   ' WHERE room_id = ?", legacy))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE room_participants "
                + "SET conversation_display_name = ? WHERE room_id = ?", "x".repeat(49), legacy))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void closedRoomRemovalIsPerMemberAndPersistsInPostgres() {
        UserAccount creator = account("Room list creator");
        UserAccount guest = account("Room list guest");
        UUID id = rooms.createRoom(creator.getId(), "en", "ta");
        rooms.joinRoom(guest.getId(), roomRepository.findById(id).orElseThrow().getJoinCode());
        rooms.closeRoom(creator.getId(), id);
        rooms.removeClosedRoom(creator.getId(), id);
        assertThat(jdbc.queryForObject("SELECT removed_at IS NOT NULL FROM room_participants "
                + "WHERE room_id = ? AND user_id = ?", Boolean.class, id, creator.getId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT removed_at IS NULL FROM room_participants "
                + "WHERE room_id = ? AND user_id = ?", Boolean.class, id, guest.getId())).isTrue();
        assertThat(rooms.listRoomsForMember(creator.getId())).noneMatch(view -> view.id().equals(id));
        assertThat(rooms.listRoomsForMember(guest.getId())).anyMatch(view -> view.id().equals(id));
        assertThat(roomRepository.findById(id)).isPresent();
    }

    @Test
    void v5ResetTokenConstraintsAndAccountDeletionCascade() {
        UserAccount account = account("Reset owner");
        String hash = "a".repeat(64);
        jdbc.update("INSERT INTO password_reset_tokens (user_id, token_hash, created_at, expires_at) "
                        + "VALUES (?, ?, now(), now() + interval '30 minutes')", account.getId(), hash);
        assertThat(jdbc.queryForObject("SELECT token_hash FROM password_reset_tokens WHERE user_id = ?",
                String.class, account.getId())).isEqualTo(hash);
        UserAccount other = account("Other reset owner");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO password_reset_tokens "
                + "(user_id, token_hash, created_at, expires_at) VALUES (?, ?, now(), now() + interval '1 minute')",
                other.getId(), hash)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO password_reset_tokens "
                + "(user_id, token_hash, created_at, expires_at) VALUES (?, ?, now(), now() + interval '1 minute')",
                other.getId(), "short")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO password_reset_tokens "
                + "(user_id, token_hash, created_at, expires_at) VALUES (?, ?, now(), now())",
                other.getId(), "b".repeat(64)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("DELETE FROM users WHERE id = ?", account.getId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM password_reset_tokens WHERE user_id = ?",
                Long.class, account.getId())).isZero();
    }

    private UserAccount account(String name) {
        return accounts.save(new UserAccount(name, UUID.randomUUID() + "@example.test", "test-only-hash"));
    }

    @Test
    void v4DefaultsExistingShapeToStandardAndConstrainsProfiles() {
        UserAccount creator = account("Profile creator");
        UUID standard = rooms.createRoom(creator.getId(), "en", "ta");
        assertThat(jdbc.queryForObject("SELECT service_profile FROM conversation_rooms WHERE id = ?",
                String.class, standard)).isEqualTo("STANDARD");
        UUID legacyShape = UUID.randomUUID();
        jdbc.update("INSERT INTO conversation_rooms "
                + "(id, join_code, status, created_by_user_id, created_at, updated_at, invite_expires_at) "
                + "VALUES (?, ?, 'WAITING', ?, now(), now(), now() + interval '1 day')",
                legacyShape, "ABCDEFGHJKMNPQRS", creator.getId());
        assertThat(jdbc.queryForObject("SELECT service_profile FROM conversation_rooms WHERE id = ?",
                String.class, legacyShape)).isEqualTo("STANDARD");
        assertThatThrownBy(() -> jdbc.update("UPDATE conversation_rooms SET service_profile = 'INVALID' WHERE id = ?",
                standard)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("UPDATE conversation_rooms SET service_profile = 'PREMIUM' WHERE id = ?", legacyShape);
        assertThat(jdbc.queryForObject("SELECT service_profile FROM conversation_rooms WHERE id = ?",
                String.class, legacyShape)).isEqualTo("PREMIUM");
        assertThat(roomRepository.findById(legacyShape).orElseThrow().getServiceProfile())
                .isEqualTo(AiServiceProfile.PREMIUM);
        assertThat(roomRepository.findById(standard).orElseThrow().getServiceProfile())
                .isEqualTo(AiServiceProfile.STANDARD);
    }

    @Test
    void v7DefaultsExistingRoomsToCostSaverOnAndConstrainsStandardRows() {
        UserAccount creator = account("Cost saver migration creator");
        UUID standard = rooms.createRoom(creator.getId(), "en", "ta");
        assertThat(jdbc.queryForObject(
                "SELECT premium_cost_saver_enabled FROM conversation_rooms WHERE id = ?",
                Boolean.class, standard)).isTrue();

        UUID legacyShape = UUID.randomUUID();
        jdbc.update("INSERT INTO conversation_rooms "
                + "(id, join_code, status, created_by_user_id, created_at, updated_at, invite_expires_at) "
                + "VALUES (?, ?, 'WAITING', ?, now(), now(), now() + interval '1 day')",
                legacyShape, "TVWXYZ23456789AB", creator.getId());
        assertThat(jdbc.queryForObject(
                "SELECT premium_cost_saver_enabled FROM conversation_rooms WHERE id = ?",
                Boolean.class, legacyShape)).isTrue();

        jdbc.update("UPDATE conversation_rooms SET service_profile = 'PREMIUM', "
                + "premium_cost_saver_enabled = FALSE WHERE id = ?", legacyShape);
        assertThat(roomRepository.findById(legacyShape).orElseThrow().isPremiumCostSaverEnabled()).isFalse();
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE conversation_rooms SET service_profile = 'STANDARD' WHERE id = ?", legacyShape))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
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

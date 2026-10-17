package com.mahroosdev.voicelink.auth;

import java.util.TreeSet;
import java.util.UUID;

import com.mahroosdev.voicelink.glossary.RoomGlossaryRepository;
import com.mahroosdev.voicelink.room.ConversationRoomRepository;
import com.mahroosdev.voicelink.room.RoomParticipantRepository;
import com.mahroosdev.voicelink.room.RoomService;
import com.mahroosdev.voicelink.room.RoomStatus;
import com.mahroosdev.voicelink.user.UserAccountRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountDeletionService {
    private final UserAccountRepository accounts;
    private final ConversationRoomRepository rooms;
    private final RoomParticipantRepository participants;
    private final RoomGlossaryRepository glossary;
    private final RoomService roomService;
    private final PasswordEncoder encoder;
    private final ApplicationEventPublisher events;

    public AccountDeletionService(UserAccountRepository accounts, ConversationRoomRepository rooms,
            RoomParticipantRepository participants, RoomGlossaryRepository glossary,
            RoomService roomService, PasswordEncoder encoder, ApplicationEventPublisher events) {
        this.accounts = accounts;
        this.rooms = rooms;
        this.participants = participants;
        this.glossary = glossary;
        this.roomService = roomService;
        this.encoder = encoder;
        this.events = events;
    }

    @Transactional
    public boolean delete(UUID userId, String currentPassword, String confirmation) {
        if (!"DELETE".equals(confirmation) || currentPassword == null || currentPassword.isBlank()) return false;
        var account = accounts.lockById(userId).orElse(null);
        if (account == null || !account.isEnabled()
                || !encoder.matches(currentPassword, account.getPasswordHash())) return false;

        // Account -> rooms as creator, room memberships, glossary authors, reset token and preferences.
        // Reset tokens/preferences cascade. Rooms and memberships require explicit cleanup.
        var affected = new TreeSet<UUID>();
        rooms.findByCreatedBy_Id(userId).forEach(room -> affected.add(room.getId()));
        participants.findByUser_Id(userId).forEach(member -> affected.add(member.getRoom().getId()));
        for (UUID roomId : affected) {
            var room = rooms.findLockedById(roomId).orElseThrow();
            if (room.getStatus() != RoomStatus.CLOSED) roomService.closeRoom(userId, roomId);
            // Closed-room glossary entries normally have already been deleted. This also
            // clears any legacy entry authored by the departing account.
            glossary.deleteByRoom_Id(roomId);
            if (room.getCreatedBy().getId().equals(userId)) {
                // The creator FK is non-null and has no cascade. A created room cannot
                // survive deletion of its creator under the current schema.
                participants.deleteAll(participants.findByRoom_IdOrderByParticipantSlotAsc(roomId));
                participants.flush();
                rooms.delete(room);
                rooms.flush();
            } else {
                participants.findByRoom_IdOrderByParticipantSlotAsc(roomId).stream()
                        .filter(member -> member.getUser().getId().equals(userId))
                        .forEach(participants::delete);
                participants.flush();
            }
        }
        accounts.delete(account);
        accounts.flush();
        events.publishEvent(new AccountDeletedEvent(userId));
        return true;
    }
}

package com.mahroosdev.voicelink.room;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface ConversationRoomRepository extends JpaRepository<ConversationRoom, UUID> {
    List<ConversationRoom> findByCreatedBy_Id(UUID userId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ConversationRoom> findByJoinCode(String joinCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ConversationRoom> findLockedById(UUID id);
}

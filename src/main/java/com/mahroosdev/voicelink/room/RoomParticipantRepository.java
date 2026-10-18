package com.mahroosdev.voicelink.room;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomParticipantRepository extends JpaRepository<RoomParticipant, UUID> {
    List<RoomParticipant> findByUser_Id(UUID userId);
    boolean existsByRoom_IdAndUser_Id(UUID roomId, UUID userId);
    boolean existsByRoom_IdAndUser_IdAndRemovedAtIsNull(UUID roomId, UUID userId);
    Optional<RoomParticipant> findByRoom_IdAndUser_IdAndRemovedAtIsNull(UUID roomId, UUID userId);
    boolean existsByRoom_IdAndParticipantSlot(UUID roomId, short slot);

    @EntityGraph(attributePaths = "room")
    List<RoomParticipant> findByUser_IdOrderByJoinedAtDesc(UUID userId);

    @EntityGraph(attributePaths = "room")
    List<RoomParticipant> findByUser_IdAndRemovedAtIsNullOrderByJoinedAtDesc(UUID userId);

    @EntityGraph(attributePaths = "user")
    List<RoomParticipant> findByRoom_IdOrderByParticipantSlotAsc(UUID roomId);
}

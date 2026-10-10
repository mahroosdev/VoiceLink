package com.mahroosdev.voicelink.glossary;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomGlossaryRepository extends JpaRepository<RoomGlossaryEntry, UUID> {
    List<RoomGlossaryEntry> findByRoom_IdOrderByCreatedAtAsc(UUID roomId);
    Optional<RoomGlossaryEntry> findByRoom_IdAndId(UUID roomId, UUID id);
    Optional<RoomGlossaryEntry> findByRoom_IdAndSourceLanguageAndTargetLanguageAndNormalizedSourceTerm(
            UUID roomId, String sourceLanguage, String targetLanguage, String normalizedSourceTerm);
    long countByRoom_Id(UUID roomId);
    long deleteByRoom_Id(UUID roomId);
}

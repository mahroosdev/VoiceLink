package com.mahroosdev.voicelink.glossary;

import java.time.Instant;
import java.util.UUID;

import com.mahroosdev.voicelink.room.ConversationRoom;
import com.mahroosdev.voicelink.user.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "room_glossary_entries")
public class RoomGlossaryEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private ConversationRoom room;

    @Column(name = "source_language_tag", nullable = false, length = 64)
    private String sourceLanguage;

    @Column(name = "target_language_tag", nullable = false, length = 64)
    private String targetLanguage;

    @Column(name = "source_term", nullable = false, length = 192)
    private String sourceTerm;

    @Column(name = "normalized_source_term", nullable = false, length = 192)
    private String normalizedSourceTerm;

    @Column(name = "preferred_term", nullable = false, length = 256)
    private String preferredTerm;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false)
    private UserAccount createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected RoomGlossaryEntry() {}

    public RoomGlossaryEntry(ConversationRoom room, UserAccount createdBy, String sourceLanguage,
                             String targetLanguage, String sourceTerm, String normalizedSourceTerm,
                             String preferredTerm, Instant now) {
        this.room = room;
        this.createdBy = createdBy;
        update(sourceLanguage, targetLanguage, sourceTerm, normalizedSourceTerm, preferredTerm, now);
        this.createdAt = now;
    }

    public void update(String sourceLanguage, String targetLanguage, String sourceTerm,
                       String normalizedSourceTerm, String preferredTerm, Instant now) {
        this.sourceLanguage = sourceLanguage;
        this.targetLanguage = targetLanguage;
        this.sourceTerm = sourceTerm;
        this.normalizedSourceTerm = normalizedSourceTerm;
        this.preferredTerm = preferredTerm;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public String getSourceLanguage() { return sourceLanguage; }
    public String getTargetLanguage() { return targetLanguage; }
    public String getSourceTerm() { return sourceTerm; }
    public String getNormalizedSourceTerm() { return normalizedSourceTerm; }
    public String getPreferredTerm() { return preferredTerm; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getRowVersion() { return rowVersion; }
}

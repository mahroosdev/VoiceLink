package com.mahroosdev.voicelink.user;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_preferences")
public class UserPreferences {
    @Id
    @Column(name = "user_id")
    private UUID userId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccount user;

    @Column(name = "preferred_speaking_language_tag", length = 64)
    private String preferredSpeakingLanguageTag;

    @Column(name = "preferred_listening_language_tag", length = 64)
    private String preferredListeningLanguageTag;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserPreferences() {}

    public UserPreferences(UserAccount user) {
        this.user = user;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getUserId() { return userId; }
    public UserAccount getUser() { return user; }
    public String getPreferredSpeakingLanguageTag() { return preferredSpeakingLanguageTag; }
    public String getPreferredListeningLanguageTag() { return preferredListeningLanguageTag; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

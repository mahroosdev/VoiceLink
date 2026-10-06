package com.mahroosdev.voicelink.room;

import java.time.Instant;
import java.util.UUID;

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

@Entity
@Table(name = "room_participants")
public class RoomParticipant {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private ConversationRoom room;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccount user;

    @Column(name = "participant_slot", nullable = false)
    private short participantSlot;

    @Column(name = "speaking_language_tag", nullable = false, length = 64)
    private String speakingLanguageTag;

    @Column(name = "listening_language_tag", nullable = false, length = 64)
    private String listeningLanguageTag;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    protected RoomParticipant() {}

    public RoomParticipant(ConversationRoom room, UserAccount user, short participantSlot,
                           String speakingLanguageTag, String listeningLanguageTag, Instant joinedAt) {
        this.room = room;
        this.user = user;
        this.participantSlot = participantSlot;
        this.speakingLanguageTag = speakingLanguageTag;
        this.listeningLanguageTag = listeningLanguageTag;
        this.joinedAt = joinedAt;
    }

    public UUID getId() { return id; }
    public ConversationRoom getRoom() { return room; }
    public UserAccount getUser() { return user; }
    public short getParticipantSlot() { return participantSlot; }
    public String getSpeakingLanguageTag() { return speakingLanguageTag; }
    public String getListeningLanguageTag() { return listeningLanguageTag; }
    public Instant getJoinedAt() { return joinedAt; }
}

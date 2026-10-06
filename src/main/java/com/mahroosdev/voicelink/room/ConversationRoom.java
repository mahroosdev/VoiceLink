package com.mahroosdev.voicelink.room;

import java.time.Instant;
import java.util.UUID;

import com.mahroosdev.voicelink.user.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "conversation_rooms")
public class ConversationRoom {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "join_code", nullable = false, length = 16, unique = true)
    private String joinCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 7)
    private RoomStatus status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false)
    private UserAccount createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "invite_expires_at", nullable = false)
    private Instant inviteExpiresAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    protected ConversationRoom() {}

    public ConversationRoom(String joinCode, UserAccount createdBy, Instant now) {
        this.joinCode = joinCode;
        this.createdBy = createdBy;
        this.status = RoomStatus.WAITING;
        this.createdAt = now;
        this.updatedAt = now;
        this.inviteExpiresAt = now.plusSeconds(24 * 60 * 60);
    }

    public void activate(Instant now) {
        if (status != RoomStatus.WAITING) throw new IllegalStateException("Room is not waiting");
        status = RoomStatus.ACTIVE;
        updatedAt = now;
    }

    public void close(Instant now) {
        if (status == RoomStatus.CLOSED) throw new IllegalStateException("Room is already closed");
        status = RoomStatus.CLOSED;
        closedAt = now;
        updatedAt = now;
    }

    public UUID getId() { return id; }
    public String getJoinCode() { return joinCode; }
    public RoomStatus getStatus() { return status; }
    public UserAccount getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getInviteExpiresAt() { return inviteExpiresAt; }
    public Instant getClosedAt() { return closedAt; }
}

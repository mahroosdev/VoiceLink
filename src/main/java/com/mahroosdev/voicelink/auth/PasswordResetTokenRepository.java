package com.mahroosdev.voicelink.auth;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {
    @Query("select t.userId from PasswordResetToken t where t.tokenHash = :tokenHash")
    Optional<UUID> findUserIdByTokenHash(String tokenHash);

    @Modifying
    @Query("delete from PasswordResetToken t where t.userId = :userId and t.tokenHash = :tokenHash")
    int deleteIfCurrent(UUID userId, String tokenHash);
}

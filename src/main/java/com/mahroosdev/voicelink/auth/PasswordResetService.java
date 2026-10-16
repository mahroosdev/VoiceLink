package com.mahroosdev.voicelink.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import com.mahroosdev.voicelink.user.EmailNormalizer;
import com.mahroosdev.voicelink.user.UserAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.TaskExecutor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PasswordResetService {
    private static final Logger logger = LoggerFactory.getLogger(PasswordResetService.class);
    private static final Duration LIFETIME = Duration.ofMinutes(30);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserAccountRepository accounts;
    private final PasswordResetTokenRepository tokens;
    private final EmailNormalizer emails;
    private final PasswordEncoder encoder;
    private final PasswordResetSettings settings;
    private final PasswordResetNotifier notifier;
    private final TaskExecutor mailExecutor;
    private final TransactionTemplate transactions;
    private final ApplicationEventPublisher events;

    public PasswordResetService(UserAccountRepository accounts, PasswordResetTokenRepository tokens,
            EmailNormalizer emails, PasswordEncoder encoder, PasswordResetSettings settings,
            PasswordResetNotifier notifier, @Qualifier("passwordResetExecutor") TaskExecutor mailExecutor,
            PlatformTransactionManager transactionManager, ApplicationEventPublisher events) {
        this.accounts = accounts;
        this.tokens = tokens;
        this.emails = emails;
        this.encoder = encoder;
        this.settings = settings;
        this.notifier = notifier;
        this.mailExecutor = mailExecutor;
        this.transactions = new TransactionTemplate(transactionManager);
        this.events = events;
    }

    public void request(String submittedEmail) {
        if (!settings.enabled()) return;
        accounts.findByEmail(emails.normalize(submittedEmail)).filter(account -> account.isEnabled())
                .ifPresent(account -> {
                    String rawToken = transactions.execute(status -> issue(account.getId()));
                    if (rawToken == null) return;
                    String tokenHash = hash(rawToken);
                    try {
                        mailExecutor.execute(() -> {
                            try {
                                // A replaced token should not produce an avoidable stale email.
                                if (tokens.findById(account.getId()).filter(token ->
                                        token.getTokenHash().equals(tokenHash)).isEmpty()) return;
                                notifier.send(account.getEmail(), settings.resetUrl(rawToken));
                            } catch (RuntimeException ex) {
                                removeFailedToken(account.getId(), tokenHash);
                                logger.warn("Password reset email delivery failed");
                            }
                        });
                    } catch (RuntimeException ex) {
                        removeFailedToken(account.getId(), tokenHash);
                        logger.warn("Password reset email dispatch unavailable");
                    }
                });
    }

    private String issue(UUID userId) {
        if (accounts.lockById(userId).filter(account -> account.isEnabled()).isEmpty()) return null;
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        Instant now = Instant.now();
        String tokenHash = hash(rawToken);
        PasswordResetToken token = tokens.findById(userId).orElse(null);
        if (token == null) tokens.save(new PasswordResetToken(userId, tokenHash, now, now.plus(LIFETIME)));
        else token.replace(tokenHash, now, now.plus(LIFETIME));
        return rawToken;
    }

    private void removeFailedToken(UUID userId, String tokenHash) {
        try {
            transactions.executeWithoutResult(status -> tokens.deleteIfCurrent(userId, tokenHash));
        } catch (RuntimeException ex) {
            logger.warn("Password reset token cleanup failed");
        }
    }

    public boolean reset(String rawToken, String newPassword) {
        if (!settings.enabled() || !PasswordPolicy.valid(newPassword) || rawToken == null
                || !rawToken.matches("[A-Za-z0-9_-]{43}")) return false;
        try {
            if (Base64.getUrlDecoder().decode(rawToken).length != 32) return false;
        } catch (IllegalArgumentException ex) {
            return false;
        }
        String tokenHash = hash(rawToken);
        UUID userId = tokens.findUserIdByTokenHash(tokenHash).orElse(null);
        if (userId == null) return false;
        return Boolean.TRUE.equals(transactions.execute(status -> {
            var account = accounts.lockById(userId).orElse(null);
            if (account == null || !account.isEnabled()) return false;
            PasswordResetToken current = tokens.findById(userId).orElse(null);
            if (current == null || !current.getTokenHash().equals(tokenHash)
                    || !current.getExpiresAt().isAfter(Instant.now())) return false;
            account.changePasswordHash(encoder.encode(newPassword));
            tokens.delete(current);
            events.publishEvent(new PasswordResetCompletedEvent(userId));
            return true;
        }));
    }

    public static String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}

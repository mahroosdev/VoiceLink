package com.mahroosdev.voicelink.auth;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class AccountSessionRegistry {
    private final ConcurrentHashMap<UUID, Set<HttpSession>> sessions = new ConcurrentHashMap<>();
    private final Set<UUID> deletedAccounts = ConcurrentHashMap.newKeySet();

    public void register(UUID userId, HttpSession session) {
        if (deletedAccounts.contains(userId)) {
            invalidate(session);
            return;
        }
        sessions.computeIfAbsent(userId, ignored -> ConcurrentHashMap.newKeySet()).add(session);
        if (deletedAccounts.contains(userId)) {
            remove(session);
            invalidate(session);
        }
    }

    public void remove(HttpSession session) {
        sessions.forEach((userId, owned) -> {
            owned.remove(session);
            if (owned.isEmpty()) sessions.remove(userId, owned);
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccountDeleted(AccountDeletedEvent event) {
        deletedAccounts.add(event.userId());
        Set<HttpSession> owned = sessions.remove(event.userId());
        if (owned == null) return;
        for (HttpSession session : owned) invalidate(session);
    }

    private void invalidate(HttpSession session) {
        try {
            session.invalidate();
        } catch (RuntimeException ignored) {
            // Another request or container may already have ended this session.
            // CurrentCredentialFilter still rejects its deleted account on reuse.
        }
    }
}

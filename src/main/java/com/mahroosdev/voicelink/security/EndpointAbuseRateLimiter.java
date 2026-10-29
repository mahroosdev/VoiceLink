package com.mahroosdev.voicelink.security;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Bounded, process-local rolling-window limits for public and account-scoped endpoints. */
@Component
public class EndpointAbuseRateLimiter {
    private static final long WINDOW_NANOS = TimeUnit.MINUTES.toNanos(1);
    private static final int MAX_LIMIT = 10_000;

    public enum Route { LOGIN, REGISTRATION, PASSWORD_RESET, ROOM_CREATION, INVITATION }

    private final Map<Route, Window> windows = new EnumMap<>(Route.class);
    private final LongSupplier ticker;

    @Autowired
    public EndpointAbuseRateLimiter(
            @Value("${voicelink.abuse.login-per-address-per-minute:120}") int loginAddress,
            @Value("${voicelink.abuse.login-per-instance-per-minute:1200}") int loginInstance,
            @Value("${voicelink.abuse.registration-per-address-per-minute:20}") int registrationAddress,
            @Value("${voicelink.abuse.registration-per-instance-per-minute:400}") int registrationInstance,
            @Value("${voicelink.abuse.password-reset-per-address-per-minute:10}") int resetAddress,
            @Value("${voicelink.abuse.password-reset-per-instance-per-minute:200}") int resetInstance,
            @Value("${voicelink.abuse.room-creation-per-account-per-minute:30}") int roomAccount,
            @Value("${voicelink.abuse.room-creation-per-instance-per-minute:600}") int roomInstance,
            @Value("${voicelink.abuse.invitation-per-address-per-minute:60}") int invitationAddress,
            @Value("${voicelink.abuse.invitation-per-account-per-minute:120}") int invitationAccount,
            @Value("${voicelink.abuse.invitation-per-instance-per-minute:1800}") int invitationInstance) {
        this(new Limits(loginAddress, loginInstance, registrationAddress, registrationInstance,
                resetAddress, resetInstance, roomAccount, roomInstance,
                invitationAddress, invitationAccount, invitationInstance), System::nanoTime);
    }

    EndpointAbuseRateLimiter(Limits limits, LongSupplier ticker) {
        this.ticker = ticker;
        add(Route.LOGIN, limits.loginAddress, 0, limits.loginInstance);
        add(Route.REGISTRATION, limits.registrationAddress, 0, limits.registrationInstance);
        add(Route.PASSWORD_RESET, limits.resetAddress, 0, limits.resetInstance);
        add(Route.ROOM_CREATION, 0, limits.roomAccount, limits.roomInstance);
        add(Route.INVITATION, limits.invitationAddress, limits.invitationAccount, limits.invitationInstance);
    }

    public synchronized boolean tryAcquire(Route route, String remoteAddress, UUID accountId) {
        Window window = windows.get(route);
        if (window == null) return false;
        prune(window);
        List<Identity> identities = switch (route) {
            case LOGIN -> List.of(new Identity(Scope.ADDRESS, normalize(remoteAddress), window.addressLimit));
            case REGISTRATION -> List.of(new Identity(Scope.ADDRESS, normalize(remoteAddress), window.addressLimit));
            case PASSWORD_RESET -> List.of(new Identity(Scope.ADDRESS, normalize(remoteAddress), window.addressLimit));
            case ROOM_CREATION -> accountId == null ? List.of()
                    : List.of(new Identity(Scope.ACCOUNT, accountId.toString(), window.accountLimit));
            case INVITATION -> accountId == null ? List.of()
                    : List.of(new Identity(Scope.ADDRESS, normalize(remoteAddress), window.addressLimit),
                              new Identity(Scope.ACCOUNT, accountId.toString(), window.accountLimit));
        };
        if (identities.isEmpty() || window.accepted.size() >= window.instanceLimit) return false;
        for (Identity identity : identities) {
            if (window.counts.getOrDefault(identity, 0) >= identity.limit) return false;
        }

        Attempt attempt = new Attempt(ticker.getAsLong(), identities.stream()
                .map(identity -> new Identity(identity.scope, identity.value, identity.limit)).toList());
        window.accepted.addLast(attempt);
        for (Identity identity : attempt.identities) {
            window.counts.merge(identity, 1, Integer::sum);
        }
        return true;
    }

    synchronized int trackedAttempts(Route route) {
        prune(windows.get(route));
        return windows.get(route).accepted.size();
    }

    synchronized int trackedIdentities(Route route) {
        prune(windows.get(route));
        return windows.get(route).counts.size();
    }

    private void add(Route route, int addressLimit, int accountLimit, int instanceLimit) {
        if (addressLimit < 0 || accountLimit < 0 || instanceLimit < 1 || instanceLimit > MAX_LIMIT
                || addressLimit > instanceLimit || accountLimit > instanceLimit
                || addressLimit == 0 && accountLimit == 0) {
            throw new IllegalArgumentException("Invalid endpoint abuse limits for " + route);
        }
        windows.put(route, new Window(addressLimit, accountLimit, instanceLimit));
    }

    private void prune(Window window) {
        if (window == null) return;
        long now = ticker.getAsLong();
        while (!window.accepted.isEmpty() && now - window.accepted.peekFirst().time >= WINDOW_NANOS) {
            Attempt old = window.accepted.removeFirst();
            for (Identity identity : old.identities) {
                window.counts.computeIfPresent(identity, (key, count) -> count == 1 ? null : count - 1);
            }
        }
    }

    private static String normalize(String remoteAddress) {
        return remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress;
    }

    private enum Scope { ADDRESS, ACCOUNT }
    private record Identity(Scope scope, String value, int limit) {}
    private record Attempt(long time, List<Identity> identities) {}
    record Limits(int loginAddress, int loginInstance, int registrationAddress,
                  int registrationInstance, int resetAddress, int resetInstance,
                  int roomAccount, int roomInstance, int invitationAddress,
                  int invitationAccount, int invitationInstance) {}

    private static final class Window {
        private final int addressLimit;
        private final int accountLimit;
        private final int instanceLimit;
        private final ArrayDeque<Attempt> accepted = new ArrayDeque<>();
        private final Map<Identity, Integer> counts = new HashMap<>();

        private Window(int addressLimit, int accountLimit, int instanceLimit) {
            this.addressLimit = addressLimit;
            this.accountLimit = accountLimit;
            this.instanceLimit = instanceLimit;
        }
    }
}

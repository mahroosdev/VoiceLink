package com.mahroosdev.voicelink.security;

import java.util.concurrent.Semaphore;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Process-local non-blocking bound for concurrent memory-hard password operations. */
@Component
public class PasswordHashConcurrencyGate {
    static final int MAX_CONCURRENT_LIMIT = 4;

    private final Semaphore permits;

    public PasswordHashConcurrencyGate(
            @Value("${voicelink.abuse.password-hash-max-concurrent:1}") int maximumConcurrent) {
        if (maximumConcurrent < 1 || maximumConcurrent > MAX_CONCURRENT_LIMIT) {
            throw new IllegalArgumentException("Password hash concurrency must be between 1 and 4");
        }
        permits = new Semaphore(maximumConcurrent);
    }

    boolean tryAcquire() { return permits.tryAcquire(); }

    void release() { permits.release(); }
}

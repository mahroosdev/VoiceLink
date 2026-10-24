package com.mahroosdev.voicelink.conversation;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class TextTranslationFailureException extends ResponseStatusException {
    enum SafeCode {
        TIMED_OUT, TEMPORARILY_UNAVAILABLE, RATE_LIMITED, CONNECTION_PROBLEM,
        RESPONSE_INVALID, CONFIGURATION
    }

    private final SafeCode safeCode;

    TextTranslationFailureException(HttpStatus status, SafeCode safeCode, String reason) {
        super(status, reason);
        this.safeCode = safeCode;
    }

    SafeCode safeCode() {
        return safeCode;
    }
}

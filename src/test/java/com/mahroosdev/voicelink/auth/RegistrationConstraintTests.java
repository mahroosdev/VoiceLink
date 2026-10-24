package com.mahroosdev.voicelink.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class RegistrationConstraintTests {
    @Test
    void onlyNamedEmailUniquenessViolationIsClassifiedAsDuplicate() {
        assertThat(RegistrationService.emailConstraintViolated(failure("uq_users_email"))).isTrue();
        assertThat(RegistrationService.emailConstraintViolated(failure("fk_user_preferences_user"))).isFalse();
        assertThat(RegistrationService.emailConstraintViolated(new DataIntegrityViolationException("unknown")))
                .isFalse();
    }

    private DataIntegrityViolationException failure(String name) {
        return new DataIntegrityViolationException("write failed",
                new ConstraintViolationException("constraint", new SQLException("write failed"), name));
    }
}

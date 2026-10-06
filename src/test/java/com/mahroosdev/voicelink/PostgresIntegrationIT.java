package com.mahroosdev.voicelink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import com.mahroosdev.voicelink.auth.RegistrationForm;
import com.mahroosdev.voicelink.auth.RegistrationService;
import com.mahroosdev.voicelink.user.UserAccountRepository;
import com.mahroosdev.voicelink.user.UserPreferencesRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers
class PostgresIntegrationIT {
    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-bookworm")
            .withDatabaseName("voicelink_it");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired RegistrationService registration;
    @Autowired UserAccountRepository accounts;
    @Autowired UserPreferencesRepository preferences;
    @Autowired JdbcTemplate jdbc;

    @Test
    void flywayAndHibernatePersistUuidAccountAndPreferences() {
        Integer version = jdbc.queryForObject(
                "SELECT max(installed_rank) FROM flyway_schema_history WHERE success = true", Integer.class);
        assertThat(version).isEqualTo(1);
        RegistrationForm form = new RegistrationForm();
        form.setDisplayName("Postgres Test");
        form.setEmail("  " + UUID.randomUUID() + "@EXAMPLE.TEST  ");
        form.setPassword("long integration passphrase");
        UUID id = registration.register(form);
        assertThat(accounts.findById(id)).isPresent();
        assertThat(preferences.findById(id)).isPresent();
        assertThat(accounts.findById(id).orElseThrow().getEmail()).endsWith("@example.test");

        String email = accounts.findById(id).orElseThrow().getEmail();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO users "
                + "(id, display_name, email, password_hash, enabled, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, true, now(), now())",
                UUID.randomUUID(), "Duplicate", email, "irrelevant"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO user_preferences "
                + "(user_id, created_at, updated_at) VALUES (?, now(), now())", UUID.randomUUID()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        jdbc.update("DELETE FROM users WHERE id = ?", id);
        assertThat(preferences.findById(id)).isEmpty();
    }
}

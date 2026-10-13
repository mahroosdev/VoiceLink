package com.mahroosdev.voicelink;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

class ProductionConfigurationTests {

    @Test
    void productionProfileSetsProxyAndSessionPolicyWithoutDuplicatingDatabaseSettings()
            throws IOException {
        var environment = environment("application-prod.yml", "application.yml");

        assertThat(environment.getProperty("server.forward-headers-strategy")).isEqualTo("native");
        assertThat(environment.getProperty("server.tomcat.redirect-context-root", Boolean.class)).isFalse();
        assertThat(environment.getProperty("server.servlet.session.tracking-modes")).isEqualTo("cookie");
        assertThat(environment.getProperty("server.servlet.session.cookie.secure", Boolean.class)).isTrue();
        assertThat(environment.getProperty("server.servlet.session.cookie.http-only", Boolean.class)).isTrue();
        assertThat(environment.getProperty("server.servlet.session.cookie.same-site")).isEqualTo("lax");
        assertThat(environment.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health");
        assertThat(environment.getProperty("management.endpoint.health.show-details")).isEqualTo("never");
        assertThat(environment.getProperty("voicelink.standard.free-tier-confirmed", Boolean.class)).isFalse();
        assertThat(environment.getProperty("voicelink.premium.enabled", Boolean.class)).isFalse();
    }

    @Test
    void railwayPortOverridesLocalPortAndDefaultsTo8080() throws IOException {
        var environment = environment("application.yml");

        assertThat(environment.getProperty("server.port", Integer.class)).isEqualTo(8080);
        environment.setProperty("SERVER_PORT", "9090");
        assertThat(environment.getProperty("server.port", Integer.class)).isEqualTo(9090);
        environment.setProperty("PORT", "18080");
        assertThat(environment.getProperty("server.port", Integer.class)).isEqualTo(18080);
    }

    @Test
    void productionDatabaseUsesExplicitJdbcUrlAndCredentials() throws IOException {
        var environment = environment("application-prod.yml", "application.yml");
        environment.setProperty("DB_URL", "jdbc:postgresql://db.internal:5432/voicelink?sslmode=require");
        environment.setProperty("DB_USERNAME", "synthetic-user");
        environment.setProperty("DB_PASSWORD", "synthetic-password");

        assertThat(environment.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://db.internal:5432/voicelink?sslmode=require");
        assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("synthetic-user");
        assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("synthetic-password");
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(environment.getProperty("spring.flyway.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("spring.flyway.locations")).isEqualTo("classpath:db/migration");
    }

    private static MockEnvironment environment(String... resources) throws IOException {
        var environment = new MockEnvironment();
        var loader = new YamlPropertySourceLoader();
        for (String resource : resources) {
            for (PropertySource<?> source : loader.load(resource, new ClassPathResource(resource))) {
                environment.getPropertySources().addLast(source);
            }
        }
        return environment;
    }
}

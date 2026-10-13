package com.mahroosdev.voicelink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mahroosdev.voicelink.ai.PremiumProviderSettings;
import com.mahroosdev.voicelink.ai.ProviderFailure;
import com.mahroosdev.voicelink.ai.StandardProviderSettings;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "voicelink.standard.free-tier-confirmed=false")
@AutoConfigureMockMvc
@ActiveProfiles({"prod", "test"})
class ProductionProfileIntegrationTests {
    @Autowired Environment environment;
    @Autowired MockMvc mvc;
    @Autowired StandardProviderSettings standard;
    @Autowired PremiumProviderSettings premium;

    @Test
    void productionProfileLoadsWithSecureSessionsAndOfflineProviders() throws Exception {
        assertThat(environment.acceptsProfiles("prod")).isTrue();
        assertThat(environment.getProperty("server.servlet.session.cookie.secure", Boolean.class)).isTrue();
        assertThat(environment.getProperty("server.servlet.session.cookie.http-only", Boolean.class)).isTrue();
        assertThat(environment.getProperty("server.servlet.session.cookie.same-site")).isEqualTo("lax");
        assertThat(environment.getProperty("server.forward-headers-strategy")).isEqualTo("native");
        assertThatThrownBy(standard::groqKey).isInstanceOf(ProviderFailure.class);
        assertThatThrownBy(standard::geminiKey).isInstanceOf(ProviderFailure.class);
        assertThat(premium.available()).isFalse();
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/env")).andExpect(status().isFound());
    }
}

package com.mahroosdev.voicelink.auth;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
public class PasswordResetSettings {
    private final boolean enabled;
    private final URI publicBaseUrl;
    private final String apiKey;
    private final String fromAddress;

    public PasswordResetSettings(
            @Value("${voicelink.password-reset.enabled:false}") boolean enabled,
            @Value("${voicelink.password-reset.public-base-url:}") String publicBaseUrl,
            @Value("${voicelink.password-reset.mail-api-key:}") String apiKey,
            @Value("${voicelink.password-reset.mail-from-address:}") String fromAddress,
            Environment environment) {
        this.enabled = enabled;
        this.apiKey = apiKey;
        this.fromAddress = fromAddress;
        if (!enabled) {
            this.publicBaseUrl = null;
            return;
        }
        if (!environment.acceptsProfiles(Profiles.of("prod", "test"))) {
            throw new IllegalStateException("Password reset can only be enabled in production or offline tests");
        }
        try {
            URI origin = URI.create(publicBaseUrl);
            if (!"https".equalsIgnoreCase(origin.getScheme()) || origin.getHost() == null
                    || origin.getRawUserInfo() != null || origin.getRawQuery() != null
                    || origin.getRawFragment() != null || !(origin.getRawPath() == null
                    || origin.getRawPath().isEmpty() || "/".equals(origin.getRawPath()))
                    || apiKey.isBlank() || apiKey.chars().anyMatch(ch -> ch <= 32 || ch >= 127)
                    || !fromAddress.matches("^[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+$")) {
                throw new IllegalArgumentException();
            }
            this.publicBaseUrl = origin;
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Password reset is enabled without valid HTTPS origin or mail settings");
        }
    }

    public boolean enabled() { return enabled; }
    public String apiKey() { return apiKey; }
    public String fromAddress() { return fromAddress; }

    public String resetUrl(String rawToken) {
        if (!enabled) throw new IllegalStateException("Password reset is disabled");
        return publicBaseUrl.getScheme() + "://" + publicBaseUrl.getRawAuthority()
                + "/reset-password#token=" + rawToken;
    }
}

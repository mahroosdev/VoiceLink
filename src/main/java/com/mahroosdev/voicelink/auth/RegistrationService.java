package com.mahroosdev.voicelink.auth;

import java.util.UUID;

import com.mahroosdev.voicelink.user.EmailNormalizer;
import com.mahroosdev.voicelink.user.UserAccount;
import com.mahroosdev.voicelink.user.UserAccountRepository;
import com.mahroosdev.voicelink.user.UserPreferences;
import com.mahroosdev.voicelink.user.UserPreferencesRepository;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrationService {
    private final UserAccountRepository accounts;
    private final UserPreferencesRepository preferences;
    private final EmailNormalizer emails;
    private final PasswordEncoder passwordEncoder;
    private final Validator validator;

    public RegistrationService(UserAccountRepository accounts, UserPreferencesRepository preferences,
            EmailNormalizer emails, PasswordEncoder passwordEncoder, Validator validator) {
        this.accounts = accounts;
        this.preferences = preferences;
        this.emails = emails;
        this.passwordEncoder = passwordEncoder;
        this.validator = validator;
    }

    @Transactional
    public UUID register(RegistrationForm form) {
        String displayName = form.getDisplayName() == null ? "" : form.getDisplayName().strip();
        String email = emails.normalize(form.getEmail());
        RegistrationForm normalized = new RegistrationForm();
        normalized.setDisplayName(displayName);
        normalized.setEmail(email);
        normalized.setPassword(form.getPassword());
        var violations = validator.validate(normalized);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        if (accounts.existsByEmail(email)) {
            throw new DuplicateRegistrationException();
        }
        UserAccount account = new UserAccount(displayName, email, passwordEncoder.encode(form.getPassword()));
        try {
            accounts.saveAndFlush(account);
            preferences.saveAndFlush(new UserPreferences(account));
        } catch (DataIntegrityViolationException exception) {
            throw new DuplicateRegistrationException();
        }
        return account.getId();
    }
}

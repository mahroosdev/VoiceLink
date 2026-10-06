package com.mahroosdev.voicelink.auth;

import com.mahroosdev.voicelink.user.EmailNormalizer;
import com.mahroosdev.voicelink.user.UserAccountRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AccountUserDetailsService implements UserDetailsService {
    private final UserAccountRepository accounts;
    private final EmailNormalizer emails;

    public AccountUserDetailsService(UserAccountRepository accounts, EmailNormalizer emails) {
        this.accounts = accounts;
        this.emails = emails;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return accounts.findByEmail(emails.normalize(username))
                .map(account -> new AccountPrincipal(account.getId(), account.getEmail(),
                        account.getPasswordHash(), account.isEnabled()))
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }
}

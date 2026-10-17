package com.mahroosdev.voicelink.auth;

import java.io.IOException;

import com.mahroosdev.voicelink.user.UserAccountRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public final class CurrentCredentialFilter extends OncePerRequestFilter {
    private final UserAccountRepository accounts;
    private final AccountSessionRegistry sessions;

    public CurrentCredentialFilter(UserAccountRepository accounts, AccountSessionRegistry sessions) {
        this.accounts = accounts;
        this.sessions = sessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AccountPrincipal principal
                && accounts.findById(principal.getUserId()).filter(account -> account.isEnabled()
                        && account.getPasswordHash().equals(principal.getPassword())).isEmpty()) {
            HttpSession session = request.getSession(false);
            if (session != null) session.invalidate();
            SecurityContextHolder.clearContext();
            if (request.getRequestURI().startsWith("/api/") || request.getRequestURI().startsWith("/ws/")) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            } else {
                response.sendRedirect(request.getContextPath() + "/login?expired");
            }
            return;
        }
        if (authentication != null && authentication.getPrincipal() instanceof AccountPrincipal principal) {
            HttpSession session = request.getSession(false);
            if (session != null) sessions.register(principal.getUserId(), session);
        }
        chain.doFilter(request, response);
    }
}

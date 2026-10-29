package com.mahroosdev.voicelink.security;

import java.io.IOException;
import java.util.UUID;

import com.mahroosdev.voicelink.auth.AccountPrincipal;
import com.mahroosdev.voicelink.auth.PasswordResetSettings;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Applies endpoint quotas after CSRF validation and before controller/authentication handlers. */
public final class EndpointAbuseFilter extends OncePerRequestFilter {
    private static final String TOO_MANY_REQUESTS = "Too many requests. Please wait before trying again.";

    private final EndpointAbuseRateLimiter limiter;
    private final PasswordResetSettings passwordResetSettings;

    public EndpointAbuseFilter(EndpointAbuseRateLimiter limiter, PasswordResetSettings passwordResetSettings) {
        this.limiter = limiter;
        this.passwordResetSettings = passwordResetSettings;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getServletPath();
        if (path == null || path.isBlank()) {
            String requestUri = request.getRequestURI();
            path = requestUri.substring(Math.min(request.getContextPath().length(), requestUri.length()));
        }
        EndpointAbuseRateLimiter.Route route = route(request.getMethod(), path);
        if (route == null || route == EndpointAbuseRateLimiter.Route.PASSWORD_RESET
                && !passwordResetSettings.enabled()) {
            chain.doFilter(request, response);
            return;
        }

        UUID accountId = authenticatedAccountId();
        if ((route == EndpointAbuseRateLimiter.Route.ROOM_CREATION
                || route == EndpointAbuseRateLimiter.Route.INVITATION) && accountId == null) {
            chain.doFilter(request, response);
            return;
        }

        // Use the servlet connection address only; never parse forwarded-address headers here.
        if (!limiter.tryAcquire(route, request.getRemoteAddr(), accountId)) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", "60");
            response.setHeader("Cache-Control", "no-store");
            response.setContentType("text/plain");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(TOO_MANY_REQUESTS);
            return;
        }
        chain.doFilter(request, response);
    }

    private static EndpointAbuseRateLimiter.Route route(String method, String path) {
        if (!"POST".equals(method)) return null;
        return switch (path) {
            case "/login" -> EndpointAbuseRateLimiter.Route.LOGIN;
            case "/register" -> EndpointAbuseRateLimiter.Route.REGISTRATION;
            case "/forgot-password", "/reset-password" -> EndpointAbuseRateLimiter.Route.PASSWORD_RESET;
            case "/rooms" -> EndpointAbuseRateLimiter.Route.ROOM_CREATION;
            case "/rooms/join", "/rooms/join/preview", "/rooms/join/setup" ->
                    EndpointAbuseRateLimiter.Route.INVITATION;
            default -> null;
        };
    }

    private static UUID authenticatedAccountId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AccountPrincipal principal)) return null;
        return principal.getUserId();
    }
}

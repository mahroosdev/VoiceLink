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
    private final PasswordHashConcurrencyGate passwordHashGate;

    public EndpointAbuseFilter(EndpointAbuseRateLimiter limiter, PasswordResetSettings passwordResetSettings,
                               PasswordHashConcurrencyGate passwordHashGate) {
        this.limiter = limiter;
        this.passwordResetSettings = passwordResetSettings;
        this.passwordHashGate = passwordHashGate;
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
        boolean hashRequest = passwordHashRequest(request, path);
        if (route == null && !hashRequest || route == EndpointAbuseRateLimiter.Route.PASSWORD_RESET
                && !passwordResetSettings.enabled()) {
            chain.doFilter(request, response);
            return;
        }

        UUID accountId = authenticatedAccountId();
        if (route != null && (route == EndpointAbuseRateLimiter.Route.ROOM_CREATION
                || route == EndpointAbuseRateLimiter.Route.INVITATION
                || route == EndpointAbuseRateLimiter.Route.LIVE_INVALIDATION) && accountId == null) {
            chain.doFilter(request, response);
            return;
        }

        // Use the servlet connection address only; never parse forwarded-address headers here.
        if (route != null && !limiter.tryAcquire(route, request.getRemoteAddr(), accountId)) {
            writeTooManyRequests(response);
            return;
        }
        boolean hashPermit = hashRequest && passwordHashGate.tryAcquire();
        if (hashRequest && !hashPermit) {
            writeTooManyRequests(response);
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            if (hashPermit) passwordHashGate.release();
        }
    }

    private static void writeTooManyRequests(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", "60");
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("text/plain");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(TOO_MANY_REQUESTS);
    }

    private boolean passwordHashRequest(HttpServletRequest request, String path) {
        if (!"POST".equals(request.getMethod())) return false;
        return switch (path) {
            case "/login", "/register", "/account/delete" -> true;
            case "/reset-password" -> passwordResetSettings.enabled();
            default -> false;
        };
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
            default -> liveInvalidationRoute(method, path);
        };
    }

    private static EndpointAbuseRateLimiter.Route liveInvalidationRoute(String method, String path) {
        if (!"POST".equals(method)) return null;
        String[] segments = path.split("/", -1);
        return segments.length == 8 && segments[0].isEmpty()
                && "api".equals(segments[1]) && "rooms".equals(segments[2])
                && !segments[3].isEmpty() && "text".equals(segments[4])
                && "shares".equals(segments[5]) && !segments[6].isEmpty()
                && "invalidate".equals(segments[7])
                ? EndpointAbuseRateLimiter.Route.LIVE_INVALIDATION : null;
    }

    private static UUID authenticatedAccountId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AccountPrincipal principal)) return null;
        return principal.getUserId();
    }
}

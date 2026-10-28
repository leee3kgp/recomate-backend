package com.recomate.security;

import com.recomate.user.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/** Apply to all future API routes too: an unexpired JWT must not bypass withdrawal state. */
final class UserAccessFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(UserAccessFilter.class);
    private final UserRepository users;
    UserAccessFilter(UserRepository users) { this.users = users; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean deletionRetry = request.getMethod().equals("DELETE") && path.equals("/api/users/me");
        if (path.startsWith("/api/") && !deletionRetry && authentication instanceof JwtAuthenticationToken jwt) {
            try {
                if (users.find(jwt.getToken().getSubject()).map(user -> !user.active()).orElse(false)) {
                    SecurityConfig.unauthorized(response);
                    return;
                }
            } catch (RuntimeException unavailable) {
                LOG.error("User access check failed: {}", unavailable.getClass().getSimpleName());
                response.setStatus(500);
                response.setCharacterEncoding("UTF-8");
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":{\"code\":\"INTERNAL_ERROR\",\"message\":\"요청을 처리하지 못했습니다.\"}}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}

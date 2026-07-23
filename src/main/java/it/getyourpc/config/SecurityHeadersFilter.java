package it.getyourpc.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityHeadersFilter extends OncePerRequestFilter {
    private static final Set<String> FRONTEND_ASSETS_WITHOUT_CACHE = Set.of(
            "/", "/index.html", "/styles.css", "/app.js");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        response.setHeader("Cross-Origin-Resource-Policy", "same-origin");
        // Vue compiles the trusted in-DOM template from index.html at startup.
        response.setHeader("Content-Security-Policy",
                "default-src 'self'; script-src 'self' 'unsafe-eval'; style-src 'self'; "
                        + "img-src 'self' data: blob:; connect-src 'self'; object-src 'none'; "
                        + "base-uri 'self'; frame-ancestors 'none'; form-action 'self'");
        if (request.isSecure()) {
            response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
        String requestUri = request.getRequestURI();
        if (requestUri.startsWith("/api/auth") || FRONTEND_ASSETS_WITHOUT_CACHE.contains(requestUri)) {
            response.setHeader("Cache-Control", "no-store");
        }
        filterChain.doFilter(request, response);
    }
}

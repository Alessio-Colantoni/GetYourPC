package it.getyourpc.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SecurityHeadersFilterTest {
    @Test
    void addsSecurityHeadersToEveryResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/listings/1/photos/1");
        request.setSecure(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new SecurityHeadersFilter().doFilter(request, response, new MockFilterChain());

        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
        assertEquals("DENY", response.getHeader("X-Frame-Options"));
        assertEquals("strict-origin-when-cross-origin", response.getHeader("Referrer-Policy"));
        assertEquals("camera=(), microphone=(), geolocation=()", response.getHeader("Permissions-Policy"));
        assertEquals("same-origin", response.getHeader("Cross-Origin-Resource-Policy"));
        assertEquals("max-age=31536000; includeSubDomains",
                response.getHeader("Strict-Transport-Security"));
        assertEquals("default-src 'self'; script-src 'self' 'unsafe-eval'; style-src 'self'; "
                        + "img-src 'self' data: blob:; connect-src 'self'; object-src 'none'; "
                        + "base-uri 'self'; frame-ancestors 'none'; form-action 'self'",
                response.getHeader("Content-Security-Policy"));
    }

    @Test
    void preventsCachingFrontendFilesThatMustBelongToTheSameDeployment() throws Exception {
        SecurityHeadersFilter filter = new SecurityHeadersFilter();

        for (String path : List.of("/", "/index.html", "/styles.css", "/app.js")) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());

            assertEquals("no-store", response.getHeader("Cache-Control"), path);
        }
    }
}

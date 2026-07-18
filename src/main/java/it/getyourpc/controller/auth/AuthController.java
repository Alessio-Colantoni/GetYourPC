package it.getyourpc.controller.auth;

import it.getyourpc.model.auth.*;
import it.getyourpc.model.common.RequestRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    public static final String SESSION_USER = SessionUserGuard.SESSION_USER;
    private final AuthService authService;
    private final RequestRateLimiter rateLimiter;
    private final SessionUserGuard sessionUserGuard;

    public AuthController(AuthService authService, RequestRateLimiter rateLimiter,
                          SessionUserGuard sessionUserGuard) {
        this.authService = authService;
        this.rateLimiter = rateLimiter;
        this.sessionUserGuard = sessionUserGuard;
    }

    @PostMapping("/login")
    public AuthenticatedUser login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        rateLimiter.check("login", httpRequest.getRemoteAddr(), 10, Duration.ofMinutes(1),
                "Troppi tentativi di accesso. Riprova tra un minuto");
        AuthService.AuthenticatedSession authenticated = authService.authenticateSession(request);
        HttpSession previousSession = httpRequest.getSession(false);
        if (previousSession != null) previousSession.invalidate();
        SessionUserGuard.storeAuthenticatedUser(httpRequest.getSession(true),
                authenticated.user(), authenticated.credentialFingerprint());
        return authenticated.user();
    }

    @GetMapping("/me")
    public AuthenticatedUser me(HttpServletRequest request) {
        return sessionUserGuard.requireAuthenticated(request);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
    }
}

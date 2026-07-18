package it.getyourpc.controller.auth;

import it.getyourpc.model.auth.*;
import it.getyourpc.model.common.RequestRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/auth")
public class AccountManagementController {
    private static final Duration START_WINDOW = Duration.ofMinutes(15);
    private final AccountVerificationService accountService;
    private final AccountProfileService profileService;
    private final RequestRateLimiter rateLimiter;
    private final SessionUserGuard sessionUserGuard;

    public AccountManagementController(AccountVerificationService accountService,
                                       AccountProfileService profileService,
                                       RequestRateLimiter rateLimiter,
                                       SessionUserGuard sessionUserGuard) {
        this.accountService = accountService;
        this.profileService = profileService;
        this.rateLimiter = rateLimiter;
        this.sessionUserGuard = sessionUserGuard;
    }

    @PostMapping("/register/start")
    public VerificationStarted startRegistration(@Valid @RequestBody RegisterStartRequest request,
                                                 HttpServletRequest httpRequest) {
        checkStart(httpRequest, "register", request.email());
        return accountService.startRegistration(request);
    }

    @PostMapping("/register/confirm")
    public AuthenticatedUser confirmRegistration(@Valid @RequestBody RegisterConfirmRequest request,
                                                 HttpServletRequest httpRequest) {
        checkConfirmation(httpRequest, "register-confirm");
        AuthenticatedUser user = accountService.confirmRegistration(request);
        var previousSession = httpRequest.getSession(false);
        if (previousSession != null) previousSession.invalidate();
        return sessionUserGuard.storeCurrentAuthenticatedUser(httpRequest.getSession(true), user);
    }

    @PostMapping("/password/forgot/start")
    public VerificationStarted startForgotPassword(
            @Valid @RequestBody ForgotPasswordStartRequest request, HttpServletRequest httpRequest) {
        checkStart(httpRequest, "forgot-password", request.email());
        return accountService.startForgotPassword(request);
    }

    @PostMapping("/password/forgot/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirmForgotPassword(@Valid @RequestBody ForgotPasswordConfirmRequest request,
                                      HttpServletRequest httpRequest) {
        checkConfirmation(httpRequest, "forgot-password-confirm");
        accountService.confirmForgotPassword(request);
    }

    @PostMapping("/password/change/start")
    public VerificationStarted startPasswordChange(
            @Valid @RequestBody PasswordChangeStartRequest request, HttpServletRequest httpRequest) {
        AuthenticatedUser user = sessionUserGuard.requireAuthenticated(httpRequest);
        checkUserStart("password-change", user.id());
        return accountService.startPasswordChange(user, request);
    }

    @PostMapping("/password/change/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirmPasswordChange(@Valid @RequestBody PasswordChangeConfirmRequest request,
                                      HttpServletRequest httpRequest) {
        AuthenticatedUser user = sessionUserGuard.requireAuthenticated(httpRequest);
        checkConfirmation(httpRequest, "password-change-confirm");
        accountService.confirmPasswordChange(user, request);
        var session = httpRequest.getSession(false);
        if (session != null) session.invalidate();
    }

    @PostMapping("/email/change/start")
    public VerificationStarted startEmailChange(@Valid @RequestBody EmailChangeStartRequest request,
                                                HttpServletRequest httpRequest) {
        AuthenticatedUser user = sessionUserGuard.requireAuthenticated(httpRequest);
        checkUserStart("email-change", user.id());
        return accountService.startEmailChange(user, request);
    }

    @PostMapping("/email/change/confirm")
    public AuthenticatedUser confirmEmailChange(@Valid @RequestBody VerificationCodeRequest request,
                                                HttpServletRequest httpRequest) {
        AuthenticatedUser user = sessionUserGuard.requireAuthenticated(httpRequest);
        checkConfirmation(httpRequest, "email-change-confirm");
        AuthenticatedUser updated = accountService.confirmEmailChange(user, request);
        httpRequest.getSession(false).setAttribute(SessionUserGuard.SESSION_USER, updated);
        return updated;
    }

    @PatchMapping("/profile")
    public AuthenticatedUser updateProfile(@Valid @RequestBody ProfileUpdateRequest request,
                                           HttpServletRequest httpRequest) {
        AuthenticatedUser user = sessionUserGuard.requireAuthenticated(httpRequest);
        AuthenticatedUser updated = profileService.update(user, request);
        httpRequest.getSession(false).setAttribute(SessionUserGuard.SESSION_USER, updated);
        return updated;
    }

    @DeleteMapping("/account")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAccount(@Valid @RequestBody DeleteAccountRequest request,
                              HttpServletRequest httpRequest) {
        AuthenticatedUser user = sessionUserGuard.requireAuthenticated(httpRequest);
        profileService.delete(user, request);
        var session = httpRequest.getSession(false);
        if (session != null) session.invalidate();
    }

    private void checkStart(HttpServletRequest request, String scope, String email) {
        rateLimiter.check(scope + "-ip", request.getRemoteAddr(), 5, START_WINDOW,
                "Troppe richieste. Riprova più tardi");
        rateLimiter.check(scope + "-email", AccountCredentials.normalizeEmail(email), 3, START_WINDOW,
                "Troppe richieste per questa email. Riprova più tardi");
    }

    private void checkUserStart(String scope, int userId) {
        rateLimiter.check(scope + "-user", Integer.toString(userId), 3, START_WINDOW,
                "Troppe richieste. Riprova più tardi");
    }

    private void checkConfirmation(HttpServletRequest request, String scope) {
        rateLimiter.check(scope + "-ip", request.getRemoteAddr(), 20, Duration.ofMinutes(10),
                "Troppi tentativi. Riprova più tardi");
    }
}

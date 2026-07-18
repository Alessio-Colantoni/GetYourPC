package it.getyourpc.controller.auth;

import it.getyourpc.model.auth.*;
import it.getyourpc.model.common.RequestRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/admin/reviewers")
public class AdminController {
    private final AdminService adminService;
    private final SessionUserGuard sessionUserGuard;
    private final RequestRateLimiter rateLimiter;

    public AdminController(AdminService adminService, SessionUserGuard sessionUserGuard,
                           RequestRateLimiter rateLimiter) {
        this.adminService = adminService;
        this.sessionUserGuard = sessionUserGuard;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AuthenticatedUser create(@Valid @RequestBody CreateReviewerRequest request,
                                    HttpServletRequest httpRequest) {
        AuthenticatedUser admin = sessionUserGuard.requireRole(httpRequest, SessionUserGuard.ADMIN_ROLE);
        rateLimiter.check("reviewer-create-admin", Integer.toString(admin.id()), 10, Duration.ofHours(1),
                "Troppi reviewer creati. Riprova più tardi");
        return adminService.createReviewer(admin, request);
    }
}

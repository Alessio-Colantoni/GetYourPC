package it.getyourpc.model.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class SessionUserGuard {
    public static final String SESSION_USER = "authenticatedUser";
    public static final String SESSION_CREDENTIAL_FINGERPRINT = "credentialFingerprint";
    public static final String USER_ROLE = "user";
    public static final String REVIEWER_ROLE = "reviewer";
    public static final String ADMIN_ROLE = "admin";

    private final AuthService authService;

    public SessionUserGuard(AuthService authService) {
        this.authService = authService;
    }

    public AuthenticatedUser requireAuthenticated(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw unauthorized();
        }
        Object storedUser = session.getAttribute(SESSION_USER);
        if (storedUser instanceof AuthenticatedUser authenticatedUser) {
            var currentSession = authService.findActiveSession(authenticatedUser.id());
            if (currentSession.isPresent()) {
                String currentFingerprint = currentSession.get().credentialFingerprint();
                Object storedFingerprint = session.getAttribute(SESSION_CREDENTIAL_FINGERPRINT);
                if (storedFingerprint instanceof String fingerprint
                        && fingerprint.equals(currentFingerprint)) {
                    session.setAttribute(SESSION_USER, currentSession.get().user());
                    return currentSession.get().user();
                }
            }
        }
        session.invalidate();
        throw unauthorized();
    }

    public AuthenticatedUser requireRole(HttpServletRequest request, String role) {
        AuthenticatedUser user = requireAuthenticated(request);
        if (!role.equalsIgnoreCase(user.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Non hai i permessi necessari");
        }
        return user;
    }

    public AuthenticatedUser requireAnyRole(HttpServletRequest request, String... roles) {
        AuthenticatedUser user = requireAuthenticated(request);
        for (String role : roles) {
            if (role.equalsIgnoreCase(user.role())) return user;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Non hai i permessi necessari");
    }

    public AuthenticatedUser optionalUser(HttpServletRequest request) {
        try {
            return requireAuthenticated(request);
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                return null;
            }
            throw e;
        }
    }

    public static void storeAuthenticatedUser(HttpSession session, AuthenticatedUser user,
                                              String credentialFingerprint) {
        session.setAttribute(SESSION_USER, user);
        session.setAttribute(SESSION_CREDENTIAL_FINGERPRINT, credentialFingerprint);
    }

    public AuthenticatedUser storeCurrentAuthenticatedUser(HttpSession session, AuthenticatedUser user) {
        AuthService.AuthenticatedSession current = authService.findActiveSession(user.id())
                .orElseThrow(SessionUserGuard::unauthorized);
        storeAuthenticatedUser(session, current.user(), current.credentialFingerprint());
        return current.user();
    }

    public static boolean canSell(AuthenticatedUser user) {
        return hasRole(user, USER_ROLE) || hasRole(user, ADMIN_ROLE);
    }

    public static boolean canReview(AuthenticatedUser user) {
        return hasRole(user, REVIEWER_ROLE) || hasRole(user, ADMIN_ROLE);
    }

    private static boolean hasRole(AuthenticatedUser user, String role) {
        return user != null && role.equalsIgnoreCase(user.role());
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Devi effettuare il login");
    }
}

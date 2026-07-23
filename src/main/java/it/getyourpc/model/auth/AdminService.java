package it.getyourpc.model.auth;

import it.getyourpc.mail.MailjetClient;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;

@Service
public class AdminService {
    private static final char[] PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%".toCharArray();
    private final ReviewerProvisioningService reviewerProvisioningService;
    private final MailjetClient mailjetClient;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);
    private final SecureRandom secureRandom = new SecureRandom();

    public AdminService(ReviewerProvisioningService reviewerProvisioningService,
                        MailjetClient mailjetClient) {
        this.reviewerProvisioningService = reviewerProvisioningService;
        this.mailjetClient = mailjetClient;
    }

    public AuthenticatedUser createReviewer(AuthenticatedUser admin, CreateReviewerRequest request) {
        requireAdmin(admin);
        String name = AccountCredentials.normalizeName(request.name(), "Inserisci il nome");
        String surname = AccountCredentials.normalizeName(request.surname(), "Inserisci il cognome");
        String email = AccountCredentials.normalizeEmail(request.email());
        String temporaryPassword = temporaryPassword();
        AuthenticatedUser reviewer = reviewerProvisioningService.create(name, surname, email,
                passwordEncoder.encode(temporaryPassword));
        try {
            mailjetClient.sendReviewerCredentials(email, temporaryPassword);
        } catch (RuntimeException exception) {
            try {
                reviewerProvisioningService.discardUndelivered(reviewer.id());
            } catch (RuntimeException cleanupException) {
                exception.addSuppressed(cleanupException);
            }
            throw exception;
        }
        return reviewer;
    }

    private String temporaryPassword() {
        StringBuilder password = new StringBuilder(20);
        for (int index = 0; index < 20; index++) {
            password.append(PASSWORD_ALPHABET[secureRandom.nextInt(PASSWORD_ALPHABET.length)]);
        }
        return password.toString();
    }

    private static void requireAdmin(AuthenticatedUser user) {
        if (user == null || !SessionUserGuard.ADMIN_ROLE.equalsIgnoreCase(user.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Accesso riservato agli amministratori");
        }
    }
}

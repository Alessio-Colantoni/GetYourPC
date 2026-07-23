package it.getyourpc.model.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class AccountCredentials {
    static final int MINIMUM_PASSWORD_LENGTH = 8;
    static final int MAXIMUM_BCRYPT_BYTES = 72;

    private AccountCredentials() {
    }

    public static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw invalid("Inserisci l'email");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    static String normalizeName(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw invalid(message);
        }
        String normalized = value.trim();
        if (normalized.length() > 255) {
            throw invalid("Nome o cognome troppo lungo");
        }
        return normalized;
    }

    static void validatePassword(String password, String email) {
        if (password == null || password.length() < MINIMUM_PASSWORD_LENGTH) {
            throw invalid("La password deve contenere almeno 8 caratteri");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_BCRYPT_BYTES) {
            throw invalid("La password non può superare 72 byte UTF-8");
        }
        if (email != null && password.equalsIgnoreCase(email)) {
            throw invalid("La password non può coincidere con l'email");
        }
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}

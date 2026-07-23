package it.getyourpc.model.auth;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Optional;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Service
public class AuthService {
    private static final int PASSWORD_STRENGTH = 12;
    private final AccountRepository accountRepository;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(PASSWORD_STRENGTH);
    private final String dummyPasswordHash = passwordEncoder.encode("GetYourPC timing placeholder");

    public AuthService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    public AuthenticatedUser authenticate(LoginRequest request) {
        return authenticateSession(request).user();
    }

    public AuthenticatedSession authenticateSession(LoginRequest request) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(UNAUTHORIZED, "Credenziali non valide");
        }
        Optional<AccountRepository.AccountRecord> account = accountRepository.findActiveByEmail(request.email());
        String passwordHash = account.map(AccountRepository.AccountRecord::passwordHash)
                .orElse(dummyPasswordHash);
        boolean passwordMatches = passwordEncoder.matches(request.password(), passwordHash);
        if (account.isEmpty() || !passwordMatches) {
            throw new ResponseStatusException(UNAUTHORIZED, "Credenziali non valide");
        }
        AccountRepository.AccountRecord authenticated = account.orElseThrow();
        return toAuthenticatedSession(authenticated);
    }

    public Optional<AuthenticatedSession> findActiveSession(int id) {
        return accountRepository.findActiveById(id).map(AuthService::toAuthenticatedSession);
    }

    static String credentialFingerprint(String passwordHash) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(passwordHash.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 non disponibile", exception);
        }
    }

    private static AuthenticatedSession toAuthenticatedSession(AccountRepository.AccountRecord account) {
        return new AuthenticatedSession(account.toUser(), credentialFingerprint(account.passwordHash()));
    }

    public record AuthenticatedSession(AuthenticatedUser user, String credentialFingerprint) {
    }
}

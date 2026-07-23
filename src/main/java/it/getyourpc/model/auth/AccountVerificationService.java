package it.getyourpc.model.auth;

import it.getyourpc.mail.MailjetClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
public class AccountVerificationService {
    static final String REGISTER = "register";
    static final String FORGOT_PASSWORD = "forgot_password";
    static final String CHANGE_PASSWORD = "change_password";
    static final String CHANGE_EMAIL = "change_email";
    static final int EXPIRES_IN_SECONDS = 600;
    private static final int MAXIMUM_ATTEMPTS = 5;

    private final AccountRepository accountRepository;
    private final VerificationRepository verificationRepository;
    private final MailjetClient mailjetClient;
    private final ForgotPasswordTimingGuard forgotPasswordTimingGuard;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);
    private final String dummyCodeHash = passwordEncoder.encode("00000");
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public AccountVerificationService(AccountRepository accountRepository,
                                      VerificationRepository verificationRepository,
                                      MailjetClient mailjetClient,
                                      ForgotPasswordTimingGuard forgotPasswordTimingGuard) {
        this.accountRepository = accountRepository;
        this.verificationRepository = verificationRepository;
        this.mailjetClient = mailjetClient;
        this.forgotPasswordTimingGuard = forgotPasswordTimingGuard;
    }

    AccountVerificationService(AccountRepository accountRepository,
                               VerificationRepository verificationRepository,
                               MailjetClient mailjetClient) {
        this(accountRepository, verificationRepository, mailjetClient, new ForgotPasswordTimingGuard(0));
    }

    public VerificationStarted startRegistration(RegisterStartRequest request) {
        String email = AccountCredentials.normalizeEmail(request.email());
        String name = AccountCredentials.normalizeName(request.name(), "Inserisci il nome");
        String surname = AccountCredentials.normalizeName(request.surname(), "Inserisci il cognome");
        AccountCredentials.validatePassword(request.password(), email);
        if (accountRepository.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email già registrata");
        }
        String code = newCode();
        replace(new VerificationRepository.VerificationDraft(
                REGISTER, null, email, name, surname, passwordEncoder.encode(request.password()),
                passwordEncoder.encode(code), expiresAt()));
        return sendCode(email, code, "completare la registrazione");
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public AuthenticatedUser confirmRegistration(RegisterConfirmRequest request) {
        String email = AccountCredentials.normalizeEmail(request.email());
        VerificationRepository.VerificationRecord verification = requireValid(
                verificationRepository.findForEmailForUpdate(REGISTER, email), request.code());
        accountRepository.lockEmail(email);
        if (accountRepository.existsByEmail(email)) {
            verificationRepository.delete(verification.id());
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email già registrata");
        }
        int userId = accountRepository.createActive(verification.name(), verification.surname(),
                verification.email(), verification.passwordHash());
        verificationRepository.delete(verification.id());
        return accountRepository.findActiveUserById(userId).orElseThrow(this::inactiveAccount);
    }

    public VerificationStarted startForgotPassword(ForgotPasswordStartRequest request) {
        long startedAt = forgotPasswordTimingGuard.start();
        String email = AccountCredentials.normalizeEmail(request.email());
        try {
            Optional<AccountRepository.AccountRecord> account = accountRepository.findActiveByEmail(email);
            String code = newCode();
            String codeHash = passwordEncoder.encode(code);
            if (account.isPresent()) {
                replace(new VerificationRepository.VerificationDraft(
                        FORGOT_PASSWORD, account.get().id(), account.get().email(), null, null, null,
                        codeHash, expiresAt()));
                sendCode(account.get().email(), code, "reimpostare la password");
            }
            // Lo stato di consegna resta volutamente indistinguibile tra account esistenti e assenti.
            return started(email, false);
        } finally {
            forgotPasswordTimingGuard.awaitMinimumDuration(startedAt);
        }
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public void confirmForgotPassword(ForgotPasswordConfirmRequest request) {
        String email = AccountCredentials.normalizeEmail(request.email());
        VerificationRepository.VerificationRecord verification = requireValid(
                verificationRepository.findForEmailForUpdate(FORGOT_PASSWORD, email), request.code());
        Optional<AccountRepository.AccountRecord> foundAccount =
                accountRepository.findActiveById(verification.userId());
        if (foundAccount.isEmpty()) {
            verificationRepository.delete(verification.id());
            throw invalidCode();
        }
        AccountRepository.AccountRecord account = foundAccount.orElseThrow();
        AccountCredentials.validatePassword(request.newPassword(), account.email());
        rejectUnchangedPassword(request.newPassword(), account.passwordHash());
        if (!accountRepository.updatePassword(account.id(), passwordEncoder.encode(request.newPassword()))) {
            throw inactiveAccount();
        }
        verificationRepository.delete(verification.id());
    }

    public VerificationStarted startPasswordChange(AuthenticatedUser user,
                                                    PasswordChangeStartRequest request) {
        AccountRepository.AccountRecord account = requireCurrentPassword(user.id(), request.currentPassword());
        String code = newCode();
        replace(new VerificationRepository.VerificationDraft(
                CHANGE_PASSWORD, account.id(), account.email(), null, null, null,
                passwordEncoder.encode(code), expiresAt()));
        return sendCode(account.email(), code, "cambiare la password");
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public void confirmPasswordChange(AuthenticatedUser user, PasswordChangeConfirmRequest request) {
        VerificationRepository.VerificationRecord verification = requireValid(
                verificationRepository.findForUserForUpdate(CHANGE_PASSWORD, user.id()), request.code());
        Optional<AccountRepository.AccountRecord> foundAccount = accountRepository.findActiveById(user.id());
        if (foundAccount.isEmpty()) {
            verificationRepository.delete(verification.id());
            throw inactiveAccount();
        }
        AccountRepository.AccountRecord account = foundAccount.orElseThrow();
        AccountCredentials.validatePassword(request.newPassword(), account.email());
        rejectUnchangedPassword(request.newPassword(), account.passwordHash());
        if (!accountRepository.updatePassword(user.id(), passwordEncoder.encode(request.newPassword()))) {
            throw inactiveAccount();
        }
        verificationRepository.delete(verification.id());
    }

    public VerificationStarted startEmailChange(AuthenticatedUser user, EmailChangeStartRequest request) {
        AccountRepository.AccountRecord account = requireCurrentPassword(user.id(), request.currentPassword());
        String newEmail = AccountCredentials.normalizeEmail(request.newEmail());
        if (newEmail.equalsIgnoreCase(account.email())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La nuova email coincide con quella attuale");
        }
        if (accountRepository.existsByEmail(newEmail)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email già registrata");
        }
        String code = newCode();
        replace(new VerificationRepository.VerificationDraft(
                CHANGE_EMAIL, account.id(), newEmail, null, null, null,
                passwordEncoder.encode(code), expiresAt()));
        return sendCode(newEmail, code, "confermare la nuova email");
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public AuthenticatedUser confirmEmailChange(AuthenticatedUser user, VerificationCodeRequest request) {
        VerificationRepository.VerificationRecord verification = requireValid(
                verificationRepository.findForUserForUpdate(CHANGE_EMAIL, user.id()), request.code());
        accountRepository.lockEmail(verification.email());
        if (accountRepository.existsByEmail(verification.email())) {
            verificationRepository.delete(verification.id());
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email già registrata");
        }
        if (!accountRepository.updateEmail(user.id(), verification.email())) {
            throw inactiveAccount();
        }
        verificationRepository.delete(verification.id());
        return accountRepository.findActiveUserById(user.id()).orElseThrow(this::inactiveAccount);
    }

    private AccountRepository.AccountRecord requireCurrentPassword(int userId, String password) {
        AccountRepository.AccountRecord account = accountRepository.findActiveById(userId)
                .orElseThrow(this::inactiveAccount);
        if (password == null || password.getBytes(StandardCharsets.UTF_8).length > 72
                || !passwordEncoder.matches(password, account.passwordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password attuale non corretta");
        }
        return account;
    }

    private VerificationRepository.VerificationRecord requireValid(
            Optional<VerificationRepository.VerificationRecord> found, String code) {
        if (found.isEmpty()) {
            passwordEncoder.matches(code, dummyCodeHash);
            throw invalidCode();
        }
        VerificationRepository.VerificationRecord verification = found.orElseThrow();
        if (!verification.expiresAt().isAfter(Instant.now())
                || verification.attempts() >= MAXIMUM_ATTEMPTS) {
            verificationRepository.delete(verification.id());
            throw invalidCode();
        }
        if (!passwordEncoder.matches(code, verification.codeHash())) {
            if (verification.attempts() + 1 >= MAXIMUM_ATTEMPTS) {
                verificationRepository.delete(verification.id());
            } else {
                verificationRepository.incrementAttempts(verification.id());
            }
            throw invalidCode();
        }
        return verification;
    }

    private void rejectUnchangedPassword(String password, String currentHash) {
        if (passwordEncoder.matches(password, currentHash)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La nuova password deve essere diversa da quella attuale");
        }
    }

    private void replace(VerificationRepository.VerificationDraft draft) {
        verificationRepository.replace(draft);
    }

    private String newCode() {
        return "%05d".formatted(secureRandom.nextInt(100_000));
    }

    private static Instant expiresAt() {
        return Instant.now().plus(Duration.ofSeconds(EXPIRES_IN_SECONDS));
    }

    private VerificationStarted sendCode(String email, String code, String operation) {
        try {
            mailjetClient.sendVerificationCode(email, code, operation);
            return started(email, true);
        } catch (ResponseStatusException exception) {
            int status = exception.getStatusCode().value();
            if (status == HttpStatus.BAD_GATEWAY.value()
                    || status == HttpStatus.SERVICE_UNAVAILABLE.value()) {
                return started(email, false);
            }
            throw exception;
        }
    }

    private static VerificationStarted started(String email, boolean deliveryConfirmed) {
        return new VerificationStarted(email, EXPIRES_IN_SECONDS, deliveryConfirmed);
    }

    private ResponseStatusException invalidCode() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Codice non valido o scaduto");
    }

    private ResponseStatusException inactiveAccount() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account non disponibile");
    }
}

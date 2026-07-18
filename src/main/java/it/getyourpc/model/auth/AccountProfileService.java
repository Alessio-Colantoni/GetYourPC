package it.getyourpc.model.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;

@Service
public class AccountProfileService {
    private final AccountRepository accountRepository;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);

    public AccountProfileService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Transactional
    public AuthenticatedUser update(AuthenticatedUser user, ProfileUpdateRequest request) {
        String name = AccountCredentials.normalizeName(request.name(), "Inserisci il nome");
        String surname = AccountCredentials.normalizeName(request.surname(), "Inserisci il cognome");
        String phone = normalizePhone(request.phone());
        if (!accountRepository.updateProfile(user.id(), name, surname, phone)) throw missingAccount();
        return accountRepository.findActiveUserById(user.id()).orElseThrow(AccountProfileService::missingAccount);
    }

    @Transactional
    public void delete(AuthenticatedUser user, DeleteAccountRequest request) {
        AccountRepository.AccountRecord account = accountRepository.findActiveById(user.id())
                .orElseThrow(AccountProfileService::missingAccount);
        String password = request.currentPassword();
        if (password.getBytes(StandardCharsets.UTF_8).length > AccountCredentials.MAXIMUM_BCRYPT_BYTES
                || !passwordEncoder.matches(password, account.passwordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password attuale non corretta");
        }
        if (!accountRepository.deleteAccount(user.id())) throw missingAccount();
    }

    private static String normalizePhone(String phone) {
        if (phone == null || phone.isBlank()) return null;
        return phone.trim();
    }

    private static ResponseStatusException missingAccount() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account non più disponibile");
    }
}

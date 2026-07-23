package it.getyourpc.model.auth;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReviewerProvisioningService {
    private final AccountRepository accountRepository;

    public ReviewerProvisioningService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Transactional
    public AuthenticatedUser create(String name, String surname, String email, String passwordHash) {
        accountRepository.lockEmail(email);
        if (accountRepository.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Esiste già un account con questa email");
        }
        int id = accountRepository.createActiveWithRole(name, surname, email,
                passwordHash, SessionUserGuard.REVIEWER_ROLE);
        return accountRepository.findActiveUserById(id).orElseThrow(() ->
                new IllegalStateException("Reviewer creato ma non rileggibile"));
    }

    @Transactional
    public void discardUndelivered(int reviewerId) {
        if (!accountRepository.deleteReviewer(reviewerId)) {
            throw new IllegalStateException("Impossibile annullare il reviewer senza credenziali consegnate");
        }
    }
}

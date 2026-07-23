package it.getyourpc.model.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class BootstrapAccountInitializer implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(BootstrapAccountInitializer.class);
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final int MINIMUM_PASSWORD_LENGTH = 8;
    private static final int MAXIMUM_BCRYPT_BYTES = 72;

    private final AccountRepository accountRepository;
    private final Environment environment;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);

    public BootstrapAccountInitializer(AccountRepository accountRepository, Environment environment) {
        this.accountRepository = accountRepository;
        this.environment = environment;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!bootstrapEnabled()) {
            return;
        }

        String email = required("app.bootstrap-account.email", "BOOTSTRAP_ACCOUNT_EMAIL")
                .trim().toLowerCase(Locale.ROOT);
        String password = required("app.bootstrap-account.password", "BOOTSTRAP_ACCOUNT_PASSWORD");
        String name = required("app.bootstrap-account.name", "BOOTSTRAP_ACCOUNT_NAME").trim();
        String surname = required("app.bootstrap-account.surname", "BOOTSTRAP_ACCOUNT_SURNAME").trim();
        validate(email, password, name, surname);

        accountRepository.lockEmail(email);
        boolean created = accountRepository.createActiveIfAbsent(
                name, surname, email, passwordEncoder.encode(password));
        if (created) {
            LOGGER.warn("Account iniziale creato. Disabilitare subito il bootstrap e rimuovere la password dall'ambiente.");
        }
    }

    private boolean bootstrapEnabled() {
        String configured = environment.getProperty("app.bootstrap-account.enabled", "false").trim();
        if (configured.equalsIgnoreCase("true")) {
            return true;
        }
        if (configured.equalsIgnoreCase("false")) {
            return false;
        }
        throw new IllegalStateException("BOOTSTRAP_ACCOUNT_ENABLED deve essere true oppure false");
    }

    private String required(String property, String environmentVariable) {
        String value = environment.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(environmentVariable + " e' obbligatoria quando il bootstrap e' abilitato");
        }
        return value;
    }

    private void validate(String email, String password, String name, String surname) {
        if (email.length() > 255 || !EMAIL_PATTERN.matcher(email).matches()) {
            throw new IllegalStateException("BOOTSTRAP_ACCOUNT_EMAIL non e' un indirizzo email valido");
        }
        if (name.length() > 255 || surname.length() > 255) {
            throw new IllegalStateException("Nome e cognome del bootstrap non possono superare 255 caratteri");
        }
        int passwordBytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (password.length() < MINIMUM_PASSWORD_LENGTH) {
            throw new IllegalStateException("BOOTSTRAP_ACCOUNT_PASSWORD deve contenere almeno 8 caratteri");
        }
        if (passwordBytes > MAXIMUM_BCRYPT_BYTES) {
            throw new IllegalStateException("BOOTSTRAP_ACCOUNT_PASSWORD non puo' superare 72 byte UTF-8");
        }
        if (password.equalsIgnoreCase(email)) {
            throw new IllegalStateException("BOOTSTRAP_ACCOUNT_PASSWORD non puo' coincidere con l'email");
        }
    }
}

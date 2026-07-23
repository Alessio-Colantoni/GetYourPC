package it.getyourpc.model.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordCompatibilityTest {
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Test
    void demoPasswordUsesCompatibleBcryptHash() {
        assertThat(encoder.matches("password1",
                "$2y$10$yIR790UTQfEMLAs9.qmPDuFJ1y.6eD9qxrvjkUwLhWwvT9tHk23u."))
                .isTrue();
    }

    @Test
    void wrongPasswordDoesNotMatch() {
        assertThat(encoder.matches("wrong-password",
                "$2y$10$yIR790UTQfEMLAs9.qmPDuFJ1y.6eD9qxrvjkUwLhWwvT9tHk23u."))
                .isFalse();
    }
}

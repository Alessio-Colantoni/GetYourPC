package it.getyourpc.model.auth;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class AccountCredentialsTest {

    @Test
    void acceptsEightCharactersAndRejectsSeven() {
        assertDoesNotThrow(() -> AccountCredentials.validatePassword("password", "user@example.com"));
        assertThatThrownBy(() -> AccountCredentials.validatePassword("1234567", "user@example.com"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("almeno 8 caratteri");
    }
}

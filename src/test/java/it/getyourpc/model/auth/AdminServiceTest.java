package it.getyourpc.model.auth;

import it.getyourpc.mail.MailjetClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminServiceTest {
    private final ReviewerProvisioningService provisioning = mock(ReviewerProvisioningService.class);
    private final MailjetClient mailjet = mock(MailjetClient.class);
    private final AdminService service = new AdminService(provisioning, mailjet);

    @Test
    void createsAReviewerAndEmailsTheGeneratedPassword() {
        AuthenticatedUser admin = user("admin");
        AuthenticatedUser reviewer = new AuthenticatedUser(
                12, "Rita", "Reviewer", "reviewer", "rita@example.com", null);
        when(provisioning.create(eq("Rita"), eq("Reviewer"), eq("rita@example.com"), anyString()))
                .thenReturn(reviewer);

        assertThat(service.createReviewer(admin,
                new CreateReviewerRequest(" Rita ", " Reviewer ", "RITA@example.com")))
                .isEqualTo(reviewer);

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> password = ArgumentCaptor.forClass(String.class);
        verify(provisioning).create(eq("Rita"), eq("Reviewer"), eq("rita@example.com"),
                hash.capture());
        verify(mailjet).sendReviewerCredentials(eq("rita@example.com"), password.capture());
        assertThat(password.getValue()).hasSize(20);
        assertThat(new BCryptPasswordEncoder().matches(password.getValue(), hash.getValue())).isTrue();
    }

    @Test
    void rejectsNonAdministratorsBeforeAccessingPersistenceOrMail() {
        assertThatThrownBy(() -> service.createReviewer(user("user"),
                new CreateReviewerRequest("Rita", "Reviewer", "rita@example.com")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");

        verifyNoInteractions(provisioning, mailjet);
    }

    @Test
    void removesTheNewReviewerWhenMailDeliveryFails() {
        AuthenticatedUser reviewer = new AuthenticatedUser(
                12, "Rita", "Reviewer", "reviewer", "rita@example.com", null);
        when(provisioning.create(eq("Rita"), eq("Reviewer"), eq("rita@example.com"), anyString()))
                .thenReturn(reviewer);
        RuntimeException mailFailure = new RuntimeException("Mailjet non disponibile");
        doThrow(mailFailure).when(mailjet).sendReviewerCredentials(eq("rita@example.com"), anyString());

        assertThatThrownBy(() -> service.createReviewer(user("admin"),
                new CreateReviewerRequest("Rita", "Reviewer", "rita@example.com")))
                .isSameAs(mailFailure);

        verify(provisioning).discardUndelivered(12);
    }

    private static AuthenticatedUser user(String role) {
        return new AuthenticatedUser(1, "Ada", "Admin", role, "ada@example.com", null);
    }
}

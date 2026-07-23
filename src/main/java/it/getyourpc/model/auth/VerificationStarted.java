package it.getyourpc.model.auth;

public record VerificationStarted(String email, int expiresInSeconds, boolean deliveryConfirmed) {
    public VerificationStarted(String email, int expiresInSeconds) {
        this(email, expiresInSeconds, true);
    }
}

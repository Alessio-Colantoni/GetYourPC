package it.getyourpc.model.auth;

import java.io.Serializable;

public record AuthenticatedUser(int id, String name, String surname, String role, String email, String phone)
        implements Serializable {
}

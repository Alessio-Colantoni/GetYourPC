package it.getyourpc.model.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
public class VerificationRepository {
    private final JdbcClient jdbcClient;

    public VerificationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public int deleteExpired() {
        return jdbcClient.sql("DELETE FROM AccountVerification WHERE expires_at <= CURRENT_TIMESTAMP").update();
    }

    public void replace(VerificationDraft draft) {
        if (draft.userId() == null) {
            jdbcClient.sql("""
                            INSERT INTO AccountVerification
                                (purpose, id_user, email, name, surname, password_hash,
                                 code_hash, expires_at, attempts)
                            VALUES
                                (:purpose, NULL, :email, :name, :surname, :passwordHash,
                                 :codeHash, :expiresAt, 0)
                            ON CONFLICT (purpose, (LOWER(email))) WHERE id_user IS NULL
                            DO UPDATE SET
                                email = EXCLUDED.email,
                                name = EXCLUDED.name,
                                surname = EXCLUDED.surname,
                                password_hash = EXCLUDED.password_hash,
                                code_hash = EXCLUDED.code_hash,
                                expires_at = EXCLUDED.expires_at,
                                attempts = 0,
                                created_at = CURRENT_TIMESTAMP
                            """)
                    .param("purpose", draft.purpose())
                    .param("email", draft.email())
                    .param("name", draft.name())
                    .param("surname", draft.surname())
                    .param("passwordHash", draft.passwordHash())
                    .param("codeHash", draft.codeHash())
                    .param("expiresAt", Timestamp.from(draft.expiresAt()))
                    .update();
        } else {
            jdbcClient.sql("""
                            INSERT INTO AccountVerification
                                (purpose, id_user, email, name, surname, password_hash,
                                 code_hash, expires_at, attempts)
                            VALUES
                                (:purpose, :userId, :email, :name, :surname, :passwordHash,
                                 :codeHash, :expiresAt, 0)
                            ON CONFLICT (purpose, id_user) WHERE id_user IS NOT NULL
                            DO UPDATE SET
                                email = EXCLUDED.email,
                                name = EXCLUDED.name,
                                surname = EXCLUDED.surname,
                                password_hash = EXCLUDED.password_hash,
                                code_hash = EXCLUDED.code_hash,
                                expires_at = EXCLUDED.expires_at,
                                attempts = 0,
                                created_at = CURRENT_TIMESTAMP
                            """)
                    .param("purpose", draft.purpose())
                    .param("userId", draft.userId())
                    .param("email", draft.email())
                    .param("name", draft.name())
                    .param("surname", draft.surname())
                    .param("passwordHash", draft.passwordHash())
                    .param("codeHash", draft.codeHash())
                    .param("expiresAt", Timestamp.from(draft.expiresAt()))
                    .update();
        }
    }

    public Optional<VerificationRecord> findForEmailForUpdate(String purpose, String email) {
        return jdbcClient.sql("""
                        SELECT id_verification, purpose, id_user, email, name, surname,
                               password_hash, code_hash, expires_at, attempts
                        FROM AccountVerification
                        WHERE purpose = :purpose AND LOWER(email) = LOWER(:email)
                        ORDER BY id_verification DESC
                        LIMIT 1
                        FOR UPDATE
                        """)
                .param("purpose", purpose)
                .param("email", email)
                .query(this::map)
                .optional();
    }

    public Optional<VerificationRecord> findForUserForUpdate(String purpose, int userId) {
        return jdbcClient.sql("""
                        SELECT id_verification, purpose, id_user, email, name, surname,
                               password_hash, code_hash, expires_at, attempts
                        FROM AccountVerification
                        WHERE purpose = :purpose AND id_user = :userId
                        ORDER BY id_verification DESC
                        LIMIT 1
                        FOR UPDATE
                        """)
                .param("purpose", purpose)
                .param("userId", userId)
                .query(this::map)
                .optional();
    }

    public void incrementAttempts(long verificationId) {
        jdbcClient.sql("""
                        UPDATE AccountVerification SET attempts = attempts + 1
                        WHERE id_verification = :verificationId
                        """)
                .param("verificationId", verificationId)
                .update();
    }

    public void delete(long verificationId) {
        jdbcClient.sql("DELETE FROM AccountVerification WHERE id_verification = :verificationId")
                .param("verificationId", verificationId)
                .update();
    }

    private VerificationRecord map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new VerificationRecord(
                rs.getLong("id_verification"), rs.getString("purpose"),
                (Integer) rs.getObject("id_user"), rs.getString("email"),
                rs.getString("name"), rs.getString("surname"), rs.getString("password_hash"),
                rs.getString("code_hash"), rs.getTimestamp("expires_at").toInstant(),
                rs.getInt("attempts"));
    }

    public record VerificationDraft(String purpose, Integer userId, String email, String name,
                                    String surname, String passwordHash, String codeHash,
                                    Instant expiresAt) {
    }

    public record VerificationRecord(long id, String purpose, Integer userId, String email,
                                     String name, String surname, String passwordHash,
                                     String codeHash, Instant expiresAt, int attempts) {
    }
}

package it.getyourpc.model.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class AccountRepository {
    private final JdbcClient jdbcClient;

    public AccountRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<AccountRecord> findActiveByEmail(String email) {
        return jdbcClient.sql("""
                        SELECT id_user, name, surname, role, email, phone, password
                        FROM Users
                        WHERE LOWER(email) = LOWER(:email) AND status = 'active'
                        """)
                .param("email", email.trim())
                .query((rs, rowNum) -> new AccountRecord(
                        rs.getInt("id_user"), rs.getString("name"), rs.getString("surname"),
                        rs.getString("role"), rs.getString("email"), rs.getString("phone"),
                        rs.getString("password")))
                .optional();
    }

    public Optional<AccountRecord> findActiveById(int id) {
        return jdbcClient.sql("""
                        SELECT id_user, name, surname, role, email, phone, password
                        FROM Users
                        WHERE id_user = :id AND status = 'active'
                        """)
                .param("id", id)
                .query((rs, rowNum) -> new AccountRecord(
                        rs.getInt("id_user"), rs.getString("name"), rs.getString("surname"),
                        rs.getString("role"), rs.getString("email"), rs.getString("phone"),
                        rs.getString("password")))
                .optional();
    }

    public boolean existsByEmail(String email) {
        return jdbcClient.sql("SELECT COUNT(*) FROM Users WHERE LOWER(email) = LOWER(:email)")
                .param("email", email)
                .query(Integer.class)
                .single() > 0;
    }

    public void lockEmail(String email) {
        jdbcClient.sql("SELECT pg_advisory_xact_lock(hashtextextended(LOWER(:email), 0))")
                .param("email", email)
                .query((resultSet, rowNumber) -> Boolean.TRUE)
                .single();
    }

    public Optional<AuthenticatedUser> findActiveUserById(int id) {
        return jdbcClient.sql("""
                        SELECT id_user, name, surname, role, email, phone
                        FROM Users
                        WHERE id_user = :id AND status = 'active'
                        """)
                .param("id", id)
                .query((rs, rowNum) -> new AuthenticatedUser(
                        rs.getInt("id_user"), rs.getString("name"), rs.getString("surname"),
                        rs.getString("role"), rs.getString("email"), rs.getString("phone")))
                .optional();
    }

    public boolean createActiveIfAbsent(String name, String surname, String email, String passwordHash) {
        int insertedRows = jdbcClient.sql("""
                        INSERT INTO Users (name, surname, role, email, password, status)
                        VALUES (:name, :surname, 'user', :email, :password, 'active')
                        ON CONFLICT (LOWER(email)) DO NOTHING
                        """)
                .param("name", name)
                .param("surname", surname)
                .param("email", email)
                .param("password", passwordHash)
                .update();
        return insertedRows == 1;
    }

    public int createActive(String name, String surname, String email, String passwordHash) {
        return createActiveWithRole(name, surname, email, passwordHash, SessionUserGuard.USER_ROLE);
    }

    public int createActiveWithRole(String name, String surname, String email,
                                    String passwordHash, String role) {
        return jdbcClient.sql("""
                        INSERT INTO Users (name, surname, role, email, password, status)
                        VALUES (:name, :surname, :role, :email, :password, 'active')
                        RETURNING id_user
                        """)
                .param("name", name)
                .param("surname", surname)
                .param("email", email)
                .param("password", passwordHash)
                .param("role", role)
                .query(Integer.class)
                .single();
    }

    public boolean updatePassword(int userId, String passwordHash) {
        return jdbcClient.sql("""
                        UPDATE Users
                        SET password = :password
                        WHERE id_user = :userId AND status = 'active'
                        """)
                .param("password", passwordHash)
                .param("userId", userId)
                .update() == 1;
    }

    public boolean updateEmail(int userId, String email) {
        return jdbcClient.sql("""
                        UPDATE Users SET email = :email
                        WHERE id_user = :userId AND status = 'active'
                        """)
                .param("email", email)
                .param("userId", userId)
                .update() == 1;
    }

    public boolean updateProfile(int userId, String name, String surname, String phone) {
        return jdbcClient.sql("""
                        UPDATE Users SET name = :name, surname = :surname, phone = :phone
                        WHERE id_user = :userId AND status = 'active'
                        """)
                .param("name", name)
                .param("surname", surname)
                .param("phone", phone)
                .param("userId", userId)
                .update() == 1;
    }

    public boolean deleteAccount(int userId) {
        jdbcClient.sql("DELETE FROM PostGeneralInfo WHERE id_user = :userId")
                .param("userId", userId).update();
        return jdbcClient.sql("DELETE FROM Users WHERE id_user = :userId AND status = 'active'")
                .param("userId", userId).update() == 1;
    }

    public boolean deleteReviewer(int userId) {
        return jdbcClient.sql("""
                        DELETE FROM Users
                        WHERE id_user = :userId AND role = 'reviewer'
                        """)
                .param("userId", userId)
                .update() == 1;
    }

    record AccountRecord(int id, String name, String surname, String role, String email,
                         String phone, String passwordHash) {
        AuthenticatedUser toUser() {
            return new AuthenticatedUser(id, name, surname, role, email, phone);
        }
    }
}

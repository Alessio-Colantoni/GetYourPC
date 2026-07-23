package it.getyourpc;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ApplicationContextPostgresTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("JDBC_DATABASE_URL", POSTGRES::getJdbcUrl);
        registry.add("DB_USERNAME", POSTGRES::getUsername);
        registry.add("DB_PASSWORD", POSTGRES::getPassword);
        registry.add("app.maintenance.cleanup-cron", () -> "-");
        registry.add("spring.session.jdbc.cleanup-cron", () -> "-");
    }

    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcClient jdbcClient;
    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void prepareDatabase() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("db.sql"));
        }
        jdbcClient.sql("""
                        TRUNCATE TABLE SPRING_SESSION_ATTRIBUTES, SPRING_SESSION,
                            RequestRateLimit, AccountVerification, Users
                        RESTART IDENTITY CASCADE
                        """).update();
        jdbcClient.sql("""
                        INSERT INTO Users (name, surname, role, email, password, status)
                        VALUES ('Ada', 'Lovelace', 'user', 'ada@example.com', :password, 'active')
                        """)
                .param("password", new BCryptPasswordEncoder(4).encode("password-sicura"))
                .update();
    }

    @Test
    void applicationStartsAndPersistsLoginSessionInPostgres() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/favicon.svg"))
                .andExpect(status().isOk());

        Cookie sessionCookie = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("""
                                {"email":"ada@example.com","password":"password-sicura"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("ada@example.com"))
                .andReturn().getResponse().getCookie("SESSION");

        assertThat(sessionCookie).isNotNull();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM SPRING_SESSION")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                        SELECT requests FROM RequestRateLimit
                        WHERE scope = 'login' AND client_id = '127.0.0.1'
                        """).query(Integer.class).single()).isEqualTo(1);

        mockMvc.perform(get("/api/auth/me").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Ada"));
    }
}

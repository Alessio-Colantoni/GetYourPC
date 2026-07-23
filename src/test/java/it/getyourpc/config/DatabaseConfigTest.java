package it.getyourpc.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseConfigTest {
    @Test
    void acceptsAivenPostgresqlUriWithEmbeddedCredentials() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("JDBC_DATABASE_URL",
                        "postgresql://avnadmin:p%40ss@pg.example.aivencloud.com:12345/defaultdb?sslmode=require");

        DatabaseConfig.DatabaseSettings settings = DatabaseConfig.DatabaseSettings.from(environment);

        assertThat(settings.jdbcUrl())
                .isEqualTo("jdbc:postgresql://pg.example.aivencloud.com:12345/defaultdb?sslmode=require");
        assertThat(settings.username()).isEqualTo("avnadmin");
        assertThat(settings.password()).isEqualTo("p@ss");
    }

    @Test
    void acceptsRenderPostgresAliasAndSeparateCredentials() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("DATABASE_URL", "postgres://internal-host/getyourpc")
                .withProperty("DB_USERNAME", "app_user")
                .withProperty("DB_PASSWORD", "secret")
                .withProperty("DB_POOL_SIZE", "8");

        DatabaseConfig.DatabaseSettings settings = DatabaseConfig.DatabaseSettings.from(environment);

        assertThat(settings.jdbcUrl())
                .isEqualTo("jdbc:postgresql://internal-host/getyourpc?sslmode=require");
        assertThat(settings.username()).isEqualTo("app_user");
        assertThat(settings.password()).isEqualTo("secret");
        assertThat(settings.poolSize()).isEqualTo(8);
    }

    @Test
    void acceptsAnExistingJdbcUrl() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("JDBC_DATABASE_URL", "jdbc:postgresql://localhost:5432/getyourpc")
                .withProperty("DB_USERNAME", "postgres")
                .withProperty("DB_PASSWORD", "postgres");

        assertThat(DatabaseConfig.DatabaseSettings.from(environment).jdbcUrl())
                .isEqualTo("jdbc:postgresql://localhost:5432/getyourpc");
    }

    @Test
    void rejectsMissingDatabaseUrl() {
        assertThatThrownBy(() -> DatabaseConfig.DatabaseSettings.from(new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DATABASE_URL");
    }

    @Test
    void addsTlsToRemotePostgresUrls() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("JDBC_DATABASE_URL", "postgresql://user:password@pg.example.com:5432/getyourpc");

        assertThat(DatabaseConfig.DatabaseSettings.from(environment).jdbcUrl())
                .isEqualTo("jdbc:postgresql://pg.example.com:5432/getyourpc?sslmode=require");
    }

    @Test
    void rejectsInsecureRemotePostgresUrls() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("JDBC_DATABASE_URL",
                        "postgresql://user:password@pg.example.com:5432/getyourpc?sslmode=disable");

        assertThatThrownBy(() -> DatabaseConfig.DatabaseSettings.from(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sslmode");
    }
}

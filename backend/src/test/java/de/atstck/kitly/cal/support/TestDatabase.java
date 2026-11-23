package de.atstck.kitly.cal.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A disposable PostgreSQL for tests. Looked up in this order:
 * <ol>
 *   <li>{@code KITLY_CAL_TEST_DB_URL} (with {@code KITLY_CAL_TEST_DB_USER} / {@code KITLY_CAL_TEST_DB_PASSWORD}),
 *       e.g. the database of {@code docker compose up -d db};</li>
 *   <li>a Testcontainers PostgreSQL when Docker is available;</li>
 *   <li>otherwise the calling test is skipped.</li>
 * </ol>
 * The tests only ever touch the schema {@link #SCHEMA}, which they drop and recreate.
 */
public final class TestDatabase {

    public static final String SCHEMA = "kitly_cal_test";

    public record Coordinates(String url, String user, String password) {
    }

    private static Coordinates coordinates;
    private static boolean migrated;

    private TestDatabase() {
    }

    /** Coordinates of the test database, with a freshly migrated schema. Skips the test when there is none. */
    public static synchronized Coordinates require() {
        if (coordinates == null) {
            coordinates = locate();
        }
        if (!migrated) {
            Flyway flyway = Flyway.configure()
                    .dataSource(coordinates.url(), coordinates.user(), coordinates.password())
                    .schemas(SCHEMA)
                    .defaultSchema(SCHEMA)
                    .locations("classpath:db/migration")
                    .cleanDisabled(false)
                    .load();
            flyway.clean();
            flyway.migrate();
            migrated = true;
        }
        return coordinates;
    }

    /** For {@code @EnabledIf}: true when a test database can be had. */
    public static boolean available() {
        String url = System.getenv("KITLY_CAL_TEST_DB_URL");
        if (url != null && !url.isBlank()) {
            return true;
        }
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** A small pool on the test schema. Close it after use. */
    public static HikariDataSource dataSource() {
        Coordinates target = require();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(target.url());
        config.setUsername(target.user());
        config.setPassword(target.password());
        config.setSchema(SCHEMA);
        config.setMaximumPoolSize(10);
        return new HikariDataSource(config);
    }

    private static Coordinates locate() {
        String url = System.getenv("KITLY_CAL_TEST_DB_URL");
        if (url != null && !url.isBlank()) {
            return new Coordinates(url,
                    System.getenv().getOrDefault("KITLY_CAL_TEST_DB_USER", "postgres"),
                    System.getenv().getOrDefault("KITLY_CAL_TEST_DB_PASSWORD", "postgres"));
        }
        boolean docker;
        try {
            docker = DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            docker = false;
        }
        Assumptions.assumeTrue(docker, "No test database: set KITLY_CAL_TEST_DB_URL or start Docker for Testcontainers.");
        PostgreSQLContainer container = new PostgreSQLContainer("postgres:16-alpine");
        container.start();
        return new Coordinates(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }
}

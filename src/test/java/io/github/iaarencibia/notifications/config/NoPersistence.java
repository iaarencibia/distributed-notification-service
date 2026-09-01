package io.github.iaarencibia.notifications.config;

/**
 * Autoconfiguration the security tests switch off.
 *
 * <p>Persistence is excluded rather than mocked. No path through the security chain reaches a
 * database, so loading one would only make an unrelated failure -- a broken migration, say --
 * surface as a red authentication test. It also keeps these tests runnable through
 * {@code docker compose run --rm test}, which has no access to a Docker daemon of its own.
 *
 * <p>Shared by the two security test classes so the list cannot drift between them.
 */
final class NoPersistence {

    static final String EXCLUDE = "spring.autoconfigure.exclude="
            + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration";

    /** The configured key, shared so a test cannot assert against a value the context never saw. */
    static final String API_KEY = "a-key-only-this-test-knows";

    static final String API_KEY_PROPERTY = "notifications.security.api-key=" + API_KEY;

    private NoPersistence() {
    }
}

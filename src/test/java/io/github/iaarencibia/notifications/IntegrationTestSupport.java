package io.github.iaarencibia.notifications;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A real PostgreSQL and the credential to get past the security chain, shared by every test that
 * boots the application.
 *
 * <p>Not a home for shared assertions or fixtures: a base class that accumulates those makes every
 * test unreadable without its ancestors.
 */
public abstract class IntegrationTestSupport {

    // Static and without @Container on purpose: that annotation stops the container when its own
    // class finishes, and the next class would find a dead one. The reaper removes it at exit.
    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    /** Shared so a test cannot assert against a key the context never saw. */
    public static final String API_KEY = "a-key-only-this-test-knows";

    public static final String API_KEY_PROPERTY = "notifications.security.api-key=" + API_KEY;
}

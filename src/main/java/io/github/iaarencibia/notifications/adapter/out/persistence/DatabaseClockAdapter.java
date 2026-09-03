package io.github.iaarencibia.notifications.adapter.out.persistence;

import io.github.iaarencibia.notifications.application.port.out.SystemClock;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * The clock, read from PostgreSQL.
 *
 * <p>It is here, beside the statements, because that is where the clock is: the whole point of the
 * port is that the time comes from the database every instance shares rather than from the machine
 * each one happens to be running on.
 */
@Component
class DatabaseClockAdapter implements SystemClock {

    private final NotificationJpaStore store;

    DatabaseClockAdapter(NotificationJpaStore store) {
        this.store = store;
    }

    @Override
    public Instant now() {
        return store.databaseNow();
    }
}

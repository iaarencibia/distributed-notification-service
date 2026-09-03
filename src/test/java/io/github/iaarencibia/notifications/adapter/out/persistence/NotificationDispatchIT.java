package io.github.iaarencibia.notifications.adapter.out.persistence;

import io.github.iaarencibia.notifications.IntegrationTestSupport;
import io.github.iaarencibia.notifications.application.port.out.AbandonedClaims;
import io.github.iaarencibia.notifications.application.port.out.ClaimLostException;
import io.github.iaarencibia.notifications.application.port.out.ClaimedNotification;
import io.github.iaarencibia.notifications.application.port.out.NotificationDispatchQueue;
import io.github.iaarencibia.notifications.application.port.out.SystemClock;
import io.github.iaarencibia.notifications.domain.AttemptOutcome;
import io.github.iaarencibia.notifications.domain.Channel;
import io.github.iaarencibia.notifications.domain.DispatchOutcome;
import io.github.iaarencibia.notifications.domain.NotificationAttempt;
import io.github.iaarencibia.notifications.domain.NotificationStatus;
import io.github.iaarencibia.notifications.domain.Priority;
import io.github.iaarencibia.notifications.domain.RetryPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Claiming, recording and reaping, against the real schema.
 *
 * <p>Everything under test here is the database's work rather than Java's: {@code SKIP LOCKED},
 * the order the partial index serves rows in, the optimistic lock that refuses a write from an
 * instance whose claim was taken away, and the clock all three are measured against. None of it
 * can be observed without a real PostgreSQL and a second connection.
 *
 * <p>This is the first test in the suite that queries the whole table rather than rows it looks up
 * by key, so it is also the first that has to start from an empty one -- the claim query does not
 * know which rows belong to which test.
 */
@SpringBootTest
class NotificationDispatchIT extends IntegrationTestSupport {

    private static final String INSTANCE = "notification-service-1";
    private static final String OTHER_INSTANCE = "notification-service-2";

    @Autowired
    private NotificationDispatchQueue queue;

    @Autowired
    private AbandonedClaims abandonedClaims;

    @Autowired
    private SystemClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void emptyTheTable() {
        // The attempts go with them: the foreign key cascades.
        jdbc.execute("TRUNCATE TABLE notification CASCADE");
    }

    @Test
    @DisplayName("claims what is due, highest priority first and longest overdue within it")
    void claimsInTheOrderTheIndexServes() {
        Instant now = clock.now();
        UUID lowPriority = insertPending(Priority.LOW, now.minusSeconds(300));
        UUID highPriority = insertPending(Priority.HIGH, now.minusSeconds(10));
        UUID mediumOverdue = insertPending(Priority.MEDIUM, now.minusSeconds(600));
        UUID mediumRecent = insertPending(Priority.MEDIUM, now.minusSeconds(5));

        List<ClaimedNotification> claimed = queue.claimDue(4, INSTANCE);

        assertThat(claimed.stream().map(each -> each.notification().id()))
                .as("priority decides first; within a priority, whoever has been due longest")
                .containsExactly(highPriority, mediumOverdue, mediumRecent, lowPriority);
    }

    @Test
    @DisplayName("leaves a notification alone until it is due")
    void doesNotClaimBeforeTheDueInstant() {
        // The whole retry schedule rests on this comparison. Claimed early, a backoff would be a
        // number written to a column that nothing enforces.
        insertPending(Priority.HIGH, clock.now().plusSeconds(600));

        assertThat(queue.claimDue(10, INSTANCE)).isEmpty();
    }

    @Test
    @DisplayName("marks what it claims as this instance's, timed by the database clock")
    void stampsTheClaim() {
        Instant beforeTheClaim = clock.now();
        UUID id = insertPending(Priority.MEDIUM, beforeTheClaim.minusSeconds(1));

        ClaimedNotification claimed = queue.claimDue(1, INSTANCE).getFirst();

        assertThat(claimed.notification().status()).isEqualTo(NotificationStatus.DISPATCHING);
        assertThat(claimed.notification().claimedBy()).isEqualTo(INSTANCE);
        assertThat(claimed.notification().claimedAt())
                .as("the reaper compares this against the database clock, so it must come from it")
                .isBetween(beforeTheThousandthOf(beforeTheClaim), clock.now());
        assertThat(claimed.version())
                .as("the claim moved the row, so the version it hands back is the one after it")
                .isEqualTo(1L);
        assertThat(statusOf(id)).isEqualTo("DISPATCHING");
    }

    @Test
    @DisplayName("passes over a row another transaction is holding instead of waiting for it")
    void skipsALockedRow() throws SQLException {
        // Without SKIP LOCKED a second instance would block on the first instance's rows and
        // dispatch them afterwards, one batch behind. With it, it takes different work. The lock
        // is taken on a connection of its own because that is the only way to have one.
        Instant due = clock.now().minusSeconds(60);
        UUID locked = insertPending(Priority.HIGH, due);
        UUID free = insertPending(Priority.LOW, due);

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            lockRow(holder, locked);

            List<ClaimedNotification> claimed = queue.claimDue(10, INSTANCE);

            assertThat(claimed.stream().map(each -> each.notification().id()))
                    .as("the locked row is skipped even though it sorts first")
                    .containsExactly(free);
            holder.rollback();
        }
    }

    @Test
    @DisplayName("writes the attempt and the new state of the notification together")
    void recordsTheAttemptAlongsideTheTransition() {
        insertPending(Priority.MEDIUM, clock.now().minusSeconds(1));
        ClaimedNotification claimed = queue.claimDue(1, INSTANCE).getFirst();

        NotificationAttempt attempt = deliver(claimed, new DispatchOutcome.Success(202));
        queue.recordAttempt(claimed, attempt);

        UUID id = claimed.notification().id();
        assertThat(statusOf(id)).isEqualTo("SENT");
        assertThat(jdbc.queryForObject(
                "SELECT attempts FROM notification WHERE id = ?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT claimed_by FROM notification WHERE id = ?", String.class, id))
                .as("a delivered notification is nobody's any more")
                .isNull();
        assertThat(jdbc.queryForObject("SELECT outcome FROM notification_attempt "
                + "WHERE notification_id = ?", String.class, id))
                .isEqualTo(AttemptOutcome.SUCCESS.name());
    }

    @Test
    @DisplayName("refuses the result of a claim that was taken away in the meantime")
    void refusesAWriteFromAnInstanceThatLostItsClaim() {
        // The scenario the version column exists for: a destination takes longer than the reaper's
        // threshold, the row is released, another instance delivers it, and the first instance
        // comes back holding a copy of the notification from before any of that.
        insertPending(Priority.MEDIUM, clock.now().minusSeconds(1));
        ClaimedNotification stale = queue.claimDue(1, INSTANCE).getFirst();

        abandonedClaims.release(Duration.ZERO);

        assertThatExceptionOfType(ClaimLostException.class)
                .isThrownBy(() -> queue.recordAttempt(stale,
                        deliver(stale, new DispatchOutcome.Success(202))))
                .satisfies(lost -> assertThat(lost.notificationId())
                        .isEqualTo(stale.notification().id()));

        assertThat(statusOf(stale.notification().id()))
                .as("the row keeps the state the winner left it in")
                .isEqualTo("PENDING");
    }

    @Test
    @DisplayName("returns a claim its owner never came back for")
    void reapsAnAbandonedClaim() {
        UUID abandoned = insertDispatching(clock.now().minusSeconds(600), OTHER_INSTANCE);

        assertThat(abandonedClaims.release(Duration.ofMinutes(5))).isEqualTo(1);

        assertThat(statusOf(abandoned)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                "SELECT claimed_by FROM notification WHERE id = ?", String.class, abandoned))
                .isNull();
        assertThat(queue.claimDue(1, INSTANCE))
                .as("released means claimable again, or the recovery achieved nothing")
                .hasSize(1);
    }

    @Test
    @DisplayName("leaves a claim alone while it is still within the threshold")
    void leavesAFreshClaimAlone() {
        // The other half of the same guard, and the dangerous half: released early, the row is
        // delivered twice on purpose while its owner is still working on it.
        UUID inFlight = insertDispatching(clock.now().minusSeconds(30), OTHER_INSTANCE);

        assertThat(abandonedClaims.release(Duration.ofMinutes(5))).isZero();

        assertThat(statusOf(inFlight)).isEqualTo("DISPATCHING");
    }

    @Test
    @DisplayName("moves the version on every claim, so a stale writer always loses")
    void everyClaimMovesTheVersion() {
        // What the guard rests on. If reaping and re-claiming left the version where it was, the
        // instance coming back from a long delivery would write over the winner's result.
        insertPending(Priority.MEDIUM, clock.now().minusSeconds(1));
        long firstClaim = queue.claimDue(1, INSTANCE).getFirst().version();

        abandonedClaims.release(Duration.ZERO);
        long secondClaim = queue.claimDue(1, OTHER_INSTANCE).getFirst().version();

        assertThat(secondClaim).isGreaterThan(firstClaim);
    }

    /**
     * @param claimed the notification to deliver
     * @param outcome what the channel would have reported
     * @return the attempt the aggregate produces, so the test writes what production writes
     */
    private NotificationAttempt deliver(ClaimedNotification claimed, DispatchOutcome outcome) {
        Instant startedAt = clock.now();
        return claimed.notification().recordAttempt(outcome, startedAt, clock.now(),
                new RetryPolicy(Duration.ofSeconds(5), 3.0, Duration.ofMinutes(5), 0.0));
    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject("SELECT status FROM notification WHERE id = ?", String.class, id);
    }

    /**
     * PostgreSQL stores timestamps to the microsecond, so an instant read back is never after the
     * one read before it, but may round a hair below it.
     *
     * @param instant the instant the claim cannot precede
     * @return that instant, with a millisecond of slack
     */
    private static Instant beforeTheThousandthOf(Instant instant) {
        return instant.minusMillis(1);
    }

    private static void lockRow(Connection connection, UUID id) throws SQLException {
        try (PreparedStatement lock = connection
                .prepareStatement("SELECT id FROM notification WHERE id = ? FOR UPDATE")) {
            lock.setObject(1, id);
            lock.execute();
        }
    }

    private UUID insertPending(Priority priority, Instant nextAttemptAt) {
        return insert(NotificationStatus.PENDING, priority, nextAttemptAt, null, null);
    }

    private UUID insertDispatching(Instant claimedAt, String claimedBy) {
        return insert(NotificationStatus.DISPATCHING, Priority.MEDIUM, claimedAt, claimedAt,
                claimedBy);
    }

    /**
     * Written with SQL rather than through a repository, because no production path creates a row
     * that is already overdue or already claimed -- and those are exactly the states the claim
     * query and the reaper are about.
     *
     * @param status        the lifecycle state the row starts in
     * @param priority      what decides its place in the claim order
     * @param nextAttemptAt when it becomes claimable
     * @param claimedAt     when its owner took it, or {@code null}
     * @param claimedBy     the owner, or {@code null}
     * @return the identity of the row
     */
    private UUID insert(NotificationStatus status, Priority priority, Instant nextAttemptAt,
            Instant claimedAt, String claimedBy) {
        UUID id = UUID.randomUUID();
        Instant createdAt = nextAttemptAt;

        jdbc.update("""
                INSERT INTO notification (id, recipient, channel, subject, body, priority,
                        metadata, status, attempts, max_attempts, next_attempt_at, claimed_at,
                        claimed_by, correlation_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, '{}'::jsonb, ?, 0, 3, ?, ?, ?, ?, ?, ?)
                """,
                id, "https://example.test/hook", Channel.SERVICE.name(), "a subject", "a body",
                priority.name(), status.name(), Timestamp.from(nextAttemptAt),
                claimedAt == null ? null : Timestamp.from(claimedAt), claimedBy, "order-4471",
                Timestamp.from(createdAt), Timestamp.from(createdAt));

        return id;
    }
}

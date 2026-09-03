package io.github.iaarencibia.notifications.adapter.out.persistence;

import io.github.iaarencibia.notifications.domain.AttemptOutcome;
import io.github.iaarencibia.notifications.domain.Channel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One attempt, as the row stores it. Written once and never updated: an attempt is something that
 * already happened.
 *
 * <p>The notification is held as a plain identifier rather than as an association. Mapping it as
 * one would invite loading a notification's whole history alongside it, which is the cost the
 * aggregate was shaped to avoid -- and nothing in this direction ever needs to navigate back.
 */
@Entity
@Table(name = "notification_attempt")
class NotificationAttemptEntity {

    @Id
    private UUID id;

    @Column(name = "notification_id")
    private UUID notificationId;

    @Column(name = "attempt_number")
    private int attemptNumber;

    @Enumerated(EnumType.STRING)
    private Channel channel;

    @Enumerated(EnumType.STRING)
    private AttemptOutcome outcome;

    @Column(name = "response_code")
    private Integer responseCode;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /**
     * Stored rather than derived on read, because the question this table answers -- how long
     * deliveries to a destination are taking -- is one the database has to be able to aggregate
     * without reconstructing every row.
     */
    @Column(name = "duration_ms")
    private long durationMs;

    protected NotificationAttemptEntity() {
        // Required by JPA.
    }

    NotificationAttemptEntity(UUID id, UUID notificationId, int attemptNumber, Channel channel,
            AttemptOutcome outcome, Integer responseCode, String errorMessage, Instant startedAt,
            Instant finishedAt, long durationMs) {
        this.id = id;
        this.notificationId = notificationId;
        this.attemptNumber = attemptNumber;
        this.channel = channel;
        this.outcome = outcome;
        this.responseCode = responseCode;
        this.errorMessage = errorMessage;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.durationMs = durationMs;
    }

    UUID getId() {
        return id;
    }

    UUID getNotificationId() {
        return notificationId;
    }

    int getAttemptNumber() {
        return attemptNumber;
    }

    Channel getChannel() {
        return channel;
    }

    AttemptOutcome getOutcome() {
        return outcome;
    }

    Integer getResponseCode() {
        return responseCode;
    }

    String getErrorMessage() {
        return errorMessage;
    }

    Instant getStartedAt() {
        return startedAt;
    }

    Instant getFinishedAt() {
        return finishedAt;
    }

    long getDurationMs() {
        return durationMs;
    }
}

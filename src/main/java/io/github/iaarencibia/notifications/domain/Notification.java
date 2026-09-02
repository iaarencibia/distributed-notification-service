package io.github.iaarencibia.notifications.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A notification and where it stands in its lifecycle. It owns the legal transitions, the
 * attempt budget, and the record of every attempt.
 *
 * <p>
 * It does not own the schedule. Whether a notification is due is decided by the claim query,
 * at the database, so that every instance reads one clock; this class computes
 * {@code nextAttemptAt} but deliberately does not enforce it.
 *
 * <p>
 * Time is always an argument, never read from a clock inside. So is the retry
 * policy: the
 * aggregate is the only party that knows whether a delay even needs
 * calculating, because that
 * depends on the outcome and on how many attempts remain.
 */
public final class Notification {

    /**
     * Mirrors the width of {@code notification.idempotency_key}. Public so that the
     * inbound
     * DTO can declare the same limit without writing the number a third time.
     */
    public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;

    /**
     * Mirrors the width of {@code notification.claimed_by}. Public so that the
     * typed
     * configuration holding the instance id can reject an oversized one at startup,
     * rather
     * than letting every claim fail once dispatch is already running.
     */
    public static final int MAX_CLAIMED_BY_LENGTH = 128;

    private final UUID id;
    private final NotificationPayload payload;
    private final CorrelationId correlationId;
    private final String idempotencyKey;
    private final int maxAttempts;
    private final Instant createdAt;

    private NotificationStatus status;
    private int attempts;
    private Instant nextAttemptAt;
    private String lastError;
    private Instant claimedAt;
    private String claimedBy;
    private Instant updatedAt;
    private Instant sentAt;

    private Notification(UUID id, NotificationPayload payload, CorrelationId correlationId,
            String idempotencyKey, int maxAttempts, Instant now) {
        this.id = id;
        this.payload = payload;
        this.correlationId = correlationId;
        this.idempotencyKey = idempotencyKey;
        this.maxAttempts = maxAttempts;
        this.createdAt = now;
        this.updatedAt = now;
        this.status = NotificationStatus.PENDING;
        this.attempts = 0;
        // Claimable on the very next poll: any later value would add latency nothing
        // asked for.
        this.nextAttemptAt = now;
    }

    /**
     * Creates a notification already queued: it starts PENDING and due immediately, so the
     * very next poll can claim it.
     *
     * @param id             identity of the notification, assigned by the caller
     * @param payload        what the client asked to be sent, and to whom
     * @param correlationId  groups this notification with the rest of its operation
     * @param idempotencyKey the client's own key, or {@code null}. Optional because only the
     *                       client knows whether two requests are the same logical send
     * @param maxAttempts    how many delivery attempts this notification is given; persisted
     *                       with it, so a later change of configuration does not move the
     *                       budget of a notification already in flight
     * @param now            the current instant, taken from the database clock
     * @return the notification, PENDING and claimable
     */
    public static Notification submit(UUID id, NotificationPayload payload,
            CorrelationId correlationId, String idempotencyKey, int maxAttempts, Instant now) {

        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        if (idempotencyKey != null) {
            // Blank is rejected rather than read as absent. An empty string is NOT NULL, so
            // the partial unique index stores it as a real key -- one that every caller
            // making the same mistake would share, turning unrelated notifications into
            // duplicates of each other.
            if (idempotencyKey.isBlank()) {
                throw new IllegalArgumentException("idempotencyKey must not be blank");
            }
            // UTF-16 units rather than characters, which is exact here: the key arrives in an
            // HTTP header, and header values are ASCII, so one unit is always one character.
            if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
                throw new IllegalArgumentException("idempotencyKey must not exceed "
                        + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
            }
        }

        return new Notification(id, payload, correlationId, idempotencyKey, maxAttempts, now);
    }

    /**
     * Rebuilds a notification that already exists, with the history it has accumulated.
     *
     * <p>It is not {@link #submit} with more arguments. That one asks whether a birth is legal
     * and refuses anything past PENDING with no attempts; this one asks whether a state is one a
     * notification can be found in, and has to accept exactly those.
     *
     * <p>What it refuses is what the schema refuses, and no more. A rule stricter here than the
     * {@code CHECK} constraints would make a row that legally exists impossible to read back.
     *
     * <p>The parameters follow the column order of the {@code notification} table, so that the
     * mapper reads as a transcription and a crossed pair shows up side by side.
     *
     * @param id             identity of the notification
     * @param payload        what the client asked to be sent, and to whom
     * @param status         the lifecycle state the row is in
     * @param attempts       deliveries already recorded, never above {@code maxAttempts}
     * @param maxAttempts    the budget this notification was created with
     * @param nextAttemptAt  when the claim query may take it again
     * @param lastError      why the most recent attempt failed, or {@code null}
     * @param claimedAt      when the current owner took it, or {@code null}
     * @param claimedBy      the instance that owns it, or {@code null}
     * @param correlationId  groups this notification with the rest of its operation
     * @param idempotencyKey the client's own key, or {@code null}
     * @param createdAt      when it entered the system
     * @param updatedAt      when the row was last written
     * @param sentAt         when it was delivered, or {@code null}
     * @return the notification as it stands
     */
    public static Notification rehydrate(UUID id, NotificationPayload payload,
            NotificationStatus status, int attempts, int maxAttempts, Instant nextAttemptAt,
            String lastError, Instant claimedAt, String claimedBy, CorrelationId correlationId,
            String idempotencyKey, Instant createdAt, Instant updatedAt, Instant sentAt) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");

        // Mirrors ck_notification_max_attempts and ck_notification_attempts.
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        if (attempts < 0 || attempts > maxAttempts) {
            throw new IllegalArgumentException(
                    "attempts must be between 0 and " + maxAttempts + ", was " + attempts);
        }
        // Mirrors ck_notification_claim_consistency. A claimed row with no owner is not a
        // notification with an odd history: it is corrupt data, and the reaper could never
        // trace it back to the instance that abandoned it.
        if (status == NotificationStatus.DISPATCHING && (claimedAt == null || claimedBy == null)) {
            throw new IllegalArgumentException("a DISPATCHING notification must name its owner");
        }

        return new Notification(id, payload, status, attempts, maxAttempts, nextAttemptAt,
                lastError, claimedAt, claimedBy, correlationId, idempotencyKey, createdAt,
                updatedAt, sentAt);
    }

    private Notification(UUID id, NotificationPayload payload, NotificationStatus status,
            int attempts, int maxAttempts, Instant nextAttemptAt, String lastError,
            Instant claimedAt, String claimedBy, CorrelationId correlationId,
            String idempotencyKey, Instant createdAt, Instant updatedAt, Instant sentAt) {
        this.id = id;
        this.payload = payload;
        this.status = status;
        this.attempts = attempts;
        this.maxAttempts = maxAttempts;
        this.nextAttemptAt = nextAttemptAt;
        this.lastError = lastError;
        this.claimedAt = claimedAt;
        this.claimedBy = claimedBy;
        this.correlationId = correlationId;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.sentAt = sentAt;
    }

    /**
     * Takes ownership of the notification for dispatch.
     *
     * <p>
     * Does not consume an attempt: attempts count deliveries tried, not rows
     * claimed, so a
     * claim the reaper later reverses costs the notification nothing.
     *
     * @param now       the current instant, taken from the database clock
     * @param claimedBy the instance taking ownership. The reaper recovers work by looking at
     *                  this, so an anonymous claim could never be traced back
     */
    public void claim(Instant now, String claimedBy) {
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(claimedBy, "claimedBy must not be null");
        if (claimedBy.isBlank()) {
            // The reaper recovers work by looking at who owns it; an anonymous claim could
            // never be traced back to the instance that abandoned it.
            throw new IllegalArgumentException("claimedBy must not be blank");
        }
        // Same as above: the owner is the configured instance id, a host name.
        if (claimedBy.length() > MAX_CLAIMED_BY_LENGTH) {
            throw new IllegalArgumentException("claimedBy must not exceed " + MAX_CLAIMED_BY_LENGTH + " characters");
        }

        transitionTo(NotificationStatus.DISPATCHING, now);
        this.claimedAt = now;
        this.claimedBy = claimedBy;
    }

    /**
     * Applies the result of one delivery and returns the record of it.
     *
     * <p>
     * A single entry point on purpose. Splitting the state change from the record
     * would let
     * a caller perform one without the other, leaving an attempt with no transition
     * or a
     * transition with no trace.
     *
     * @param outcome     what the channel reported
     * @param startedAt   when the delivery was attempted
     * @param finishedAt  when it returned; must not precede {@code startedAt}. The backoff is
     *                    counted from here, since a destination that took thirty seconds to
     *                    time out has already had those thirty seconds of grace
     * @param retryPolicy consulted only when the notification is rescheduled, which this
     *                    aggregate is the only party able to determine
     * @return the record of this attempt, for the application service to persist alongside
     *         the notification in the same transaction
     */
    public NotificationAttempt recordAttempt(DispatchOutcome outcome, Instant startedAt,
            Instant finishedAt, RetryPolicy retryPolicy) {

        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        Objects.requireNonNull(finishedAt, "finishedAt must not be null");
        Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
        if (finishedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("finishedAt must not precede startedAt");
        }
        if (status != NotificationStatus.DISPATCHING) {
            // A result for a row nobody owns means two instances dispatched it.
            throw new InvalidStateTransitionException(status, "record a dispatch attempt");
        }

        attempts++;
        NotificationAttempt attempt = new NotificationAttempt(UUID.randomUUID(), id, attempts,
                payload.channel(), outcome.attemptOutcome(), outcome.responseCode(),
                outcome.errorMessage(), startedAt, finishedAt);

        // Exhaustive over the sealed hierarchy: a fourth outcome would not compile
        // until it
        // was given a branch here.
        switch (outcome) {
            case DispatchOutcome.Success success -> markSent(finishedAt);
            case DispatchOutcome.PermanentFailure failure -> markFailed(finishedAt, failure);
            case DispatchOutcome.RetryableFailure failure -> {
                if (attempts >= maxAttempts) {
                    markFailed(finishedAt, failure);
                } else {
                    reschedule(finishedAt, failure, retryPolicy);
                }
            }
        }

        return attempt;
    }

    private void markSent(Instant at) {
        transitionTo(NotificationStatus.SENT, at);
        this.sentAt = at;
        this.lastError = null;
        releaseClaim();
    }

    private void markFailed(Instant at, DispatchOutcome failure) {
        transitionTo(NotificationStatus.FAILED, at);
        this.lastError = failure.errorMessage();
        releaseClaim();
    }

    private void reschedule(Instant at, DispatchOutcome.RetryableFailure failure, RetryPolicy policy) {
        transitionTo(NotificationStatus.PENDING, at);
        this.lastError = failure.errorMessage();
        // Counted from when the attempt ended, not when it started: a destination that
        // took
        // thirty seconds to time out has already had those thirty seconds of grace.
        this.nextAttemptAt = at.plus(policy.delayFor(attempts, failure.retryAfter()));
        releaseClaim();
    }

    /**
     * The single place a status changes, so no move can bypass the lifecycle rules.
     */
    private void transitionTo(NotificationStatus target, Instant at) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStateTransitionException(status, target);
        }
        this.status = target;
        this.updatedAt = at;
    }

    private void releaseClaim() {
        this.claimedAt = null;
        this.claimedBy = null;
    }

    public UUID id() {
        return id;
    }

    public NotificationPayload payload() {
        return payload;
    }

    public CorrelationId correlationId() {
        return correlationId;
    }

    /** @return the client's own key, or {@code null} when it supplied none */
    public String idempotencyKey() {
        return idempotencyKey;
    }

    public NotificationStatus status() {
        return status;
    }

    /**
     * @return how many deliveries have been attempted and recorded. Not the number of network
     *         calls actually made: a delivery whose instance died before recording it is
     *         reclaimed by the reaper and never counted
     */
    public int attempts() {
        return attempts;
    }

    /** @return the attempt budget this notification was created with, and keeps for life */
    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * @return the instant from which this notification may be claimed again. Computed here
     *         but enforced by the claim query, not by this class
     */
    public Instant nextAttemptAt() {
        return nextAttemptAt;
    }

    /**
     * @return the most recent failure, or {@code null} if none. Only the most recent: the
     *         full history lives in {@code notification_attempt}
     */
    public String lastError() {
        return lastError;
    }

    /** @return when the current claim was taken, or {@code null} outside DISPATCHING */
    public Instant claimedAt() {
        return claimedAt;
    }

    /** @return the instance that owns the current claim, or {@code null} outside DISPATCHING */
    public String claimedBy() {
        return claimedBy;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    /** @return when delivery succeeded, or {@code null} until it does */
    public Instant sentAt() {
        return sentAt;
    }
}

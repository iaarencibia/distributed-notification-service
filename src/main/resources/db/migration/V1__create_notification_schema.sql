-- ---------------------------------------------------------------------------
-- notification
--
-- This table is both the record of the notification and the queue it waits in.
-- Because the intake endpoint writes it inside the request transaction, "persist"
-- and "enqueue" are a single atomic operation: there is no window in which a
-- notification is acknowledged to the caller but invisible to the dispatcher.
-- ---------------------------------------------------------------------------
CREATE TABLE notification
(
    id              UUID         PRIMARY KEY,

    -- Payload as submitted by the client.
    recipient       VARCHAR(2048) NOT NULL,
    channel         VARCHAR(32)  NOT NULL,
    subject         VARCHAR(512) NOT NULL,
    -- Bounded, and not TEXT. The brief calls this "the content of the message", and sixteen
    -- thousand characters is several pages -- past anything a message is, and short of a size
    -- that hurts. It is not stored alone: the LOG channel writes it into a structured log line
    -- and the SERVICE channel posts it to another service, so its width is paid three times.
    body            VARCHAR(16384) NOT NULL,
    priority        VARCHAR(16)  NOT NULL,
    metadata        JSONB        NOT NULL DEFAULT '{}'::JSONB,

    -- Lifecycle.
    status          VARCHAR(32)  NOT NULL,
    attempts        INTEGER      NOT NULL DEFAULT 0,
    max_attempts    INTEGER      NOT NULL,
    next_attempt_at TIMESTAMPTZ  NOT NULL,
    last_error      TEXT,

    -- Claim ownership. A row in DISPATCHING is owned by whichever instance claimed it;
    -- these two columns are what lets the reaper recover work from a dead instance.
    claimed_at      TIMESTAMPTZ,
    claimed_by      VARCHAR(128),

    -- Trace identifier propagated from intake through every dispatch attempt.
    correlation_id  VARCHAR(128) NOT NULL,

    -- Client-supplied key that makes the intake endpoint safe to retry. Optional: only
    -- the client knows whether two requests are the same logical send, and forcing a key
    -- would push indifferent callers into generating a fresh one per call, which removes
    -- the protection while appearing to provide it.
    idempotency_key VARCHAR(255),

    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    sent_at         TIMESTAMPTZ,

    -- Write counter, not an attempt counter: it moves on every update of the row and nothing
    -- ever reads it for meaning. It exists so that a dispatcher holding a copy from before the
    -- reaper released its row cannot write over a newer state. The dispatcher wires it in; a
    -- row inserted by the intake endpoint has no concurrent writer to lose to.
    version         BIGINT       NOT NULL DEFAULT 0,

    -- Numeric ordering key for priority. Kept as a generated column because the textual
    -- values do not sort correctly on their own: alphabetically 'LOW' precedes 'MEDIUM'.
    -- Deriving it in the database guarantees it can never drift from `priority`.
    priority_rank   SMALLINT     NOT NULL GENERATED ALWAYS AS (
                        CASE priority
                            WHEN 'HIGH'   THEN 0
                            WHEN 'MEDIUM' THEN 1
                            ELSE 2
                        END
                    ) STORED,

    CONSTRAINT ck_notification_channel
        CHECK (channel IN ('LOG', 'SERVICE', 'EMAIL')),
    CONSTRAINT ck_notification_priority
        CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT ck_notification_status
        CHECK (status IN ('PENDING', 'DISPATCHING', 'SENT', 'FAILED')),
    -- The one place where the column and the domain do not state the same rule, and the
    -- difference is the database's, not a decision. The domain bounds metadata the way a caller
    -- understands it -- at most 32 entries, each key 128 characters and each value 2048 -- and a
    -- CHECK cannot count an object's keys, because doing so needs a set-returning function and
    -- therefore a subquery, which a CHECK may not contain. So the column bounds the consequence
    -- instead: 32 entries at their full width serialise to roughly 72 kB, and the ceiling here
    -- sits above that on purpose. It never fires before the domain rule does. It is a backstop
    -- against a writer that reached the table without passing through the application at all.
    CONSTRAINT ck_notification_metadata
        CHECK (jsonb_typeof(metadata) = 'object' AND length(metadata::text) <= 131072),
    CONSTRAINT ck_notification_attempts
        CHECK (attempts >= 0 AND attempts <= max_attempts),
    CONSTRAINT ck_notification_max_attempts
        CHECK (max_attempts >= 1),
    -- A claimed row must record who claimed it and when; the reaper depends on it.
    CONSTRAINT ck_notification_claim_consistency
        CHECK (status <> 'DISPATCHING' OR (claimed_at IS NOT NULL AND claimed_by IS NOT NULL))
);

-- Claim query support.
--
-- Partial on purpose: only PENDING rows are ever polled, so the index stays proportional
-- to the backlog rather than to the full history. A table holding millions of SENT rows
-- keeps a small, cache-resident index.
--
-- Column order matches the claim query's ORDER BY exactly -- priority_rank,
-- next_attempt_at, created_at -- so the rows come out already ordered and no sort is
-- needed: highest priority first, and within a priority the one that has been due longest.
--
-- Ordered by next_attempt_at rather than created_at on purpose. A notification returning
-- from a backoff asked to wait; letting it overtake one that has been waiting since it
-- arrived would reward having failed. created_at remains as a deterministic tiebreaker.
CREATE INDEX idx_notification_claimable
    ON notification (priority_rank, next_attempt_at, created_at)
    WHERE status = 'PENDING';

-- Reaper support: find claims that outlived their owner.
CREATE INDEX idx_notification_stale_claims
    ON notification (claimed_at)
    WHERE status = 'DISPATCHING';

-- Idempotent intake.
--
-- Unique and partial: a key may not repeat, and rows submitted without one are not
-- indexed at all. Enforcing it in the database rather than in application code is what
-- makes it correct under concurrency — two instances handling the same client retry
-- cannot both succeed, because one of them loses the insert.
CREATE UNIQUE INDEX idx_notification_idempotency_key
    ON notification (idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- Operational lookups (dashboards, "what failed today").
CREATE INDEX idx_notification_status_created_at
    ON notification (status, created_at DESC);

CREATE INDEX idx_notification_correlation_id
    ON notification (correlation_id);


-- ---------------------------------------------------------------------------
-- notification_attempt
--
-- One row per dispatch attempt. `notification.last_error` only remembers the most
-- recent failure; this table keeps the full history, which is what makes the question
-- "why did this notification end up FAILED?" answerable with a single query.
-- ---------------------------------------------------------------------------
CREATE TABLE notification_attempt
(
    id              UUID        PRIMARY KEY,
    notification_id UUID        NOT NULL,
    attempt_number  INTEGER     NOT NULL,
    channel         VARCHAR(32) NOT NULL,
    outcome         VARCHAR(32) NOT NULL,

    -- Populated by the SERVICE channel; null for channels without a status code.
    response_code   INTEGER,
    error_message   TEXT,

    started_at      TIMESTAMPTZ NOT NULL,
    finished_at     TIMESTAMPTZ NOT NULL,
    duration_ms     BIGINT      NOT NULL,

    CONSTRAINT fk_attempt_notification
        FOREIGN KEY (notification_id) REFERENCES notification (id) ON DELETE CASCADE,
    -- Also serves as the lookup index for "all attempts of this notification".
    CONSTRAINT uk_attempt_notification_number
        UNIQUE (notification_id, attempt_number),
    CONSTRAINT ck_attempt_outcome
        CHECK (outcome IN ('SUCCESS', 'RETRYABLE_FAILURE', 'PERMANENT_FAILURE')),
    CONSTRAINT ck_attempt_number
        CHECK (attempt_number >= 1),
    -- A failed attempt with no recorded reason cannot answer the one question this table
    -- exists to answer. Enforced here and not only in the domain because the row outlives
    -- the object that created it, and a later reader has nothing else to rely on.
    CONSTRAINT ck_attempt_failure_has_reason
        CHECK (outcome = 'SUCCESS' OR (error_message IS NOT NULL AND error_message <> ''))
);

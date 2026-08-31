package io.github.iaarencibia.notifications.domain;

/**
 * How urgently a notification should be dispatched.
 *
 * <p>Carries no ordering of its own on purpose: the claim query sorts on the generated
 * {@code priority_rank} column, so the database is the single place where the order lives.
 */
public enum Priority {

    LOW,

    MEDIUM,

    HIGH
}

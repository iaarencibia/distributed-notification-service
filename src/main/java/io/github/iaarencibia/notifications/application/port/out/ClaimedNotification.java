package io.github.iaarencibia.notifications.application.port.out;

import io.github.iaarencibia.notifications.domain.Notification;

import java.util.Objects;

/**
 * A notification this instance owns, together with the row version it owned it at.
 *
 * <p>The version is a token rather than a fact about the notification: it goes back with the
 * result of the delivery, and storage refuses that write if anything moved the row in between.
 * Keeping it here instead of inside the aggregate leaves the domain free of a storage concern,
 * and makes plain that a claim is only good against the state it was taken from.
 *
 * @param notification the notification to deliver, already claimed
 * @param version      the row version at the moment of the claim
 */
public record ClaimedNotification(Notification notification, long version) {

    public ClaimedNotification {
        Objects.requireNonNull(notification, "notification must not be null");
    }
}

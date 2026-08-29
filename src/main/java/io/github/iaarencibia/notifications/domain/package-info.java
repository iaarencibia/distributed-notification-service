/**
 * Pure domain model: notification state, lifecycle transitions, retry policy.
 *
 * <p>Nothing in this package may import Spring, Jakarta Persistence, or any other
 * framework. The rule is not stylistic: it is what keeps the lifecycle rules testable
 * without a container and portable across runtimes.
 */
package io.github.iaarencibia.notifications.domain;

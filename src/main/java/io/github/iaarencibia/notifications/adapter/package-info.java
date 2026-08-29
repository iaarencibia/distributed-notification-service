/**
 * Adapters: every piece of code that speaks a specific technology.
 *
 * <p>{@code in} drives the application (REST intake, dispatch scheduler).
 * {@code out} is driven by it (JPA persistence, notification channels).
 *
 * <p>This is the only layer allowed to change when the runtime changes. Swapping Spring
 * MVC for JAX-RS, or the scheduler for a container-managed timer, is confined here.
 */
package io.github.iaarencibia.notifications.adapter;

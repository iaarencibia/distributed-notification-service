/**
 * Application layer: use cases and the ports they depend on.
 *
 * <p>{@code port.in} declares what the outside world can ask this service to do.
 * {@code port.out} declares what this service needs from the outside world, expressed
 * in domain terms rather than in terms of any particular technology.
 *
 * <p>Dependencies point inward only: this layer knows the domain, and knows nothing
 * about the adapters that implement its outbound ports.
 */
package io.github.iaarencibia.notifications.application;

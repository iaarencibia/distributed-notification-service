package io.github.iaarencibia.notifications.application.port.in;

/**
 * One pass of the dispatcher: take whatever is due and deliver it.
 *
 * <p>Driven by a scheduler rather than by a request, which is the whole point of the design -- the
 * caller that submitted the notification was answered long before this runs.
 */
public interface DispatchDueNotificationsUseCase {

    /**
     * Claims the notifications that are due and delivers each one.
     *
     * <p>Returns as soon as the batch is claimed and handed to the workers, not when the last
     * delivery finishes: a destination that takes thirty seconds to time out must not hold up the
     * next poll. That holds while the workers can take the batch. An implementation is free to
     * push back instead -- performing a refused delivery on the calling thread, which blocks this
     * call for that one delivery -- and doing so is sound: a caller busy delivering claims no
     * more work, so the backlog stays in the table, which is durable, rather than in memory.
     *
     * @return how many notifications this pass claimed, which is zero when nothing was due
     */
    int dispatchDue();
}

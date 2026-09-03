package io.github.iaarencibia.notifications.application.port.out;

import java.time.Instant;

/**
 * The clock, as an outbound dependency rather than a call to {@code Instant.now()}.
 *
 * <p>There has to be exactly one, and it has to be the database's: every instant deciding whether
 * a notification may be claimed is compared against that clock, so an instance a few seconds off
 * would either have its work reclaimed mid-delivery or leave rows the reaper never rescues.
 */
public interface SystemClock {

    /**
     * @return the current instant, according to the one clock the whole system shares
     */
    Instant now();
}

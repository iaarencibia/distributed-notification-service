package io.github.iaarencibia.notifications.domain;

/**
 * The mechanism a notification is delivered through. Selects which channel adapter handles
 * the dispatch, so adding one is adding an implementation of the outbound port.
 */
public enum Channel {

    /** Structured JSON on stdout. Always available, and the one channel that cannot fail. */
    LOG,

    /** HTTP POST to the URL supplied as the recipient. */
    SERVICE,

    /** Accepted by the schema but not implemented; see the trade-offs section of the README. */
    EMAIL
}

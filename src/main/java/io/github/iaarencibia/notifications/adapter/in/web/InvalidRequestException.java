package io.github.iaarencibia.notifications.adapter.in.web;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The headers a caller sent that the service will not accept.
 *
 * <p>Bean Validation covers the body, but the two headers this endpoint reads are not fields of
 * any object it validates, and one of them is turned into a domain type whose own constructor does
 * the checking. This carries those refusals out with the name of each header attached, so the
 * response can say which ones and why instead of reporting a generic bad request.
 *
 * <p>It holds every offending header rather than only the first. A caller fixing its integration
 * should learn about all of them in one round trip, which is the promise the body path already
 * keeps -- and holding the two paths to different standards is worse than either standard.
 */
class InvalidRequestException extends RuntimeException {

    private final Map<String, String> reasonsByHeader;

    /**
     * @param reasonsByHeader what is wrong with each refused header, keyed by the header's name
     *                        exactly as it travels on the wire, and phrased for whoever has to fix
     *                        the caller
     */
    InvalidRequestException(Map<String, String> reasonsByHeader) {
        // The reasons are stored unprefixed, because in the response the header name is already
        // the key they sit under and repeating it there would read as a stutter. Only this
        // message, which nothing but a log ever sees, joins the name back onto the reason.
        super(reasonsByHeader.entrySet().stream()
                .map(refusal -> refusal.getKey() + " " + refusal.getValue())
                .collect(Collectors.joining("; ")));
        // Sorted, so that two bad headers produce the same response whichever order they were
        // checked in.
        this.reasonsByHeader = Collections.unmodifiableMap(new TreeMap<>(reasonsByHeader));
    }

    /**
     * @return the reason each refused header was refused, keyed by header name
     */
    Map<String, String> reasonsByHeader() {
        return reasonsByHeader;
    }
}

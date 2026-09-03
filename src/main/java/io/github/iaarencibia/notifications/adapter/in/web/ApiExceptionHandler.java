package io.github.iaarencibia.notifications.adapter.in.web;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import io.github.iaarencibia.notifications.application.DeliverableChannels;
import io.github.iaarencibia.notifications.application.port.in.UndeliverableChannelException;
import io.github.iaarencibia.notifications.domain.Channel;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Turns everything that can go wrong inside the dispatcher servlet into a problem detail.
 *
 * <p>It is the second of two producers of these responses, and that is not an oversight. The
 * other, the authentication entry point, answers refusals that happen in the filter chain, before
 * a request ever reaches a controller and therefore out of reach of any advice. The split follows
 * where the failure happens, and the two are held to the same shape by a test that asks both for
 * one and compares them.
 *
 * <p>Extending Spring's own handler is what makes the rest of the surface -- wrong method, wrong
 * media type, missing parameter -- arrive in that shape too, instead of only the cases named here.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final DeliverableChannels deliverableChannels;

    /**
     * @param deliverableChannels the same set the use case decides with. Held here only to
     *                            describe it: a caller that named a channel it cannot use has to
     *                            be told which ones it can, and reporting the contract is what a
     *                            web adapter is for. Nothing here decides anything
     */
    ApiExceptionHandler(DeliverableChannels deliverableChannels) {
        this.deliverableChannels = deliverableChannels;
    }

    /**
     * A field that failed validation is reported with the field's name and what it needed, which
     * is the descriptive message the brief asks for. No field <em>value</em> is echoed back.
     *
     * <p>One name is the caller's own text rather than ours: a violation inside {@code metadata}
     * is reported as {@code metadata[<key>]}, because a caller holding thirty-two entries has to
     * be told which one to shorten. Only the key travels, never the value, and it goes back as a
     * JSON string to the client that sent it.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpHeaders headers, HttpStatusCode status,
            WebRequest request) {

        // Grouped into sorted sets rather than merged as they arrive. Bean Validation does not
        // define the order it reports violations in, so a field breaking two rules at once --
        // blank and over length, which a long run of spaces is -- would otherwise be described
        // by a different string from one run to the next.
        Map<String, SortedSet<String>> byName = new TreeMap<>();
        exception.getFieldErrors().forEach(error -> byName
                .computeIfAbsent(error.getField(), field -> new TreeSet<>())
                .add(String.valueOf(error.getDefaultMessage())));
        // A constraint on the object as a whole has no field to report against, and there are
        // none today. Reading them anyway is what keeps the first one that is ever added from
        // being answered with a 400 whose error map names nothing.
        exception.getGlobalErrors().forEach(error -> byName
                .computeIfAbsent(error.getObjectName(), object -> new TreeSet<>())
                .add(String.valueOf(error.getDefaultMessage())));

        Map<String, String> errors = new TreeMap<>();
        byName.forEach((name, messages) -> errors.put(name, String.join("; ", messages)));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "One or more fields of the request body are invalid");
        problem.setProperty("errors", errors);

        return handleExceptionInternal(exception, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    /**
     * Covers a body that is not JSON at all and, more usefully, one where an enum carries a value
     * the service does not have. Jackson fails that before validation runs, so without this the
     * caller would be told only that the body was unreadable.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception, HttpHeaders headers, HttpStatusCode status,
            WebRequest request) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "The request body could not be read as JSON");

        if (exception.getCause() instanceof InvalidFormatException invalidValue) {
            problem.setProperty("errors", Map.of(fieldOf(invalidValue), reasonFor(invalidValue)));
        }

        return handleExceptionInternal(exception, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    /**
     * Answers the headers the endpoint refused. Each is reported under its own name rather than
     * under a field name, because that is what the caller has to go and change, and every one it
     * got wrong is named at once for the same reason the body path names every field.
     *
     * @param exception the refusals, carrying the header each applies to
     * @return the problem detail sent back to the caller
     */
    @ExceptionHandler(InvalidRequestException.class)
    ProblemDetail handleInvalidHeader(InvalidRequestException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "One or more request headers are invalid");
        problem.setProperty("errors", exception.reasonsByHeader());
        return problem;
    }

    /**
     * Answers a channel this deployment has no adapter for. Reported under {@code channel}, the
     * same name the field carries in the request, so a caller reads it exactly as it reads any
     * other rejected field -- the fact that this one was decided by the use case and not by Bean
     * Validation is this service's business, not the caller's.
     *
     * @param exception the refusal, carrying what was asked for and what is available
     * @return the problem detail sent back to the caller
     */
    @ExceptionHandler(UndeliverableChannelException.class)
    ProblemDetail handleUndeliverableChannel(UndeliverableChannelException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "One or more fields of the request body are invalid");
        problem.setProperty("errors", Map.of("channel",
                "must be one of " + exception.deliverable().describe()));
        return problem;
    }

    /**
     * The last resort, so that no caller ever receives the container's own error page in place of
     * a problem detail. The cause is written to the log and not to the response: the caller can do
     * nothing with a stack trace, and an unhandled failure is the worst place to start describing
     * the inside of the service.
     *
     * @param exception whatever was not handled anywhere above
     * @param request   the request being served, named in the log so the entry can be found
     * @return a problem detail that says only that the request failed
     */
    @ExceptionHandler(Exception.class)
    ProblemDetail handleAnythingElse(Exception exception, HttpServletRequest request) {
        // The method and path are logged with the stack trace. Without them the entry says only
        // that something failed somewhere, which is the one line in the log that most needs to
        // say where -- and the caller cannot supply it, since the response deliberately does not.
        log.error("Unhandled failure while serving {} {}", request.getMethod(),
                request.getRequestURI(), exception);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "The request could not be completed");
    }

    private static String fieldOf(InvalidFormatException invalidValue) {
        return invalidValue.getPath().stream()
                .map(JsonMappingException.Reference::getFieldName)
                .filter(name -> name != null)
                .collect(Collectors.joining("."));
    }

    /**
     * Names the values a caller may send, and for {@link Channel} that is not the same list as
     * the type's own constants.
     *
     * <p>A channel exists in the model and is deliverable only where an adapter is wired, so the
     * enum's constants are what parses and the deliverable set is what works. Answering with the
     * constants would advertise a channel this deployment refuses -- and a caller that took the
     * advice would be told, on its next request, that it may not send what it had just been told
     * to send.
     */
    private String reasonFor(InvalidFormatException invalidValue) {
        Class<?> expected = invalidValue.getTargetType();
        if (expected == Channel.class) {
            return "must be one of " + deliverableChannels.describe();
        }
        if (expected != null && expected.isEnum()) {
            String allowed = java.util.Arrays.stream(expected.getEnumConstants())
                    .map(String::valueOf)
                    .collect(Collectors.joining(", "));
            return "must be one of " + allowed;
        }
        return "is not of the expected type";
    }
}

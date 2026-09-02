package io.github.iaarencibia.notifications.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.iaarencibia.notifications.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The intake endpoint end to end: the real filter chain, the real controller, the real use case
 * and a real PostgreSQL.
 *
 * <p>This is the integration test the brief requires over the main REST endpoint, and it is here
 * rather than at a lower level because most of what it proves is only true when all of it runs
 * together. That a repeated idempotency key returns the first notification's identifier depends on
 * a unique index; that an unknown channel is a {@code 400} and not a {@code 500} depends on the
 * exception handler being registered; that any of it is reachable at all depends on the security
 * chain accepting the credential.
 *
 * <p>Rows are asserted through their own idempotency key rather than by counting the table, so no
 * test here can be made to pass or fail by what another one wrote.
 */
@SpringBootTest(properties = IntegrationTestSupport.API_KEY_PROPERTY)
@AutoConfigureMockMvc
class NotificationIntakeIT extends IntegrationTestSupport {

    private static final String PATH = "/api/v1/notifications";
    private static final String CREDENTIAL = "ApiKey " + API_KEY;

    private static final String VALID_BODY = """
            {
              "recipient": "https://destination.test/hook",
              "channel": "SERVICE",
              "subject": "Your order shipped",
              "body": "It is on its way.",
              "priority": "HIGH",
              "metadata": {"orderId": "4471"}
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("accepts a notification and answers 202 with its identifier")
    void acceptsANotification() throws Exception {
        MvcResult result = mockMvc.perform(submit(VALID_BODY).header("Idempotency-Key", "it-accept"))
                .andExpect(status().isAccepted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andReturn();

        String id = idOf(result);
        Map<String, Object> row = rowOf("it-accept");

        assertThat(row.get("id")).hasToString(id);
        assertThat(row.get("recipient")).isEqualTo("https://destination.test/hook");
        assertThat(row.get("channel")).isEqualTo("SERVICE");
        assertThat(row.get("subject")).isEqualTo("Your order shipped");
        assertThat(row.get("priority")).isEqualTo("HIGH");
    }

    @Test
    @DisplayName("stores it PENDING and already due, so the dispatcher needs no second signal")
    void storesItQueued() throws Exception {
        mockMvc.perform(submit(VALID_BODY).header("Idempotency-Key", "it-queued"))
                .andExpect(status().isAccepted());

        Map<String, Object> row = rowOf("it-queued");
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("attempts")).isEqualTo(0);
        assertThat(row.get("claimed_by")).isNull();
        assertThat(row.get("sent_at")).isNull();
        // The configured budget, stamped on the row: what this pins is that the value travels
        // from configuration to column through the whole path. It cannot also pin the mapper
        // against a constant, because three is the configured default and a mapper writing that
        // same literal would satisfy it. That case belongs to
        // NotificationRepositoryIT.writesTheBudgetTheAggregateCarries, which asserts a budget
        // the configuration never produces.
        assertThat(row.get("max_attempts")).isEqualTo(3);
        // The insert is the enqueue: nothing else has to happen for this row to be picked up.
        assertThat((java.sql.Timestamp) row.get("next_attempt_at"))
                .isEqualTo((java.sql.Timestamp) row.get("created_at"));
    }

    @Test
    @DisplayName("answers a repeated idempotency key with the identifier of the first notification")
    void answersARepeatedKeyWithTheFirstIdentifier() throws Exception {
        String first = idOf(mockMvc.perform(submit(VALID_BODY).header("Idempotency-Key", "it-repeat"))
                .andExpect(status().isAccepted()).andReturn());

        String second = idOf(mockMvc.perform(submit(VALID_BODY).header("Idempotency-Key", "it-repeat"))
                .andExpect(status().isAccepted()).andReturn());

        assertThat(second).isEqualTo(first);
        // And the repeat created nothing: a client retrying a timed-out request must not cause a
        // second delivery.
        assertThat(countOf("it-repeat")).isEqualTo(1);
    }

    @Test
    @DisplayName("leaves the stored notification untouched when a repeat carries other headers")
    void aRepeatDoesNotDisturbTheStoredNotification() throws Exception {
        // A real retry rarely reproduces the first request exactly -- a client that never sent a
        // correlation id sends none again, and one that did may have moved on. Whatever arrives
        // the second time, the notification that already exists keeps the identity it was filed
        // under, because that is the one the first response referred to.
        mockMvc.perform(submit(VALID_BODY)
                        .header("X-Correlation-Id", "order-first")
                        .header("Idempotency-Key", "it-repeat-headers"))
                .andExpect(status().isAccepted());

        mockMvc.perform(submit(VALID_BODY)
                        .header("X-Correlation-Id", "order-second")
                        .header("Idempotency-Key", "it-repeat-headers"))
                .andExpect(status().isAccepted());

        assertThat(rowOf("it-repeat-headers").get("correlation_id")).isEqualTo("order-first");
        assertThat(countOf("it-repeat-headers")).isEqualTo(1);
    }

    @Test
    @DisplayName("accepts two notifications of one operation, each with its own key")
    void acceptsTwoNotificationsOfTheSameOperation() throws Exception {
        // The behaviour the two identifiers exist for, proven through the endpoint: a correlation
        // id is shared by every notification of an operation, an idempotency key never is. Were
        // the endpoint to cross them, the second would come back as a duplicate of the first and
        // would never be delivered.
        String toTheBuyer = idOf(mockMvc.perform(submit(VALID_BODY)
                        .header("X-Correlation-Id", "order-4471")
                        .header("Idempotency-Key", "it-buyer"))
                .andExpect(status().isAccepted()).andReturn());

        String toTheWarehouse = idOf(mockMvc.perform(submit(VALID_BODY)
                        .header("X-Correlation-Id", "order-4471")
                        .header("Idempotency-Key", "it-warehouse"))
                .andExpect(status().isAccepted()).andReturn());

        assertThat(toTheBuyer).isNotEqualTo(toTheWarehouse);
        assertThat(rowOf("it-buyer").get("correlation_id")).isEqualTo("order-4471");
        assertThat(rowOf("it-warehouse").get("correlation_id")).isEqualTo("order-4471");
    }

    @Test
    @DisplayName("accepts a notification with no idempotency key at all")
    void acceptsANotificationWithoutAKey() throws Exception {
        mockMvc.perform(submit(VALID_BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").isNotEmpty());
    }

    @Test
    @DisplayName("files a notification under a generated correlation id when the caller sends none")
    void generatesACorrelationId() throws Exception {
        mockMvc.perform(submit(VALID_BODY).header("Idempotency-Key", "it-generated"))
                .andExpect(status().isAccepted());

        // The column is NOT NULL, so something has to be written, and it has to be a real
        // identifier rather than a placeholder: the grouping is worthless with holes in it.
        String generated = (String) rowOf("it-generated").get("correlation_id");
        assertThat(generated).isNotBlank();
        assertThat(UUID.fromString(generated)).isNotNull();
    }

    @Test
    @DisplayName("accepts a subject of 512 emoji, which the column measures as 512 characters")
    void acceptsASubjectOfEmojiAtTheColumnWidth() throws Exception {
        // PostgreSQL counts characters and so does the domain; anything on this path that
        // counted UTF-16 units instead would read 512 emoji as 1024 and turn a legal subject
        // away, quoting back a limit the caller has not exceeded. The domain already asserts
        // this about itself -- but the endpoint is the only way in, so if the endpoint refuses,
        // that guarantee reaches nobody.
        String atTheLimit = "🚀".repeat(512);

        mockMvc.perform(submit(VALID_BODY.replace("Your order shipped", atTheLimit))
                        .header("Idempotency-Key", "it-emoji"))
                .andExpect(status().isAccepted());

        assertThat(rowOf("it-emoji").get("subject")).isEqualTo(atTheLimit);
    }

    @Test
    @DisplayName("accepts a recipient and an idempotency key exactly at their column widths")
    void acceptsValuesExactlyAtTheirLimits() throws Exception {
        // The rejecting side is covered below. Both sides are needed: a limit written with the
        // comparison one off turns a legal value away, and no test that only sends oversized
        // input can ever notice.
        String recipientAtTheLimit = "https://destination.test/"
                + "x".repeat(2048 - "https://destination.test/".length());

        mockMvc.perform(submit(VALID_BODY.replace("https://destination.test/hook",
                                recipientAtTheLimit))
                        .header("Idempotency-Key", "k".repeat(255)))
                .andExpect(status().isAccepted());

        assertThat(rowOf("k".repeat(255)).get("recipient")).isEqualTo(recipientAtTheLimit);
    }

    @Test
    @DisplayName("refuses a body whose required fields are missing, naming each one")
    void refusesAnIncompleteBody() throws Exception {
        mockMvc.perform(submit("""
                        {"recipient": "  ", "subject": "", "metadata": {}}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors.recipient").value("must not be blank"))
                .andExpect(jsonPath("$.errors.subject").value("must not be blank"))
                .andExpect(jsonPath("$.errors.channel").value("must be one of LOG, SERVICE, EMAIL"))
                .andExpect(jsonPath("$.errors.priority").value("must be one of LOW, MEDIUM, HIGH"))
                .andExpect(jsonPath("$.errors.body").exists());
    }

    @Test
    @DisplayName("names every offending field at once, which is what the README shows")
    void namesEveryOffendingFieldAtOnce() throws Exception {
        // The exact body printed in the README's API section. A reader who copies it has to get
        // what the page shows -- and the page claims a 400 names every field, not the first.
        mockMvc.perform(submit("""
                        {"recipient": "  ", "channel": "SERVICE"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail")
                        .value("One or more fields of the request body are invalid"))
                .andExpect(jsonPath("$.errors.recipient").value("must not be blank"))
                .andExpect(jsonPath("$.errors.subject").value("must not be blank"))
                .andExpect(jsonPath("$.errors.body")
                        .value("must be present, though it may be empty"))
                .andExpect(jsonPath("$.errors.priority").value("must be one of LOW, MEDIUM, HIGH"))
                .andExpect(jsonPath("$.errors.channel").doesNotExist());
    }

    @Test
    @DisplayName("refuses a channel the service does not have, and says which it does")
    void refusesAnUnknownChannel() throws Exception {
        // Jackson rejects this before validation ever runs, so without a handler for it the
        // caller would be told only that the body was unreadable.
        mockMvc.perform(submit(VALID_BODY.replace("\"SERVICE\"", "\"SMS\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.channel").value("must be one of LOG, SERVICE, EMAIL"));
    }

    @Test
    @DisplayName("refuses a recipient longer than the column that has to hold it")
    void refusesAnOversizedRecipient() throws Exception {
        mockMvc.perform(submit(VALID_BODY.replace("https://destination.test/hook",
                        "https://destination.test/" + "x".repeat(2048))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.recipient").value("must not exceed 2048 characters"));
    }

    @Test
    @DisplayName("refuses a correlation id outside the character set it is allowed")
    void refusesAMalformedCorrelationId() throws Exception {
        // The value is written into a structured log line on every attempt, which is why the set
        // is narrow. A newline is not the case to test here: it cannot travel in an HTTP header at
        // all, and Spring Security's firewall rejects it before any controller sees it. A space
        // can arrive, and must still be refused.
        // Asserted whole rather than by fragment. The reason sits under the header's own name, so
        // repeating that name inside the message would read as a stutter -- and only an exact
        // comparison notices if it comes back.
        mockMvc.perform(submit(VALID_BODY).header("X-Correlation-Id", "order 4471"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors.['X-Correlation-Id']").value("must be 1 to 128 "
                        + "characters, and may contain only letters, digits and the characters "
                        + ". _ : -"));
    }

    @Test
    @DisplayName("names every header it refused, not only the first one it looked at")
    void refusesBothBadHeadersAtOnce() throws Exception {
        // The body path names every invalid field, and the README makes that promise in order to
        // spare a caller a second round trip. Checking the headers one at a time and stopping at
        // the first would quietly hold the same caller to a worse standard on the same request.
        mockMvc.perform(submit(VALID_BODY)
                        .header("X-Correlation-Id", "order 4471")
                        .header("Idempotency-Key", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.['X-Correlation-Id']").exists())
                .andExpect(jsonPath("$.errors.['Idempotency-Key']").exists());
    }

    @Test
    @DisplayName("refuses a blank idempotency key rather than reading it as absent")
    void refusesABlankIdempotencyKey() throws Exception {
        // The empty string is not null, so the partial unique index would store it as a real key
        // and every caller making the same mistake would collide with all the others.
        mockMvc.perform(submit(VALID_BODY).header("Idempotency-Key", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.['Idempotency-Key']")
                        .value("must not be blank; omit the header rather than sending it empty"));
    }

    @Test
    @DisplayName("refuses an idempotency key longer than the column that has to hold it")
    void refusesAnOversizedIdempotencyKey() throws Exception {
        mockMvc.perform(submit(VALID_BODY).header("Idempotency-Key", "k".repeat(256)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.['Idempotency-Key']")
                        .value("must not exceed 255 characters"));
    }

    @Test
    @DisplayName("answers the wrong method and the wrong media type in the documented shape")
    void refusesTheWrongMethodAndMediaType() throws Exception {
        // Both statuses are published in the README's error table, and both come from extending
        // Spring's ResponseEntityExceptionHandler rather than from anything written here. Dropping
        // that one clause would turn each of them into a 500 with nothing else going red.
        mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, CREDENTIAL))
                .andExpect(status().isMethodNotAllowed());

        mockMvc.perform(post(PATH)
                        .header(HttpHeaders.AUTHORIZATION, CREDENTIAL)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(VALID_BODY))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    @DisplayName("refuses the notification without a credential, and writes no row")
    void refusesAnUnauthenticatedCaller() throws Exception {
        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY)
                        .header("Idempotency-Key", "it-anonymous"))
                .andExpect(status().isUnauthorized());

        assertThat(countOf("it-anonymous")).isZero();
    }

    @Test
    @DisplayName("answers a refusal and a rejection in the same shape, from its two producers")
    void bothProducersAgreeOnTheShape() throws Exception {
        // The authentication entry point and the exception handler are two separate producers of
        // these responses, because a credential is refused in the filter chain and a body is
        // rejected in the controller. The README promises RFC 9457 for both, and only this
        // comparison keeps that promise honest.
        MvcResult refused = mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andReturn();

        MvcResult rejected = mockMvc.perform(submit("{}"))
                .andExpect(status().isBadRequest())
                .andReturn();

        for (MvcResult result : new MvcResult[] {refused, rejected}) {
            assertThat(result.getResponse().getContentType())
                    .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);

            JsonNode problem = objectMapper.readTree(result.getResponse().getContentAsString());

            // Compared exactly, not by containment. A subset check passes while one producer
            // quietly omits a member the other sends -- which is exactly what happened: the 401
            // carried no instance, because Spring fills that in on the way out of the dispatcher
            // servlet and a refusal in the filter chain never reaches it.
            Set<String> members = new TreeSet<>();
            problem.fieldNames().forEachRemaining(members::add);
            // errors is an extension member of RFC 9457, present only when there is a list of
            // offending fields or headers to carry. The members below are the standard ones, and
            // those are what the two producers have to agree on.
            members.remove("errors");
            assertThat(members).containsExactly("detail", "instance", "status", "title", "type");
            assertThat(problem.get("status").asInt())
                    .isEqualTo(result.getResponse().getStatus());
            assertThat(problem.get("detail").asText()).isNotBlank();
        }
    }

    private static MockHttpServletRequestBuilder submit(String body) {
        return post(PATH)
                .header(HttpHeaders.AUTHORIZATION, CREDENTIAL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private String idOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private Map<String, Object> rowOf(String idempotencyKey) {
        return jdbc.queryForMap(
                "SELECT * FROM notification WHERE idempotency_key = ?", idempotencyKey);
    }

    private Integer countOf(String idempotencyKey) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM notification WHERE idempotency_key = ?",
                Integer.class, idempotencyKey);
    }
}

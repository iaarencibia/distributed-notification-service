package io.github.iaarencibia.notifications.config;

import io.github.iaarencibia.notifications.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the production filter chain: the real filter, the real entry point and the real
 * actuator endpoints.
 *
 * <p>{@code /actuator/metrics} stands in for a protected route because no business endpoint
 * exists yet. It is exposed, it is not on the permitted list, and it exists — so a {@code 200}
 * proves the request cleared the chain, with nothing inferred about unmapped paths.
 */
@SpringBootTest(properties = IntegrationTestSupport.API_KEY_PROPERTY)
@AutoConfigureMockMvc
class ApiKeySecurityIT extends IntegrationTestSupport {

    private static final String CREDENTIAL = "ApiKey " + API_KEY;
    private static final String CHALLENGE = "ApiKey realm=\"notification-service\"";

    private static final String PROTECTED_PATH = "/actuator/metrics";
    private static final String HEALTH_PATH = "/actuator/health";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("rejects a request that carries no credential, and says what is missing")
    void rejectsMissingCredential() throws Exception {
        mockMvc.perform(get(PROTECTED_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, CHALLENGE))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").value(containsString("ApiKey")));
    }

    @Test
    @DisplayName("rejects a credential that uses the right scheme with the wrong key")
    void rejectsWrongKey() throws Exception {
        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "ApiKey not-the-key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("says the same thing whether the credential was absent or merely wrong")
    void rejectionsDoNotRevealWhichPartWasWrong() throws Exception {
        String withoutHeader = bodyOfRejection(get(PROTECTED_PATH));
        String withWrongKey = bodyOfRejection(
                get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "ApiKey not-the-key"));

        // Telling the two apart would confirm to someone guessing that the header and the scheme
        // were already right. It would also make the message untrue in one of the two cases.
        assertThat(withWrongKey)
                .as("a wrong key must not be distinguishable from a missing one")
                .isEqualTo(withoutHeader);
        assertThat(withWrongKey)
                .as("the detail must describe the credential, not claim the header was absent")
                .contains("A valid credential");
    }


    @Test
    @DisplayName("rejects the right key presented under a scheme this service does not accept")
    void rejectsWrongScheme() throws Exception {
        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(status().isUnauthorized());
        // A near miss as well as the obvious one. Which parser slip would let either of them
        // through is pinned in ApiKeyAuthenticationFilterTest, where the parser's answer is
        // readable; what this asserts is only that the chain turns both away end to end.
        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "ApiKeyV2 " + API_KEY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("admits a request carrying a valid credential")
    void admitsValidCredential() throws Exception {
        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, CREDENTIAL))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("leaves the health probe open, because the container healthcheck has no credential")
    void healthProbeNeedsNoCredential() throws Exception {
        mockMvc.perform(get(HEALTH_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    @DisplayName("leaves open the readiness probe the container healthcheck actually polls")
    void readinessProbeNeedsNoCredential() throws Exception {
        // docker-compose.yml polls this exact path, with no credential. Asserting only the group
        // root would let a narrower matcher leave the container permanently unhealthy -- and
        // `docker compose up` permanently broken -- with this suite still green.
        mockMvc.perform(get(HEALTH_PATH + "/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("widens the health response to per-component detail for an authenticated caller")
    void healthDetailIsAuthenticatedOnly() throws Exception {
        mockMvc.perform(get(HEALTH_PATH).header(HttpHeaders.AUTHORIZATION, CREDENTIAL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components").exists());
    }

    @Test
    @DisplayName("creates no session, on the rejected request as much as on the accepted one")
    void createsNoSession() throws Exception {
        // Asserted on the request, not on a Set-Cookie header. That header is written by the
        // servlet container, which this test does not run, so asserting its absence here would
        // hold whether or not a session had been opened.
        MvcResult accepted = mockMvc
                .perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, CREDENTIAL))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(accepted.getRequest().getSession(false))
                .as("an accepted request must not open a session")
                .isNull();

        // The rejection is the path that matters. Before handing over to the entry point, the
        // chain saves the original request so it could be replayed after a login, and the
        // default place to save it is a session -- which an unauthenticated caller could then
        // allocate on every attempt, without holding a credential at all.
        MvcResult rejected = mockMvc.perform(get(PROTECTED_PATH))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertThat(rejected.getRequest().getSession(false))
                .as("a rejected request must not open a session either")
                .isNull();
    }

    @Test
    @DisplayName("does not reject an unsafe method for want of a CSRF token")
    void unsafeMethodsAreNotGuardedByCsrf() throws Exception {
        // The status that comes back is beside the point -- what matters is that it is not the
        // 403 the CSRF filter produces. Pinning it would tie this test to how the actuator
        // happens to answer a POST today.
        mockMvc.perform(post(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, CREDENTIAL))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("a POST must not be turned away for want of a CSRF token")
                        .isNotEqualTo(HttpStatus.FORBIDDEN.value()));
    }

    private String bodyOfRejection(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}

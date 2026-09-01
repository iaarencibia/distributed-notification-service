package io.github.iaarencibia.notifications.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the one thing the sliced security test structurally cannot.
 *
 * <p>When a request ends in {@code sendError}, the container dispatches it a second time to
 * {@code /error}, and the security chain runs again over that dispatch. A filter that only wrote
 * its result into the thread-local holder has already lost it by then, so an authenticated caller
 * would be re-evaluated as anonymous and told its credential was missing -- in place of the status
 * that actually applies.
 *
 * <p>MockMvc performs no container error dispatch, so no assertion made through it can observe
 * that. This test therefore runs a real servlet container on a random port, and is the only
 * reason a second application context exists in this package.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {NoPersistence.API_KEY_PROPERTY, NoPersistence.EXCLUDE})
class ApiKeyErrorDispatchTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("answers an authenticated caller with the real status when the request errors")
    void authenticatedCallerSurvivesTheErrorDispatch() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "ApiKey " + NoPersistence.API_KEY);

        ResponseEntity<String> response = restTemplate.exchange(
                "/no-such-path", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode())
                .as("a credential the service accepted must not come back as 401 on an error")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("still refuses an unauthenticated caller on the same error path")
    void unauthenticatedCallerIsStillRefused() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/no-such-path", String.class);

        assertThat(response.getStatusCode())
                .as("carrying the context across the dispatch must not open a way in")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("hides the real status of an error even on the one permitted route")
    void anonymousErrorsAreIndistinguishable() {
        // A POST to the health endpoint is a 405 once past the chain, yet an anonymous caller is
        // answered 401, because the container's /error dispatch is denied like everything else.
        // That is deliberate, not an oversight: letting the true status through would tell an
        // anonymous caller which routes exist and which do not. Declared in the README.
        ResponseEntity<String> response = restTemplate.exchange(
                "/actuator/health", HttpMethod.POST, HttpEntity.EMPTY, String.class);

        assertThat(response.getStatusCode())
                .as("an anonymous caller must not learn a route apart by its error status")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}

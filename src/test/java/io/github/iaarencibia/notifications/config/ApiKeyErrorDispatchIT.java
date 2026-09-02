package io.github.iaarencibia.notifications.config;

import io.github.iaarencibia.notifications.IntegrationTestSupport;
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
 * What a caller actually receives, over real HTTP, from a real servlet container on a random
 * port: the statuses that separate "you may not" from "there is nothing there", and the one route
 * that answers everybody.
 *
 * <p>It was written for something narrower. A request that ends in {@code sendError} used to be
 * dispatched a second time by the container to {@code /error}, and a filter that had written its
 * result only into the thread-local holder had already lost it by then -- so an authenticated
 * caller came back anonymous. MockMvc performs no such dispatch, which is why a real container
 * was needed to see it.
 *
 * <p>That dispatch no longer happens: the web adapter's exception handler writes error responses
 * itself. So this class can no longer observe the loss, and the assertion that protects against
 * it moved to {@code ApiKeyAuthenticationFilterTest}, where it is a unit test and still fails when
 * the save is removed. Verified by removing it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = IntegrationTestSupport.API_KEY_PROPERTY)
class ApiKeyErrorDispatchIT extends IntegrationTestSupport {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("answers an authenticated caller with the real status when the request errors")
    void authenticatedCallerSurvivesTheErrorDispatch() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "ApiKey " + API_KEY);

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
    @DisplayName("tells an anonymous caller nothing that separates a real route from a missing one")
    void anonymousCallersCannotMapTheSurface() {
        // The property that matters, asserted directly rather than through a side effect. A route
        // that exists and requires a credential, and a route that does not exist at all, answer
        // an anonymous caller identically -- so no amount of guessing draws a map of the service.
        ResponseEntity<String> real = restTemplate.getForEntity("/actuator/metrics", String.class);
        ResponseEntity<String> missing = restTemplate.getForEntity("/no-such-path", String.class);

        assertThat(real.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(missing.getStatusCode())
                .as("a route that exists and one that does not must answer the same")
                .isEqualTo(real.getStatusCode());
    }

    @Test
    @DisplayName("answers the accurate status on the one route open to everyone")
    void thePermittedRouteAnswersAccurately() {
        // A POST to the health probe is a 405, and an anonymous caller is now told so. It used to
        // be answered 401, because the status travelled through the container's /error dispatch
        // and that dispatch is denied like everything else; the web adapter's exception handler
        // now writes the response itself and never reaches it.
        //
        // Nothing leaks by that. This route is permitted and documented, so its existence was
        // never a secret -- and every route that is not permitted still fails in the chain, above
        // the handler, which is what the test before this one holds in place.
        ResponseEntity<String> response = restTemplate.exchange(
                "/actuator/health", HttpMethod.POST, HttpEntity.EMPTY, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }
}

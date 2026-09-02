package io.github.iaarencibia.notifications.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reads the parser's answer directly, which is the one thing a test through the filter chain
 * cannot do.
 *
 * <p>From outside, "no credential was found" and "a credential was found and did not match" are
 * the same {@code 401} — deliberately, so that nobody guessing keys learns which half they got
 * right. That makes an end-to-end assertion unable to fail for a parsing regression: a parser
 * that returned the raw header instead of {@code null} would still be turned away by the
 * comparison, and the response would not change. Here the outcome is the authentication itself,
 * so the two are told apart.
 */
class ApiKeyAuthenticationFilterTest {

    private static final String KEY = "the-configured-key";

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @DisplayName("reads no credential at all from a header it cannot parse")
    @ValueSource(strings = {"", "   ", "ApiK", "ApiKey", "ApiKey ", "ApiKey   ",
            "ApiKey" + KEY, "ApiKeyV2 " + KEY, "Bearer " + KEY})
    void readsNoCredentialFromAnUnparseableHeader(String authorization) throws Exception {
        // The one that earns its place is the scheme name run straight into the key, with no
        // separator. Drop the guard that requires one and every other value here still comes
        // back unauthenticated -- because none of them parses into something that matches --
        // while that one parses into the key itself and lets the caller in.
        assertThat(authenticationAfter(KEY, authorization))
                .as("the request must be left unauthenticated, not merely unmatched")
                .isNull();
    }

    @ParameterizedTest
    @DisplayName("reads the credential through every separator form RFC 9110 allows")
    @ValueSource(strings = {"ApiKey " + KEY, "ApiKey  " + KEY, "ApiKey " + KEY + "  ",
            "ApiKey   " + KEY + " ", "apikey " + KEY})
    void readsTheCredentialThroughEverySeparatorForm(String authorization) throws Exception {
        assertThat(authenticationAfter(KEY, authorization)).isNotNull();
    }

    @Test
    @DisplayName("leaves a request carrying the wrong key unauthenticated")
    void leavesTheWrongKeyUnauthenticated() throws Exception {
        assertThat(authenticationAfter(KEY, "ApiKey not-" + KEY)).isNull();
    }

    @Test
    @DisplayName("still authenticates when the configured key was typed with stray whitespace")
    void toleratesWhitespaceAroundTheConfiguredKey() throws Exception {
        // A space cannot travel inside a token68, so it is never part of a key -- only a typo in
        // the file the operator edited, and an invisible one. Trimming what the caller sends
        // while keeping it on the configured side would reject every request forever, and
        // nothing in the logs would say why.
        assertThat(authenticationAfter("  " + KEY + "  ", "ApiKey " + KEY))
                .as("a stray space in configuration must not lock the service out of itself")
                .isNotNull();
    }

    @Test
    @DisplayName("saves the recognised caller where a later dispatch of the same request finds it")
    void savesTheContextForALaterDispatch() throws Exception {
        // The holder is cleared when the REQUEST pass ends, and this filter does not run again on
        // the ERROR or ASYNC dispatch that can follow. Without the save, an authenticated caller
        // whose request takes either path comes back as anonymous and is told its credential was
        // missing. That was a real defect, found by the judgment day of the previous PR.
        //
        // It is asserted here rather than end to end, and that is a change worth knowing about:
        // it used to be covered by a test that drove a real servlet container into the ERROR
        // dispatch. Since the web adapter answers errors itself, that dispatch no longer happens
        // and the end-to-end test stopped being able to fail. This one still can.
        RequestAttributeSecurityContextRepository repository =
                new RequestAttributeSecurityContextRepository();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/anything");
        request.addHeader(HttpHeaders.AUTHORIZATION, "ApiKey " + KEY);

        new ApiKeyAuthenticationFilter(KEY, repository)
                .doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(repository.loadDeferredContext(request).get().getAuthentication())
                .as("the context has to outlive the holder, which the request pass clears")
                .isNotNull();
    }

    @Test
    @DisplayName("saves nothing for a caller it did not recognise")
    void savesNothingForAnUnrecognisedCaller() throws Exception {
        // The other half of the pair: carrying the context across a dispatch must not become a
        // way in for someone who never presented a credential the service accepted.
        RequestAttributeSecurityContextRepository repository =
                new RequestAttributeSecurityContextRepository();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/anything");
        request.addHeader(HttpHeaders.AUTHORIZATION, "ApiKey not-" + KEY);

        new ApiKeyAuthenticationFilter(KEY, repository)
                .doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(repository.containsContext(request)).isFalse();
    }

    /**
     * @param configuredKey the key the service is started with
     * @param authorization the raw header the caller sends
     * @return the authentication the filter left behind, or {@code null} if it recognised nobody
     */
    private static Authentication authenticationAfter(String configuredKey, String authorization)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/anything");
        request.addHeader(HttpHeaders.AUTHORIZATION, authorization);

        new ApiKeyAuthenticationFilter(configuredKey, new RequestAttributeSecurityContextRepository())
                .doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        return SecurityContextHolder.getContext().getAuthentication();
    }
}

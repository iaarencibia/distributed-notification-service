package io.github.iaarencibia.notifications.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * Writes the response for a request the chain refused to let through.
 *
 * <p>It exists because that refusal happens inside the filter chain, before the request reaches
 * the dispatcher servlet, and so out of reach of any {@code @ControllerAdvice} the web adapter
 * registers later. Left to itself, a chain that configures neither form login nor HTTP basic
 * falls back to {@code Http403ForbiddenEntryPoint}: the caller would get a bodiless {@code 403},
 * with no challenge and the wrong status, as the first thing a new integration ever sees.
 *
 * <p>The wording is the same whether the credential was absent or simply wrong. The two are
 * distinguishable here, and are deliberately not distinguished: telling them apart would confirm
 * to a caller that its header and scheme were right and only the key was not, which narrows the
 * search for someone guessing.
 */
class ApiKeyAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final String CHALLENGE =
            ApiKeyAuthenticationFilter.SCHEME + " realm=\"notification-service\"";

    private static final String DETAIL =
            "A valid credential using the " + ApiKeyAuthenticationFilter.SCHEME
                    + " scheme is required in the Authorization header";

    private final ObjectMapper objectMapper;

    ApiKeyAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authenticationException) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        // RFC 9110 requires a 401 to carry a challenge. Naming the scheme is also the only way a
        // caller learns what to send, since the credential does not travel in a header it could
        // have guessed.
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, CHALLENGE);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, DETAIL);
        this.objectMapper.writeValue(response.getOutputStream(), problem);
    }
}

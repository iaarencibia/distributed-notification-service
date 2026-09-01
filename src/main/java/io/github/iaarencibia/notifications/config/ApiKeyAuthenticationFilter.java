package io.github.iaarencibia.notifications.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Recognises the caller, and nothing else.
 *
 * <p>A request that arrives without a credential, or with the wrong one, is left
 * unauthenticated and allowed to continue: <strong>rejecting is not this filter's job</strong>.
 * Which routes require a caller is declared once, in {@link SecurityConfig}, so that the access
 * policy can be read in one place instead of being split between a filter and a rule that could
 * drift apart.
 */
class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    /** The authentication scheme, as it is announced in the {@code WWW-Authenticate} challenge. */
    static final String SCHEME = "ApiKey";

    /**
     * Names the caller in the security context, which is what reaches the logs and the actuator.
     * There is a single credential, so there is nothing more specific to say.
     */
    private static final String PRINCIPAL = "api-client";

    private final byte[] expectedKey;
    private final SecurityContextRepository securityContextRepository;

    ApiKeyAuthenticationFilter(String apiKey, SecurityContextRepository securityContextRepository) {
        // Trimmed on both sides of the comparison or on neither. A token68 cannot carry a space,
        // so one around the configured value is never the key -- only an invisible typo, and
        // trimming just the caller's side would let it lock the service out of itself.
        this.expectedKey = apiKey.strip().getBytes(StandardCharsets.UTF_8);
        this.securityContextRepository = securityContextRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String presented = presentedKey(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (presented != null && matches(presented)) {
            // No authority is granted: access here is all or nothing, and an authority nobody
            // consults would be a permission model that does not exist.
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new PreAuthenticatedAuthenticationToken(
                    PRINCIPAL, null, AuthorityUtils.NO_AUTHORITIES));
            SecurityContextHolder.setContext(context);

            // Saved as well as held: the holder is cleared when the REQUEST pass ends and this
            // filter does not run on the ERROR dispatch that may follow, so without the save an
            // authenticated caller whose request ends in sendError comes back as anonymous.
            this.securityContextRepository.saveContext(context, request, response);
        }
        chain.doFilter(request, response);
    }

    /**
     * @param authorization the raw {@code Authorization} header, or {@code null} when absent
     * @return the credential that follows the scheme name, or {@code null} when the header is
     *         missing, names another scheme, or carries no credential after the scheme
     */
    private static String presentedKey(String authorization) {
        if (authorization == null
                || !authorization.regionMatches(true, 0, SCHEME, 0, SCHEME.length())) {
            // The scheme name is case-insensitive in RFC 9110, so `apikey` names the same scheme.
            return null;
        }

        // RFC 9110 puts 1*SP between the scheme and the credential, not exactly one, and the
        // field value may carry trailing whitespace. A caller that sends either is sending a
        // well-formed header, and refusing it would break the very specification the challenge
        // above claims to speak.
        int start = SCHEME.length();
        while (start < authorization.length() && authorization.charAt(start) == ' ') {
            start++;
        }
        if (start == SCHEME.length()) {
            // Nothing separates the scheme name from whatever follows it. Either the header names
            // a different scheme that merely begins with these characters -- `ApiKeyV2 ...` -- or
            // it names this one and stops there, carrying no credential to read. Without this
            // guard, `ApiKey<key>` would parse into the key itself and let the caller straight in.
            return null;
        }
        String credential = authorization.substring(start).strip();
        return credential.isEmpty() ? null : credential;
    }

    /**
     * Compares in constant time. {@code String.equals} returns as soon as two characters differ,
     * so how long it takes tells a caller how many leading characters it guessed, and the key can
     * be recovered one at a time. {@link MessageDigest#isEqual} folds the length difference into
     * an accumulator and walks the whole presented array instead, so neither the content nor the
     * length of the configured key shows up in the timing.
     *
     * @param presented the credential the caller supplied
     * @return whether it is the configured key
     */
    private boolean matches(String presented) {
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), this.expectedKey);
    }
}

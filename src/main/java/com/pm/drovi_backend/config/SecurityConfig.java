package com.pm.drovi_backend.config;

import com.pm.drovi_backend.common.ApiError;
import com.pm.drovi_backend.common.CorrelationIdFilter;
import com.pm.drovi_backend.common.ErrorCode;
import com.pm.drovi_backend.identity.AccountService;
import com.pm.drovi_backend.identity.DroviPrincipal;
import com.pm.drovi_backend.identity.VerifiedIdentity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.config.Customizer;
import tools.jackson.databind.ObjectMapper;

/**
 * Deny by default, with exactly two anonymous surfaces — each listed here with its reason.
 *
 * <p>The sandbox chain comes first and is the one to be careful with: {@code /s/**} has its
 * own authentication (project API keys, per the project's {@code auth_mode}) and must never
 * be subjected to Firebase auth. Doing so would break every user's integration at once,
 * because their applications hold an API key and no Firebase token.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(FirebaseProperties.class)
@RequiredArgsConstructor
@Slf4j
public class SecurityConfig {

    private final ObjectMapper mapper;

    /**
     * The sandbox surface. Anonymous to Spring Security by design — {@code SandboxRuntime}
     * authenticates it against {@code project_api_key} according to each project's own
     * auth mode.
     */
    @Bean
    @Order(1)
    SecurityFilterChain sandboxChain(HttpSecurity http,
                                     @Qualifier("corsConfigurationSource") CorsConfigurationSource corsSource)
            throws Exception {
        return http.securityMatcher("/s/**")
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .cors(c -> c.configurationSource(corsSource))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // The imitated product sets its own headers; ours must not leak into a
                // response that is pretending to come from somebody else's API.
                .headers(h -> h.disable())
                .build();
    }

    /** Liveness only. {@code env} and {@code heapdump} are not exposed at all. */
    @Bean
    @Order(2)
    SecurityFilterChain healthChain(HttpSecurity http) throws Exception {
        return http.securityMatcher("/actuator/health/**")
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                .build();
    }

    /**
     * Everything else. Authenticated always — including paths that do not exist yet, so a
     * new controller is protected the moment it is written rather than when someone
     * remembers to add it to a list.
     *
     * <p>When no Firebase project is configured there is no {@link JwtDecoder}, and the
     * chain denies everything with {@code AUTH_NOT_CONFIGURED} rather than starting up in
     * a state where console routes are reachable without a credential.
     */
    @Bean
    @Order(3)
    SecurityFilterChain consoleChain(HttpSecurity http,
                                     @Qualifier("corsConfigurationSource") CorsConfigurationSource corsSource,
                                     ObjectProvider<JwtDecoder> jwtDecoder,
                                     ObjectProvider<Converter<Jwt, AbstractAuthenticationToken>> converter)
            throws Exception {

        // Not merely authenticated: the account must be usable. A suspended one holds a
        // valid token and no authority, so it is denied rather than let through.
        http.authorizeHttpRequests(a -> a.anyRequest().hasAuthority(DroviPrincipal.ACTIVE_AUTHORITY))
                // Before the authority check has any say: a preflight carries no Authorization
                // header, so without CORS wired here every console request fails at OPTIONS and
                // the browser reports a network error rather than a 401.
                .cors(c -> c.configurationSource(corsSource))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        JwtDecoder decoder = jwtDecoder.getIfAvailable();
        if (decoder == null) {
            log.warn("security.unconfigured — no drovi.firebase.project-id; every console route will 503");
            http.exceptionHandling(e -> e.authenticationEntryPoint(
                    entryPoint(ErrorCode.AUTH_NOT_CONFIGURED,
                            "Authentication is not configured on this server.")));
            return http.build();
        }

        http.oauth2ResourceServer(o -> o
                        .jwt(j -> j.decoder(decoder)
                                .jwtAuthenticationConverter(converter.getObject()))
                        .authenticationEntryPoint(
                                entryPoint(ErrorCode.UNAUTHENTICATED, "Missing or invalid credentials.")))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(
                                entryPoint(ErrorCode.UNAUTHENTICATED, "Missing or invalid credentials."))
                        // Deliberately not "your account is suspended": the reason is the
                        // operator's business, and spelling it out confirms which accounts
                        // exist and are disabled.
                        .accessDeniedHandler((request, response, denied) ->
                                write(response, ErrorCode.FORBIDDEN, "This account cannot be used.")));
        return http.build();
    }

    /**
     * Rejections leave in the same envelope as every other console error, so a client has
     * one shape to parse. The reason is never spelled out — "expired" versus "wrong
     * audience" is a hint we do not owe an unauthenticated caller.
     */
    private AuthenticationEntryPoint entryPoint(ErrorCode code, String message) {
        return (request, response, authException) -> write(response, code, message);
    }

    private void write(jakarta.servlet.http.HttpServletResponse response, ErrorCode code, String message)
            throws java.io.IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(CorrelationIdFilter.HEADER, CorrelationIdFilter.current());
        response.getWriter().write(mapper.writeValueAsString(
                ApiError.of(code, message, CorrelationIdFilter.current())));
    }

    /**
     * Present only when a project is configured, which is what makes the unconfigured case
     * fail closed instead of silently accepting anything.
     */
    @Bean
    @ConditionalOnProperty(prefix = "drovi.firebase", name = "project-id")
    JwtDecoder firebaseJwtDecoder(FirebaseProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withJwkSetUri(FirebaseProperties.JWK_SET_URI)
                .build();

        // Issuer and expiry come from the default validator. Audience is added because
        // without it, a valid Firebase token minted for ANY OTHER project would verify --
        // the signing keys are shared across all of Firebase.
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()),
                audienceValidator(properties.projectId())));
        return decoder;
    }

    private static OAuth2TokenValidator<Jwt> audienceValidator(String projectId) {
        return jwt -> {
            if (jwt.getAudience() != null && jwt.getAudience().contains(projectId)
                    && jwt.getSubject() != null && !jwt.getSubject().isBlank()) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Token is not for this project.", null));
        };
    }

    /**
     * Verified token → local account. Provisioning happens here, on the first authenticated
     * call, which is why there is no signup endpoint.
     */
    @Bean
    Converter<Jwt, AbstractAuthenticationToken> droviJwtConverter(AccountService accounts) {
        return jwt -> {
            DroviPrincipal principal = accounts.resolve(new VerifiedIdentity(
                    jwt.getSubject(),
                    jwt.getClaimAsString("email"),
                    jwt.getClaimAsString("name")));
            return new DroviAuthenticationToken(principal);
        };
    }
}

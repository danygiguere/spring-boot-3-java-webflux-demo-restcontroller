package com.example.restcontrollerdemo.security;

import com.example.restcontrollerdemo.exception.AppException;
import com.example.restcontrollerdemo.exception.ApplicationExceptionHandler;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;

/**
 * Stateless bearer-token API. This filter chain only AUTHENTICATES; whether the caller may touch a given resource
 * (author) is checked in the service at the point of action.
 */
@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    /** The only routes reachable without a token. Everything else is denied by default. */
    static final String[] PUBLIC_PATHS = {
        // API docs (disabled unless SWAGGER_ENABLED)
        "/swagger-ui.html",
        "/swagger-ui/**",
        "/webjars/**",
        "/v3/api-docs/**",
        // Dev token endpoint (exists only with DEV_TOKEN_ENABLED)
        "/auth/dev-token"
    };

    private static final Pattern NUMERIC = Pattern.compile("[1-9][0-9]{0,18}");

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http, ApplicationExceptionHandler exceptionHandler) {
        // 401/403 go through the same handler as every other error: same RFC 9457 shape, locale and correlationId.
        ServerAuthenticationEntryPoint unauthorized =
                (exchange, ex) -> exceptionHandler.handle(exchange, new AppException.Unauthorized("error.401"));
        ServerAccessDeniedHandler forbidden =
                (exchange, ex) -> exceptionHandler.handle(exchange, new AppException.Forbidden("error.403"));

        return http
                // Bearer tokens, no cookies: the browser never attaches credentials on its own, so CSRF does not apply.
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(PUBLIC_PATHS)
                        .permitAll()
                        .anyExchange()
                        .authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden))
                .exceptionHandling(handling ->
                        handling.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
                .build();
    }

    /**
     * Verifies the signature with the pinned algorithm (HS256 only — the token's own {@code alg} header is not
     * trusted), then {@code exp} (required), {@code nbf}, {@code iss}, {@code aud}, and the shape of the claims this
     * application reads. Any failure is a 401.
     */
    @Bean
    public ReactiveJwtDecoder jwtDecoder(JwtProperties properties) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withSecretKey(secretKey(properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()),
                new JwtClaimValidator<>(JwtClaimNames.EXP, Objects::nonNull),
                new JwtClaimValidator<List<String>>(
                        JwtClaimNames.AUD, aud -> aud != null && aud.contains(properties.audience())),
                claimShapeValidator()));
        return decoder;
    }

    /** {@code sub} is a positive user id: a malformed claim is a 401, not a 500. */
    static OAuth2TokenValidator<Jwt> claimShapeValidator() {
        return jwt -> {
            String subject = jwt.getSubject();
            return subject != null && NUMERIC.matcher(subject).matches()
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(
                            new OAuth2Error("invalid_token", "Missing or malformed sub claim", null));
        };
    }

    static SecretKey secretKey(JwtProperties properties) {
        return new SecretKeySpec(properties.secretBytes(), "HmacSHA256");
    }
}

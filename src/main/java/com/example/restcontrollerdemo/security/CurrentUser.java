package com.example.restcontrollerdemo.security;

import com.example.restcontrollerdemo.exception.AppException;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** The authenticated caller. In WebFlux the security context travels in the Reactor context, hence a {@link Mono}. */
@Component
public class CurrentUser {

    public Mono<AuthenticatedUser> get() {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .map(token -> AuthenticatedUser.from(token.getToken()))
                .switchIfEmpty(Mono.error(() -> new AppException.Unauthorized("error.401")));
    }
}

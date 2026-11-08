package com.example.restcontrollerdemo.security;

import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.BindingContext;
import org.springframework.web.reactive.result.method.HandlerMethodArgumentResolver;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Lets controller methods declare {@code AuthenticatedUser me}: the caller from the verified JWT, or a 401. */
@Component
public class AuthenticatedUserArgumentResolver implements HandlerMethodArgumentResolver {

    private final CurrentUser currentUser;

    public AuthenticatedUserArgumentResolver(CurrentUser currentUser) {
        this.currentUser = currentUser;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return AuthenticatedUser.class.equals(parameter.getParameterType());
    }

    @Override
    public Mono<Object> resolveArgument(
            MethodParameter parameter, BindingContext bindingContext, ServerWebExchange exchange) {
        return currentUser.get().cast(Object.class);
    }
}

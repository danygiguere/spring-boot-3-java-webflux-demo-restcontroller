package com.example.restcontrollerdemo.config;

import com.example.restcontrollerdemo.security.AuthenticatedUserArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.result.method.annotation.ArgumentResolverConfigurer;

@Configuration
public class WebConfig implements WebFluxConfigurer {

    private final AuthenticatedUserArgumentResolver authenticatedUserResolver;

    public WebConfig(AuthenticatedUserArgumentResolver authenticatedUserResolver) {
        this.authenticatedUserResolver = authenticatedUserResolver;
    }

    @Override
    public void configureArgumentResolvers(ArgumentResolverConfigurer configurer) {
        configurer.addCustomResolver(authenticatedUserResolver);
    }
}

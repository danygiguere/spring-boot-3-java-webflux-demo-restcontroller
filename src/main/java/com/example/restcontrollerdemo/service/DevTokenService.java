package com.example.restcontrollerdemo.service;

import com.example.restcontrollerdemo.dto.TokenResponse;
import com.example.restcontrollerdemo.exception.AppException;
import com.example.restcontrollerdemo.repository.UserRepository;
import com.example.restcontrollerdemo.security.DevTokenIssuer;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** Passwordless token for local development. Exists only in the dev profile, which binds to loopback. */
@Service
@Profile("dev")
public class DevTokenService {

    private final UserRepository userRepository;
    private final DevTokenIssuer issuer;

    public DevTokenService(UserRepository userRepository, DevTokenIssuer issuer) {
        this.userRepository = userRepository;
        this.issuer = issuer;
    }

    public Mono<TokenResponse> issue(String email) {
        return userRepository
                .findByEmail(email)
                .switchIfEmpty(Mono.error(() -> new AppException.Unauthorized("error.auth.invalid")))
                .map(issuer::issue);
    }
}

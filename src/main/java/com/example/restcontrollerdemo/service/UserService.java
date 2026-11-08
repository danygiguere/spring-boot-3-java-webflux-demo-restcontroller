package com.example.restcontrollerdemo.service;

import com.example.restcontrollerdemo.domain.entity.User;
import com.example.restcontrollerdemo.dto.MeResponse;
import com.example.restcontrollerdemo.dto.ProfileRequest;
import com.example.restcontrollerdemo.dto.ProfileResponse;
import com.example.restcontrollerdemo.dto.UserResponse;
import com.example.restcontrollerdemo.exception.AppException;
import com.example.restcontrollerdemo.repository.ProfileRepository;
import com.example.restcontrollerdemo.repository.UserRepository;
import com.example.restcontrollerdemo.security.AuthenticatedUser;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;

    public UserService(UserRepository userRepository, ProfileRepository profileRepository) {
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
    }

    public Mono<MeResponse> me(AuthenticatedUser me) {
        return requireColleague(me, me.userId()).map(MeResponse::of);
    }

    public Flux<UserResponse> findAll(AuthenticatedUser me, long afterId, int limit) {
        return userRepository
                .findPageInEnterprise(me.enterpriseId(), afterId, limit)
                .map(UserResponse::of);
    }

    public Mono<UserResponse> findById(AuthenticatedUser me, long id) {
        return requireColleague(me, id).map(UserResponse::of);
    }

    public Mono<ProfileResponse> findProfile(AuthenticatedUser me, long userId) {
        return requireColleague(me, userId)
                .flatMap(user -> profileRepository.findByUserId(user.id()))
                .switchIfEmpty(Mono.error(() -> new AppException.NotFound("error.profile.not.found", userId)))
                .map(ProfileResponse::of);
    }

    /** Only the caller's own profile is writable; the target comes from the token, not the request. */
    public Mono<ProfileResponse> replaceMyProfile(AuthenticatedUser me, ProfileRequest request) {
        return requireColleague(me, me.userId())
                .flatMap(user -> profileRepository.upsert(user.id(), request.bio(), request.avatarUrl()))
                .map(ProfileResponse::of);
    }

    /** A user of the caller's enterprise; any other user is reported as not found (no cross-tenant probing). */
    public Mono<User> requireColleague(AuthenticatedUser me, long userId) {
        return userRepository
                .findInEnterprise(userId, me.enterpriseId())
                .switchIfEmpty(Mono.error(() -> new AppException.NotFound("error.user.not.found", userId)));
    }
}

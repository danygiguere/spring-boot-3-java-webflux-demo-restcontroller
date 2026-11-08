package com.example.restcontrollerdemo.repository;

import com.example.restcontrollerdemo.domain.entity.Profile;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface ProfileRepository extends ReactiveCrudRepository<Profile, Long> {

    /** hasOne — a {@link Mono} is safe because of UNIQUE (user_id). */
    Mono<Profile> findByUserId(Long userId);

    /** Create or replace in one atomic statement: no check-then-insert race, idempotent. */
    @Query(
            """
            INSERT INTO profiles (user_id, bio, avatar_url)
            VALUES (:userId, :bio, :avatarUrl)
            ON CONFLICT (user_id) DO UPDATE SET bio = EXCLUDED.bio, avatar_url = EXCLUDED.avatar_url
            RETURNING *
            """)
    Mono<Profile> upsert(Long userId, String bio, String avatarUrl);
}

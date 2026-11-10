package com.example.restcontrollerdemo.repository;

import com.example.restcontrollerdemo.domain.entity.User;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** R2DBC has no lazy relations, so every relation is an explicit query. */
public interface UserRepository extends ReactiveCrudRepository<User, Long> {

    /** Dev token lookup only (email is UNIQUE). */
    Mono<User> findByEmail(String email);

    /** Oldest first, keyset-paginated. */
    @Query("SELECT * FROM users WHERE id > :afterId ORDER BY id LIMIT :limit")
    Flux<User> findPage(long afterId, int limit);

    /** manyToMany — users who like a post, through post_likes. */
    @Query(
            """
            SELECT u.* FROM users u
            JOIN post_likes l ON l.user_id = u.id
            WHERE l.post_id = :postId AND u.id > :afterId
            ORDER BY u.id
            LIMIT :limit
            """)
    Flux<User> findLikers(Long postId, long afterId, int limit);
}

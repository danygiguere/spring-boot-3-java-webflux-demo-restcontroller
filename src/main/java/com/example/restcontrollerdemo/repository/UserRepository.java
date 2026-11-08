package com.example.restcontrollerdemo.repository;

import com.example.restcontrollerdemo.domain.entity.User;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Tenant-facing queries take the caller's {@code enterpriseId}: a user of another enterprise behaves exactly like a
 * missing one. R2DBC has no lazy relations, so every relation is an explicit query.
 */
public interface UserRepository extends ReactiveCrudRepository<User, Long> {

    /** Cross-tenant by design: dev token lookup only (email is UNIQUE). */
    Mono<User> findByEmail(String email);

    @Query("SELECT * FROM users WHERE id = :id AND enterprise_id = :enterpriseId")
    Mono<User> findInEnterprise(Long id, Long enterpriseId);

    /** hasMany (enterprise → users), oldest first, keyset-paginated. */
    @Query(
            """
            SELECT * FROM users
            WHERE enterprise_id = :enterpriseId AND id > :afterId
            ORDER BY id
            LIMIT :limit
            """)
    Flux<User> findPageInEnterprise(Long enterpriseId, long afterId, int limit);

    /** Bulk load by ids: one array parameter, so the statement does not grow with the list. */
    @Query("SELECT * FROM users WHERE id = ANY(:ids) AND enterprise_id = :enterpriseId")
    Flux<User> findAllInEnterprise(Long[] ids, Long enterpriseId);

    /** manyToMany — users who like a post, through post_likes. */
    @Query(
            """
            SELECT u.* FROM users u
            JOIN post_likes l ON l.user_id = u.id
            WHERE l.post_id = :postId AND u.enterprise_id = :enterpriseId AND u.id > :afterId
            ORDER BY u.id
            LIMIT :limit
            """)
    Flux<User> findLikersInEnterprise(Long postId, Long enterpriseId, long afterId, int limit);
}

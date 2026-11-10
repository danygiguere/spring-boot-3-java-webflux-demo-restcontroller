package com.example.restcontrollerdemo.repository;

import com.example.restcontrollerdemo.domain.entity.PostLike;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface PostLikeRepository extends ReactiveCrudRepository<PostLike, Long> {

    Mono<Long> countByPostId(Long postId);

    /** Idempotent: liking twice leaves one row, enforced by UNIQUE (post_id, user_id). */
    @Modifying
    @Query(
            """
            INSERT INTO post_likes (post_id, user_id)
            VALUES (:postId, :userId)
            ON CONFLICT (post_id, user_id) DO NOTHING
            """)
    Mono<Long> likeIfAbsent(Long postId, Long userId);

    /** Idempotent; targets at most one row (UNIQUE (post_id, user_id)). */
    @Modifying
    @Query("DELETE FROM post_likes WHERE post_id = :postId AND user_id = :userId")
    Mono<Long> unlike(Long postId, Long userId);
}

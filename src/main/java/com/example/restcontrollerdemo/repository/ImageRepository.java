package com.example.restcontrollerdemo.repository;

import com.example.restcontrollerdemo.domain.entity.Image;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface ImageRepository extends ReactiveCrudRepository<Image, Long> {

    /** hasMany (post → images), at most 10 rows (CHECK on position). */
    Flux<Image> findAllByPostIdOrderByPositionAsc(Long postId);

    /** hasManyThrough (user → posts → images), newest first, keyset-paginated. */
    @Query(
            """
            SELECT i.* FROM images i
            JOIN posts p ON p.id = i.post_id
            WHERE p.user_id = :userId AND i.id < :beforeId
            ORDER BY i.id DESC
            LIMIT :limit
            """)
    Flux<Image> findByUser(Long userId, long beforeId, int limit);
}

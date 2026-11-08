package com.example.restcontrollerdemo.repository;

import com.example.restcontrollerdemo.domain.entity.IdempotencyKey;
import java.time.Instant;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface IdempotencyKeyRepository extends ReactiveCrudRepository<IdempotencyKey, Long> {

    /**
     * Claims the key atomically and returns its row id; empty when the key was already claimed. A concurrent claim of
     * the same key waits on the UNIQUE index until the first transaction ends, then sees the committed row.
     */
    @Query(
            """
            INSERT INTO idempotency_keys (user_id, idempotency_key, request_hash, created_at)
            VALUES (:userId, :key, :requestHash, :createdAt)
            ON CONFLICT (user_id, idempotency_key) DO NOTHING
            RETURNING id
            """)
    Mono<Long> claim(Long userId, String key, String requestHash, Instant createdAt);

    Mono<IdempotencyKey> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    @Modifying
    @Query("UPDATE idempotency_keys SET post_id = :postId WHERE id = :id")
    Mono<Long> attachPost(Long id, Long postId);
}

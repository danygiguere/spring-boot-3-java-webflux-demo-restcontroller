package com.example.restcontrollerdemo.repository;

import com.example.restcontrollerdemo.domain.entity.Post;
import com.example.restcontrollerdemo.dto.PostSummaryRow;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A post is visible to every user and writable by its author: every write takes the author's {@code userId}, so the
 * WHERE clause is the permission check and targets at most one row (primary key). List queries return a page of
 * summaries in one statement, newest first, keyset-paginated.
 */
public interface PostRepository extends ReactiveCrudRepository<Post, Long> {

    /**
     * Columns of {@link PostSummaryRow}, selected from a page {@code p} of posts. The page is limited first, so the
     * counts run for at most {@code limit} posts (indexed by UNIQUE (post_id, …)).
     */
    String SUMMARY_COLUMNS =
            """
            SELECT p.id, p.user_id, u.name AS author_name, p.content, p.created_at,
                   (SELECT COUNT(*) FROM images i WHERE i.post_id = p.id) AS image_count,
                   (SELECT COUNT(*) FROM post_likes l WHERE l.post_id = p.id) AS like_count
            """;

    /** Joins the author and keeps the page order. */
    String SUMMARY_JOIN =
            """
            JOIN users u ON u.id = p.user_id
            ORDER BY p.id DESC
            """;

    /** All posts. */
    @Query(SUMMARY_COLUMNS
            + "FROM (SELECT * FROM posts WHERE id < :beforeId ORDER BY id DESC LIMIT :limit) p\n"
            + SUMMARY_JOIN)
    Flux<PostSummaryRow> findFeed(long beforeId, int limit);

    /** hasMany (user → posts). */
    @Query(SUMMARY_COLUMNS
            + "FROM (SELECT * FROM posts WHERE user_id = :userId AND id < :beforeId ORDER BY id DESC LIMIT :limit) p\n"
            + SUMMARY_JOIN)
    Flux<PostSummaryRow> findByAuthor(Long userId, long beforeId, int limit);

    /** manyToMany — posts liked by a user, through post_likes. */
    @Query(SUMMARY_COLUMNS
            + """
            FROM (
                SELECT q.* FROM posts q
                JOIN post_likes pl ON pl.post_id = q.id
                WHERE pl.user_id = :userId AND q.id < :beforeId
                ORDER BY q.id DESC
                LIMIT :limit
            ) p
            """
            + SUMMARY_JOIN)
    Flux<PostSummaryRow> findLikedBy(Long userId, long beforeId, int limit);

    /** Returns the number of rows updated: 1, or 0 when the post is gone or not the caller's. */
    @Modifying
    @Query("UPDATE posts SET content = :content WHERE id = :id AND user_id = :userId")
    Mono<Long> updateContent(Long id, Long userId, String content);

    /** Returns the number of rows deleted: 1, or 0 when the post is gone or not the caller's. */
    @Modifying
    @Query("DELETE FROM posts WHERE id = :id AND user_id = :userId")
    Mono<Long> deleteOwned(Long id, Long userId);
}

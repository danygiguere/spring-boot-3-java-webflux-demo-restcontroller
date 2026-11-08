package com.example.restcontrollerdemo.repository;

import com.example.restcontrollerdemo.domain.entity.Image;
import com.example.restcontrollerdemo.dto.PostCount;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface ImageRepository extends ReactiveCrudRepository<Image, Long> {

    /** hasMany (post → images), at most 10 rows (CHECK on position). */
    Flux<Image> findAllByPostIdOrderByPositionAsc(Long postId);

    /** One aggregate for a whole page of posts, instead of one COUNT per post. */
    @Query("SELECT post_id, COUNT(*) AS total FROM images WHERE post_id = ANY(:postIds) GROUP BY post_id")
    Flux<PostCount> countByPostIds(Long[] postIds);
}

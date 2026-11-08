package com.example.restcontrollerdemo.service;

import com.example.restcontrollerdemo.domain.entity.Post;
import com.example.restcontrollerdemo.domain.entity.User;
import com.example.restcontrollerdemo.dto.PostCount;
import com.example.restcontrollerdemo.dto.PostSummary;
import com.example.restcontrollerdemo.repository.ImageRepository;
import com.example.restcontrollerdemo.repository.PostLikeRepository;
import com.example.restcontrollerdemo.repository.UserRepository;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Turns a page of posts into summaries with a constant number of queries — authors, image counts and like counts are
 * each loaded in bulk (three queries, run concurrently), then joined in memory through hash maps.
 */
@Component
public class PostSummaryLoader {

    private final UserRepository userRepository;
    private final ImageRepository imageRepository;
    private final PostLikeRepository postLikeRepository;

    public PostSummaryLoader(
            UserRepository userRepository, ImageRepository imageRepository, PostLikeRepository postLikeRepository) {
        this.userRepository = userRepository;
        this.imageRepository = imageRepository;
        this.postLikeRepository = postLikeRepository;
    }

    /** Keeps the order of {@code posts}. The page is already bounded by the caller's limit. */
    public Flux<PostSummary> load(Flux<Post> posts, long enterpriseId) {
        return posts.collectList().flatMapMany(page -> {
            if (page.isEmpty()) {
                return Flux.empty();
            }
            Long[] postIds = page.stream().map(Post::id).toArray(Long[]::new);
            Long[] authorIds = page.stream().map(Post::userId).distinct().toArray(Long[]::new);

            Mono<Map<Long, User>> authors =
                    userRepository.findAllInEnterprise(authorIds, enterpriseId).collectMap(User::id);
            Mono<Map<Long, Long>> imageCounts =
                    imageRepository.countByPostIds(postIds).collectMap(PostCount::postId, PostCount::total);
            Mono<Map<Long, Long>> likeCounts =
                    postLikeRepository.countByPostIds(postIds).collectMap(PostCount::postId, PostCount::total);

            return Mono.zip(authors, imageCounts, likeCounts)
                    .flatMapIterable(maps -> toSummaries(page, maps.getT1(), maps.getT2(), maps.getT3()));
        });
    }

    private static List<PostSummary> toSummaries(
            List<Post> page, Map<Long, User> authors, Map<Long, Long> imageCounts, Map<Long, Long> likeCounts) {
        return page.stream()
                // An author deleted between the two queries takes their posts with them (ON DELETE CASCADE).
                .filter(post -> authors.containsKey(post.userId()))
                .map(post -> PostSummary.of(
                        post,
                        authors.get(post.userId()),
                        imageCounts.getOrDefault(post.id(), 0L),
                        likeCounts.getOrDefault(post.id(), 0L)))
                .toList();
    }
}

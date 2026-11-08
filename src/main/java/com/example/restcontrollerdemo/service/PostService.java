package com.example.restcontrollerdemo.service;

import com.example.restcontrollerdemo.domain.entity.IdempotencyKey;
import com.example.restcontrollerdemo.domain.entity.Image;
import com.example.restcontrollerdemo.domain.entity.Post;
import com.example.restcontrollerdemo.dto.CreatePostRequest;
import com.example.restcontrollerdemo.dto.ImageResponse;
import com.example.restcontrollerdemo.dto.PostDetails;
import com.example.restcontrollerdemo.dto.PostSummary;
import com.example.restcontrollerdemo.dto.UpdatePostRequest;
import com.example.restcontrollerdemo.dto.UserResponse;
import com.example.restcontrollerdemo.exception.AppException;
import com.example.restcontrollerdemo.repository.IdempotencyKeyRepository;
import com.example.restcontrollerdemo.repository.ImageRepository;
import com.example.restcontrollerdemo.repository.PostLikeRepository;
import com.example.restcontrollerdemo.repository.PostRepository;
import com.example.restcontrollerdemo.repository.UserRepository;
import com.example.restcontrollerdemo.security.AuthenticatedUser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.IntStream;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Posts are visible to the author's enterprise and writable by the author only. Both checks happen here, at the point
 * of action, from the verified token — never from ids in the request body.
 */
@Service
public class PostService {

    private final PostRepository postRepository;
    private final ImageRepository imageRepository;
    private final PostLikeRepository postLikeRepository;
    private final UserRepository userRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final UserService userService;
    private final PostSummaryLoader summaryLoader;
    private final TransactionalOperator transactionalOperator;
    private final Clock clock;

    public PostService(
            PostRepository postRepository,
            ImageRepository imageRepository,
            PostLikeRepository postLikeRepository,
            UserRepository userRepository,
            IdempotencyKeyRepository idempotencyKeyRepository,
            UserService userService,
            PostSummaryLoader summaryLoader,
            TransactionalOperator transactionalOperator,
            Clock clock) {
        this.postRepository = postRepository;
        this.imageRepository = imageRepository;
        this.postLikeRepository = postLikeRepository;
        this.userRepository = userRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.userService = userService;
        this.summaryLoader = summaryLoader;
        this.transactionalOperator = transactionalOperator;
        this.clock = clock;
    }

    // --- Reads ----------------------------------------------------------------------------------------------------

    public Flux<PostSummary> feed(AuthenticatedUser me, long beforeId, int limit) {
        return summaryLoader.load(postRepository.findFeed(me.enterpriseId(), beforeId, limit), me.enterpriseId());
    }

    public Flux<PostSummary> findByAuthor(AuthenticatedUser me, long userId, long beforeId, int limit) {
        return userService
                .requireColleague(me, userId)
                .thenMany(summaryLoader.load(
                        postRepository.findByAuthor(userId, me.enterpriseId(), beforeId, limit), me.enterpriseId()));
    }

    public Flux<PostSummary> findLikedBy(AuthenticatedUser me, long userId, long beforeId, int limit) {
        return userService
                .requireColleague(me, userId)
                .thenMany(summaryLoader.load(
                        postRepository.findLikedBy(userId, me.enterpriseId(), beforeId, limit), me.enterpriseId()));
    }

    /** The post, its author, images and like count: three independent queries run concurrently. */
    public Mono<PostDetails> findById(AuthenticatedUser me, long id) {
        return requireVisible(me, id).flatMap(post -> Mono.zip(
                        userService.requireColleague(me, post.userId()),
                        imageRepository.findAllByPostIdOrderByPositionAsc(id).collectList(),
                        postLikeRepository.countByPostId(id))
                .map(t -> PostDetails.of(post, t.getT1(), t.getT2(), t.getT3())));
    }

    public Flux<ImageResponse> findImages(AuthenticatedUser me, long id) {
        return requireVisible(me, id)
                .thenMany(imageRepository.findAllByPostIdOrderByPositionAsc(id))
                .map(ImageResponse::of);
    }

    public Flux<UserResponse> findLikers(AuthenticatedUser me, long id, long afterId, int limit) {
        return requireVisible(me, id)
                .thenMany(userRepository.findLikersInEnterprise(id, me.enterpriseId(), afterId, limit))
                .map(UserResponse::of);
    }

    // --- Writes ---------------------------------------------------------------------------------------------------

    /**
     * Creates the post and its images in ONE transaction, together with the claim on {@code idempotencyKey}. A retry
     * with the same key and body returns the post created the first time; the same key with another body is a 409.
     */
    public Mono<CreatedPost> create(AuthenticatedUser me, String idempotencyKey, CreatePostRequest request) {
        String requestHash = hash(request);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS); // PostgreSQL precision

        Mono<Claim> firstAttempt = idempotencyKeyRepository
                .claim(me.userId(), idempotencyKey, requestHash, now)
                .flatMap(keyId -> insertPost(me, request, now).flatMap(details -> idempotencyKeyRepository
                        .attachPost(keyId, details.id())
                        .thenReturn(new Claim.Inserted(details))));

        Mono<Claim> retry = Mono.defer(() -> idempotencyKeyRepository
                .findByUserIdAndIdempotencyKey(me.userId(), idempotencyKey)
                .map(existing -> new Claim.AlreadyCreated(replayedPostId(existing, requestHash))));

        // The replayed post is read after the transaction ends: its concurrent queries need their own connections.
        return transactionalOperator
                .transactional(firstAttempt.switchIfEmpty(retry))
                .flatMap(claim -> switch (claim) {
                    case Claim.Inserted inserted -> Mono.just(new CreatedPost(inserted.post(), false));
                    case Claim.AlreadyCreated replay -> findById(me, replay.postId())
                            .map(details -> new CreatedPost(details, true));
                });
    }

    /** What the transaction of {@link #create} did with the Idempotency-Key. */
    private sealed interface Claim {
        record Inserted(PostDetails post) implements Claim {}

        record AlreadyCreated(long postId) implements Claim {}
    }

    /** Only the author may edit. Setting the content to an absolute value is naturally idempotent. */
    public Mono<PostDetails> update(AuthenticatedUser me, long id, UpdatePostRequest request) {
        return requireAuthor(me, id)
                .flatMap(post -> postRepository.updateContent(id, me.userId(), request.content()))
                .flatMap(rows -> rows == 1 ? findById(me, id) : Mono.error(postNotFound(id)));
    }

    /** Only the author may delete. Images and likes go with the post (ON DELETE CASCADE). */
    public Mono<Void> delete(AuthenticatedUser me, long id) {
        return requireAuthor(me, id)
                .flatMap(post -> postRepository.deleteOwned(id, me.userId()))
                .flatMap(rows -> rows == 1 ? Mono.empty() : Mono.error(postNotFound(id)));
    }

    /** Idempotent: liking twice keeps one like. */
    public Mono<Void> like(AuthenticatedUser me, long id) {
        return requireVisible(me, id)
                .flatMap(post -> postLikeRepository.likeIfAbsent(id, me.userId()))
                // The post was deleted between the visibility check and the insert (foreign key violation).
                .onErrorMap(
                        DataIntegrityViolationException.class,
                        e -> new AppException.NotFound(e, "error.post.not.found", id))
                .then();
    }

    /** Idempotent: unliking a post that is not liked changes nothing. */
    public Mono<Void> unlike(AuthenticatedUser me, long id) {
        return requireVisible(me, id)
                .flatMap(post -> postLikeRepository.unlike(id, me.userId()))
                .then();
    }

    // --- Helpers --------------------------------------------------------------------------------------------------

    private Mono<PostDetails> insertPost(AuthenticatedUser me, CreatePostRequest request, Instant now) {
        return userService.requireColleague(me, me.userId()).flatMap(author -> postRepository
                .save(new Post(null, author.id(), request.content(), now))
                .flatMap(post -> imageRepository
                        .saveAll(toImages(post.id(), request.imageUrls()))
                        .collectList()
                        .map(images -> PostDetails.of(post, author, images, 0))));
    }

    private static List<Image> toImages(Long postId, List<String> urls) {
        return IntStream.range(0, urls.size())
                .mapToObj(i -> new Image(null, postId, urls.get(i), i + 1))
                .toList();
    }

    private static Long replayedPostId(IdempotencyKey existing, String requestHash) {
        if (!existing.requestHash().equals(requestHash)) {
            throw new AppException.Conflict("error.idempotency.key.reused");
        }
        if (existing.postId() == null) {
            throw new AppException.Conflict("error.idempotency.key.gone");
        }
        return existing.postId();
    }

    /** Length-prefixed fields, so two different requests can never produce the same input. */
    private static String hash(CreatePostRequest request) {
        StringBuilder canonical = new StringBuilder();
        appendField(canonical, request.content());
        request.imageUrls().forEach(url -> appendField(canonical, url));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }

    private static void appendField(StringBuilder builder, String value) {
        builder.append(value.length()).append(':').append(value);
    }

    private Mono<Post> requireVisible(AuthenticatedUser me, long id) {
        return postRepository.findVisible(id, me.enterpriseId()).switchIfEmpty(Mono.error(() -> postNotFound(id)));
    }

    /** Visible but not authored by the caller is a 403: the post's existence is no secret within the enterprise. */
    private Mono<Post> requireAuthor(AuthenticatedUser me, long id) {
        return requireVisible(me, id)
                .flatMap(post -> post.userId().equals(me.userId())
                        ? Mono.just(post)
                        : Mono.error(new AppException.Forbidden("error.post.not.author", id)));
    }

    private static AppException postNotFound(long id) {
        return new AppException.NotFound("error.post.not.found", id);
    }
}

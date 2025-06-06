package com.example.snsserver.domain.post.service;

import com.example.snsserver.common.service.BaseImageService;
import com.example.snsserver.common.service.SearchUtils;
import com.example.snsserver.domain.like.repository.LikeRepository;
import com.example.snsserver.domain.post.entity.Post;
import com.example.snsserver.domain.post.entity.tag.PostTag;
import com.example.snsserver.domain.post.entity.tag.Tag;
import com.example.snsserver.domain.post.repository.PostRepository;
import com.example.snsserver.domain.post.repository.tag.TagRepository;
import com.example.snsserver.domain.auth.entity.Member;
import com.example.snsserver.dto.auth.request.PageRequestDto;
import com.example.snsserver.dto.auth.response.PageResponseDto;
import com.example.snsserver.dto.post.request.PostRequestDto;
import com.example.snsserver.dto.post.request.SearchPostRequestDto;
import com.example.snsserver.dto.post.request.SearchPostByTagRequestDto;
import com.example.snsserver.dto.post.response.PostResponseDto;
import com.example.snsserver.dto.post.response.SearchPostResponseDto;
import com.example.snsserver.domain.auth.service.MemberService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class PostService {

    private final PostRepository postRepository;
    private final TagRepository tagRepository;
    private final MemberService memberService;
    private final LikeRepository likeRepository;
    private final BaseImageService baseImageService;

    @Transactional
    public PostResponseDto createPost(PostRequestDto requestDto, MultipartFile file) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        Member member = memberService.getMemberByUsername(username);
        if (member == null) {
            throw new IllegalArgumentException("사용자를 찾을 수 없습니다: " + username);
        }

        String filePath = baseImageService.uploadImage(file, "post");

        Post post = Post.builder()
                .title(requestDto.getTitle())
                .content(requestDto.getContent())
                .filePath(filePath)
                .member(member)
                .createdAt(LocalDateTime.now())
                .build();

        Post savedPost = postRepository.save(post);
        addTagsToPost(savedPost, requestDto.getTags()); // 태그 추가
        return mapToResponseDto(savedPost);
    }

    @Transactional(readOnly = true)
    public PageResponseDto<PostResponseDto> getAllPosts(PageRequestDto pageRequestDto) {
        Page<Post> postPage = postRepository.findAll(
                pageRequestDto.toPageableWithSort("createdAt", Sort.Direction.DESC));
        List<Post> posts = postPage.getContent();
        Map<Long, Long> likeCounts = getLikeCounts(posts);
        Page<PostResponseDto> postResponsePage = postPage.map(post -> mapToResponseDto(post, likeCounts));

        log.info("Fetched {} posts", postResponsePage.getTotalElements());
        return PageResponseDto.from(postResponsePage);
    }

    @Transactional(readOnly = true)
    public PostResponseDto getPost(Long postId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("게시물이 존재하지 않습니다."));
        return mapToResponseDto(post);
    }

    @Transactional(readOnly = true)
    public SearchPostResponseDto searchPosts(SearchPostRequestDto requestDto) {
        log.info("Searching posts with keyword: {}, page: {}, size: {}",
                requestDto.getKeyword(), requestDto.getPage(), requestDto.getSize());

        PageRequest pageable = PageRequest.of(requestDto.getPage(), requestDto.getSize(),
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Specification<Post> spec = SearchUtils.buildTitleSearchSpecification(requestDto.getKeyword());
        Page<Post> postPage = postRepository.findAll(spec, pageable);

        List<Post> posts = postPage.getContent();
        Map<Long, Long> likeCounts = getLikeCounts(posts);
        Page<PostResponseDto> postResponsePage = postPage.map(post -> mapToResponseDto(post, likeCounts));

        log.info("Found {} posts matching keyword: {}", postResponsePage.getTotalElements(), requestDto.getKeyword());
        return SearchPostResponseDto.from(postResponsePage);
    }

    @Transactional(readOnly = true)
    public SearchPostResponseDto searchPostsByTag(SearchPostByTagRequestDto requestDto) {
        log.info("Searching posts with tag: {}, page: {}, size: {}",
                requestDto.getTag(), requestDto.getPage(), requestDto.getSize());

        PageRequest pageable = PageRequest.of(requestDto.getPage(), requestDto.getSize(),
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Tag tag = tagRepository.findByName(requestDto.getTag())
                .orElseThrow(() -> new IllegalArgumentException("태그를 찾을 수 없습니다: " + requestDto.getTag()));

        Page<Post> postPage = postRepository.findAll((root, query, cb) -> {
            return cb.equal(root.join("postTags").get("tag"), tag);
        }, pageable);

        List<Post> posts = postPage.getContent();
        Map<Long, Long> likeCounts = getLikeCounts(posts);
        Page<PostResponseDto> postResponsePage = postPage.map(post -> mapToResponseDto(post, likeCounts));

        log.info("Found {} posts with tag: {}", postResponsePage.getTotalElements(), requestDto.getTag());
        return SearchPostResponseDto.from(postResponsePage);
    }

    @Transactional
    public PostResponseDto updatePost(Long postId, PostRequestDto requestDto, MultipartFile file) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("게시물이 존재하지 않습니다."));
        if (!post.getMember().getUsername().equals(username)) {
            throw new SecurityException("본인의 게시물만 수정할 수 있습니다.");
        }

        String filePath = handleFileUpdate(post.getFilePath(), file);
        post.getPostTags().clear(); // 기존 태그 삭제
        post = post.toBuilder()
                .title(requestDto.getTitle())
                .content(requestDto.getContent())
                .filePath(filePath)
                .build();

        postRepository.save(post);
        addTagsToPost(post, requestDto.getTags()); // 새 태그 추가
        return mapToResponseDto(post);
    }

    @Transactional
    public void deletePost(Long postId) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("게시물이 존재하지 않습니다."));
        if (!post.getMember().getUsername().equals(username)) {
            throw new SecurityException("본인의 게시물만 삭제할 수 있습니다.");
        }

        baseImageService.deleteImage(post.getFilePath());
        postRepository.delete(post);
    }

    private PostResponseDto mapToResponseDto(Post post) {
        List<String> tags = post.getPostTags().stream()
                .map(postTag -> postTag.getTag().getName())
                .collect(Collectors.toList());

        return PostResponseDto.builder()
                .id(post.getId())
                .title(post.getTitle())
                .content(post.getContent())
                .filePath(post.getFilePath())
                .username(post.getMember().getUsername())
                .createdAt(post.getCreatedAt())
                .likeCount(likeRepository.countByPostId(post.getId()))
                .commentCount(post.getComments().size())
                .tags(tags) // 태그 추가
                .build();
    }

    private PostResponseDto mapToResponseDto(Post post, Map<Long, Long> likeCounts) {
        List<String> tags = post.getPostTags().stream()
                .map(postTag -> postTag.getTag().getName())
                .collect(Collectors.toList());

        return PostResponseDto.builder()
                .id(post.getId())
                .title(post.getTitle())
                .content(post.getContent())
                .filePath(post.getFilePath())
                .username(post.getMember().getUsername())
                .createdAt(post.getCreatedAt())
                .likeCount(likeCounts.getOrDefault(post.getId(), 0L))
                .commentCount(post.getComments().size())
                .tags(tags) // 태그 추가
                .build();
    }

    private Map<Long, Long> getLikeCounts(List<Post> posts) {
        List<Object[]> results = postRepository.countLikesByPosts(posts);
        return results.stream()
                .collect(Collectors.toMap(
                        result -> (Long) result[0],
                        result -> (Long) result[1]
                ));
    }

    private String handleFileUpdate(String existingFilePath, MultipartFile file) {
        if (file != null && !file.isEmpty()) {
            baseImageService.deleteImage(existingFilePath);
            return baseImageService.uploadImage(file, "post");
        }
        return existingFilePath;
    }

    private void addTagsToPost(Post post, List<String> tagNames) {
        if (tagNames == null) {
            return;
        }

        for (String tagName : tagNames) {
            if (tagName == null || tagName.trim().isEmpty()) {
                continue;
            }

            Tag tag = tagRepository.findByName(tagName)
                    .orElseGet(() -> {
                        Tag newTag = Tag.builder()
                                .name(tagName)
                                .build();
                        return tagRepository.save(newTag);
                    });

            PostTag postTag = PostTag.builder()
                    .post(post)
                    .tag(tag)
                    .build();

            post.getPostTags().add(postTag);
            tag.getPostTags().add(postTag);
        }
    }
}
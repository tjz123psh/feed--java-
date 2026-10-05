package io.github.p_feed.video;

import io.github.p_feed.account.Account;
import io.github.p_feed.account.AccountMapper;
import io.github.p_feed.infra.ApiException;
import io.github.p_feed.infra.Errors;
import io.github.p_feed.tool.FilesTool;
import io.github.p_feed.video.dto.PublishVideoRequest;
import io.github.p_feed.video.dto.UploadCoverResponse;
import io.github.p_feed.video.dto.UploadVideoResponse;

import jakarta.servlet.http.HttpServletRequest;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class VideoService {
    private static final long MAX_VIDEO_SIZE = 200L * 1024 * 1024;
    private static final long MAX_COVER_SIZE = 10L * 1024 * 1024;
    private static final Set<String> COVER_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");
    
    private final AccountMapper accountMapper;
    private final VideoMapper videoMapper;
    private final OutboxMsgMapper outboxMsgMapper;
    private final FilesTool filesTool;
    private final VideoTagMapper videoTagMapper;
    private final TagMapper tagMapper;
    private final VideoCacheService videoCacheService;

    public VideoService(FilesTool filesTool,AccountMapper accountMapper,VideoMapper videoMapper, OutboxMsgMapper outboxMsgMapper, VideoTagMapper videoTagMapper, TagMapper tagMapper, VideoCacheService videoCacheService) {
        this.videoMapper = videoMapper;
        this.outboxMsgMapper = outboxMsgMapper;
        this.filesTool = filesTool;
        this.accountMapper = accountMapper;
        this.videoTagMapper = videoTagMapper;
        this.tagMapper = tagMapper;
        this.videoCacheService = videoCacheService;
    }

    // 视频上传
    public UploadVideoResponse uploadVideo(
            Long accountId, MultipartFile file, HttpServletRequest request) {

        if (file == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, Errors.MISSING_FILE);
        }

        if (file.getSize() <= 0 || file.getSize() > MAX_VIDEO_SIZE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, Errors.INVALID_FILE_SIZE);
        }

        String ext = extensionOf(file.getOriginalFilename());
        if (!"mp4".equals(ext)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, Errors.ONLY_MP4_ALLOWED);
        }

        Path target = filesTool.resolvePath("videos", accountId, true, ".mp4");
        String url = store(target, file, request);
        return UploadVideoResponse.of(url);
    }

    // 封面上传
    public UploadCoverResponse uploadCover(
            Long accountId, MultipartFile file, HttpServletRequest request) {
        if (file == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, Errors.MISSING_FILE);
        }

        if (file.getSize() <= 0 || file.getSize() > MAX_COVER_SIZE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, Errors.INVALID_FILE_SIZE);
        }

        String ext = extensionOf(file.getOriginalFilename());
        if (!COVER_EXTENSIONS.contains(ext)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, Errors.ONLY_COVER_EXTENSIONS);
        }

        Path target = filesTool.resolvePath("covers", accountId, true, "." + ext);
        String url = store(target, file, request);
        return UploadCoverResponse.of(url);
    }

    // 发布
    @Transactional
    public Video publish(Long accountId, PublishVideoRequest req) {
        // 做trim检查
        String title = req.title() == null ? "" : req.title().trim();
        String playUrl = req.playUrl() == null ? "" : req.playUrl().trim();
        String coverUrl = req.coverUrl() == null ? "" : req.coverUrl().trim();

        if (title.isEmpty()) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, Errors.TITLE_IS_REQUIRED);
        }

        if(playUrl.isEmpty()){
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, Errors.PLAY_URL_IS_REQUIRED);
        }  

        if(coverUrl.isEmpty()){
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, Errors.COVER_URL_IS_REQUIRED);
        }

        // 取发布者
        Account author = accountMapper.selectById(accountId);
        if(author == null){
            throw new ApiException(HttpStatus.NOT_FOUND, Errors.ACCOUNT_NOT_FOUND);
        }

        OffsetDateTime now = OffsetDateTime.now();
        
        Video video = new Video();
        video.setAuthorId(accountId);
        video.setUsername(author.getUsername());
        video.setTitle(title);
        video.setDescription(req.description());
        video.setPlayUrl(playUrl);
        video.setCoverUrl(coverUrl);
        video.setCreateTime(now);
        video.setLikesCount(0L);
        video.setPopularity(0L);
        videoMapper.insert(video);

        // insert outbox_msgs
        OutboxMsg outboxMsg = new OutboxMsg();
        outboxMsg.setVideoId(video.getId());
        outboxMsg.setEventType("video_published");
        outboxMsg.setStatus("pending");
        outboxMsg.setCreateTime(now);
        outboxMsgMapper.insert(outboxMsg);

        // 将话题去重update->建关联
        List<String> tagNames = TagExtractor.extract(title, req.description());
        for(String name : tagNames){
            Long tagId = upsertTag(name);
            VideoTag videoTag = new VideoTag();
            videoTag.setVideoId(video.getId());
            videoTag.setTagId(tagId);
            videoTagMapper.insert(videoTag);
        }
        
        return video;
    }

    // 视频详情，走videoCacheService的缓存/锁/轮询，查无404
    public Video getDetail(long id){
        return videoCacheService.getDetail(id);
    }

    // 作者列表：无缓存直查，空返回[]
    public List<Video> listByAuthorID(long authorId){
        return videoMapper.listByAuthorID(authorId);
    }

    private Long upsertTag(String name) {
        tagMapper.insertIgnore(name);
        Tag found = tagMapper.selectOne(
            new LambdaQueryWrapper<Tag>().eq(Tag::getName, name)
        );
        return found.getId();
    }

    private String store(Path target, MultipartFile file, HttpServletRequest request) {
        try {
            Files.createDirectories(target.getParent());
            file.transferTo(target);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
        return filesTool.toUrl(request, target);
    }

    private String extensionOf(@Nullable String originalFilename) {
        if (originalFilename == null) {
            return "";
        }

        int dot = originalFilename.lastIndexOf(".");
        return dot < 0 ? "" : originalFilename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}

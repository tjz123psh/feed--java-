package io.github.p_feed.video;

import io.github.p_feed.infra.ApiException;
import io.github.p_feed.infra.Errors;
import io.github.p_feed.video.dto.GetDetailRequest;
import io.github.p_feed.video.dto.ListByAuthorIDRequest;
import io.github.p_feed.video.dto.PublishVideoRequest;
import io.github.p_feed.video.dto.UploadCoverResponse;
import io.github.p_feed.video.dto.UploadVideoResponse;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/video")
public class VideoController {
    private final VideoService videoService;

    public VideoController(VideoService videoService) {
        this.videoService = videoService;
    }

    @PostMapping("/uploadVideo")
    public UploadVideoResponse uploadVideo(
            HttpServletRequest request,
            @RequestParam(value = "file", required = false) MultipartFile file) {
        return videoService.uploadVideo(accountId(request), file, request);
    }

    @PostMapping("/uploadCover")
    public UploadCoverResponse uploadCover(
            HttpServletRequest request,
            @RequestParam(value = "file", required = false) MultipartFile file) {
        return videoService.uploadCover(accountId(request), file, request);
    }

    @PostMapping("/publish")
    public Video publish(HttpServletRequest request, @RequestBody PublishVideoRequest body) {
        return videoService.publish(accountId(request), body);
    }

    @PostMapping("/getDetail")
    public Video getDetail(@RequestBody GetDetailRequest body) {
        return videoService.getDetail(body.id());
    }

    @PostMapping("/listByAuthorID")
        public List<Video> listByAuthorID(@RequestBody ListByAuthorIDRequest body) {
        return videoService.listByAuthorID(body.authorId());
        }
        

    private Long accountId(HttpServletRequest request) {
        Long accountId = (Long) request.getAttribute("accountID");
        if (accountId == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, Errors.ACCOUNT_ID_IS_REQUIRED);
        }

        return accountId;
    }
}

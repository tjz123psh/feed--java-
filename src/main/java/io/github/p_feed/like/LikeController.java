package io.github.p_feed.like;

import io.github.p_feed.account.dto.MessageResponse;
import io.github.p_feed.infra.ApiException;
import io.github.p_feed.like.dto.IsLikedResponse;
import io.github.p_feed.like.dto.LikeRequest;
import io.github.p_feed.video.Video;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/like")
public class LikeController {

    private final LikeService likeService;

    public LikeController(LikeService likeService) {
        this.likeService = likeService;
    }

    @PostMapping("/like")
    public MessageResponse like(HttpServletRequest request, @RequestBody LikeRequest body) {
        return likeService.like(accountId(request), body.videoId());
    }

    @PostMapping("/unlike")
    public MessageResponse unlike(HttpServletRequest request, @RequestBody LikeRequest body) {
        return likeService.unlike(accountId(request), body.videoId());
    }

    @PostMapping("/isLiked")
    public IsLikedResponse isLiked(HttpServletRequest request, @RequestBody LikeRequest body) {
        return likeService.isLiked(accountId(request), body.videoId());
    }

    @PostMapping("/listMyLikedVideos")
    public List<Video> listMyLikedVideos(HttpServletRequest request) {
        return likeService.listMyLikedVideos(accountId(request));
    }
        

    private long accountId(HttpServletRequest request) {
        Long accountId = (Long) request.getAttribute("accountID");
        if (accountId == null) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED, io.github.p_feed.infra.Errors.ACCOUNT_ID_NOT_FOUND);
        }
        return accountId;
    }
}

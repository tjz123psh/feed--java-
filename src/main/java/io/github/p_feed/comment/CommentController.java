package io.github.p_feed.comment;

import io.github.p_feed.account.dto.MessageResponse;
import io.github.p_feed.comment.dto.DeleteCommentRequest;
import io.github.p_feed.comment.dto.ListCommentsRequest;
import io.github.p_feed.comment.dto.PublishCommentRequest;
import io.github.p_feed.infra.ApiException;
import io.github.p_feed.infra.Errors;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/comment")
public class CommentController {

    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    // 免登录：不加入鉴权
    @PostMapping("/listAll")
    public List<Comment> listAll(@RequestBody ListCommentsRequest body) {
        return commentService.listAll(body.videoId());
    }

    @PostMapping("/publish")
    public MessageResponse publish(
            HttpServletRequest request, @RequestBody PublishCommentRequest body) {
        return commentService.publish(accountId(request), body);
    }

    @PostMapping("/delete")
    public MessageResponse delete(
            HttpServletRequest request, @RequestBody DeleteCommentRequest body) {
        return commentService.delete(accountId(request), body);
    }

    private long accountId(HttpServletRequest request) {
        Long accountId = (Long) request.getAttribute("accountID");
        if (accountId == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, Errors.ACCOUNT_ID_NOT_FOUND);
        }
        return accountId;
    }
}

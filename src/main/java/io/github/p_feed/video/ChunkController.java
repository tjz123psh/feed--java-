package io.github.p_feed.video;

import io.github.p_feed.infra.ApiError;
import io.github.p_feed.infra.ApiException;
import io.github.p_feed.infra.Errors;
import io.github.p_feed.video.dto.ChunkCompleteRequest;
import io.github.p_feed.video.dto.ChunkInitRequest;
import io.github.p_feed.video.dto.ChunkInitResponse;
import io.github.p_feed.video.dto.ChunkStatusRequest;
import io.github.p_feed.video.dto.ChunkStatusResponse;
import io.github.p_feed.video.dto.ChunkUploadResponse;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import io.github.p_feed.video.dto.UploadVideoResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/video")
public class ChunkController {

    private final ChunkService chunkService;

    public ChunkController(ChunkService chunkService) {
        this.chunkService = chunkService;
    }

    @PostMapping("/chunk/init")
    public ChunkInitResponse init(
            HttpServletRequest request, @Valid @RequestBody ChunkInitRequest body) {
        return chunkService.init(accountId(request), body);
    }

    @PostMapping("/chunk/upload")
        public ChunkUploadResponse upload(HttpServletRequest request,
                @RequestParam("upload_id") String uploadId,
                @RequestParam("chunk_index") int chunkIndex,
                @RequestParam("chunk_hash") String chunkHash,
                @RequestParam(value = "file",required = false) MultipartFile file){
        return chunkService.upload(accountId(request), uploadId, chunkIndex, chunkHash, file);
        }

    @PostMapping("/chunk/status")
        public ChunkStatusResponse status(HttpServletRequest request,@RequestBody ChunkStatusRequest body) {
        return chunkService.status(accountId(request),body.uploadId());
        }

    @PostMapping("/chunk/complete")
        public UploadVideoResponse complete(HttpServletRequest request, @RequestBody ChunkCompleteRequest body) {
        return chunkService.complete(accountId(request), body.uploadId(), request);
        }
        
        
        

    // chunk 族契约：绑定类失败 → 400 + 底层原文（全局处理器给 /account 族定的是 500）
    //  - 请求体 JSON 坏了             → HttpMessageNotReadableException
    //  - form 缺 upload_id 等必填字段  → MissingServletRequestParameterException
    //  - chunk_index 不是数字          → MethodArgumentTypeMismatchException（契约表明确要求 400）
    @ExceptionHandler({HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleBadBody(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(e.getMessage()));
    }
    
    // chunk 族"带附加字段"的错误（expected/actual、missing/completed/total）：
    // 控制器局部处理器优先于全局 @ControllerAdvice，只影响本控制器
    
    @ExceptionHandler(ChunkException.class)
    public ResponseEntity<Map<String,Object>> handleChunkError(ChunkException e){
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("error", e.getMessage());
        body.putAll(e.getDetails());
        return ResponseEntity.status(e.getStatus()).body(body);
    }


    private long accountId(HttpServletRequest request) {
        Long accountId = (Long) request.getAttribute("accountID");
        if (accountId == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, Errors.ACCOUNT_ID_NOT_FOUND);
        }
        return accountId;
    }
}

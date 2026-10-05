package io.github.p_feed.infra;


import org.slf4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class GlobalExceptionHandler{

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // 业务异常：状态码 + 契约文案
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException e){
        return ResponseEntity.status(e.getStatus()).body(new ApiError(e.getMessage()));
    }

    // 请求体解析失败 -> 500 + 原始错误
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handlereadable(HttpMessageNotReadableException e){
        log.warn("request body unreadable: {}",e.getMessage());
        return ResponseEntity.status(500).body(new ApiError(e.getMessage()));
    }
    
    // 兜底：500 + 底层错误原文
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneric(Exception e){
        log.error("unhandled exception", e);
        return ResponseEntity.status(500).body(new ApiError(e.getMessage()));
    }
    
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleMaxUploadSize(MaxUploadSizeExceededException e){
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(Errors.INVALID_FILE_SIZE));
    }

    // 缺字段/校验失败-> 400 + 底层错误原文
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e){
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(e.getMessage()));
    }

}

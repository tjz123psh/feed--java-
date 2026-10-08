package io.github.p_feed.infra;
public final class Errors{

    private Errors(){}

    // 400
    public static final String UNSUCCESSFULLY_PASSWORD_CHANGED = "unsuccessfully password changed";
    public static final String NOTHING_TO_UPDATE = "nothing to update";
    public static final String MISSING_FILE = "missing file";
    public static final String INVALID_FILE_SIZE = "invalid file size";
    public static final String ONLY_IMAGE_EXTENSIONS = "only .jpg/.jpeg/.png/.webp allowed";
    public static final String ONLY_COVER_EXTENSIONS = "only .jpg/.jpeg/.png/.webp is allowed";
    public static final String INVALID_USERNAME_OR_PASSWORD = "invalid username or password";
    public static final String ACCOUNT_ID_IS_REQUIRED = "account_id is required";
    public static final String ONLY_MP4_ALLOWED = "only .mp4 is allowed";

    // 401
    public static final String ACCOUNT_ID_NOT_FOUND = "accountID not found";
    public static final String INVALID_REFRESH_TOKEN = "invalid refresh token";
    public static final String INVALID_ACCOUNT = "invalid account";
    public static final String MISSING_TOKEN = "missing token";
    public static final String INVALID_TOKEN = "invalid token";

    // 404
    public static final String RECORD_NOT_FOUND = "record not found";
    public static final String ACCOUNT_NOT_FOUND = "account not found";

    // 409
    public static final String USERNAME_ALREADY_EXISTS = "username already exists";

    // 鉴权拦截器
    public static final String MISSING_AUTHORIZATION_HEADER = "missing authorization header";
    public static final String INVALID_AUTHORIZATION_HEADER = "invalid authorization header";
    public static final String INVALID_OR_EXPIRED_TOKEN = "invalid or expired token";
    public static final String TOKEN_HAS_BEEN_REVOKED = "token has been revoked";
    
    //429
    public static final String TOO_MANY_REQUESTS = "too many requests";

    // 500
    public static final String TITLE_IS_REQUIRED = "title is required";
    public static final String PLAY_URL_IS_REQUIRED = "play url is required";
    public static final String COVER_URL_IS_REQUIRED = "cover url is required";

    // 分块上传
    public static final String CHUNK_UPLOAD_REQUIRES_REDIS = "chunk upload requires redis";
    public static final String FILE_SIZE_EXCEEDS_200MB = "file size exceeds 200MB limit";
    public static final String FAILED_TO_CREATE_SESSION = "failed to create session";
    public static final String FORBIDDEN = "forbidden";
    public static final String INVALID_SESSION_DATA = "invalid session data";
    public static final String UPLOAD_SESSION_NOT_FOUND = "upload session not found";
    public static final String INVALID_CHUNK_INDEX = "invalid chunk_index";
    public static final String CHUNK_HASH_MISMATCH = "chunk hash mismatch";
    public static final String NOT_ALL_CHUNKS_UPLOADED = "not all chunks uploaded";
    public static final String FAILED_TO_UPDATE_SESSION = "failed to update session";
    public static final String FAILED_TO_READ_CHUNK = "failed to read chunk";
    public static final String FAILED_TO_HASH_CHUNK = "failed to hash chunk";
    public static final String FAILED_TO_CREATE_TEMP_DIR = "failed to create temp dir";
    public static final String FAILED_TO_SAVE_CHUNK = "failed to save chunk";
    public static final String FAILED_TO_CREATE_OUTPUT_DIR = "failed to create output dir";
    public static final String FAILED_TO_CREATE_FINAL_FILE = "failed to create final file";
    public static final String FAILED_TO_MERGE_CHUNKS = "failed to merge chunks";
    public static final String FAILED_TO_GENERATE_FILENAME = "failed to generate filename";
        // 评论与点赞（阶段5）
    public static final String VIDEO_ID_IS_REQUIRED = "video_id is required";
    public static final String VIDEO_NOT_FOUND = "video not found";
    public static final String CONTENT_IS_REQUIRED = "content is required";
    public static final String COMMENT_ID_IS_REQUIRED = "comment_id is required";
    public static final String COMMENT_NOT_FOUND = "comment not found";
    public static final String UNAUTHORIZED = "unauthorized";
    public static final String USER_HAS_LIKED_THIS_VIDEO = "user has liked this video";
    public static final String USER_HAS_NOT_LIKED_THIS_VIDEO = "user has not liked this video";
    // 关注关系
    public static final String VLOGGER_ID_IS_REQUIRED = "vlogger_id is required";
    public static final String CAN_NOT_FOLLOW_SELF = "can not follow self";
    public static final String ALREADY_FOLLOWED = "already followed";
    public static final String NOT_FOLLOWED = "not followed";


    //feed
    public static final String TAG_NAME_IS_REQUIRED = "tag_name is required";
    //feed 游标校验
    public static final String LIKES_COUNT_AND_ID_MUST_BE_TOGETHER = "likes_count_before and id_before must be provided together";
    public static final String INVALID_CURSOR_LIKES_COUNT_BEFORE = "invalid cursor: likes_count_before must be >= 0";
    public static final String INVALID_CURSOR_ID_BEFORE = "invalid cursor: id_before must be > 0";
    public static final String LATEST_POPULARITY_MUST_BE_NON_NEGATIVE = "latest_popularity must be >= 0";
    public static final String LATEST_BEFORE_AND_ID_MUST_BE_TOGETHER = "latest_before and latest_id_before must be provided together";

    // 私信
    public static final String TO_ID_AND_CONTENT_ARE_REQUIRED = "to_id and content are required";
    public static final String PEER_ID_IS_REQUIRED = "peer_id is required";
    

}

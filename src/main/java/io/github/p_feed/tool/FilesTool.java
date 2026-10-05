package io.github.p_feed.tool;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

import jakarta.servlet.http.HttpServletRequest;


/**
 * 文件落盘路径与访问 URL 的统一生成器。
 * 磁盘根目录来自配置 feed.upload-root（绝对路径）；URL 一律 {scheme}://{Host}/static/{...}。
 */

public class FilesTool{

    private static final String STATIC_PREFIX = "static";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final Path uploadRoot;

    // 允许不注入：根目录为null 表示未启用上传
    public FilesTool(Path uploadRoot){
        this.uploadRoot = uploadRoot == null ? null : uploadRoot.toAbsolutePath().normalize();
    }

    // 是否为null根(未启用上传)
    public boolean isEnabled(){
        return uploadRoot != null;
    }

    /** 头像/封面同款：{root}/avatars|videos|covers/{userId}[/{yyyyMMdd}]/{16字节hex}{ext} */
    public Path resolvePath(String kind ,long userId, boolean withDay, String ext){
        String hex = HexFormat.of().formatHex(randomRytes());
        String fileName =hex + ext;
        Path dir = uploadRoot.resolve(kind).resolve(String.valueOf(userId));
        if(withDay){
            dir = dir.resolve(LocalDate.now().format(DAY));
        }

        return dir.resolve(fileName);
    }
    
    private byte[] randomRytes(){
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    /**
     * 对外访问 URL：{scheme}://{Host}/static/{相对路径}
     * scheme 优先取 X-Forwarded-Proto（反向代理后保留原始协议），否则按请求是否 TLS。
     */
    public String toUrl(HttpServletRequest request , Path file){
        if(uploadRoot == null){
            throw new IllegalStateException("upload not enabled");
        }

        String relative = STATIC_PREFIX + "/" + uploadRoot.relativize(file).toString().replace('\\', '/');
        String scheme = scheme(request);
        String host = request.getHeader("Host");
        return scheme + "://" + (host != null ? host : "localhost") + "/" + relative;
    }

    private String scheme(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-Proto");
        if(forwarded != null && !forwarded.isBlank()){
            return forwarded;
        }

        return request.isSecure() ? "https" : "http";
    }

    /** 分块临时目录：{root}/tmp/{uploadId}（分片文件名就是数字下标，见目录布局） */
    public Path tmpDir(String uploadId){
        if(uploadRoot == null){
            throw new IllegalStateException("upload not enabled");
        }
        return uploadRoot.resolve("tmp").resolve(uploadId);
    }
}

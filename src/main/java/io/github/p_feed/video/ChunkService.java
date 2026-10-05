package io.github.p_feed.video;


import io.github.p_feed.infra.ApiException;
import io.github.p_feed.infra.Errors;
import io.github.p_feed.infra.FeatureFlags;
import io.github.p_feed.tool.FilesTool;
import io.github.p_feed.video.dto.ChunkInitRequest;
import io.github.p_feed.video.dto.ChunkInitResponse;
import io.github.p_feed.video.dto.ChunkStatusResponse;
import io.github.p_feed.video.dto.ChunkUploadResponse;
import io.github.p_feed.video.dto.UploadVideoResponse;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Stream;

import jakarta.servlet.http.HttpServletRequest;

@Service // init 编排：校验 → 续传判断 → 新建/复用
public class ChunkService {

    private static final long MAX_FILE_SIZE = 200L * 1024 * 1024;
    private static final int MISSING_REPORT_LIMIT = 5; // complete的missing的计算封顶;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ChunkSessionStore store;
    private final FilesTool filesTool;
    private final FeatureFlags flags;

    public ChunkService(ChunkSessionStore store, FilesTool filesTool, FeatureFlags flags) {
        this.store = store;
        this.filesTool = filesTool;
        this.flags = flags;
    }

    // init: hash命中续传 / 未命中新建
    public ChunkInitResponse init(long accountId, ChunkInitRequest req) {

        // 新增降级
        requireCache();

        // 1.检验
        if (req.fileName() == null
                || req.fileName().isBlank()
                || req.fileHash() == null
                || req.fileHash().isBlank()
                || req.fileSize() <= 0
                || req.chunkSize() <= 0
                || req.totalChunks() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "missing required parameter");
        }

        // 2.大小上限为200MB
        if (req.fileSize() > MAX_FILE_SIZE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, Errors.FILE_SIZE_EXCEEDS_200MB);
        }  

        // 3.续传分支：hash键命中 -> 会话还在 -> 复用同一个upload_id
        String existId = store.findByHash(accountId, req.fileHash());
        if (existId != null) {
            ChunkSession exist = store.load(existId);
            if (exist != null) {
                if (exist.accountId() != accountId) {
                    throw new ApiException(HttpStatus.FORBIDDEN, Errors.FORBIDDEN);
                }
                return new ChunkInitResponse(existId, uploadedIndexes(exist.uploadedBits()));
            }
        }

        // 4.建立分支： 随机hex+纳秒时间戳 -> upload_id 
        String uploadId = HexFormat.of().toHexDigits(System.nanoTime()) + HexFormat.of().formatHex(randomBytes());
        ChunkSession session = new ChunkSession(uploadId, accountId,req.fileName(), req.fileSize(), req.chunkSize(), req.totalChunks(), req.fileHash(), new boolean[req.totalChunks()]);;
        store.create(session);
        return new ChunkInitResponse(uploadId, new int[0]);
    }

    // upload
    public ChunkUploadResponse upload(long accountId, String uploadId, int chunkIndex, String chunkHash, MultipartFile file)
{
        requireCache();
        // 查会话
        ChunkSession s = store.load(uploadId);
        if(s == null){
            throw new ApiException(HttpStatus.NOT_FOUND, Errors.UPLOAD_SESSION_NOT_FOUND);
        }
        // 会话属主
        if(s.accountId() != accountId){
            throw new ApiException(HttpStatus.FORBIDDEN, Errors.FORBIDDEN);
        }

        // 下标越界
        if(chunkIndex < 0 || chunkIndex >= s.totalChunks()){
            throw new ApiException(HttpStatus.BAD_REQUEST, Errors.INVALID_CHUNK_INDEX);
        }

        // 幂等：该位已经就位直接200返回
        if(s.uploadedBits()[chunkIndex]){
            return new ChunkUploadResponse(chunkIndex);
        }

        // 没有带file 400
        if(file == null){
            throw new ApiException(HttpStatus.BAD_REQUEST, Errors.MISSING_FILE);
        }

        //读分片
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, Errors.FAILED_TO_READ_CHUNK);
        }

        // MD5 比对(不符->400 + expected/actual；expected是客户端值，actual是服务端)
        String actual = md5Hex(data);
        if(!actual.equalsIgnoreCase(chunkHash)){
            throw new ChunkException(HttpStatus.BAD_REQUEST, Errors.CHUNK_HASH_MISMATCH, Map.of("expected", chunkHash, "actual", actual));
        }

        // 落盘tmp/{uploadId}/{index} 
        Path part = filesTool.tmpDir(uploadId).resolve(String.valueOf(chunkIndex));
        try {
            Files.createDirectories(part.getParent());
            Files.write(part, data);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, Errors.FAILED_TO_SAVE_CHUNK);
        }

        // 置位图 + 会写会话 ； clone 一份避免改到load出来的对象
        boolean[] bits = s.uploadedBits().clone();
        bits[chunkIndex] = true;
        store.update(new ChunkSession(s.uploadId(), s.accountId(), s.filename(), s.fileSize(), s.chunkSize(), s.totalChunks(),s.fileHash(), bits));
        return new ChunkUploadResponse(chunkIndex);
    }
        
    // 查status
    public ChunkStatusResponse status(long accountId, String uploadId){
        requireCache();
        ChunkSession s = store.load(uploadId);
        if(s == null){
            throw new ApiException(HttpStatus.NOT_FOUND, Errors.UPLOAD_SESSION_NOT_FOUND);
        }

        if(s.accountId() != accountId){
            throw new ApiException(HttpStatus.FORBIDDEN, Errors.FORBIDDEN);
        }

        return new ChunkStatusResponse(s.uploadId(),uploadedIndexes(s.uploadedBits()),s.totalChunks());
    }

    // 合并complete
    public UploadVideoResponse complete(long accountId, String uploadId, HttpServletRequest request){
        requireCache();

        ChunkSession s = store.load(uploadId);
        if(s == null){
            throw new ApiException(HttpStatus.NOT_FOUND, Errors.UPLOAD_SESSION_NOT_FOUND);
        }
        if(s.accountId() != accountId){
            throw new ApiException(HttpStatus.FORBIDDEN, Errors.FORBIDDEN);
        }

        // 位图不满： missing 是 缺片个数，封顶为5  
        int completed = 0;
        int missing = 0;
        for(boolean uploaded : s.uploadedBits()){
            if(uploaded){
                completed++;
            }else if(missing < MISSING_REPORT_LIMIT){
                missing++;
            }
        }

        if(completed < s.totalChunks()){
            throw new ChunkException(HttpStatus.BAD_REQUEST, Errors.NOT_ALL_CHUNKS_UPLOADED, Map.of("missing",missing,"completed",completed,"total",s.totalChunks()));
        }
        
        // 产物：video/{uid}/{yyyyMMdd}/{16字节hex}.mp4
        Path tmpDir = filesTool.tmpDir(uploadId);
        Path target = filesTool.resolvePath("videos", accountId, true, ".mp4");

        try {
            Files.createDirectories(target.getParent());
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,Errors.FAILED_TO_CREATE_OUTPUT_DIR);
        }

        // 注意：APPEND 单独使用不建文件（NoSuchFileException），必须 CREATE + APPEND 一起给
        try(OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.APPEND)){
            for(int i = 0 ; i < s.totalChunks(); i++){
                Path part = tmpDir.resolve(String.valueOf(i));
                if(!Files.exists(part)){
                    throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "chunk " + i + " missing");
                }
                Files.copy(part, out);
            }
        }catch(ApiException e){
            // 缺片：删半成品后原样抛（保留 "chunk N missing" 精确文案）
            deleteQuietly(target);
            throw e;
        }catch(IOException e){
            deleteQuietly(target);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, Errors.FAILED_TO_MERGE_CHUNKS);
        }

        // 成功：清理tmp目录 + 清两个redis键 ， 返回 url 和 play_url
        deleteDirectoryQuietly(tmpDir);
        store.deleteSession(uploadId, accountId, s.fileHash());
        return UploadVideoResponse.of(filesTool.toUrl(request, target));
    }

    private void deleteDirectoryQuietly(Path tmpDir) {
        try(Stream<Path> walk = Files.walk(tmpDir)){
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }catch(IOException ignored){
        }
    }

    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch(IOException ignored) {
        }
    }

    // bool位图-> 已传下标数组
    private String md5Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, Errors.FAILED_TO_HASH_CHUNK);
        }
    }

    private void requireCache() {
        if(!flags.cacheEnabled()){
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, Errors.CHUNK_UPLOAD_REQUIRES_REDIS);
        }
    }

    // bool 位图 -> 已传下标数组
    private int[] uploadedIndexes(boolean[] uploadedBits) {
        int count = 0;
        for (boolean b : uploadedBits) if (b) count++;

        int[] idx = new int[count];
        int p = 0;
        for (int i = 0; i < uploadedBits.length; i++) {
            if (uploadedBits[i]) {
                idx[p++] = i;
            }
        }

        return idx;
    }

    private byte[] randomBytes(){
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return b;
    }
}

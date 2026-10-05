package io.github.p_feed.account;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import io.github.p_feed.account.dto.ChangePasswordRequest;
import io.github.p_feed.account.dto.GetProfileResponse;
import io.github.p_feed.account.dto.LoginRequest;
import io.github.p_feed.account.dto.MessageResponse;
import io.github.p_feed.account.dto.RefreshRequest;
import io.github.p_feed.account.dto.RegisterRequest;
import io.github.p_feed.account.dto.RenameResponse;
import io.github.p_feed.account.dto.TokenPairResponse;
import io.github.p_feed.account.dto.UpdateProfileRequest;
import io.github.p_feed.account.dto.UploadAvatarResponse;
import io.github.p_feed.auth.JwtService;
import io.github.p_feed.auth.TokenStore;
import io.github.p_feed.infra.ApiException;
import io.github.p_feed.infra.Errors;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

@Service
public class AccountService {
    private final AccountMapper accountMapper;
    private final PasswordCipher passwordCipher;
    private final JwtService jwtService;
    private final TokenStore tokenStore;
    private final JdbcTemplate jdbcTemplate;
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");

    public AccountService(
            AccountMapper accountMapper,
            PasswordCipher passwordCipher,
            JwtService jwtService,
            TokenStore tokenStore,
            JdbcTemplate jdbcTemplate) {
        this.accountMapper = accountMapper;
        this.passwordCipher = passwordCipher;
        this.jwtService = jwtService;
        this.tokenStore = tokenStore;
        this.jdbcTemplate = jdbcTemplate;
    }

    public MessageResponse register(RegisterRequest req) {
        Account account = new Account();
        account.setUsername(req.username());
        account.setPassword(passwordCipher.hash(req.password()));

        try {
            accountMapper.insert(account);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, Errors.USERNAME_ALREADY_EXISTS);
        }

        return new MessageResponse("account created");
    }

    public TokenPairResponse login(LoginRequest req) {
        Account account =
                accountMapper.selectOne(
                        new LambdaQueryWrapper<Account>().eq(Account::getUsername, req.username()));

        if (account == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, Errors.INVALID_USERNAME_OR_PASSWORD);
        }
        if (!passwordCipher.matches(req.password(), account.getPassword())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, Errors.INVALID_USERNAME_OR_PASSWORD);
        }

        String token = jwtService.createAccessToken(account.getId(), account.getUsername());
        String refresh = jwtService.newRefreshToken();
        tokenStore.save(account.getId(), token, refresh);
        return new TokenPairResponse(token, refresh, account.getId(), account.getUsername());
    }

    public MessageResponse logout(HttpServletRequest request) {
        Long accountId = (Long) request.getAttribute("accountID");

        if (accountId == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, Errors.ACCOUNT_ID_NOT_FOUND);
        }

        tokenStore.revoke(accountId);

        return new MessageResponse("account logged out");
    }

    public TokenPairResponse refresh(RefreshRequest req) {
        String refreshToken = req.refreshToken();
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, Errors.INVALID_REFRESH_TOKEN);
        }

        Long accountId = tokenStore.findAccountIdByRefresh(refreshToken);
        if (accountId == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, Errors.INVALID_REFRESH_TOKEN);
        }

        Account account = accountMapper.selectById(accountId);
        if (account == null || !refreshToken.equals(account.getRefreshToken())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, Errors.INVALID_REFRESH_TOKEN);
        }

        String newToken = jwtService.createAccessToken(accountId, account.getUsername());
        tokenStore.save(accountId, newToken, refreshToken);

        return new TokenPairResponse(newToken, "", accountId, account.getUsername());
    }

    public MessageResponse changePassword(ChangePasswordRequest req) {
        Account account =
                accountMapper.selectOne(
                        new LambdaQueryWrapper<Account>().eq(Account::getUsername, req.username()));
        if (account == null || !passwordCipher.matches(req.oldPassword(), account.getPassword())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, Errors.UNSUCCESSFULLY_PASSWORD_CHANGED);
        }

        account.setPassword(passwordCipher.hash(req.newPassword()));
        accountMapper.updateById(account);
        tokenStore.revoke(account.getId());

        return new MessageResponse("successfully password changed");
    }

    @Transactional
    public RenameResponse rename(long accountId, String newUsername) {
        Account account = accountMapper.selectById(accountId);
        if (account == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, Errors.ACCOUNT_NOT_FOUND);
        }

        // 新token的claims里带着新用户名
        String newToken = jwtService.createAccessToken(accountId, newUsername);
        try {
            accountMapper.updateUsernameAndToken(accountId, newUsername, newToken);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, Errors.USERNAME_ALREADY_EXISTS);
        }

        // 回写缓存
        tokenStore.save(accountId, newToken, account.getRefreshToken());
        return new RenameResponse(newToken);
    }

    public MessageResponse updateProfile(long accountId, UpdateProfileRequest req) {
        boolean avatarAbsent = (req.avatarUrl() == null || req.avatarUrl().isBlank());
        boolean bioAbsent = (req.bio() == null || req.bio().isBlank());

        if (avatarAbsent && bioAbsent) {
            throw new ApiException(HttpStatus.BAD_REQUEST, Errors.NOTHING_TO_UPDATE);
        }

        Account patch = new Account();
        patch.setId(accountId);
        patch.setAvatarUrl(req.avatarUrl());
        patch.setBio(req.bio());
        accountMapper.updateById(patch);
        return new MessageResponse("profile updated");
    }

    public Account findByID(long id) {
        Account account = accountMapper.selectById(id);
        if (account == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, Errors.RECORD_NOT_FOUND);
        }

        return account;
    }

    public Account findByUsername(String username) {
        Account account =
                accountMapper.selectOne(
                        new LambdaQueryWrapper<Account>().eq(Account::getUsername, username));

        if (account == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, Errors.RECORD_NOT_FOUND);
        }

        return account;
    }

    public GetProfileResponse getProfile(long accountId) {
        Account account = accountMapper.selectById(accountId);
        if (account == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, Errors.RECORD_NOT_FOUND);
        }

        // 四个计数：任一失败返回0不报错, 暂时的
        long videoCount = safeCount("select count(*) from videos where author_id = ?", account);
        long followerCount =
                safeCount("select count(*) from socials where followee_id = ?", accountId);
        long vloggerCount =
                safeCount("select count(*) from socials where follower_id = ?", accountId);
        long totalLikes =
                safeCount(
                        "select count(*) from likes where video_id in (select id from videos where"
                            + " author_id = ?)",
                        accountId);

        return new GetProfileResponse(account, videoCount, totalLikes, followerCount, vloggerCount);
    }

    // 图片上传
    public UploadAvatarResponse uploadAvatar(
            Long accountId, MultipartFile file, HttpServletRequest request) {
        if (file == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, Errors.MISSING_FILE);
        }

        if (file.getSize() <= 0 || file.getSize() > 10L * 1024 * 1024) {
            throw new ApiException(HttpStatus.BAD_REQUEST, Errors.INVALID_FILE_SIZE);
        }

        String original = file.getOriginalFilename();
        int dot = original == null ? -1 : original.lastIndexOf('.');
        String ext = dot >= 0 ? original.substring(dot + 1).toLowerCase(Locale.ROOT) : "";

        if (!IMAGE_EXTENSIONS.contains(ext)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, Errors.ONLY_IMAGE_EXTENSIONS);
        }

        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        String fileName = HexFormat.of().formatHex(random) + "." + ext;

        try {
            // data/uploads/avatars/{accountId}
            Path dir = Paths.get("data/uploads/avatars", String.valueOf(accountId));
            Files.createDirectories(dir);
            file.transferTo(dir.resolve(fileName));
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }

        String proto = request.getHeader("X-Forwarded-Proto");
        String host = request.getHeader("Host");
        String url =
                (proto != null ? proto : "http")
                        + "://"
                        + (host != null ? host : "localhost:8081")
                        + "/static/avatars/"
                        + accountId
                        + "/"
                        + fileName
        ;

        Account account = new Account();
        account.setId(accountId);
        account.setAvatarUrl(url);
        accountMapper.updateById(account);

        return new UploadAvatarResponse(url);
    }

    private long safeCount(String sql, Object... params) {
        try {
            Long n = jdbcTemplate.queryForObject(sql, Long.class, params);
            return n == null ? 0L : n;
        } catch (RuntimeException e) {
            return 0L;
        }
    }
}

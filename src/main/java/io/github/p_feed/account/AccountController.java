package io.github.p_feed.account;

import io.github.p_feed.account.dto.ChangePasswordRequest;
import io.github.p_feed.account.dto.FindByIDRequest;
import io.github.p_feed.account.dto.FindByUsernameRequest;
import io.github.p_feed.account.dto.GetProfileRequest;
import io.github.p_feed.account.dto.GetProfileResponse;
import io.github.p_feed.account.dto.LoginRequest;
import io.github.p_feed.account.dto.MessageResponse;
import io.github.p_feed.account.dto.RefreshRequest;
import io.github.p_feed.account.dto.RegisterRequest;
import io.github.p_feed.account.dto.RenameRequest;
import io.github.p_feed.account.dto.RenameResponse;
import io.github.p_feed.account.dto.TokenPairResponse;
import io.github.p_feed.account.dto.UpdateProfileRequest;
import io.github.p_feed.account.dto.UploadAvatarResponse;
import io.github.p_feed.infra.ApiException;
import io.github.p_feed.infra.Errors;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/account")
public class AccountController {

    private final AccountService service;

    public AccountController(AccountService service) {
        this.service = service;
    }

    @PostMapping("/register")
    public MessageResponse register(@RequestBody RegisterRequest req) {
        return service.register(req);
    }

    @PostMapping("/login")
    public TokenPairResponse login(@RequestBody LoginRequest req) {
        return service.login(req);
    }

    @PostMapping("/logout")
    public MessageResponse logout(HttpServletRequest req) {
        return service.logout(req);
    }

    @PostMapping("/refresh")
    public TokenPairResponse refresh(@RequestBody RefreshRequest req) {
        return service.refresh(req);
    }

    @PostMapping("/changePassword")
    public MessageResponse changePassword(@RequestBody ChangePasswordRequest req) {
        return service.changePassword(req);
    }

    @PostMapping("/rename")
    public RenameResponse rename(HttpServletRequest request, @RequestBody RenameRequest req) {
        Long accountId = (Long) request.getAttribute("accountID");
        return service.rename(accountId, req.newUsername());
    }

    @PostMapping("/updateProfile")
    public MessageResponse updateProfile(
            HttpServletRequest request, @RequestBody UpdateProfileRequest req) {
        Long accountId = (Long) request.getAttribute("accountID");
        return service.updateProfile(accountId, req);
    }

    @PostMapping("/findByID")
    public Account findByID(@RequestBody FindByIDRequest req) {
        return service.findByID(req.id());
    }

    @PostMapping("/findByUsername")
    public Account findByUsername(@RequestBody FindByUsernameRequest req) {
        return service.findByUsername(req.username());
    }

    @PostMapping("/getProfile")
    public GetProfileResponse getProfile(@RequestBody GetProfileRequest req) {
        Long accountId = req.accountId();
        if (accountId == null || accountId == 0L) {
            throw new ApiException(HttpStatus.BAD_REQUEST, Errors.ACCOUNT_ID_IS_REQUIRED);
        }
        return service.getProfile(accountId);
    }

    @PostMapping("/uploadAvatar")
        public UploadAvatarResponse uploadAvatar(HttpServletRequest request, @RequestPart(value = "file", required = false) MultipartFile file) {
        Long accountId = (Long) request.getAttribute("accountID");
        if(accountId == null){
            throw new ApiException(HttpStatus.UNAUTHORIZED, Errors.ACCOUNT_ID_NOT_FOUND);
        }

        return service.uploadAvatar(accountId, file, request);
        }
        

}

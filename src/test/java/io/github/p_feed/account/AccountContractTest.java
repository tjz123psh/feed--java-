package io.github.p_feed.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.p_feed.ApiApplication;
import io.github.p_feed.infra.Errors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = ApiApplication.class)
@AutoConfigureMockMvc
class AccountContractTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String uniqueName() {
        return "ct_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private void register(String username, String password) throws Exception {
        mockMvc.perform(post("/account/register").contentType(JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("account created"));
    }

    private JsonNode login(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/account/login").contentType(JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private JsonNode registerAndLogin() throws Exception {
        String name = uniqueName();
        register(name, "pw123456");
        return login(name, "pw123456");
    }

    private String tokenOf(JsonNode login) {
        return login.get("token").asText();
    }

    // ---- register / login ----

    @Test
    void registerDuplicateUsername_returns409() throws Exception {
        String name = uniqueName();
        register(name, "pw123456");
        mockMvc.perform(post("/account/register").contentType(JSON)
                        .content("{\"username\":\"" + name + "\",\"password\":\"pw123456\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(Errors.USERNAME_ALREADY_EXISTS));
    }

    @Test
    void loginWrongPassword_returns400() throws Exception {
        String name = uniqueName();
        register(name, "pw123456");
        mockMvc.perform(post("/account/login").contentType(JSON)
                        .content("{\"username\":\"" + name + "\",\"password\":\"wrong123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(Errors.INVALID_USERNAME_OR_PASSWORD));
    }

    // ---- logout ----

    @Test
    void logoutThenSameTokenAgain_returns401() throws Exception {
        String token = tokenOf(registerAndLogin());
        mockMvc.perform(post("/account/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("account logged out"));
        mockMvc.perform(post("/account/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(Errors.TOKEN_HAS_BEEN_REVOKED));
    }

    // ---- refresh ----

    @Test
    void refreshWithInvalidToken_returns401() throws Exception {
        mockMvc.perform(post("/account/refresh").contentType(JSON)
                        .content("{\"refresh_token\":\"not-a-real-token\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(Errors.INVALID_REFRESH_TOKEN));
    }

    @Test
    void refreshSuccess_returnsEmptyRefreshToken() throws Exception {
        String refresh = registerAndLogin().get("refresh_token").asText();
        mockMvc.perform(post("/account/refresh").contentType(JSON)
                        .content("{\"refresh_token\":\"" + refresh + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.refresh_token").value(""));
    }

    // ---- changePassword ----

    @Test
    void changePasswordWrongOld_returns400() throws Exception {
        String name = uniqueName();
        register(name, "pw123456");
        mockMvc.perform(post("/account/changePassword").contentType(JSON)
                        .content("{\"username\":\"" + name
                                + "\",\"old_password\":\"wrong\",\"new_password\":\"new123456\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(Errors.UNSUCCESSFULLY_PASSWORD_CHANGED));
    }

    @Test
    void changePasswordSuccess_returns200() throws Exception {
        String name = uniqueName();
        register(name, "pw123456");
        mockMvc.perform(post("/account/changePassword").contentType(JSON)
                        .content("{\"username\":\"" + name
                                + "\",\"old_password\":\"pw123456\",\"new_password\":\"new123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("successfully password changed"));
    }

    // ---- findByID / findByUsername ----

    @Test
    void findByIDMissing_returns404() throws Exception {
        mockMvc.perform(post("/account/findByID").contentType(JSON)
                        .content("{\"id\":999999999}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(Errors.RECORD_NOT_FOUND));
    }

    @Test
    void findByUsernameMissing_returns404() throws Exception {
        mockMvc.perform(post("/account/findByUsername").contentType(JSON)
                        .content("{\"username\":\"nobody_here\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(Errors.RECORD_NOT_FOUND));
    }

    @Test
    void findByIDAndFindByUsername_ReturnSameAccount() throws Exception {
        JsonNode login = registerAndLogin();
        long id = login.get("account_id").asLong();
        String name = login.get("username").asText();
        mockMvc.perform(post("/account/findByID").contentType(JSON)
                        .content("{\"id\":" + id + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(name));
        mockMvc.perform(post("/account/findByUsername").contentType(JSON)
                        .content("{\"username\":\"" + name + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
    }

    // ---- getProfile ----

    @Test
    void getProfileWithZeroId_returns400() throws Exception {
        mockMvc.perform(post("/account/getProfile").contentType(JSON)
                        .content("{\"account_id\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(Errors.ACCOUNT_ID_IS_REQUIRED));
    }

    @Test
    void getProfileSuccess_returnsFourCounts() throws Exception {
        JsonNode login = registerAndLogin();
        long id = login.get("account_id").asLong();
        mockMvc.perform(post("/account/getProfile").contentType(JSON)
                        .content("{\"account_id\":" + id + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account").exists())
                .andExpect(jsonPath("$.video_count").isNumber())
                .andExpect(jsonPath("$.total_likes").isNumber())
                .andExpect(jsonPath("$.follower_count").isNumber())
                .andExpect(jsonPath("$.vlogger_count").isNumber());
    }

    // ---- rename ----

    @Test
    void renameToTakenName_returns409() throws Exception {
        String taken = uniqueName();
        register(taken, "pw123456");
        String token = tokenOf(registerAndLogin());
        mockMvc.perform(post("/account/rename").header("Authorization", "Bearer " + token)
                        .contentType(JSON).content("{\"new_username\":\"" + taken + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(Errors.USERNAME_ALREADY_EXISTS));
    }

    @Test
    void renameSuccess_returnsNewToken() throws Exception {
        String token = tokenOf(registerAndLogin());
        mockMvc.perform(post("/account/rename").header("Authorization", "Bearer " + token)
                        .contentType(JSON).content("{\"new_username\":\"" + uniqueName() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    // ---- updateProfile ----

    @Test
    void updateProfileEmpty_returns400NothingToUpdate() throws Exception {
        String token = tokenOf(registerAndLogin());
        mockMvc.perform(post("/account/updateProfile").header("Authorization", "Bearer " + token)
                        .contentType(JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(Errors.NOTHING_TO_UPDATE));
    }

    @Test
    void updateProfileWithBio_returns200() throws Exception {
        String token = tokenOf(registerAndLogin());
        mockMvc.perform(post("/account/updateProfile").header("Authorization", "Bearer " + token)
                        .contentType(JSON).content("{\"bio\":\"hi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("profile updated"));
    }

    // ---- uploadAvatar ----

    @Test
    void uploadAvatarMissingFile_returns400() throws Exception {
        String token = tokenOf(registerAndLogin());
        mockMvc.perform(multipart("/account/uploadAvatar")
                        .file(new MockMultipartFile("avatar", "a.jpg", "image/jpeg", new byte[]{1}))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(Errors.MISSING_FILE));
    }

    @Test
    void uploadAvatarEmptyFile_returns400() throws Exception {
        String token = tokenOf(registerAndLogin());
        mockMvc.perform(multipart("/account/uploadAvatar")
                        .file(new MockMultipartFile("file", "empty.jpg", "image/jpeg", new byte[0]))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(Errors.INVALID_FILE_SIZE));
    }

    @Test
    void uploadAvatarOver10MB_returns400() throws Exception {
        String token = tokenOf(registerAndLogin());
        mockMvc.perform(multipart("/account/uploadAvatar")
                        .file(new MockMultipartFile("file", "big.png", "image/png",
                                new byte[10 * 1024 * 1024 + 1]))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(Errors.INVALID_FILE_SIZE));
    }

    @Test
    void uploadAvatarBadExtension_returns400() throws Exception {
        String token = tokenOf(registerAndLogin());
        mockMvc.perform(multipart("/account/uploadAvatar")
                        .file(new MockMultipartFile("file", "avatar.txt", "text/plain", new byte[]{1, 2, 3}))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(Errors.ONLY_IMAGE_EXTENSIONS));
    }

    @Test
    void uploadAvatarSuccess_returnsAbsoluteUrl() throws Exception {
        JsonNode login = registerAndLogin();
        String token = tokenOf(login);
        long id = login.get("account_id").asLong();
        mockMvc.perform(multipart("/account/uploadAvatar")
                        .file(new MockMultipartFile("file", "avatar.JPG", "image/jpeg", new byte[]{1, 2, 3}))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatar_url", startsWith("http://localhost")))
                .andExpect(jsonPath("$.avatar_url",
                        containsString("/static/avatars/" + id + "/")))
                .andExpect(jsonPath("$.avatar_url", endsWith(".jpg")));
    }
}
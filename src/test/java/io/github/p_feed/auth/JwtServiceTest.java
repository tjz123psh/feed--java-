package io.github.p_feed.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;

import io.github.p_feed.config.AppProps;

class JwtServiceTest {

    private final JwtService jwtService = new JwtService(
            new AppProps(new AppProps.Redis("v1:"), new AppProps.Jwt("test-secret-key"), null, null, null));

    @Test
    void signThenParse_claimsMatch() {
        String token = jwtService.createAccessToken(7L, "u3");
        DecodedJWT decoded = jwtService.parseToken(token);
        assertThat(decoded.getClaim("account_id").asLong()).isEqualTo(7L);
        assertThat(decoded.getClaim("username").asString()).isEqualTo("u3");
    }

    @Test
    void tamperedSignature_throws() {
        String token = jwtService.createAccessToken(7L, "u3");
        // 改签名段的【第一个】字符：它的 6 位全部有效，必然改变签名内容。
        // 别改最后一个字符——base64url 尾字符只有 4 位有效（后 2 位是填充），
        // 换成同前缀的字符解码后完全相同，签名没变、验签照样通过，测试会假失败（实测约 6%）。
        String[] parts = token.split("\\.");
        char replacement = parts[2].charAt(0) == 'A' ? 'B' : 'A';
        String tampered = parts[0] + "." + parts[1] + "." + replacement + parts[2].substring(1);

        assertThat(tampered).isNotEqualTo(token);
        assertThatThrownBy(() -> jwtService.parseToken(tampered))
                .isInstanceOf(JWTVerificationException.class);
    }

    @Test
    void tokenLives15Minutes() {
        String token = jwtService.createAccessToken(7L, "u3");
        DecodedJWT decoded = jwtService.parseToken(token);
        long exp = decoded.getClaim("exp").asLong();   // 秒
        long iat = decoded.getClaim("iat").asLong();
        assertThat(exp - iat).isEqualTo(900L);         // 15 分钟 = 900 秒
    }

    @Test
    void refreshTokenIs64Hex() {
        String rt = jwtService.newRefreshToken();
        assertThat(rt).hasSize(64).matches("[0-9a-f]{64}");
    }
}

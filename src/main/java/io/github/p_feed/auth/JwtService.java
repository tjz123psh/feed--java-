package io.github.p_feed.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;

import org.springframework.stereotype.Component;
import io.github.p_feed.config.AppProps;

@Component
public class JwtService{
    private static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(15);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Algorithm alg;

    private final JWTVerifier verifier;

    public JwtService(AppProps props){
        // 这里的secret很关键，这是不公开的，本地服务器持有
        String secret = props.jwt().secret();
        this.alg = Algorithm.HMAC256(secret);
        //算法识别
        this.verifier = JWT.require(alg).build();
    }

    public String createAccessToken(long account,String username){
        // 时间戳
        Instant now = Instant.now();

        String token = JWT.create()
            .withClaim("account_id", account)
            .withClaim("username", username)
            .withIssuedAt(Date.from(now))
            .withNotBefore(Date.from(now))
            .withExpiresAt(Date.from(now.plus(ACCESS_TOKEN_TTL)))
            .sign(alg);

        return token;
    }

    public DecodedJWT parseToken(String token){
        // 验证token
        return verifier.verify(token);
    }

    public String newRefreshToken(){
        // 32位的字节数组
        byte[] bytes = new byte[32];
        // 更加安全的随机算法讲这32位的每一位字节转化为hex字符串
        RANDOM.nextBytes(bytes);
        // 将数组的内容连接起来
        return HexFormat.of().formatHex(bytes);
    }




}

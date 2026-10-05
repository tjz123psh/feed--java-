package io.github.p_feed.account;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component

public class PasswordCipher{

    private static final int BCRYPT_COST = 10;

    private final BCryptPasswordEncoder encoder;

    public PasswordCipher(){
        this.encoder = new BCryptPasswordEncoder(BCRYPT_COST);  
    }
    
    // 明文加密：加盐哈希
    public String hash(String rawPassword){
        return encoder.encode(rawPassword);
    }

    public boolean matches(String rawPassword, String encodeHash){
        return encoder.matches(rawPassword, encodeHash);
    }

}

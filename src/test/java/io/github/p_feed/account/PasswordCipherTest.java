package io.github.p_feed.account;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordCipherTest{

    private final PasswordCipher cipher = new PasswordCipher();

    @Test
    void hashStartsWithBcryptCost10(){
        assertThat(cipher.hash("secret123")).startsWith("$2a$10$");
    }

    @Test
    void matches_returnsTrue_forCorrectPassword() {
        String hash = cipher.hash("secret123");
        assertThat(cipher.matches("secret123", hash)).isTrue();
    }

    @Test
    void matches_returnsFalse_forWrongPassword() {
        String hash = cipher.hash("secret123");
        assertThat(cipher.matches("wrong", hash)).isFalse();
    }

    @Test
    void samePassword_producesDifferentHashes() {
        assertThat(cipher.hash("secret123")).isNotEqualTo(cipher.hash("secret123"));
    }
}

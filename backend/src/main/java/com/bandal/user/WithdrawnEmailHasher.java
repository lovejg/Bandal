package com.bandal.user;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Locale;

// 점수가 낮은 채 탈퇴한 사람의 메일을 되돌릴 수 없는 값(HMAC-SHA256)으로 바꾼다. 같은 메일로 다시 가입하면 같은 값이 나와 점수를 이어 붙인다
//
// 그냥 SHA-256이면 안 되는 이유: 학교 메일은 형식이 뻔하다(학번@학교.ac.kr). 가능한 주소를 전부 해시해 보면 원래 메일을 찾을 수 있다.
// 비밀 키를 섞은 HMAC은 키 없이는 같은 값을 만들 수 없어서 DB가 새도 메일을 알아낼 수 없다
@Component
public class WithdrawnEmailHasher {

    private final byte[] key;

    public WithdrawnEmailHasher(@Value("${app.withdrawn-email-hash-key}") String key) {
        this.key = key.getBytes(StandardCharsets.UTF_8);
    }

    // 대소문자, 앞뒤 공백을 맞춘 뒤 해시한다. 가입과 같은 정규화라 Kim@과 kim@이 같은 값이 된다
    public String hash(String email) {
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            // HmacSHA256은 모든 자바에 들어 있어서 여기 올 일이 없다
            throw new IllegalStateException(e);
        }
    }
}

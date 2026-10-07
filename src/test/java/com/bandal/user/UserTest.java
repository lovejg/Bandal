package com.bandal.user;

import com.bandal.university.University;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// DB 없이 사용자 엔티티의 규칙만 확인한다
class UserTest {

    User user;

    @BeforeEach
    void setUp() {
        University university = new University("한국대학교", "hankuk.ac.kr");
        user = new User(university, "lee@hankuk.ac.kr", "hashed-password", "마라탕러버");
    }

    @Nested
    @DisplayName("계좌 등록 (ADR-031, 041)")
    class RegisterAccount {

        @Test
        @DisplayName("등록하면 계좌가 생기고, 계좌번호는 숫자만 남긴다")
        void registers() {
            user.registerAccount("한국은행", "110-999 888", "이영희");

            assertThat(user.hasAccount()).isTrue();
            assertThat(user.getBankName()).isEqualTo("한국은행");
            assertThat(user.getAccountNumber()).isEqualTo("110999888");
            assertThat(user.getAccountHolder()).isEqualTo("이영희");
        }

        @Test
        @DisplayName("다시 등록하면 새 계좌로 덮어쓴다")
        void overwrites() {
            user.registerAccount("한국은행", "110-999-888", "이영희");
            user.registerAccount("우리은행", "1002-111-222", "이영희");

            assertThat(user.getBankName()).isEqualTo("우리은행");
            assertThat(user.getAccountNumber()).isEqualTo("1002111222");
        }

        @Test
        @DisplayName("은행이 null이면 거절한다")
        void rejectsNullBank() {
            assertThatThrownBy(() -> user.registerAccount(null, "110-999-888", "이영희"))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(user.hasAccount()).isFalse();
        }

        @Test
        @DisplayName("계좌번호가 null이면 거절한다")
        void rejectsNullAccountNumber() {
            assertThatThrownBy(() -> user.registerAccount("한국은행", null, "이영희"))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(user.hasAccount()).isFalse();
        }

        @Test
        @DisplayName("예금주가 공백뿐이면 거절한다")
        void rejectsBlankHolder() {
            assertThatThrownBy(() -> user.registerAccount("한국은행", "110-999-888", "   "))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(user.hasAccount()).isFalse();
        }

        @Test
        @DisplayName("계좌번호가 하이픈뿐이면 숫자를 남기고 나면 빈 값이라 거절한다")
        void rejectsAccountNumberWithoutDigits() {
            assertThatThrownBy(() -> user.registerAccount("한국은행", "---", "이영희"))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(user.hasAccount()).isFalse();
        }

        @Test
        @DisplayName("거절되면 원래 계좌가 그대로 남는다")
        void keepsOldAccountWhenRejected() {
            user.registerAccount("한국은행", "110-999-888", "이영희");

            // 은행만 바꾸고 계좌번호를 비운 요청. 은행만 바뀐 채로 남으면 은행과 번호가 서로 안 맞는다
            assertThatThrownBy(() -> user.registerAccount("우리은행", "", "이영희"))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(user.getBankName()).isEqualTo("한국은행");
            assertThat(user.getAccountNumber()).isEqualTo("110999888");
        }
    }
}

package com.bandal.auth;

import com.bandal.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

// Spring Security가 알아서 만드는 것 중 우리에게 필요 없는 것이 꺼져 있는지 본다
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "management.health.mail.enabled=false")
class SecurityDefaultsTest {

    @Autowired
    ApplicationContext context;

    @Test
    @DisplayName("기본 계정(user)을 만들지 않는다. 로그인은 JWT뿐이고, 만들면 그 비밀번호가 로그에 찍힌다")
    void noDefaultUser() {
        assertThat(context.getBeanNamesForType(UserDetailsService.class)).isEmpty();
    }
}

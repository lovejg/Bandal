package com.bandal;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

// 다른 패키지의 테스트(university 등)에서도 @Import 해야 해서 public이다.
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    // latest를 쓰면 어느 날 Postgres 메이저 버전이 올라가면서 테스트가 갑자기 깨진다.
    // docker-compose.yml과 같은 버전으로 고정한다.
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:17"));
    }

}

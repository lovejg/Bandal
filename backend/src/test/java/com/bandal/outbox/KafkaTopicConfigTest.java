package com.bandal.outbox;

import com.bandal.TestcontainersConfiguration;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 앱이 만든 상태 변경 토픽이 되감기에 필요한 설정을 갖고 있나. 진짜 Kafka(Testcontainers)에 물어본다
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class KafkaTopicConfigTest {

    @Autowired
    KafkaAdmin kafkaAdmin;

    @Value("${app.kafka.group-order-status-topic}")
    String topic;

    @Test
    @DisplayName("상태 변경 토픽은 칸 3개이고 글을 지우지 않는다(보관 기간 -1)")
    void keepsMessagesForever() throws Exception {
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
            Config config = admin.describeConfigs(List.of(resource)).all().get().get(resource);
            int partitions = admin.describeTopics(List.of(topic)).allTopicNames().get().get(topic).partitions().size();

            assertThat(config.get(TopicConfig.RETENTION_MS_CONFIG).value()).isEqualTo("-1");
            assertThat(partitions).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("DLT는 Spring Kafka가 옮기는 이름(원래 이름-dlt)으로 칸 3개, 보관 무기한으로 만들어진다")
    void deadLetterTopicMatchesRecoverer() throws Exception {
        String dlt = topic + "-dlt";
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, dlt);
            Config config = admin.describeConfigs(List.of(resource)).all().get().get(resource);
            // 원래 글과 같은 칸 번호로 옮겨진다. 칸이 모자라면 옮길 칸이 없다
            int partitions = admin.describeTopics(List.of(dlt)).allTopicNames().get().get(dlt).partitions().size();

            assertThat(config.get(TopicConfig.RETENTION_MS_CONFIG).value()).isEqualTo("-1");
            assertThat(partitions).isEqualTo(3);
        }
    }
}

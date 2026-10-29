package com.bandal.outbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

// 앱이 뜰 때 토픽이 없으면 만든다(있으면 그대로 둔다). 손으로 만들다 칸 수를 틀리지 않게 코드에 둔다
// 이미 있는 토픽의 설정은 바꾸지 않는다. 이 설정을 넣기 전에 만든 로컬 토픽은 kafka-configs로 한 번 고쳐야 한다
@Configuration
public class KafkaTopicConfig {

    @Bean
    NewTopic groupOrderStatusTopic(@Value("${app.kafka.group-order-status-topic}") String name) {
        // 이름은 "상태"지만 방에 생긴 변경을 다 싣는다. 상태 변경과 배달비, 도착 예정 수정(상태는 그대로)을 글의 kind로 가린다
        // 같은 방의 두 변경이 한 칸에 순서대로 서야 해서 토픽을 나누지 않았다
        // 칸 3개: worker가 늘면 칸을 나눠 읽는다. 브로커가 한 대라 복제본은 1
        // 보관 기간 무기한(-1): 기본은 7일이 지나면 글을 지운다. 통계에 새 칸이 필요해져 맨 앞으로 되감을 때 지난 글이 남아 있어야 한다
        // 글 하나가 1KB 안팎이라 방 1만 개가 상태를 다섯 번씩 바꿔도 50MB 정도다
        return TopicBuilder.name(name).partitions(3).replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, "-1")
                .build();
    }

    // 처리하지 못한 글을 옮겨 두는 곳(KafkaErrorHandlingConfig)
    // 원래 글과 같은 칸 번호로 옮겨지므로 칸 수가 원래 토픽 이상이어야 한다. 사람이 볼 때까지 남아 있어야 해서 보관 기간도 무기한
    // 이름은 Spring Kafka가 정하는 기본값(원래 이름 + "-dlt")에 맞춘다. 옛 버전 기본값은 ".DLT"였다
    // 이름이 어긋나면 DLT로 옮기는 쪽이 없는 토픽에 쓰고, 브로커가 그 이름으로 칸 1개짜리 토픽을 저절로 만들어 버린다(2026-10-27 겪음)
    @Bean
    NewTopic groupOrderStatusDeadLetterTopic(@Value("${app.kafka.group-order-status-topic}") String name) {
        return TopicBuilder.name(name + "-dlt").partitions(3).replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, "-1")
                .build();
    }
}

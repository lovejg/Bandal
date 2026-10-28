package com.bandal.outbox;

import com.bandal.TestcontainersConfiguration;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

// DLT 카운터가 첫 DLT 전에 이미 있는가. Grafana 경고는 increase()로 "늘었나"를 보는데,
// 없던 숫자가 1로 나타나면 늘어난 것으로 세지 않아 첫 DLT에 경고가 안 울린다
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DeadLetterCounterTest {

    @Autowired
    MeterRegistry meterRegistry;

    @Value("${app.kafka.group-order-status-topic}")
    String topic;

    @Test
    @DisplayName("앱이 뜨면 컨슈머 그룹마다 DLT 카운터가 이미 있다(첫 DLT가 0 → 1로 보이게)")
    void registeredAtStartup() {
        for (String group : KafkaErrorHandlingConfig.CONSUMER_GROUPS) {
            Counter counter = meterRegistry.find("bandal.kafka.dead.letter").tags("topic", topic, "group", group).counter();
            assertThat(counter).as("group=%s", group).isNotNull();
        }
    }
}

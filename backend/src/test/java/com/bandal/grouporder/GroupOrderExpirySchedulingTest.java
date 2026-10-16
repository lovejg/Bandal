package com.bandal.grouporder;

import com.bandal.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 스프링이 정말 1분마다 run()을 부르게 등록됐는지 본다
// 다른 테스트는 runAt을 직접 불러서, @EnableScheduling이나 @Scheduled가 빠져도 전부 통과한다. 그 구멍을 막는다
// 1분을 기다리지 않는다. 시작할 때 만들어진 예약 작업 목록을 꺼내 본다
@SpringBootTest(properties = "app.scheduling.enabled=true")
@Import(TestcontainersConfiguration.class)
// 이 테스트만 스케줄러가 켜진 앱이다. 끝나면 닫아서 뒤에서 계속 돌지 않게 한다
@DirtiesContext
class GroupOrderExpirySchedulingTest {

    // @EnableScheduling이 켜지면 생기는 빈이다. 등록된 예약 작업을 들고 있다
    @Autowired
    ScheduledTaskHolder scheduledTaskHolder;

    @Test
    @DisplayName("멈춘 방 스케줄러의 run()이 앞 실행이 끝나고 60초마다 도는 작업으로 등록된다")
    void registersExpiryEveryMinute() {
        List<FixedDelayTask> expiryTasks = scheduledTaskHolder.getScheduledTasks().stream()
                .map(ScheduledTask::getTask)
                .filter(task -> task instanceof FixedDelayTask)
                .map(task -> (FixedDelayTask) task)
                // 스프링 7은 실제 메서드를 실행 결과 기록용 래퍼(OutcomeTrackingRunnable)로 감싸 둔다.
                // 그래서 타입이 아니라 "클래스이름.메서드이름"으로 알려주는 이름으로 고른다
                .filter(task -> task.getRunnable().toString()
                        .equals(GroupOrderExpiryScheduler.class.getName() + ".run"))
                .toList();

        assertThat(expiryTasks).hasSize(1);
        assertThat(expiryTasks.get(0).getIntervalDuration()).isEqualTo(Duration.ofSeconds(60));
    }
}

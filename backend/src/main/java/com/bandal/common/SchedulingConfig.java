package com.bandal.common;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// @Scheduled가 붙은 메서드를 스프링이 시간마다 부르게 켠다
// 테스트에서는 app.scheduling.enabled=false로 끈다. 켜두면 테스트가 데이터를 만드는 도중에 스케줄러가 끼어든다
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}

package com.bandal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class BandalApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(BandalApplication.class, args);

        // batch 컨테이너(deploy/bandal/batch.sh)는 웹 서버 없이 Job 하나를 돌리고 꺼져야 한다
        // Spring Boot는 뜰 때 Job을 돌리고(spring.batch.job.enabled) 끝나도 앱을 내리지 않는다. 그래서 여기서 직접 끈다
        // SpringApplication.exit: Job이 실패했으면 0이 아닌 종료 코드를 돌려준다(Batch가 넣어 둔 종료 코드 생성기).
        // 그 코드가 컨테이너의 종료 코드가 되어 batch.sh와 cron 로그에 성공/실패로 남는다
        // backend, worker는 이 값을 주지 않아(기본 false) 계속 떠 있다
        if (context.getEnvironment().getProperty("app.batch.exit-when-done", Boolean.class, false)) {
            System.exit(SpringApplication.exit(context));
        }
    }

}

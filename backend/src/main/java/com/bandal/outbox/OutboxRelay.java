package com.bandal.outbox;

import com.bandal.auth.EmailVerificationService;
import com.bandal.auth.VerificationMailSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

// 집배원. 몇 초마다 보낼 편지함을 열어 찜한 줄을 보내고 결과를 적는다
// 여기에는 @Transactional을 두지 않는다. 메일 서버를 기다리는 동안 DB 커넥션을 붙잡지 않게, 트랜잭션은 OutboxService에서만 짧게 연다
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxRelay {

    private final OutboxService outboxService;
    private final EmailVerificationService emailVerificationService;
    private final VerificationMailSender mailSender;

    // 앞 실행이 끝나고 poll-delay-ms만큼 쉰 뒤 다시 돈다. 앞 실행이 길어져도 겹치지 않는다
    @Scheduled(fixedDelayString = "${app.outbox.poll-delay-ms}")
    public void run() {
        runAt(Instant.now());
    }

    // 찜한 줄을 하나씩 보내고 결과를 적는다
    public void runAt(Instant now) {
        for(OutboxService.Claimed c : outboxService.claim(now)) {
            try {
                send(c);
                outboxService.markSent(c.id(), now);
            } catch(Exception e) {
                // 줄마다 따로 잡는다. 한 통이 실패해도 다음 줄로 간다. 주소는 로그에 남기지 않는다
                log.error("가입 메일을 보내지 못했습니다. outboxId={}, userId={}", c.id(), c.userId(), e);
                outboxService.markFailed(c.id(), now);
            }
        }
    }

    private void send(OutboxService.Claimed c) {
        if(c.type() == OutboxType.VERIFICATION_MAIL) {
            // 기다리는 사이에 이미 인증했으면 보내지 않는다. 보낸 것으로 적어서 대기 줄에서 뺀다
            if(!c.verified()) emailVerificationService.send(c.userId(), c.email());
        }
        else if(c.type() == OutboxType.ALREADY_REGISTERED_MAIL) {
            mailSender.sendAlreadyRegistered(c.email());
        }
        else {
            throw new IllegalStateException("모르는 메일 종류: " + c.type());
        }
    }
}

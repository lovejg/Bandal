package com.bandal.grouporder;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

// 1분마다 멈춘 방을 찾아 넘긴다
// 찾는 건 여기서, 넘길지 말지는 서비스와 엔티티가 정한다
@Component
@RequiredArgsConstructor
@Slf4j
public class GroupOrderExpiryScheduler {

    private final GroupOrderRepository groupOrderRepository;
    private final GroupOrderService groupOrderService;

    // 앞 실행이 끝나고 60초 뒤에 다시 돈다. fixedRate와 달리 앞 실행이 길어져도 겹치지 않는다
    @Scheduled(fixedDelay = 60_000)
    public void run() {
        runAt(Instant.now());
    }

    // 시각을 밖에서 받아서 테스트가 원하는 시각으로 부를 수 있다
    public void runAt(Instant now) {
        List<Long> ids = groupOrderRepository.findIdsToExpire(
                now,
                now.minus(GroupOrder.CLOSED_EXPIRY),
                now.minus(GroupOrder.SETTLING_EXPIRY),
                now.minus(GroupOrder.ORDERED_EXPIRY));

        // 방마다 따로 잡는다. 한 방이 터져도 다음 방으로 간다(문제되는 방 건너뛰기)
        // expire가 방마다 자기 트랜잭션이라, 여기로 예외가 올라왔을 때는 그 방만 이미 롤백된 뒤다
        for (Long id : ids) {
            try {
                groupOrderService.expire(id, now);
            } catch (Exception e) {
                // 다음 1분에 다시 시도된다. 계속 실패하면 같은 id가 로그에 반복해서 찍힌다
                log.error("멈춘 방을 넘기지 못했습니다. groupOrderId={}", id, e);
            }
        }
    }
}

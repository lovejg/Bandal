package com.bandal.outbox;

import com.bandal.user.User;
import com.bandal.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// 집배원(OutboxRelay)이 부르는 짧은 트랜잭션들. 메일 발송(바깥 호출)은 여기서 하지 않는다
// 집배원과 클래스를 나눈 이유: 같은 클래스 안에서 부르면 프록시를 안 거쳐 @Transactional이 무시된다
@Service
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxMessageRepository outboxMessageRepository;
    private final UserRepository userRepository;

    // 한번에 찜할 수 있는 최대 개수(10)
    @Value("${app.outbox.batch-size}")
    private int batchSize;

    @Value("${app.outbox.retry-base-seconds}")
    private long retryBaseSeconds;

    // 찜한 줄 하나를 보내는 데 필요한 것만 담는다. 엔티티를 넘기지 않는다(트랜잭션이 끝나면 보관함 밖 객체가 된다)
    // 이메일은 메모리에서만 잠깐 들고 있다가 버린다. outbox 표에는 남지 않는다
    // 메일 줄은 userId, email, verified만, 방 상태 줄은 groupOrderId, payload만 채운다. 나머지는 null(verified는 false)
    public record Claimed(Long id, OutboxType type,
                          Long userId, String email, boolean verified,
                          Long groupOrderId, String payload) {
    }

    // 찜하기
    @Transactional
    public List<Claimed> claim(Instant now) {
        List<OutboxMessage> due = outboxMessageRepository.findDueForUpdate(now, batchSize);
        List<Claimed> claimed = new ArrayList<>();
        for (OutboxMessage message : due) {
            message.claim(now);
            if(message.getType() == OutboxType.GROUP_ORDER_STATUS_CHANGED) {
                // 방(Kafka로 들어감)
                claimed.add(new Claimed(message.getId(), message.getType(), null, null, false,
                    message.getGroupOrderId(), message.getPayload()));
            }
            else {
                // 메일
                User user = userRepository.findByIdOrThrow(message.getUserId());
                claimed.add(new Claimed(message.getId(), message.getType(), user.getId(), user.getEmail(),
                user.getEmailVerifiedAt() != null, null, null));
            }
        }
        return claimed;
    }

    // 보냄 기록
    @Transactional
    public void markSent(Long id, Instant now) {
        outboxMessageRepository.findById(id).orElseThrow().markSent(now);
    }

    // 실패 기록
    @Transactional
    public void markFailed(Long id, Instant now) {
        outboxMessageRepository.findById(id).orElseThrow()
            .markFailed(now, Duration.ofSeconds(retryBaseSeconds));
    }
}

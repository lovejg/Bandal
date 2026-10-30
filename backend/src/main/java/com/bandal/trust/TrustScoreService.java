package com.bandal.trust;

import com.bandal.grouporder.CancelType;
import com.bandal.grouporder.GroupOrderChangeKind;
import com.bandal.grouporder.GroupOrderStatus;
import com.bandal.outbox.GroupOrderStatusPayload;
import com.bandal.outbox.GroupOrderStatusPayload.Participant;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

// 방 상태 변경 글을 받아 신뢰도 점수를 움직인다. TrustScoreConsumer(worker)가 부른다
//
// 규칙(무엇이 몇 점인지)은 TrustReason에 있다. 여기서는 "이 글이 누구에게 어떤 이유를 남기나"를 정한다
// 끝나는 글(배달완료, 취소)에서만 움직인다. 그 순간의 사실(취소 종류, 참여자별 보냈는지)이 글에 다 있어서 본 서비스 DB를 다시 보지 않는다
// 같은 글을 두 번 받아도 변동 기록의 (글, 사람) 유니크가 두 번째를 막고, 막힌 줄은 점수를 움직이지 않는다
@Service
@RequiredArgsConstructor
public class TrustScoreService {

    private final TrustScoreRepository trustScoreRepository;

    // 이 시각보다 앞선 글은 세지 않는다. 컨슈머 그룹은 처음 뜰 때 토픽 맨 앞부터 읽는데(earliest),
    // 그대로 두면 점수 기능을 켜는 순간 토픽에 남은 지난 글(보관 기간만큼)로 소급해 점수가 매겨진다. 남은 기간도 들쭉날쭉하다
    // 그래서 "이때부터 센다"를 정해 둔다. 테스트는 아주 이른 시각으로 바꿔 모든 글을 센다
    @Value("${app.trust-score.counted-since}")
    private Instant countedSince;

    @Transactional
    public void record(Long eventId, GroupOrderStatusPayload payload) {
        if (payload.occurredAt().isBefore(countedSince)) return;
        // 배달비 수정, 도착 예정 수정은 상태가 그대로라 점수와 상관없다
        if (payload.kind() != GroupOrderChangeKind.STATUS_CHANGED) return;

        for (Change change : changesOf(payload)) {
            int inserted = trustScoreRepository.insertIfAbsent(eventId, change.userId(), payload.groupOrderId(),
                    payload.storeName(), change.reason().name(), change.reason().delta(), payload.occurredAt());
            if (inserted == 1) {
                trustScoreRepository.addScore(change.userId(), change.reason().delta());
            }
        }
    }

    // 이 글이 누구에게 어떤 이유를 남기나
    static List<Change> changesOf(GroupOrderStatusPayload payload) {
        List<Change> changes = new ArrayList<>();
        Long hostId = payload.hostId();

        if (payload.to() == GroupOrderStatus.DELIVERED) {
            // 참여자 목록에는 방장도 있다
            for (Participant p : payload.participants()) changes.add(new Change(p.userId(), TrustReason.DELIVERED));
            return changes;
        }
        if (payload.to() != GroupOrderStatus.CANCELED || payload.cancelType() == null) return changes;

        CancelType cancelType = payload.cancelType();
        switch (cancelType) {
            // 모집중 취소는 피해가 작고, 막으면 방을 안 열게 된다. 인원 미달 자동 취소는 누구 잘못도 아니다
            case HOST_WHILE_RECRUITING, DEADLINE_UNMET -> { }
            case HOST_WHILE_CLOSED, CLOSED_TIMEOUT -> changes.add(new Change(hostId, TrustReason.HOST_CANCELED_AFTER_CLOSE));
            case HOST_WHILE_SETTLING -> changes.add(new Change(hostId, TrustReason.HOST_CANCELED_WHILE_SETTLING));
            case SETTLING_TIMEOUT -> {
                // 안 보낸 참여자가 있으면 그 사람들만 깎는다. 전원 확인이 돼야 주문할 수 있어서 방장은 주문할 수 없었다
                // 모두 보냈는데도 시간이 지났으면 방장이 주문하지 않은 것이다
                // paid가 null이면(이 칸을 넣기 전의 옛 글) 보냈는지 모른다. 모르는 사람은 깎지 않는다
                List<Participant> unpaid = payload.participants().stream()
                        .filter(p -> !Objects.equals(p.userId(), hostId))
                        .filter(p -> Boolean.FALSE.equals(p.paid()))
                        .toList();
                if (unpaid.isEmpty()) {
                    changes.add(new Change(hostId, TrustReason.HOST_DID_NOT_ORDER));
                } else {
                    for (Participant p : unpaid) changes.add(new Change(p.userId(), TrustReason.DID_NOT_PAY));
                }
            }
        }
        return changes;
    }

    record Change(Long userId, TrustReason reason) {
    }
}

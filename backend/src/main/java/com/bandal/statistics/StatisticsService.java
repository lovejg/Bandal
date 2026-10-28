package com.bandal.statistics;

import com.bandal.grouporder.GroupOrderStatus;
import com.bandal.outbox.GroupOrderStatusPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 통계 컨슈머가 받은 상태 변경 글을 통계로 옮긴다
@Service
@RequiredArgsConstructor
public class StatisticsService {

    private final GroupOrderStatRepository groupOrderStatRepository;

    // 끝난 글(DELIVERED, CANCELED)이면 그 순간의 사실을 방 한 줄로 덮어쓴다. 아낀 배달비는 계산하지 않는다(볼 때 SQL로)
    // 끝나지 않은 글은 일찍 돌아간다. 아무것도 안 해도 예외 없이 끝나면 책갈피는 앞으로 간다
    // cancelType은 취소가 아니면 null이라 null을 확인하고 이름으로 바꾼다
    @Transactional
    public void record(Long eventId, GroupOrderStatusPayload payload) {
        if(payload.to() != GroupOrderStatus.DELIVERED && payload.to() != GroupOrderStatus.CANCELED) {
            return;
        }
        String cancelType = payload.cancelType() == null ? null : payload.cancelType().name();
        groupOrderStatRepository.upsert(payload.groupOrderId(), payload.pickupSpotId(), payload.to().name(), cancelType,
            payload.deliveryFee(), payload.participants().size(), payload.occurredAt(), eventId);
    }
}

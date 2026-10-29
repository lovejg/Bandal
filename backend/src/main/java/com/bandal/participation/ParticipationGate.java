package com.bandal.participation;

import com.bandal.grouporder.GroupOrder;
import com.bandal.grouporder.GroupOrderRepository;
import com.bandal.participation.dto.ParticipationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 참여와 나가기의 문지기. 방 락을 잡는 트랜잭션(ParticipationService)에 들어가기 전에 먼저 거른다
// 여기에는 @Transactional을 붙이지 않는다. 여기서 읽은 방이 join 트랜잭션의 영속성 컨텍스트에 남으면 안 되고,
// 꽉 찬 방에 온 사람은 join 트랜잭션(커넥션)을 아예 시작하지 않아야 한다
// 같은 클래스 안의 호출은 프록시를 거치지 않아 새 트랜잭션이 안 생기므로 ParticipationService와 클래스를 나눈다
@Component
@RequiredArgsConstructor
public class ParticipationGate {

    private final ParticipationService participationService;
    private final GroupOrderRepository groupOrderRepository;

    // 방 참여
    public ParticipationResponse join(Long groupOrderId, Long requesterId) {
        // 방 락을 잡기 전에 인원을 락 없이 미리 보고, 이미 꽉 찼으면 줄 서지 않고 바로 거절한다
        GroupOrder peek = groupOrderRepository.findByIdOrThrow(groupOrderId);
        if(peek.getParticipantCount() >= peek.getCapacity()) {
            throw new IllegalStateException("정원이 다 찼습니다");
        }
        return participationService.join(groupOrderId, requesterId);
    }

    // 방 나가기. 거를 것이 없어 그대로 넘긴다. 참여와 같은 입구로 두어, 번호표(보류)를 붙이게 되면 커밋이 끝난 이 자리에서 표를 돌려준다
    public void leave(Long participationId, Long requesterId) {
        participationService.leave(participationId, requesterId);
    }
}

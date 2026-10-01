package com.bandal.participation;

import com.bandal.common.NotFoundException;
import com.bandal.grouporder.GroupOrder;
import com.bandal.grouporder.GroupOrderRepository;
import com.bandal.participation.dto.ParticipationResponse;
import com.bandal.user.User;
import com.bandal.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

// 방에 들어오고 나간다. 메뉴 담기도 나중에 여기로 온다
@Service
@RequiredArgsConstructor
public class ParticipationService {

    private final ParticipationRepository participationRepository;
    private final UserRepository userRepository;
    private final GroupOrderRepository groupOrderRepository;
    private final OrderItemRepository orderItemRepository;

    // 방 참여
    @Transactional
    public ParticipationResponse join(Long groupOrderId, Long userId) {
         GroupOrder groupOrder = groupOrderRepository.findById(groupOrderId)
             .orElseThrow(() -> new NotFoundException("없는 방입니다"));
         User user = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("없는 사용자입니다"));

         if(participationRepository.existsByGroupOrderIdAndUserId(groupOrderId, userId)) {
             throw new IllegalStateException("이미 참여한 방입니다");
         }

         Instant now = Instant.now();
         groupOrder.checkJoinable(user, participationRepository.countByGroupOrderId(groupOrderId), now);

         Participation participation = new Participation(groupOrder, user, now);
         participationRepository.save(participation);
         return ParticipationResponse.of(participation);
    }

    // 방 나가기(방장은 나갈 수 없음. 반드시 취소해야 됨)
    @Transactional
    public void leave(Long participationId, Long userId) {
        Participation participation = participationRepository.findById(participationId)
            .orElseThrow(() -> new NotFoundException("없는 참여입니다"));

        participation.checkLeavable(userId, Instant.now());

        orderItemRepository.deleteByParticipationId(participationId);
        participationRepository.delete(participation);
    }
}

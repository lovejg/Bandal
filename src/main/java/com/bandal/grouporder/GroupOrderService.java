package com.bandal.grouporder;

import com.bandal.common.NotFoundException;
import com.bandal.grouporder.dto.CreateGroupOrderRequest;
import com.bandal.grouporder.dto.GroupOrderResponse;
import com.bandal.participation.Participation;
import com.bandal.participation.ParticipationRepository;
import com.bandal.pickupspot.PickupSpot;
import com.bandal.pickupspot.PickupSpotRepository;
import com.bandal.user.User;
import com.bandal.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

// 방을 만들고, 보여주고, 상태를 넘긴다. 트랜잭션 경계는 여기다
@Service
// final 필드를 받는 생성자를 롬복이 만들어 주고, 스프링이 그 생성자로 의존성을 넣어준다
@RequiredArgsConstructor
public class GroupOrderService {

    private final GroupOrderRepository groupOrderRepository;
    private final ParticipationRepository participationRepository;
    private final UserRepository userRepository;
    private final PickupSpotRepository pickupSpotRepository;

    @Transactional
    public GroupOrderResponse create(Long hostId, CreateGroupOrderRequest request) {
        User host = userRepository.findById(hostId).orElseThrow(() -> new NotFoundException("없는 사용자입니다"));
        PickupSpot pickupSpot = pickupSpotRepository.findById(request.pickupSpotId())
            .orElseThrow(() -> new NotFoundException("없는 수령지입니다"));
        GroupOrder groupOrder = new GroupOrder(host, pickupSpot, request.storeName(),
            request.minOrderAmount(), request.deadlineAt(), request.capacity());
        groupOrderRepository.save(groupOrder);
        Participation participation = new Participation(groupOrder, host, Instant.now());
        participationRepository.save(participation);
        return GroupOrderResponse.of(groupOrder, 1, 0);
    }
}

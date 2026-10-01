package com.bandal.pickupspot;

import com.bandal.common.NotFoundException;
import com.bandal.pickupspot.dto.CreatePickupSpotRequest;
import com.bandal.pickupspot.dto.PickupSpotListResponse;
import com.bandal.pickupspot.dto.PickupSpotResponse;
import com.bandal.university.University;
import com.bandal.user.User;
import com.bandal.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Collator;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

// 거점 목록을 보여주고, 거점을 만든다
@Service
@RequiredArgsConstructor
public class PickupSpotService {

    private final PickupSpotRepository pickupSpotRepository;
    private final UserRepository userRepository;

    // 내 학교 거점 목록 조회
    @Transactional(readOnly = true)
    public PickupSpotListResponse findList(Long userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("없는 사용자입니다"));
        Long universityId = user.getUniversity().getId();

        Collator collator = Collator.getInstance(Locale.KOREAN);
        List<PickupSpotResponse> list = pickupSpotRepository.findByUniversityId(universityId).stream()
            .sorted(Comparator.comparing(PickupSpot::getName, collator))
            .map(PickupSpotResponse::of)
            .toList();

        return new PickupSpotListResponse(list);
    }


    // 내 학교 거점 생성
    @Transactional
    public PickupSpotResponse create(Long userId, CreatePickupSpotRequest request) {
        User user = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("없는 사용자입니다"));
        University university = user.getUniversity();

        PickupSpot pickupSpot = new PickupSpot(university, request.name(), request.description());
        if(pickupSpotRepository.existsByUniversityIdAndNormalizedName(university.getId(), pickupSpot.getNormalizedName())) {
            throw new IllegalStateException("이미 있는 거점입니다");
        }

        pickupSpotRepository.save(pickupSpot);
        return PickupSpotResponse.of(pickupSpot);
    }
}

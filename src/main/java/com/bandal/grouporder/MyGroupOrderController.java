package com.bandal.grouporder;

import com.bandal.grouporder.dto.MyGroupOrderListResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 내 방 목록. 마감된 방에 다시 들어가는 입구다 (ADR-053)
// 방 목록(/api/group-orders)은 참여할 방을 고르는 화면이라 주소를 나눴다. 이건 "나"의 자원이라 /users/me 아래다
// 두 탭은 응답 모양은 같지만 고르는 방과 페이징이 달라서 주소를 둘로 나눴다
@RestController
@RequestMapping("/api/users/me/group-orders")
@RequiredArgsConstructor
public class MyGroupOrderController {

    private final GroupOrderService groupOrderService;

    // 진행 중 탭. 몇 개뿐이라 나눠 주지 않는다
    @GetMapping("/active")
    public MyGroupOrderListResponse findMyActive(@AuthenticationPrincipal Long userId) {
        return groupOrderService.findMyActive(userId);
    }

    // 끝난 방 탭. 쌓이는 쪽이라 Slice로 나눠 준다
    @GetMapping("/done")
    public MyGroupOrderListResponse findMyDone(@AuthenticationPrincipal Long userId,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return groupOrderService.findMyDone(userId, page, size);
    }
}

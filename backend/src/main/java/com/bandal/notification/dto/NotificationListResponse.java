package com.bandal.notification.dto;

import java.util.List;

// 내 알림 목록 한 묶음(최신순)
// hasNext가 true면 화면이 "더 보기"를 보여 주고, 누르면 notifications의 마지막 줄 id를 before로 넣어 다음 묶음을 받는다
// 전체 개수는 주지 않는다. 세려면 쿼리가 하나 더 필요한데 화면은 "더 있나"만 알면 된다
public record NotificationListResponse(
        List<NotificationResponse> notifications,
        boolean hasNext
) {
}

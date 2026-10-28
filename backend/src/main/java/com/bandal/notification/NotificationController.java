package com.bandal.notification;

import com.bandal.notification.dto.NotificationListResponse;
import com.bandal.notification.dto.ReadAllRequest;
import com.bandal.notification.dto.UnreadCountResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 앱 안 알림함. 알림을 넣는 건 worker이고, 보여 주는 건 백엔드다
// 그래서 worker가 죽어도 이미 받은 알림은 보이고 읽음 표시도 된다(새 알림만 늦게 온다)
//
// 모든 주소가 토큰의 사용자 id(@AuthenticationPrincipal)로만 고른다
// 주소나 본문으로 사용자 id를 받지 않아서, 남의 알림함을 열거나 남의 알림을 바꿀 길이 없다
//
// 로그인이 필요하다. GET은 로그인만, POST(읽음 표시)는 메일 인증까지 마친 사람만 된다(보안 설정의 공통 규칙)
// 메일 인증을 안 한 사람은 방에 참여할 수 없어 알림도 없으니 문제 되지 않는다
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    // 내 알림 목록. 최신순으로 size개씩
    // 처음엔 before 없이 부르고, "더 보기"는 받은 목록의 마지막 줄 id를 before로 넣어 부른다
    // GET /api/notifications?before=42&size=20
    @GetMapping
    public NotificationListResponse findMine(@AuthenticationPrincipal Long userId,
                                             @RequestParam(required = false) Long before,
                                             @RequestParam(defaultValue = "20") int size) {
        return notificationService.findMine(userId, before, size);
    }

    // 종 아이콘의 빨간 숫자. 화면이 몇십 초마다 다시 묻는다
    // GET /api/notifications/unread-count → {"count": 3}
    @GetMapping("/unread-count")
    public UnreadCountResponse countUnread(@AuthenticationPrincipal Long userId) {
        return notificationService.countUnread(userId);
    }

    // 알림 하나를 눌렀을 때. 화면은 이어서 그 방으로 간다
    // 돌려줄 내용이 없어 204(No Content)다. 이미 읽은 걸 다시 눌러도 같다. 없는 알림이나 남의 알림이면 404
    // 상태를 바꾸는 요청이라 GET이 아니라 POST다. GET은 미리 불러오기나 새로고침으로 몇 번이고 불릴 수 있다
    // POST /api/notifications/42/read
    @PostMapping("/{notificationId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@PathVariable Long notificationId, @AuthenticationPrincipal Long userId) {
        notificationService.markRead(notificationId, userId);
    }

    // "모두 읽음" 버튼. 본문에 화면이 본 가장 최신 알림 id를 받는다
    // @Valid가 ReadAllRequest의 @NotNull을 검사한다. upToId가 없으면 서비스까지 가지 않고 400이다
    // POST /api/notifications/read-all  {"upToId": 42}
    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead(@AuthenticationPrincipal Long userId, @Valid @RequestBody ReadAllRequest request) {
        notificationService.markAllRead(userId, request.upToId());
    }
}

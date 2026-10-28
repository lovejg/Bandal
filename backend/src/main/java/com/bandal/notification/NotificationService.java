package com.bandal.notification;

import com.bandal.common.NotFoundException;
import com.bandal.grouporder.StatusChangedBy;
import com.bandal.notification.dto.NotificationListResponse;
import com.bandal.notification.dto.NotificationResponse;
import com.bandal.notification.dto.UnreadCountResponse;
import com.bandal.outbox.GroupOrderStatusPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

// 알림의 일을 모은 곳. 부르는 쪽이 둘이다
// - record: worker의 알림 컨슈머가 Kafka 글 하나를 받을 때마다 부른다(알림 만들기)
// - 나머지: 백엔드의 알림함 API가 부른다(보기, 읽음 표시)
// 같은 클래스지만 실제로는 다른 프로세스(worker 컨테이너, 백엔드 컨테이너)에서 따로 돈다
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;

    // 상태 변경 하나(Kafka 글 하나)를 받는 사람마다 알림 한 줄로 저장한다
    // eventId는 Kafka 헤더에 실려 온 상태 변경 번호(outbox 줄 id)다. 같은 글을 두 번 받아도 번호가 같아서 한 벌만 남는다
    // 한 트랜잭션이라 받는 사람 셋 중 둘째에서 DB가 죽으면 셋 다 롤백된다. 예외가 컨슈머 밖으로 나가면 책갈피가 안 움직여서
    // 같은 글을 다시 받고 처음부터 다시 넣는다(이미 들어간 줄은 on conflict가 건너뛴다)
    // 몇 번까지 다시 받고 그다음 어떻게 할지는 실패 처리 단계에서 정한다
    @Transactional
    public void record(Long eventId, GroupOrderStatusPayload payload) {
        for(GroupOrderStatusPayload.Participant p : payload.participants()) {
            // 바꾼 사람에게는 알리지 않는다. 방장이 직접 마감했으면 방장은 이미 안다
            // 스케줄러가 한 일(SYSTEM: 자동 마감, 시간 초과 취소)이면 방장도 몰랐던 일이라 받는다
            if(payload.changedBy() == StatusChangedBy.HOST && p.userId().equals(payload.hostId())) continue;
            // 이미 받은 글이면 DB가 조용히 건너뛴다. 금액(p.totalAmount())은 정산표가 생긴 뒤에만 있다
            // 배달비 수정 글도 같은 길이다. 상태 대신 kind가 다르고, 금액이 새로 계산된 금액이다
            notificationRepository.insertIfAbsent(eventId, p.userId(), payload.groupOrderId(), payload.storeName(),
                    payload.kind().name(), payload.to().name(), payload.cancelReason(), p.totalAmount(),
                    payload.deliveryFee(), payload.expectedArrivalAt(), payload.occurredAt());
        }
    }

    // 내 알림 목록 한 묶음(커서 페이지)
    // before는 "이 알림보다 오래된 것부터 달라"의 기준 알림 id다. 화면이 받은 목록의 마지막 줄 id를 넣어 보낸다
    //   예: id 1~5, size 2 → 처음(before 없음) 5,4 → before=4 → 3,2 → before=2 → 1
    // 페이지 번호(앞의 n개 건너뛰기)를 안 쓰는 이유: 알림은 맨 위에 계속 쌓인다
    // 첫 묶음을 받은 뒤 새 알림이 오면 번호 방식은 한 칸씩 밀려 같은 알림이 두 번 보인다. "id < 4"는 밀리지 않는다
    // before가 없으면(처음 열 때) Long.MAX_VALUE를 넣는다. "어떤 id보다 작은 것" = 전부라서 같은 쿼리로 맨 위부터 나온다
    // 페이지 번호는 늘 0이다. 건너뛰는 일은 번호가 아니라 before가 한다
    // readOnly: 읽기만 한다는 표시. JPA가 꺼낸 객체의 변경을 찾는 검사(변경 감지)를 건너뛴다
    @Transactional(readOnly = true)
    public NotificationListResponse findMine(Long userId, Long before, int size) {
        Long cursor = before == null ? Long.MAX_VALUE : before;
        Slice<Notification> slice = notificationRepository.findByUserIdAndIdLessThanOrderByIdDesc(userId, cursor,
                PageRequest.of(0, size));
        return new NotificationListResponse(
                slice.getContent().stream().map(NotificationResponse::from).toList(), slice.hasNext());
    }

    // 종 아이콘의 빨간 숫자. 화면이 몇십 초마다 다시 묻는다
    @Transactional(readOnly = true)
    public UnreadCountResponse countUnread(Long userId) {
        return new UnreadCountResponse(notificationRepository.countByUserIdAndReadAtIsNull(userId));
    }

    // 알림 하나를 읽음으로. 사용자가 알림을 눌러 그 방으로 갈 때 화면이 부른다
    // 쿼리가 주인(userId)까지 조건으로 걸어서, 바뀐 줄이 0이면 "없는 알림"이거나 "남의 알림"이다
    // 둘을 가르지 않고 404로 낸다. 남의 알림에 403을 주면 "그 번호의 알림이 누군가에게 있다"는 걸 알려 주게 된다
    // 알림은 "내 것"만 있는 자원이라 "내 알림함에 그런 알림은 없다"가 맞는 답이다
    // userId는 컨트롤러가 토큰에서 꺼낸 값이다. 주소나 본문으로 받지 않으니 바꿔 보낼 길이 없다
    @Transactional
    public void markRead(Long notificationId, Long userId) {
        int updated = notificationRepository.markRead(notificationId, userId, Instant.now());
        if (updated == 0) {
            throw new NotFoundException("없는 알림입니다");
        }
    }

    // 모두 읽음. upToId(화면이 본 가장 최신 알림 id) 이하의 안 읽은 내 알림만 읽음으로
    // 그 뒤에 들어온 알림은 안 읽음으로 남아 빨간 숫자가 다시 켜진다
    // 바뀐 줄이 0이어도(이미 다 읽었음) 정상이라 따로 검사하지 않는다
    // 남의 알림 id보다 큰 upToId를 보내도 쿼리가 userId로 묶여 있어서 내 알림만 바뀐다
    @Transactional
    public void markAllRead(Long userId, Long upToId) {
        notificationRepository.markAllRead(userId, upToId, Instant.now());
    }
}

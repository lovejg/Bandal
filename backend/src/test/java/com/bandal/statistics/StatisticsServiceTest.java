package com.bandal.statistics;

import com.bandal.TestcontainersConfiguration;
import com.bandal.grouporder.CancelType;
import com.bandal.grouporder.GroupOrderChangeKind;
import com.bandal.grouporder.GroupOrderStatus;
import com.bandal.grouporder.StatusChangedBy;
import com.bandal.outbox.GroupOrderStatusPayload;
import com.bandal.outbox.GroupOrderStatusPayload.Participant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 상태 변경 글이 끝난 방 한 줄로 옮겨지는가. Kafka 없이 서비스만 부른다
// 같은 글 두 번, 되감아 전부 다시 읽기, 늦게 온 옛 글에서도 줄이 맞아야 한다
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class StatisticsServiceTest {

    static final Long DORM = 10L;
    static final Long LIBRARY = 20L;
    static final Instant AT = Instant.parse("2026-10-27T10:00:00Z");

    @Autowired
    StatisticsService statisticsService;

    @Autowired
    GroupOrderStatRepository groupOrderStatRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        groupOrderStatRepository.deleteAll();
    }

    GroupOrderStatusPayload payload(long roomId, Long spotId, GroupOrderStatus from, GroupOrderStatus to,
                                    CancelType cancelType, Long deliveryFee, int people) {
        List<Participant> participants = new ArrayList<>();
        for (long userId = 1; userId <= people; userId++) {
            participants.add(new Participant(userId, null));
        }
        return new GroupOrderStatusPayload(roomId, spotId, "마라탕집", 1L, from, to, StatusChangedBy.HOST,
                cancelType, null, deliveryFee, participants, AT);
    }

    // 배달비 3,000원을 4명이 나눈 방의 배달완료
    GroupOrderStatusPayload delivered(long roomId, Long spotId) {
        return payload(roomId, spotId, GroupOrderStatus.ORDERED, GroupOrderStatus.DELIVERED, null, 3_000L, 4);
    }

    GroupOrderStatusPayload canceled(long roomId, Long spotId) {
        return payload(roomId, spotId, GroupOrderStatus.RECRUITING, GroupOrderStatus.CANCELED,
                CancelType.DEADLINE_UNMET, null, 1);
    }

    GroupOrderStat stat(long roomId) {
        return groupOrderStatRepository.findById(roomId).orElseThrow();
    }

    // 대시보드가 낼 쿼리와 같은 모양. 아낀 배달비는 저장하지 않고 볼 때 센다
    long savedDeliveryFee() {
        return jdbcTemplate.queryForObject("""
                select coalesce(sum(delivery_fee * (participant_count - 1)), 0)
                from group_order_stat where outcome = 'DELIVERED'
                """, Long.class);
    }

    @Test
    @DisplayName("배달완료 글을 받으면 그 방의 사실(거점, 결과, 배달비, 인원, 끝난 시각, 번호)이 한 줄로 남는다")
    void recordsDelivered() {
        statisticsService.record(100L, delivered(1L, DORM));

        GroupOrderStat s = stat(1L);
        assertThat(s.getPickupSpotId()).isEqualTo(DORM);
        assertThat(s.getOutcome()).isEqualTo(GroupOrderStatus.DELIVERED);
        assertThat(s.getCancelType()).isNull();
        assertThat(s.getDeliveryFee()).isEqualTo(3_000L);
        assertThat(s.getParticipantCount()).isEqualTo(4);
        assertThat(s.getFinishedAt()).isEqualTo(AT);
        assertThat(s.getEventId()).isEqualTo(100L);
        assertThat(savedDeliveryFee()).isEqualTo(9_000L);
    }

    @Test
    @DisplayName("취소 글을 받으면 취소 종류와 함께 한 줄로 남는다")
    void recordsCanceled() {
        statisticsService.record(100L, canceled(1L, DORM));

        GroupOrderStat s = stat(1L);
        assertThat(s.getOutcome()).isEqualTo(GroupOrderStatus.CANCELED);
        assertThat(s.getCancelType()).isEqualTo(CancelType.DEADLINE_UNMET);
        assertThat(s.getDeliveryFee()).isNull();
    }

    @Test
    @DisplayName("끝나지 않은 상태 변경(마감, 정산 시작, 주문)은 통계에 아무것도 남기지 않는다")
    void ignoresUnfinished() {
        statisticsService.record(100L, payload(1L, DORM, GroupOrderStatus.RECRUITING, GroupOrderStatus.CLOSED, null, null, 4));
        statisticsService.record(101L, payload(1L, DORM, GroupOrderStatus.CLOSED, GroupOrderStatus.SETTLING, null, 3_000L, 4));
        statisticsService.record(102L, payload(1L, DORM, GroupOrderStatus.SETTLING, GroupOrderStatus.ORDERED, null, 3_000L, 4));

        assertThat(groupOrderStatRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("배달비 수정 글(정산중 → 정산중)도 끝난 방이 아니라 아무것도 남기지 않는다")
    void ignoresDeliveryFeeChange() {
        statisticsService.record(100L, new GroupOrderStatusPayload(1L, DORM, "마라탕집", 1L,
                GroupOrderStatus.SETTLING, GroupOrderStatus.SETTLING, StatusChangedBy.HOST, GroupOrderChangeKind.DELIVERY_FEE_CHANGED,
                null, null, 4_000L, null, List.of(new Participant(1L, 13_500L), new Participant(2L, 9_500L)), AT));

        assertThat(groupOrderStatRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("같은 글을 두 번 받아도 한 번만 센다")
    void sameEventTwice() {
        statisticsService.record(100L, delivered(1L, DORM));
        // 처리 뒤 책갈피를 커밋하기 전에 worker가 죽어 같은 글을 다시 받았다
        statisticsService.record(100L, delivered(1L, DORM));

        assertThat(groupOrderStatRepository.count()).isEqualTo(1);
        assertThat(savedDeliveryFee()).isEqualTo(9_000L);
    }

    @Test
    @DisplayName("맨 앞으로 되감아 지난 글을 전부 다시 읽어도 숫자가 그대로다")
    void replayFromStart() {
        List<Runnable> history = List.of(
                () -> statisticsService.record(100L, delivered(1L, DORM)),
                () -> statisticsService.record(101L, canceled(2L, DORM)),
                () -> statisticsService.record(102L, delivered(3L, LIBRARY)));
        history.forEach(Runnable::run);

        // statistics 그룹의 책갈피를 맨 앞으로 돌렸다. 같은 글이 처음부터 다시 온다
        history.forEach(Runnable::run);

        assertThat(groupOrderStatRepository.count()).isEqualTo(3);
        assertThat(savedDeliveryFee()).isEqualTo(18_000L);
    }

    @Test
    @DisplayName("늦게 온 옛 글(번호가 더 작은 글)은 새 결과를 덮어쓰지 않는다")
    void olderEventDoesNotOverwrite() {
        statisticsService.record(200L, canceled(1L, DORM));
        // 앞서 일어난 일이 어떤 이유로 뒤늦게 도착했다
        statisticsService.record(100L, delivered(1L, DORM));

        assertThat(stat(1L).getOutcome()).isEqualTo(GroupOrderStatus.CANCELED);
        assertThat(stat(1L).getEventId()).isEqualTo(200L);
    }

    @Test
    @DisplayName("같은 번호의 글을 다시 읽으면 새로 옮긴 값으로 바뀐다(옮기는 코드를 고치고 되감은 경우)")
    void sameEventRewritesValues() {
        statisticsService.record(100L, delivered(1L, DORM));
        // 처음엔 인원을 잘못 옮겼다고 치고, 고친 뒤 되감아 같은 글을 다시 읽었다
        statisticsService.record(100L, payload(1L, DORM, GroupOrderStatus.ORDERED, GroupOrderStatus.DELIVERED, null, 3_000L, 5));

        assertThat(stat(1L).getParticipantCount()).isEqualTo(5);
    }
}

package com.bandal.grouporder;

import com.bandal.TestcontainersConfiguration;
import com.bandal.auth.JwtProvider;
import com.bandal.grouporder.dto.CancelRequest;
import com.bandal.participation.OrderItemRepository;
import com.bandal.participation.Participation;
import com.bandal.participation.ParticipationRepository;
import com.bandal.pickupspot.PickupSpot;
import com.bandal.pickupspot.PickupSpotRepository;
import com.bandal.settlement.SettlementRepository;
import com.bandal.settlement.SettlementService;
import com.bandal.settlement.dto.DeliveryFeeRequest;
import com.bandal.settlement.dto.OrderRequest;
import com.bandal.university.University;
import com.bandal.university.UniversityRepository;
import com.bandal.user.User;
import com.bandal.user.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 내 방 목록. 마감된 방에 다시 들어가는 입구다
// 진행 중 탭, 끝난 방 탭, 참여자 칸, 방장 칸으로 묶었다
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
// 쿼리 수를 세려고 하이버네이트 통계를 켠다. 방장 칸의 N+1 테스트가 쓴다
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class MyGroupOrderApiTest {

    static final String ACTIVE = "/api/users/me/group-orders/active";
    static final String DONE = "/api/users/me/group-orders/done";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtProvider jwtProvider;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @Autowired
    GroupOrderService groupOrderService;

    @Autowired
    SettlementService settlementService;

    @Autowired
    SettlementRepository settlementRepository;

    @Autowired
    OrderItemRepository orderItemRepository;

    @Autowired
    ParticipationRepository participationRepository;

    @Autowired
    GroupOrderRepository groupOrderRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    PickupSpotRepository pickupSpotRepository;

    @Autowired
    UniversityRepository universityRepository;

    University university;
    PickupSpot pickupSpot;
    // 목록을 보는 사람
    User me;
    // 다른 방의 방장
    User other;

    int userSeq;

    @BeforeEach
    void setUp() {
        settlementRepository.deleteAll();
        orderItemRepository.deleteAll();
        participationRepository.deleteAll();
        groupOrderRepository.deleteAll();
        userRepository.deleteAll();
        pickupSpotRepository.deleteAll();
        universityRepository.deleteAll();

        university = universityRepository.save(new University("한국대학교", "hankuk.ac.kr"));
        pickupSpot = pickupSpotRepository.save(new PickupSpot(university, "제1기숙사 로비", null));
        userSeq = 0;
        me = user("나");
        other = user("남");
    }

    // 메일 인증과 계좌까지 마친 사용자
    User user(String nickname) {
        userSeq++;
        User user = new User(university, "user" + userSeq + "@hankuk.ac.kr", "hashed-password", nickname);
        ReflectionTestUtils.setField(user, "emailVerifiedAt", Instant.now());
        ReflectionTestUtils.setField(user, "bankName", "한국은행");
        ReflectionTestUtils.setField(user, "accountNumber", "110123456789");
        ReflectionTestUtils.setField(user, "accountHolder", "김민수");
        return userRepository.save(user);
    }

    // 모집중 방. 방장이 8,000원, 참여자가 한 명당 9,000원을 담는다. 참여자가 한 명이라도 있으면 최소주문금액을 넘는다
    GroupOrder room(User host, String storeName, User... members) {
        Instant now = Instant.now();
        GroupOrder room = groupOrderRepository.save(new GroupOrder(
                host, pickupSpot, storeName, 15_000, now.plus(2, ChronoUnit.HOURS), 4));
        Participation hostPart = participationRepository.save(new Participation(room, host, now));
        orderItemRepository.save(hostPart.addItem(host.getId(), "메뉴", null, 8_000, 1, now));
        for (User member : members) {
            Participation p = participationRepository.save(new Participation(room, member, now));
            orderItemRepository.save(p.addItem(member.getId(), "메뉴", null, 9_000, 1, now));
        }
        return room;
    }

    GroupOrder reload(GroupOrder room) {
        return groupOrderRepository.findById(room.getId()).orElseThrow();
    }

    GroupOrder close(GroupOrder room) {
        GroupOrder fresh = reload(room);
        fresh.closeByHost(fresh.getHost().getId(),
                participationRepository.countByGroupOrderId(room.getId()),
                orderItemRepository.sumAmountByGroupOrderId(room.getId()),
                Instant.now());
        return groupOrderRepository.save(fresh);
    }

    // 마감하고 배달비를 넣어 정산중으로. 정산표 줄이 생긴다
    GroupOrder settle(GroupOrder room) {
        close(room);
        settlementService.start(room.getId(), room.getHost().getId(), new DeliveryFeeRequest(3_000L));
        return reload(room);
    }

    // 참여자 줄을 전부 확인하고 주문완료로
    GroupOrder order(GroupOrder room) {
        settle(room);
        Long hostId = room.getHost().getId();
        settlementRepository.findByGroupOrderId(room.getId()).stream()
                .filter(s -> !s.isHost())
                .forEach(s -> settlementService.confirm(s.getId(), hostId));
        settlementService.order(room.getId(), hostId, new OrderRequest(null));
        return reload(room);
    }

    GroupOrder deliver(GroupOrder room) {
        order(room);
        settlementService.deliver(room.getId(), room.getHost().getId());
        return reload(room);
    }

    void cancel(GroupOrder room) {
        groupOrderService.cancel(room.getId(), room.getHost().getId(), new CancelRequest("가게가 문을 닫았습니다"));
    }

    Long lineId(GroupOrder room, User user) {
        return settlementRepository.findByGroupOrderIdAndUserId(room.getId(), user.getId()).orElseThrow().getId();
    }

    void markPaid(GroupOrder room, User user) {
        settlementService.markPaid(lineId(room, user), user.getId());
    }

    ResultActions fetch(String path, User viewer) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", bearer(viewer)))
                .andExpect(status().isOk());
    }

    String bearer(User user) {
        return "Bearer " + jwtProvider.createAccessToken(user.getId());
    }

    @Test
    @DisplayName("로그인하지 않으면 볼 수 없다")
    void requiresLogin() throws Exception {
        mockMvc.perform(get(ACTIVE)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(DONE)).andExpect(status().isUnauthorized());
    }

    @Nested
    @DisplayName("진행 중 탭")
    class Active {

        @Test
        @DisplayName("내가 만든 방과 참여한 방이 나오고, 남의 방은 나오지 않는다")
        void showsRoomsIHostAndJoined() throws Exception {
            room(me, "내가 만든 방");
            room(other, "내가 참여한 방", me);
            room(other, "남의 방");

            fetch(ACTIVE, me)
                    .andExpect(jsonPath("$.rooms[*].storeName").value(contains("내가 참여한 방", "내가 만든 방")));
        }

        @Test
        @DisplayName("나온 방은 나오지 않는다")
        void hidesRoomILeft() throws Exception {
            GroupOrder room = room(other, "나온 방", me);
            Long participationId = participationRepository.findByGroupOrderIdAndUserId(room.getId(), me.getId())
                    .orElseThrow().getId();
            mockMvc.perform(delete("/api/participations/" + participationId).header("Authorization", bearer(me)))
                    .andExpect(status().is2xxSuccessful());

            fetch(ACTIVE, me).andExpect(jsonPath("$.rooms.length()").value(0));
        }

        @Test
        @DisplayName("모집중, 마감, 정산중, 주문완료 방이 모두 나온다")
        void includesEveryInProgressStatus() throws Exception {
            room(other, "모집중", me);
            close(room(other, "마감", me));
            settle(room(other, "정산중", me));
            order(room(other, "주문완료", me));

            fetch(ACTIVE, me)
                    .andExpect(jsonPath("$.rooms[*].status")
                            .value(contains("ORDERED", "SETTLING", "CLOSED", "RECRUITING")));
        }

        @Test
        @DisplayName("최근에 만든 방이 위에 나온다")
        void sortsNewestFirst() throws Exception {
            room(other, "첫째", me);
            room(other, "둘째", me);
            room(other, "셋째", me);

            fetch(ACTIVE, me)
                    .andExpect(jsonPath("$.rooms[*].storeName").value(contains("셋째", "둘째", "첫째")));
        }

        @Test
        @DisplayName("배달완료된 방과 환불할 게 없는 취소 방은 나오지 않는다")
        void excludesFinishedRooms() throws Exception {
            deliver(room(other, "배달완료", me));
            GroupOrder canceled = room(other, "모집중 취소", me);
            groupOrderService.cancel(canceled.getId(), other.getId(), new CancelRequest(null));

            fetch(ACTIVE, me).andExpect(jsonPath("$.rooms.length()").value(0));
        }

        @Test
        @DisplayName("정산중에 취소됐어도 내 환불이 안 끝났으면 나온다")
        void keepsCanceledRoomWithOpenRefund() throws Exception {
            // 이 목록이 생긴 이유다. 이미 보낸 돈을 돌려받으려면 이 방에 들어가야 한다
            GroupOrder room = settle(room(other, "환불 중", me));
            markPaid(room, me);
            cancel(room);

            fetch(ACTIVE, me)
                    .andExpect(jsonPath("$.rooms.length()").value(1))
                    .andExpect(jsonPath("$.rooms[0].status").value("CANCELED"));
        }

        @Test
        @DisplayName("한 줄에 방 id, 가게 이름, 상태, 내가 방장인지가 들어 있다")
        void carriesBasicFields() throws Exception {
            GroupOrder joined = room(other, "참여한 방", me);
            GroupOrder hosted = room(me, "만든 방");

            // 최근에 만든 방이 위라 만든 방이 [0]이다
            fetch(ACTIVE, me)
                    .andExpect(jsonPath("$.rooms[0].id").value(hosted.getId()))
                    .andExpect(jsonPath("$.rooms[0].storeName").value("만든 방"))
                    .andExpect(jsonPath("$.rooms[0].status").value("RECRUITING"))
                    .andExpect(jsonPath("$.rooms[0].host").value(true))
                    .andExpect(jsonPath("$.rooms[1].id").value(joined.getId()))
                    .andExpect(jsonPath("$.rooms[1].host").value(false));
        }

        @Test
        @DisplayName("진행 중 탭은 나눠 주지 않아서 다음 페이지가 없다")
        void hasNoNextPage() throws Exception {
            room(other, "방", me);

            fetch(ACTIVE, me).andExpect(jsonPath("$.hasNext").value(false));
        }
    }

    @Nested
    @DisplayName("끝난 방 탭")
    class Done {

        @Test
        @DisplayName("배달완료된 방과 환불할 게 없는 취소 방만 나온다")
        void showsFinishedRooms() throws Exception {
            deliver(room(other, "배달완료", me));
            GroupOrder canceled = room(other, "모집중 취소", me);
            groupOrderService.cancel(canceled.getId(), other.getId(), new CancelRequest(null));
            room(other, "모집중", me);

            fetch(DONE, me)
                    .andExpect(jsonPath("$.rooms.length()").value(2))
                    .andExpect(jsonPath("$.rooms[*].status").value(contains("CANCELED", "DELIVERED")));
        }

        @Test
        @DisplayName("돌려받았어요를 누르면 진행 중에서 끝난 방으로 옮겨간다")
        void movesToDoneAfterRefund() throws Exception {
            GroupOrder room = settle(room(other, "환불 끝", me));
            markPaid(room, me);
            cancel(room);
            settlementService.receiveRefund(lineId(room, me), me.getId());

            fetch(ACTIVE, me).andExpect(jsonPath("$.rooms.length()").value(0));
            fetch(DONE, me).andExpect(jsonPath("$.rooms[*].storeName").value(contains("환불 끝")));
        }

        @Test
        @DisplayName("끝난 시각(배달완료 또는 취소)이 최근인 방이 위에 나온다")
        void sortsByFinishedTime() throws Exception {
            // 만든 순서와 끝난 순서를 일부러 엇갈리게 둔다. id 순으로 정렬하면 틀린다
            Instant now = Instant.now();
            GroupOrder first = deliver(room(other, "세 시간 전 배달", me));
            GroupOrder second = room(other, "한 시간 전 취소", me);
            groupOrderService.cancel(second.getId(), other.getId(), new CancelRequest(null));
            GroupOrder third = deliver(room(other, "두 시간 전 배달", me));
            finishedAt(first, "deliveredAt", now.minus(3, ChronoUnit.HOURS));
            finishedAt(second, "canceledAt", now.minus(1, ChronoUnit.HOURS));
            finishedAt(third, "deliveredAt", now.minus(2, ChronoUnit.HOURS));

            fetch(DONE, me)
                    .andExpect(jsonPath("$.rooms[*].storeName")
                            .value(contains("한 시간 전 취소", "두 시간 전 배달", "세 시간 전 배달")));
        }

        void finishedAt(GroupOrder room, String field, Instant at) {
            GroupOrder fresh = reload(room);
            ReflectionTestUtils.setField(fresh, field, at);
            groupOrderRepository.save(fresh);
        }

        @Test
        @DisplayName("정해진 개수만큼 나눠 주고 다음 페이지가 있는지 알려준다")
        void pages() throws Exception {
            for (int i = 0; i < 3; i++) {
                GroupOrder room = room(other, "방" + i, me);
                groupOrderService.cancel(room.getId(), other.getId(), new CancelRequest(null));
            }

            mockMvc.perform(get(DONE).param("page", "0").param("size", "2").header("Authorization", bearer(me)))
                    .andExpect(jsonPath("$.rooms.length()").value(2))
                    .andExpect(jsonPath("$.hasNext").value(true));
            mockMvc.perform(get(DONE).param("page", "1").param("size", "2").header("Authorization", bearer(me)))
                    .andExpect(jsonPath("$.rooms.length()").value(1))
                    .andExpect(jsonPath("$.hasNext").value(false));
        }
    }

    @Nested
    @DisplayName("참여자 칸: 내 입금과 환불 상태")
    class MemberColumns {

        @Test
        @DisplayName("보냈어요를 누르면 보낸 시각이 나오고 확인 시각은 비어 있다")
        void showsMyMarkedPaid() throws Exception {
            GroupOrder room = settle(room(other, "정산중", me));
            markPaid(room, me);

            fetch(ACTIVE, me)
                    .andExpect(jsonPath("$.rooms[0].markedPaidAt").isNotEmpty())
                    .andExpect(jsonPath("$.rooms[0].confirmedPaidAt").value(nullValue()))
                    .andExpect(jsonPath("$.rooms[0].refundStatus").value(nullValue()));
        }

        @Test
        @DisplayName("방장이 확인하면 확인 시각이 나온다")
        void showsMyConfirmed() throws Exception {
            GroupOrder room = settle(room(other, "정산중", me));
            markPaid(room, me);
            settlementService.confirm(lineId(room, me), other.getId());

            fetch(ACTIVE, me).andExpect(jsonPath("$.rooms[0].confirmedPaidAt").isNotEmpty());
        }

        @Test
        @DisplayName("다른 참여자의 입금은 내 줄에 섞이지 않는다")
        void showsOnlyMyLine() throws Exception {
            User friend = user("친구");
            GroupOrder room = settle(room(other, "정산중", me, friend));
            markPaid(room, friend);

            fetch(ACTIVE, me).andExpect(jsonPath("$.rooms[0].markedPaidAt").value(nullValue()));
        }

        @Test
        @DisplayName("정산중에 취소된 방에서는 내 환불 상태가 나온다")
        void showsMyRefundStatus() throws Exception {
            GroupOrder room = settle(room(other, "환불 중", me));
            markPaid(room, me);
            cancel(room);

            fetch(ACTIVE, me).andExpect(jsonPath("$.rooms[0].refundStatus").value("REFUND_PENDING"));
        }

        @Test
        @DisplayName("끝난 방 탭에도 같은 칸이 채워진다")
        void fillsDoneTabToo() throws Exception {
            GroupOrder room = settle(room(other, "환불 끝", me));
            markPaid(room, me);
            cancel(room);
            settlementService.receiveRefund(lineId(room, me), me.getId());

            fetch(DONE, me)
                    .andExpect(jsonPath("$.rooms[0].markedPaidAt").isNotEmpty())
                    .andExpect(jsonPath("$.rooms[0].refundStatus").value("REFUNDED"));
        }

        @Test
        @DisplayName("정산표가 아직 없는 방은 칸이 모두 비어 있다")
        void emptyBeforeSettlement() throws Exception {
            room(other, "모집중", me);

            fetch(ACTIVE, me)
                    .andExpect(jsonPath("$.rooms[0].markedPaidAt").value(nullValue()))
                    .andExpect(jsonPath("$.rooms[0].confirmedPaidAt").value(nullValue()))
                    .andExpect(jsonPath("$.rooms[0].refundStatus").value(nullValue()));
        }

        @Test
        @DisplayName("방장에게는 참여자 칸이 비어 있다")
        void hostHasNoMemberColumns() throws Exception {
            // 방장 줄은 만들 때 보낸 시각과 확인 시각이 채워져 있다. 그대로 내보내면 "다 냈음"으로 보인다
            settle(room(me, "내 방", other));

            fetch(ACTIVE, me)
                    .andExpect(jsonPath("$.rooms[0].markedPaidAt").value(nullValue()))
                    .andExpect(jsonPath("$.rooms[0].confirmedPaidAt").value(nullValue()))
                    .andExpect(jsonPath("$.rooms[0].refundStatus").value(nullValue()));
        }
    }

    @Nested
    @DisplayName("방장 칸: 확인을 기다리는 사람 수")
    class HostColumn {

        @Test
        @DisplayName("보냈어요를 누르고 확인을 기다리는 참여자 수가 나온다")
        void countsAwaitingConfirm() throws Exception {
            User b = user("B");
            User c = user("C");
            GroupOrder room = settle(room(me, "내 방", b, c));

            fetch(ACTIVE, me).andExpect(jsonPath("$.rooms[0].awaitingConfirmCount").value(0));

            markPaid(room, b);
            markPaid(room, c);
            fetch(ACTIVE, me).andExpect(jsonPath("$.rooms[0].awaitingConfirmCount").value(2));

            settlementService.confirm(lineId(room, b), me.getId());
            fetch(ACTIVE, me).andExpect(jsonPath("$.rooms[0].awaitingConfirmCount").value(1));
        }

        @Test
        @DisplayName("정산중이 아닌 방은 0이다")
        void zeroOutsideSettling() throws Exception {
            room(me, "모집중인 내 방");

            fetch(ACTIVE, me).andExpect(jsonPath("$.rooms[0].awaitingConfirmCount").value(0));
        }

        @Test
        @DisplayName("참여자에게는 비어 있다")
        void nullForMember() throws Exception {
            settle(room(other, "남의 방", me));

            fetch(ACTIVE, me).andExpect(jsonPath("$.rooms[0].awaitingConfirmCount").value(nullValue()));
        }

        @Test
        @DisplayName("방이 늘어나도 쿼리 수는 늘지 않는다")
        void queryCountDoesNotGrowWithRooms() throws Exception {
            // 방마다 내 줄을 찾고, 기다리는 사람을 세면 방 수에 비례해 쿼리가 는다 (N+1)
            // 방장 방과 참여 방을 섞는다. 한쪽 칸만 모아 읽으면 다른 쪽에서 늘어난다
            for (int i = 0; i < 2; i++) {
                settle(room(user("방장" + i), "참여 방" + i, me));
            }
            settle(room(me, "내 방", other));
            long withThreeRooms = countQueries();

            for (int i = 2; i < 6; i++) {
                settle(room(user("방장" + i), "참여 방" + i, me));
            }
            settle(room(me, "내 방2", other));
            long withEightRooms = countQueries();

            assertThat(withEightRooms)
                    .as("방 3개일 때 %d번, 8개일 때 %d번. 방 수에 따라 늘면 N+1이다", withThreeRooms, withEightRooms)
                    .isEqualTo(withThreeRooms);
        }

        long countQueries() throws Exception {
            Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
            statistics.clear();
            fetch(ACTIVE, me);
            return statistics.getPrepareStatementCount();
        }
    }
}

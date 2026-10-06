package com.bandal.participation;

import com.bandal.settlement.SettlementRepository;
import com.bandal.TestcontainersConfiguration;
import com.bandal.auth.JwtProvider;
import com.bandal.grouporder.GroupOrder;
import com.bandal.grouporder.GroupOrderRepository;
import com.bandal.participation.dto.OrderItemRequest;
import com.bandal.pickupspot.PickupSpot;
import com.bandal.pickupspot.PickupSpotRepository;
import com.bandal.university.University;
import com.bandal.university.UniversityRepository;
import com.bandal.user.User;
import com.bandal.user.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 참여와 메뉴를 HTTP로 부른다.
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ParticipationApiTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JwtProvider jwtProvider;

    @Autowired
    GroupOrderRepository groupOrderRepository;

    @Autowired
    ParticipationRepository participationRepository;

    @Autowired
    SettlementRepository settlementRepository;

    @Autowired
    OrderItemRepository orderItemRepository;

    @Autowired
    UniversityRepository universityRepository;

    @Autowired
    PickupSpotRepository pickupSpotRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    University university;
    PickupSpot pickupSpot;
    User host;
    User member;
    GroupOrder groupOrder;
    Participation hostParticipation;

    // 로그인한 척하는 헤더
    String bearer(Long userId) {
        return "Bearer " + jwtProvider.createAccessToken(userId);
    }

    @BeforeEach
    void setUp() {
        // 정산 행이 group_order를 참조하므로 방보다 먼저 지운다
        settlementRepository.deleteAll();
        orderItemRepository.deleteAll();
        participationRepository.deleteAll();
        groupOrderRepository.deleteAll();
        userRepository.deleteAll();
        pickupSpotRepository.deleteAll();
        universityRepository.deleteAll();

        university = universityRepository.save(new University("한국대학교", "hankuk.ac.kr"));
        pickupSpot = pickupSpotRepository.save(new PickupSpot(university, "제1기숙사 로비", null));
        host = verifiedUser(university, "kim@hankuk.ac.kr", "배고파");
        member = verifiedUser(university, "lee@hankuk.ac.kr", "마라탕러버");

        groupOrder = groupOrderRepository.save(new GroupOrder(
                host, pickupSpot, "○○마라탕", 15_000, Instant.now().plus(2, ChronoUnit.HOURS), 3));
        hostParticipation = participationRepository.save(new Participation(groupOrder, host, Instant.now()));
    }

    // 메일 인증을 마친 사용자. 미인증이면 쓰기가 전부 403이라 참여 로직을 볼 수 없다 (ADR-027)
    User verifiedUser(University university, String email, String nickname) {
        User user = new User(university, email, "hashed-password", nickname);
        ReflectionTestUtils.setField(user, "emailVerifiedAt", Instant.now());
        ReflectionTestUtils.setField(user, "bankName", "한국은행");
        ReflectionTestUtils.setField(user, "accountNumber", "110-123-456789");
        ReflectionTestUtils.setField(user, "accountHolder", "김민수");
        return userRepository.save(user);
    }

    @Nested
    @DisplayName("참여")
    class Join {

        // 참여 요청. 본문은 없다. 사용자는 토큰에, 방은 주소에 있다
        ResultActions join(Long groupOrderId, User user) throws Exception {
            return mockMvc.perform(post("/api/group-orders/" + groupOrderId + "/participations")
                    .header("Authorization", bearer(user.getId())));
        }

        @Test
        @DisplayName("같은 학교 사람이 참여하면 201이고 참여 정보가 돌아온다")
        void joins() throws Exception {
            join(groupOrder.getId(), member)
                    .andExpect(status().isCreated())
                    // 다음 단계(메뉴 담기)에서 이 참여 id를 쓴다
                    .andExpect(jsonPath("$.id").isNumber())
                    .andExpect(jsonPath("$.groupOrderId").value(groupOrder.getId()))
                    .andExpect(jsonPath("$.userId").value(member.getId()))
                    .andExpect(jsonPath("$.joinedAt").isNotEmpty());

            assertThat(participationRepository.countByGroupOrderId(groupOrder.getId())).isEqualTo(2);
        }

        @Test
        @DisplayName("로그인하지 않으면 401이다")
        void requiresLogin() throws Exception {
            mockMvc.perform(post("/api/group-orders/" + groupOrder.getId() + "/participations"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("메일 인증을 안 한 사용자는 참여할 수 없다")
        void rejectsUnverified() throws Exception {
            // 쓰기는 인증 사용자만 (ADR-027, 034). SecurityConfig가 서비스까지 오기 전에 막는다
            User unverified = userRepository.save(
                    new User(university, "new@hankuk.ac.kr", "hashed-password", "신입생"));

            join(groupOrder.getId(), unverified)
                    .andExpect(status().isForbidden());
            assertThat(participationRepository.countByGroupOrderId(groupOrder.getId())).isEqualTo(1);
        }

        @Test
        @DisplayName("계좌가 없어도 참여할 수 있다")
        void joinsWithoutAccount() throws Exception {
            // 계좌는 돈을 받는 방장에게만 필요하다 (ADR-031). 참여자는 보내기만 한다
            User noAccount = new User(university, "new@hankuk.ac.kr", "hashed-password", "계좌없음");
            ReflectionTestUtils.setField(noAccount, "emailVerifiedAt", Instant.now());
            userRepository.save(noAccount);

            join(groupOrder.getId(), noAccount)
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("같은 방에 두 번 참여하면 409다")
        void rejectsDuplicate() throws Exception {
            join(groupOrder.getId(), member).andExpect(status().isCreated());

            join(groupOrder.getId(), member)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("이미 참여한 방입니다"));

            assertThat(participationRepository.countByGroupOrderId(groupOrder.getId())).isEqualTo(2);
        }

        @Test
        @DisplayName("방장이 자기 방에 참여하려 하면 409다")
        void rejectsHostJoiningOwnRoom() throws Exception {
            // 방을 만들 때 방장의 참여 행이 이미 생겼다. 따로 규칙 없이 중복 확인에 걸린다
            join(groupOrder.getId(), host)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("이미 참여한 방입니다"));

            assertThat(participationRepository.countByGroupOrderId(groupOrder.getId())).isEqualTo(1);
        }

        @Test
        @DisplayName("이미 참여한 사람이 꽉 찬 방에 또 누르면 정원보다 중복을 먼저 알려준다")
        void duplicateBeforeFull() throws Exception {
            // 정원 3명: 방장 + member + third. member 입장에서는 "꽉 찼다"보다 "이미 들어와 있다"가 맞는 설명이다
            participationRepository.save(new Participation(groupOrder, member, Instant.now()));
            participationRepository.save(new Participation(
                    groupOrder, verifiedUser(university, "park@hankuk.ac.kr", "꿔바로우"), Instant.now()));

            join(groupOrder.getId(), member)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("이미 참여한 방입니다"));
        }

        @Test
        @DisplayName("정원이 다 찼으면 409이고 참여 행이 늘지 않는다")
        void rejectsWhenFull() throws Exception {
            User third = verifiedUser(university, "park@hankuk.ac.kr", "꿔바로우");
            User fourth = verifiedUser(university, "choi@hankuk.ac.kr", "탕수육");
            participationRepository.save(new Participation(groupOrder, member, Instant.now()));
            participationRepository.save(new Participation(groupOrder, third, Instant.now()));

            // 정원 3명이 이미 찼다
            join(groupOrder.getId(), fourth)
                    .andExpect(status().isConflict());
            assertThat(participationRepository.countByGroupOrderId(groupOrder.getId())).isEqualTo(3);
        }

        @Test
        @DisplayName("다른 학교 사람이 참여하면 409이고 참여 행이 늘지 않는다")
        void rejectsOtherUniversity() throws Exception {
            University otherUniversity = universityRepository.save(new University("민국대학교", "minguk.ac.kr"));
            User outsider = verifiedUser(otherUniversity, "park@minguk.ac.kr", "외부인");

            join(groupOrder.getId(), outsider)
                    .andExpect(status().isConflict());
            assertThat(participationRepository.countByGroupOrderId(groupOrder.getId())).isEqualTo(1);
        }

        @Test
        @DisplayName("마감 시각이 지난 방에는 참여할 수 없다")
        void rejectsAfterDeadline() throws Exception {
            GroupOrder late = groupOrderRepository.save(new GroupOrder(
                    host, pickupSpot, "△△치킨", 20_000, Instant.now().minusSeconds(60), 4));
            participationRepository.save(new Participation(late, host, Instant.now()));

            mockMvc.perform(post("/api/group-orders/" + late.getId() + "/participations")
                            .header("Authorization", bearer(member.getId())))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("마감된 방에는 참여할 수 없다")
        void rejectsClosedRoom() throws Exception {
            participationRepository.save(new Participation(groupOrder, member, Instant.now()));
            groupOrder.closeByHost(host.getId(), 2, 20_000);
            groupOrderRepository.saveAndFlush(groupOrder);

            User third = verifiedUser(university, "park@hankuk.ac.kr", "꿔바로우");

            mockMvc.perform(post("/api/group-orders/" + groupOrder.getId() + "/participations")
                            .header("Authorization", bearer(third.getId())))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("없는 방에 참여하려 하면 404다")
        void rejectsUnknownGroupOrder() throws Exception {
            mockMvc.perform(post("/api/group-orders/999999/participations")
                            .header("Authorization", bearer(member.getId())))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("나가기")
    class Leave {

        Participation memberParticipation;

        @BeforeEach
        void join() {
            memberParticipation = participationRepository.save(
                    new Participation(groupOrder, member, Instant.now()));
        }

        ResultActions leave(Long participationId, User user) throws Exception {
            return mockMvc.perform(delete("/api/participations/" + participationId)
                    .header("Authorization", bearer(user.getId())));
        }

        @Test
        @DisplayName("나가면 204이고 참여 행이 사라진다")
        void leaves() throws Exception {
            leave(memberParticipation.getId(), member)
                    .andExpect(status().isNoContent());

            assertThat(participationRepository.findById(memberParticipation.getId())).isEmpty();
            assertThat(participationRepository.countByGroupOrderId(groupOrder.getId())).isEqualTo(1);
        }

        @Test
        @DisplayName("나갔던 방에 다시 들어올 수 있다")
        void rejoinsAfterLeaving() throws Exception {
            // 행을 지우니까 유니크 제약과 부딪히지 않는다 (ADR-019)
            leave(memberParticipation.getId(), member).andExpect(status().isNoContent());

            mockMvc.perform(post("/api/group-orders/" + groupOrder.getId() + "/participations")
                            .header("Authorization", bearer(member.getId())))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("담아둔 메뉴가 있어도 나갈 수 있고 메뉴도 같이 사라진다")
        void leavesWithItems() throws Exception {
            // 메뉴가 참여 행을 가리키고 있다. 참여 행만 지우면 DB가 거부한다 (ADR-037)
            orderItemRepository.save(memberParticipation.addItem(member.getId(), "마라탕", null, 9_000, 1, Instant.now()));
            orderItemRepository.save(memberParticipation.addItem(member.getId(), "꿔바로우", null, 15_000, 1, Instant.now()));

            leave(memberParticipation.getId(), member)
                    .andExpect(status().isNoContent());

            assertThat(participationRepository.findById(memberParticipation.getId())).isEmpty();
            assertThat(orderItemRepository.findByParticipationId(memberParticipation.getId())).isEmpty();
        }

        @Test
        @DisplayName("내가 나가도 다른 사람의 메뉴는 남는다")
        void keepsOthersItems() throws Exception {
            // 메뉴를 지울 때 범위를 잘못 잡으면(예: 방 전체) 남의 장바구니까지 비운다
            orderItemRepository.save(memberParticipation.addItem(member.getId(), "마라탕", null, 9_000, 1, Instant.now()));
            orderItemRepository.save(hostParticipation.addItem(host.getId(), "탕수육", null, 18_000, 1, Instant.now()));

            leave(memberParticipation.getId(), member)
                    .andExpect(status().isNoContent());

            assertThat(orderItemRepository.findByParticipationId(hostParticipation.getId())).hasSize(1);
        }

        @Test
        @DisplayName("로그인하지 않으면 401이다")
        void requiresLogin() throws Exception {
            mockMvc.perform(delete("/api/participations/" + memberParticipation.getId()))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("남의 참여로 나가려 하면 409이고 그 사람은 방에 남는다")
        void rejectsOtherUsersParticipation() throws Exception {
            User third = verifiedUser(university, "park@hankuk.ac.kr", "꿔바로우");

            leave(memberParticipation.getId(), third)
                    .andExpect(status().isConflict());

            assertThat(participationRepository.findById(memberParticipation.getId())).isPresent();
        }

        @Test
        @DisplayName("방장이 나가려 하면 409이고 방 취소를 안내한다")
        void rejectsHost() throws Exception {
            leave(hostParticipation.getId(), host)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("방장은 나갈 수 없습니다. 방을 취소해주세요"));

            assertThat(participationRepository.findById(hostParticipation.getId())).isPresent();
        }

        @Test
        @DisplayName("마감된 방에서는 나갈 수 없다")
        void rejectsClosedRoom() throws Exception {
            groupOrder.closeByHost(host.getId(), 2, 20_000);
            groupOrderRepository.saveAndFlush(groupOrder);

            leave(memberParticipation.getId(), member)
                    .andExpect(status().isConflict());
            assertThat(participationRepository.findById(memberParticipation.getId())).isPresent();
        }

        @Test
        @DisplayName("마감 시각이 지났으면 아직 모집중으로 남아 있어도 나갈 수 없다")
        void rejectsAfterDeadline() throws Exception {
            // 마감 시각에 상태를 바꿔줄 스케줄러가 아직 없어서 이런 방이 실제로 남는다
            GroupOrder late = groupOrderRepository.save(new GroupOrder(
                    host, pickupSpot, "△△치킨", 20_000, Instant.now().minusSeconds(60), 4));
            participationRepository.save(new Participation(late, host, Instant.now()));
            Participation lateMember = participationRepository.save(new Participation(late, member, Instant.now()));

            leave(lateMember.getId(), member)
                    .andExpect(status().isConflict());
            assertThat(participationRepository.findById(lateMember.getId())).isPresent();
        }

        @Test
        @DisplayName("없는 참여로 나가려 하면 404다")
        void rejectsUnknownParticipation() throws Exception {
            leave(999_999L, member)
                    .andExpect(status().isNotFound());
        }
    }

    // 메뉴 요청 본문
    OrderItemRequest itemRequest(String menuName, String options, Long unitPrice, Integer quantity) {
        return new OrderItemRequest(menuName, options, unitPrice, quantity);
    }

    // 테스트 준비용으로 메뉴 한 줄을 DB에 바로 넣는다
    OrderItem saveItem(Participation participation, User owner, String menuName, long unitPrice) {
        return orderItemRepository.save(
                participation.addItem(owner.getId(), menuName, null, unitPrice, 1, Instant.now()));
    }

    // 방장이 손으로 마감한다. 방장 + member 2명, 메뉴 합계 조건은 테스트마다 맞춰 넣는다
    void closeRoom() {
        groupOrder.closeByHost(host.getId(), 2, 20_000);
        groupOrderRepository.saveAndFlush(groupOrder);
    }

    // 마감 시각이 이미 지났는데 상태는 모집중으로 남은 방. 스케줄러가 없어서 실제로 생긴다
    Participation lateRoomMember() {
        GroupOrder late = groupOrderRepository.save(new GroupOrder(
                host, pickupSpot, "△△치킨", 20_000, Instant.now().minusSeconds(60), 4));
        participationRepository.save(new Participation(late, host, Instant.now()));
        return participationRepository.save(new Participation(late, member, Instant.now()));
    }

    @Nested
    @DisplayName("메뉴 담기")
    class AddItem {

        Participation memberParticipation;

        @BeforeEach
        void join() {
            memberParticipation = participationRepository.save(
                    new Participation(groupOrder, member, Instant.now()));
        }

        ResultActions add(Long participationId, User user, OrderItemRequest request) throws Exception {
            return mockMvc.perform(post("/api/participations/" + participationId + "/order-items")
                    .header("Authorization", bearer(user.getId()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)));
        }

        OrderItemRequest valid() {
            return itemRequest("마라탕", "2단계, 꿔바로우 추가", 12_000L, 2);
        }

        @Test
        @DisplayName("담으면 201이고 내 메뉴 목록과 내 합계, 방 합계가 같이 온다")
        void addsItem() throws Exception {
            add(memberParticipation.getId(), member, valid())
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.participationId").value(memberParticipation.getId()))
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.items[0].id").isNumber())
                    .andExpect(jsonPath("$.items[0].menuName").value("마라탕"))
                    .andExpect(jsonPath("$.items[0].options").value("2단계, 꿔바로우 추가"))
                    .andExpect(jsonPath("$.items[0].unitPrice").value(12_000))
                    .andExpect(jsonPath("$.items[0].quantity").value(2))
                    .andExpect(jsonPath("$.items[0].amount").value(24_000))
                    .andExpect(jsonPath("$.items[0].editedByHost").value(false))
                    .andExpect(jsonPath("$.participationTotal").value(24_000))
                    .andExpect(jsonPath("$.groupOrderTotal").value(24_000));

            assertThat(orderItemRepository.findByParticipationId(memberParticipation.getId())).hasSize(1);
        }

        @Test
        @DisplayName("방 합계에는 다른 사람 메뉴도 들어가지만 목록에는 내 메뉴만 나온다")
        void groupOrderTotalIncludesOthers() throws Exception {
            // 합계 바를 다시 그리는 값이다. 그사이 다른 사람이 담은 것도 반영돼야 한다
            saveItem(hostParticipation, host, "꿔바로우", 8_000);

            add(memberParticipation.getId(), member, valid())
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.participationTotal").value(24_000))
                    .andExpect(jsonPath("$.groupOrderTotal").value(32_000));
        }

        @Test
        @DisplayName("한 줄씩 담으면 목록에 계속 쌓인다")
        void accumulates() throws Exception {
            add(memberParticipation.getId(), member, itemRequest("마라탕", "2단계", 9_000L, 1))
                    .andExpect(status().isCreated());

            add(memberParticipation.getId(), member, itemRequest("공기밥", null, 1_000L, 2))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.items.length()").value(2))
                    .andExpect(jsonPath("$.participationTotal").value(11_000));
        }

        @Test
        @DisplayName("로그인하지 않으면 401이다")
        void requiresLogin() throws Exception {
            mockMvc.perform(post("/api/participations/" + memberParticipation.getId() + "/order-items")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(valid())))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("남의 참여에 담으려 하면 409이고 아무것도 담기지 않는다")
        void rejectsOtherUsersParticipation() throws Exception {
            // 주소의 참여 id는 누구나 바꿔 칠 수 있다. 막지 않으면 남의 정산 금액이 늘어난다
            add(memberParticipation.getId(), host, valid())
                    .andExpect(status().isConflict());

            assertThat(orderItemRepository.count()).isZero();
        }

        @Test
        @DisplayName("메뉴 이름이 비거나 50자를 넘으면 400이다")
        void rejectsBadMenuName() throws Exception {
            add(memberParticipation.getId(), member, itemRequest("   ", null, 9_000L, 1))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));

            add(memberParticipation.getId(), member, itemRequest("가".repeat(51), null, 9_000L, 1))
                    .andExpect(status().isBadRequest());

            assertThat(orderItemRepository.count()).isZero();
        }

        @Test
        @DisplayName("옵션이 100자를 넘으면 400이다")
        void rejectsLongOptions() throws Exception {
            add(memberParticipation.getId(), member, itemRequest("마라탕", "가".repeat(101), 9_000L, 1))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("가격이 0원이거나 100만 원을 넘으면 400이다")
        void rejectsBadPrice() throws Exception {
            add(memberParticipation.getId(), member, itemRequest("마라탕", null, 0L, 1))
                    .andExpect(status().isBadRequest());

            // 오타로 0을 여러 개 친 경우. 받으면 합계 바가 가득 차 보인다
            add(memberParticipation.getId(), member, itemRequest("마라탕", null, 1_000_001L, 1))
                    .andExpect(status().isBadRequest());

            assertThat(orderItemRepository.count()).isZero();
        }

        @Test
        @DisplayName("개수가 0이거나 99개를 넘으면 400이다")
        void rejectsBadQuantity() throws Exception {
            add(memberParticipation.getId(), member, itemRequest("마라탕", null, 9_000L, 0))
                    .andExpect(status().isBadRequest());

            add(memberParticipation.getId(), member, itemRequest("마라탕", null, 9_000L, 100))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("마감된 방에는 담을 수 없다")
        void rejectsClosedRoom() throws Exception {
            saveItem(hostParticipation, host, "꿔바로우", 20_000);
            closeRoom();

            add(memberParticipation.getId(), member, valid())
                    .andExpect(status().isConflict());
            assertThat(orderItemRepository.findByParticipationId(memberParticipation.getId())).isEmpty();
        }

        @Test
        @DisplayName("마감 시각이 지났으면 아직 모집중으로 남아 있어도 담을 수 없다")
        void rejectsAfterDeadline() throws Exception {
            Participation lateMember = lateRoomMember();

            add(lateMember.getId(), member, valid())
                    .andExpect(status().isConflict());
            assertThat(orderItemRepository.findByParticipationId(lateMember.getId())).isEmpty();
        }

        @Test
        @DisplayName("없는 참여에 담으려 하면 404다")
        void rejectsUnknownParticipation() throws Exception {
            add(999_999L, member, valid())
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("메뉴 고치기")
    class UpdateItem {

        Participation memberParticipation;
        OrderItem item;

        @BeforeEach
        void joinAndAdd() {
            memberParticipation = participationRepository.save(
                    new Participation(groupOrder, member, Instant.now()));
            // 9,000원을 900원으로 잘못 친 상황
            item = saveItem(memberParticipation, member, "마라탕", 900);
        }

        ResultActions update(Long orderItemId, User user, OrderItemRequest request) throws Exception {
            return mockMvc.perform(put("/api/order-items/" + orderItemId)
                    .header("Authorization", bearer(user.getId()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)));
        }

        OrderItemRequest fixed() {
            return itemRequest("마라탕", "2단계", 9_000L, 1);
        }

        // DB에 남은 값이 처음 그대로인지 본다
        void assertUnchanged() {
            OrderItem found = orderItemRepository.findById(item.getId()).orElseThrow();
            assertThat(found.getUnitPrice()).isEqualTo(900);
            assertThat(found.isEditedByHost()).isFalse();
        }

        @Test
        @DisplayName("모집중에 주인이 고치면 200이고 값이 통째로 바뀐다")
        void ownerUpdates() throws Exception {
            update(item.getId(), member, fixed())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.participationId").value(memberParticipation.getId()))
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.items[0].id").value(item.getId()))
                    .andExpect(jsonPath("$.items[0].options").value("2단계"))
                    .andExpect(jsonPath("$.items[0].unitPrice").value(9_000))
                    .andExpect(jsonPath("$.items[0].editedByHost").value(false))
                    .andExpect(jsonPath("$.participationTotal").value(9_000))
                    .andExpect(jsonPath("$.groupOrderTotal").value(9_000));

            OrderItem found = orderItemRepository.findById(item.getId()).orElseThrow();
            assertThat(found.getUnitPrice()).isEqualTo(9_000);
            assertThat(found.getOptions()).isEqualTo("2단계");
        }

        @Test
        @DisplayName("로그인하지 않으면 401이다")
        void requiresLogin() throws Exception {
            mockMvc.perform(put("/api/order-items/" + item.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(fixed())))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("다른 참여자가 고치려 하면 409이고 값은 그대로다")
        void rejectsOtherUser() throws Exception {
            User third = verifiedUser(university, "park@hankuk.ac.kr", "꿔바로우");
            participationRepository.save(new Participation(groupOrder, third, Instant.now()));

            update(item.getId(), third, fixed())
                    .andExpect(status().isConflict());
            assertUnchanged();
        }

        @Test
        @DisplayName("모집중에는 방장도 남의 메뉴를 고칠 수 없다")
        void hostCannotUpdateWhileRecruiting() throws Exception {
            update(item.getId(), host, fixed())
                    .andExpect(status().isConflict());
            assertUnchanged();
        }

        @Test
        @DisplayName("마감 후 방장이 고치면 200이고 수정 표시가 남는다")
        void hostUpdatesAfterClose() throws Exception {
            saveItem(hostParticipation, host, "꿔바로우", 20_000);
            closeRoom();

            // 응답은 고친 메뉴의 주인(member) 기준이다. 방장 화면은 검수 목록을 다시 그린다
            update(item.getId(), host, fixed())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.participationId").value(memberParticipation.getId()))
                    .andExpect(jsonPath("$.items[0].unitPrice").value(9_000))
                    .andExpect(jsonPath("$.items[0].editedByHost").value(true))
                    .andExpect(jsonPath("$.participationTotal").value(9_000))
                    .andExpect(jsonPath("$.groupOrderTotal").value(29_000));

            assertThat(orderItemRepository.findById(item.getId()).orElseThrow().isEditedByHost()).isTrue();
        }

        @Test
        @DisplayName("마감 후에는 주인도 고칠 수 없다")
        void ownerCannotUpdateAfterClose() throws Exception {
            saveItem(hostParticipation, host, "꿔바로우", 20_000);
            closeRoom();

            update(item.getId(), member, fixed())
                    .andExpect(status().isConflict());
            assertUnchanged();
        }

        @Test
        @DisplayName("마감 시각이 지났으면 아직 모집중으로 남아 있어도 주인이 고칠 수 없다")
        void rejectsAfterDeadline() throws Exception {
            Participation lateMember = lateRoomMember();
            // 마감 시각 전에 담아둔 메뉴라고 친다. 엔티티로 넣으면 시각 검사에 걸려서 DB에 바로 넣는다
            OrderItem lateItem = orderItemRepository.save(new OrderItem(lateMember, "치킨", null, 900, 1));

            update(lateItem.getId(), member, fixed())
                    .andExpect(status().isConflict());
            assertThat(orderItemRepository.findById(lateItem.getId()).orElseThrow().getUnitPrice()).isEqualTo(900);
        }

        @Test
        @DisplayName("고칠 값이 잘못됐으면 400이고 값은 그대로다")
        void rejectsInvalidValues() throws Exception {
            update(item.getId(), member, itemRequest("마라탕", null, 0L, 1))
                    .andExpect(status().isBadRequest());
            update(item.getId(), member, itemRequest("마라탕", null, 9_000L, 100))
                    .andExpect(status().isBadRequest());
            assertUnchanged();
        }

        @Test
        @DisplayName("없는 메뉴를 고치려 하면 404다")
        void rejectsUnknownItem() throws Exception {
            update(999_999L, member, fixed())
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("메뉴 빼기")
    class RemoveItem {

        Participation memberParticipation;
        OrderItem item;

        @BeforeEach
        void joinAndAdd() {
            memberParticipation = participationRepository.save(
                    new Participation(groupOrder, member, Instant.now()));
            item = saveItem(memberParticipation, member, "마라탕", 9_000);
        }

        ResultActions remove(Long orderItemId, User user) throws Exception {
            return mockMvc.perform(delete("/api/order-items/" + orderItemId)
                    .header("Authorization", bearer(user.getId())));
        }

        @Test
        @DisplayName("빼면 200이고 행이 사라지며 남은 목록과 합계가 온다")
        void removes() throws Exception {
            // 나가기와 달리 화면에 남아 있으니 다시 그릴 값을 돌려준다
            saveItem(memberParticipation, member, "공기밥", 1_000);
            saveItem(hostParticipation, host, "꿔바로우", 8_000);

            remove(item.getId(), member)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.participationId").value(memberParticipation.getId()))
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.items[0].menuName").value("공기밥"))
                    .andExpect(jsonPath("$.participationTotal").value(1_000))
                    .andExpect(jsonPath("$.groupOrderTotal").value(9_000));

            assertThat(orderItemRepository.findById(item.getId())).isEmpty();
        }

        @Test
        @DisplayName("마지막 한 줄을 빼면 빈 목록과 0원이 온다")
        void removesLastItem() throws Exception {
            remove(item.getId(), member)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(0))
                    .andExpect(jsonPath("$.participationTotal").value(0))
                    .andExpect(jsonPath("$.groupOrderTotal").value(0));

            // 메뉴만 빠지고 참여는 남는다
            assertThat(participationRepository.findById(memberParticipation.getId())).isPresent();
        }

        @Test
        @DisplayName("로그인하지 않으면 401이다")
        void requiresLogin() throws Exception {
            mockMvc.perform(delete("/api/order-items/" + item.getId()))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("남의 메뉴를 빼려 하면 409이고 메뉴는 남는다")
        void rejectsOtherUser() throws Exception {
            remove(item.getId(), host)
                    .andExpect(status().isConflict());

            assertThat(orderItemRepository.findById(item.getId())).isPresent();
        }

        @Test
        @DisplayName("마감 후에는 방장도 뺄 수 없다")
        void rejectsAfterClose() throws Exception {
            saveItem(hostParticipation, host, "꿔바로우", 20_000);
            closeRoom();

            remove(item.getId(), member)
                    .andExpect(status().isConflict());
            remove(item.getId(), host)
                    .andExpect(status().isConflict());

            assertThat(orderItemRepository.findById(item.getId())).isPresent();
        }

        @Test
        @DisplayName("마감 시각이 지났으면 아직 모집중으로 남아 있어도 뺄 수 없다")
        void rejectsAfterDeadline() throws Exception {
            Participation lateMember = lateRoomMember();
            OrderItem lateItem = orderItemRepository.save(new OrderItem(lateMember, "치킨", null, 20_000, 1));

            remove(lateItem.getId(), member)
                    .andExpect(status().isConflict());
            assertThat(orderItemRepository.findById(lateItem.getId())).isPresent();
        }

        @Test
        @DisplayName("없는 메뉴를 빼려 하면 404다")
        void rejectsUnknownItem() throws Exception {
            remove(999_999L, member)
                    .andExpect(status().isNotFound());
        }
    }
    @Nested
    @DisplayName("내 메뉴 조회")
    class MyItems {

        Participation memberParticipation;

        @BeforeEach
        void joinAndAdd() {
            memberParticipation = participationRepository.save(
                    new Participation(groupOrder, member, Instant.now()));
            saveItem(memberParticipation, member, "마라탕", 9_000);
            saveItem(memberParticipation, member, "공기밥", 1_000);
            saveItem(hostParticipation, host, "꿔바로우", 8_000);
        }

        ResultActions myItems(Long participationId, User user) throws Exception {
            return mockMvc.perform(get("/api/participations/" + participationId + "/order-items")
                    .header("Authorization", bearer(user.getId())));
        }

        @Test
        @DisplayName("주인이 보면 200이고 담기 응답과 같은 모양으로 온다")
        void ownerSeesItems() throws Exception {
            // 프론트가 "내 메뉴 칸"을 그리는 코드를 하나만 쓰게 한다 (ADR-039)
            myItems(memberParticipation.getId(), member)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.participationId").value(memberParticipation.getId()))
                    .andExpect(jsonPath("$.items.length()").value(2))
                    .andExpect(jsonPath("$.items[*].menuName").value(containsInAnyOrder("마라탕", "공기밥")))
                    .andExpect(jsonPath("$.participationTotal").value(10_000))
                    .andExpect(jsonPath("$.groupOrderTotal").value(18_000));
        }

        @Test
        @DisplayName("메뉴를 아직 안 담았으면 빈 목록과 0원이 온다")
        void emptyWhenNoItems() throws Exception {
            User third = verifiedUser(university, "park@hankuk.ac.kr", "꿔바로우");
            Participation thirdParticipation = participationRepository.save(
                    new Participation(groupOrder, third, Instant.now()));

            myItems(thirdParticipation.getId(), third)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(0))
                    .andExpect(jsonPath("$.participationTotal").value(0))
                    .andExpect(jsonPath("$.groupOrderTotal").value(18_000));
        }

        @Test
        @DisplayName("방장은 남의 참여 메뉴도 볼 수 있다")
        void hostSeesOthersItems() throws Exception {
            myItems(memberParticipation.getId(), host)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.participationTotal").value(10_000));
        }

        @Test
        @DisplayName("다른 참여자는 내 메뉴를 볼 수 없다")
        void otherCannotSee() throws Exception {
            User third = verifiedUser(university, "park@hankuk.ac.kr", "꿔바로우");
            participationRepository.save(new Participation(groupOrder, third, Instant.now()));

            myItems(memberParticipation.getId(), third)
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("마감 후에도 주인은 자기 메뉴를 볼 수 있다")
        void ownerSeesAfterClose() throws Exception {
            // 고치기는 막혀도 보기는 열려 있다. 정산 화면으로 넘어가기 전에 확인한다
            saveItem(hostParticipation, host, "탕수육", 20_000);
            closeRoom();

            myItems(memberParticipation.getId(), member)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(2));
        }

        @Test
        @DisplayName("로그인하지 않으면 401이다")
        void requiresLogin() throws Exception {
            mockMvc.perform(get("/api/participations/" + memberParticipation.getId() + "/order-items"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("없는 참여면 404다")
        void rejectsUnknownParticipation() throws Exception {
            myItems(999_999L, member)
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("방장 검수 목록")
    class ReviewList {

        Participation memberParticipation;

        @BeforeEach
        void joinAndAdd() {
            memberParticipation = participationRepository.save(
                    new Participation(groupOrder, member, Instant.now()));
            saveItem(hostParticipation, host, "꿔바로우", 12_000);
            saveItem(memberParticipation, member, "마라탕", 9_000);
            saveItem(memberParticipation, member, "공기밥", 1_000);
        }

        ResultActions review(Long groupOrderId, User user) throws Exception {
            return mockMvc.perform(get("/api/group-orders/" + groupOrderId + "/order-items")
                    .header("Authorization", bearer(user.getId())));
        }

        @Test
        @DisplayName("방장이 보면 200이고 참여자마다 메뉴와 합계가 붙어서 온다")
        void hostSeesAll() throws Exception {
            review(groupOrder.getId(), host)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.groupOrderId").value(groupOrder.getId()))
                    .andExpect(jsonPath("$.status").value("RECRUITING"))
                    .andExpect(jsonPath("$.groupOrderTotal").value(22_000))
                    .andExpect(jsonPath("$.participants.length()").value(2))
                    // 방장이 맨 앞, 그다음 들어온 순서
                    .andExpect(jsonPath("$.participants[0].participationId").value(hostParticipation.getId()))
                    .andExpect(jsonPath("$.participants[0].nickname").value("배고파"))
                    .andExpect(jsonPath("$.participants[0].host").value(true))
                    .andExpect(jsonPath("$.participants[0].items.length()").value(1))
                    .andExpect(jsonPath("$.participants[0].participationTotal").value(12_000))
                    .andExpect(jsonPath("$.participants[1].participationId").value(memberParticipation.getId()))
                    .andExpect(jsonPath("$.participants[1].userId").value(member.getId()))
                    .andExpect(jsonPath("$.participants[1].nickname").value("마라탕러버"))
                    .andExpect(jsonPath("$.participants[1].host").value(false))
                    .andExpect(jsonPath("$.participants[1].items[*].menuName")
                            .value(containsInAnyOrder("마라탕", "공기밥")))
                    .andExpect(jsonPath("$.participants[1].participationTotal").value(10_000));
        }

        @Test
        @DisplayName("메뉴를 안 담은 참여자도 빈 목록과 0원으로 나온다")
        void includesParticipantWithoutItems() throws Exception {
            // 빠지면 방장은 그 사람이 있는 줄도 모르고 주문한다
            User third = verifiedUser(university, "park@hankuk.ac.kr", "꿔바로우");
            participationRepository.save(new Participation(groupOrder, third, Instant.now()));

            review(groupOrder.getId(), host)
                    .andExpect(jsonPath("$.participants.length()").value(3))
                    .andExpect(jsonPath("$.participants[2].nickname").value("꿔바로우"))
                    .andExpect(jsonPath("$.participants[2].items.length()").value(0))
                    .andExpect(jsonPath("$.participants[2].participationTotal").value(0))
                    .andExpect(jsonPath("$.groupOrderTotal").value(22_000));
        }

        @Test
        @DisplayName("마감 후에도 방장은 볼 수 있고 방장이 고친 메뉴는 표시가 붙는다")
        void hostSeesAfterCloseWithEditMark() throws Exception {
            // 검수는 마감 뒤 주문 직전에 한다
            closeRoom();
            OrderItem item = orderItemRepository.findByParticipationId(memberParticipation.getId()).stream()
                    .filter(i -> i.getMenuName().equals("마라탕")).findFirst().orElseThrow();
            mockMvc.perform(put("/api/order-items/" + item.getId())
                            .header("Authorization", bearer(host.getId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(itemRequest("마라탕", null, 9_500L, 1))))
                    .andExpect(status().isOk());

            review(groupOrder.getId(), host)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CLOSED"))
                    .andExpect(jsonPath("$.participants[1].items[?(@.menuName == '마라탕')].editedByHost")
                            .value(contains(true)))
                    .andExpect(jsonPath("$.groupOrderTotal").value(22_500));
        }

        @Test
        @DisplayName("응답에 이메일이나 비밀번호 해시가 섞여 나가지 않는다")
        void doesNotLeakUserFields() throws Exception {
            // 사람마다 User를 다루니 엔티티를 그대로 담으면 전원 이메일이 나간다
            String body = review(groupOrder.getId(), host)
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain("lee@hankuk.ac.kr");
            assertThat(body).doesNotContain("hashed-password");
        }

        @Test
        @DisplayName("방장이 아닌 참여자가 보려 하면 409다")
        void rejectsNonHost() throws Exception {
            review(groupOrder.getId(), member)
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("로그인하지 않으면 401이다")
        void requiresLogin() throws Exception {
            mockMvc.perform(get("/api/group-orders/" + groupOrder.getId() + "/order-items"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("없는 방이면 404다")
        void rejectsUnknownGroupOrder() throws Exception {
            review(999_999L, host)
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("참여자가 늘어도 쿼리 수는 그대로다")
        void queryCountDoesNotGrowWithParticipants() throws Exception {
            // N+1 확인. 사람마다 쿼리가 나가면 2명일 때보다 5명일 때 쿼리가 많다 (ADR-039)
            long withTwo = countQueries(() -> review(groupOrder.getId(), host).andExpect(status().isOk()));

            // 3명을 더 넣는다. 정원(3명)을 넘지만 쿼리 수만 보는 테스트라 참여 검사 없이 행을 바로 넣는다
            for (int i = 0; i < 3; i++) {
                User extra = verifiedUser(university, "extra" + i + "@hankuk.ac.kr", "추가" + i);
                Participation p = participationRepository.save(new Participation(groupOrder, extra, Instant.now()));
                saveItem(p, extra, "메뉴" + i, 5_000);
                saveItem(p, extra, "사이드" + i, 1_000);
            }
            long withFive = countQueries(() -> review(groupOrder.getId(), host).andExpect(status().isOk()));

            assertThat(withFive)
                    .as("2명일 때 쿼리 %d번, 5명일 때 %d번", withTwo, withFive)
                    .isEqualTo(withTwo);
        }
    }

    interface Request {
        void run() throws Exception;
    }

    // 요청 하나 동안 DB에 보낸 SQL 수를 센다. Hibernate 통계 기능을 이 테스트에서만 잠깐 켠다
    long countQueries(Request request) throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            request.run();
            return statistics.getPrepareStatementCount();
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }
}

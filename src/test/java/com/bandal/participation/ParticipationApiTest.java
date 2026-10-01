package com.bandal.participation;

import com.bandal.settlement.SettlementRepository;
import com.bandal.TestcontainersConfiguration;
import com.bandal.auth.JwtProvider;
import com.bandal.grouporder.GroupOrder;
import com.bandal.grouporder.GroupOrderRepository;
import com.bandal.participation.dto.AddOrderItemRequest;
import com.bandal.pickupspot.PickupSpot;
import com.bandal.pickupspot.PickupSpotRepository;
import com.bandal.university.University;
import com.bandal.university.UniversityRepository;
import com.bandal.user.User;
import com.bandal.user.UserRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
            orderItemRepository.save(memberParticipation.addItem(member.getId(), "마라탕", null, 9_000, 1));
            orderItemRepository.save(memberParticipation.addItem(member.getId(), "꿔바로우", null, 15_000, 1));

            leave(memberParticipation.getId(), member)
                    .andExpect(status().isNoContent());

            assertThat(participationRepository.findById(memberParticipation.getId())).isEmpty();
            assertThat(orderItemRepository.findByParticipationId(memberParticipation.getId())).isEmpty();
        }

        @Test
        @DisplayName("내가 나가도 다른 사람의 메뉴는 남는다")
        void keepsOthersItems() throws Exception {
            // 메뉴를 지울 때 범위를 잘못 잡으면(예: 방 전체) 남의 장바구니까지 비운다
            orderItemRepository.save(memberParticipation.addItem(member.getId(), "마라탕", null, 9_000, 1));
            orderItemRepository.save(hostParticipation.addItem(host.getId(), "탕수육", null, 18_000, 1));

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

    @Nested
    @DisplayName("메뉴")
    class OrderItems {

        Participation memberParticipation;

        @BeforeEach
        void join() {
            memberParticipation = participationRepository.save(
                    new Participation(groupOrder, member, Instant.now()));
        }

        AddOrderItemRequest validRequest() {
            return new AddOrderItemRequest("마라탕", "2단계, 꿔바로우 추가", 12_000L, 2);
        }

        @Test
        @DisplayName("메뉴를 담으면 201이고 줄 금액이 같이 나온다")
        void addsItem() throws Exception {
            mockMvc.perform(post("/api/participations/" + memberParticipation.getId() + "/order-items")
                            .header("Authorization", bearer(member.getId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.menuName").value("마라탕"))
                    .andExpect(jsonPath("$.options").value("2단계, 꿔바로우 추가"))
                    .andExpect(jsonPath("$.unitPrice").value(12_000))
                    .andExpect(jsonPath("$.quantity").value(2))
                    .andExpect(jsonPath("$.amount").value(24_000));

            assertThat(orderItemRepository.findByParticipationId(memberParticipation.getId())).hasSize(1);
        }

        @Test
        @DisplayName("남의 참여에 메뉴를 담으려 하면 409다")
        void rejectsOtherUsersParticipation() throws Exception {
            mockMvc.perform(post("/api/participations/" + memberParticipation.getId() + "/order-items")
                            .header("Authorization", bearer(host.getId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest())))
                    .andExpect(status().isConflict());

            assertThat(orderItemRepository.count()).isZero();
        }

        @Test
        @DisplayName("메뉴 이름이 비면 400이다")
        void rejectsBlankMenuName() throws Exception {
            AddOrderItemRequest request = new AddOrderItemRequest("   ", null, 12_000L, 1);

            mockMvc.perform(post("/api/participations/" + memberParticipation.getId() + "/order-items")
                            .header("Authorization", bearer(member.getId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @DisplayName("가격이 0원이면 400이다")
        void rejectsZeroPrice() throws Exception {
            AddOrderItemRequest request = new AddOrderItemRequest("마라탕", null, 0L, 1);

            mockMvc.perform(post("/api/participations/" + memberParticipation.getId() + "/order-items")
                            .header("Authorization", bearer(member.getId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("마감된 방에는 메뉴를 담을 수 없다")
        void rejectsAfterClose() throws Exception {
            groupOrder.closeByHost(host.getId(), 2, 20_000);
            groupOrderRepository.saveAndFlush(groupOrder);

            mockMvc.perform(post("/api/participations/" + memberParticipation.getId() + "/order-items")
                            .header("Authorization", bearer(member.getId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest())))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("없는 참여에 담으려 하면 404다")
        void rejectsUnknownParticipation() throws Exception {
            mockMvc.perform(post("/api/participations/999999/order-items")
                            .header("Authorization", bearer(member.getId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("담은 메뉴를 빼면 204이고 행이 사라진다")
        void removesItem() throws Exception {
            OrderItem item = orderItemRepository.save(
                    memberParticipation.addItem(member.getId(), "마라탕", null, 9_000, 1));

            mockMvc.perform(delete("/api/order-items/" + item.getId())
                            .header("Authorization", bearer(member.getId())))
                    .andExpect(status().isNoContent());

            assertThat(orderItemRepository.findById(item.getId())).isEmpty();
        }

        @Test
        @DisplayName("남의 메뉴를 빼려 하면 409이고 메뉴는 남는다")
        void rejectsRemoveByOtherUser() throws Exception {
            OrderItem item = orderItemRepository.save(
                    memberParticipation.addItem(member.getId(), "마라탕", null, 9_000, 1));

            mockMvc.perform(delete("/api/order-items/" + item.getId())
                            .header("Authorization", bearer(host.getId())))
                    .andExpect(status().isConflict());

            assertThat(orderItemRepository.findById(item.getId())).isPresent();
        }

        @Test
        @DisplayName("없는 메뉴를 빼려 하면 404다")
        void rejectsRemoveUnknownItem() throws Exception {
            mockMvc.perform(delete("/api/order-items/999999")
                            .header("Authorization", bearer(member.getId())))
                    .andExpect(status().isNotFound());
        }
    }
}

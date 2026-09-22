package com.bandal.participation;

import com.bandal.TestcontainersConfiguration;
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
    GroupOrderRepository groupOrderRepository;

    @Autowired
    ParticipationRepository participationRepository;

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

    @BeforeEach
    void setUp() {
        orderItemRepository.deleteAll();
        participationRepository.deleteAll();
        groupOrderRepository.deleteAll();
        userRepository.deleteAll();
        pickupSpotRepository.deleteAll();
        universityRepository.deleteAll();

        university = universityRepository.save(new University("한국대학교", "hankuk.ac.kr"));
        pickupSpot = pickupSpotRepository.save(new PickupSpot(university, "제1기숙사 로비", null));
        host = userRepository.save(new User(university, "kim@hankuk.ac.kr", "hashed-password", "배고파"));
        member = userRepository.save(new User(university, "lee@hankuk.ac.kr", "hashed-password", "마라탕러버"));

        groupOrder = groupOrderRepository.save(new GroupOrder(
                host, pickupSpot, "○○마라탕", 15_000, Instant.now().plus(2, ChronoUnit.HOURS), 3));
        hostParticipation = participationRepository.save(new Participation(groupOrder, host, Instant.now()));
    }

    @Nested
    @DisplayName("참여")
    class Join {

        @Test
        @DisplayName("같은 학교 사람이 참여하면 201이다")
        void joins() throws Exception {
            mockMvc.perform(post("/api/group-orders/" + groupOrder.getId() + "/participations")
                            .header("X-User-Id", member.getId()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").isNumber())
                    .andExpect(jsonPath("$.groupOrderId").value(groupOrder.getId()))
                    .andExpect(jsonPath("$.userId").value(member.getId()));

            assertThat(participationRepository.countByGroupOrderId(groupOrder.getId())).isEqualTo(2);
        }

        @Test
        @DisplayName("같은 방에 두 번 참여하면 409다")
        void rejectsDuplicate() throws Exception {
            mockMvc.perform(post("/api/group-orders/" + groupOrder.getId() + "/participations")
                            .header("X-User-Id", member.getId()))
                    .andExpect(status().isCreated());

            mockMvc.perform(post("/api/group-orders/" + groupOrder.getId() + "/participations")
                            .header("X-User-Id", member.getId()))
                    .andExpect(status().isConflict());

            assertThat(participationRepository.countByGroupOrderId(groupOrder.getId())).isEqualTo(2);
        }

        @Test
        @DisplayName("정원이 다 찼으면 409다")
        void rejectsWhenFull() throws Exception {
            User third = userRepository.save(new User(university, "park@hankuk.ac.kr", "hashed-password", "꿔바로우"));
            User fourth = userRepository.save(new User(university, "choi@hankuk.ac.kr", "hashed-password", "탕수육"));
            participationRepository.save(new Participation(groupOrder, member, Instant.now()));
            participationRepository.save(new Participation(groupOrder, third, Instant.now()));

            // 정원 3명이 이미 찼다
            mockMvc.perform(post("/api/group-orders/" + groupOrder.getId() + "/participations")
                            .header("X-User-Id", fourth.getId()))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("다른 학교 사람이 참여하면 409다")
        void rejectsOtherUniversity() throws Exception {
            University otherUniversity = universityRepository.save(new University("민국대학교", "minguk.ac.kr"));
            User outsider = userRepository.save(
                    new User(otherUniversity, "park@minguk.ac.kr", "hashed-password", "외부인"));

            mockMvc.perform(post("/api/group-orders/" + groupOrder.getId() + "/participations")
                            .header("X-User-Id", outsider.getId()))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("마감 시각이 지난 방에는 참여할 수 없다")
        void rejectsAfterDeadline() throws Exception {
            GroupOrder late = groupOrderRepository.save(new GroupOrder(
                    host, pickupSpot, "△△치킨", 20_000, Instant.now().minusSeconds(60), 4));
            participationRepository.save(new Participation(late, host, Instant.now()));

            mockMvc.perform(post("/api/group-orders/" + late.getId() + "/participations")
                            .header("X-User-Id", member.getId()))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("마감된 방에는 참여할 수 없다")
        void rejectsClosedRoom() throws Exception {
            participationRepository.save(new Participation(groupOrder, member, Instant.now()));
            groupOrder.closeByHost(host.getId(), 2, 20_000);
            groupOrderRepository.saveAndFlush(groupOrder);

            User third = userRepository.save(new User(university, "park@hankuk.ac.kr", "hashed-password", "꿔바로우"));

            mockMvc.perform(post("/api/group-orders/" + groupOrder.getId() + "/participations")
                            .header("X-User-Id", third.getId()))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("없는 방에 참여하려 하면 404다")
        void rejectsUnknownGroupOrder() throws Exception {
            mockMvc.perform(post("/api/group-orders/999999/participations")
                            .header("X-User-Id", member.getId()))
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
                            .header("X-User-Id", member.getId())
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
                            .header("X-User-Id", host.getId())
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
                            .header("X-User-Id", member.getId())
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
                            .header("X-User-Id", member.getId())
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
                            .header("X-User-Id", member.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest())))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("없는 참여에 담으려 하면 404다")
        void rejectsUnknownParticipation() throws Exception {
            mockMvc.perform(post("/api/participations/999999/order-items")
                            .header("X-User-Id", member.getId())
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
                            .header("X-User-Id", member.getId()))
                    .andExpect(status().isNoContent());

            assertThat(orderItemRepository.findById(item.getId())).isEmpty();
        }

        @Test
        @DisplayName("남의 메뉴를 빼려 하면 409이고 메뉴는 남는다")
        void rejectsRemoveByOtherUser() throws Exception {
            OrderItem item = orderItemRepository.save(
                    memberParticipation.addItem(member.getId(), "마라탕", null, 9_000, 1));

            mockMvc.perform(delete("/api/order-items/" + item.getId())
                            .header("X-User-Id", host.getId()))
                    .andExpect(status().isConflict());

            assertThat(orderItemRepository.findById(item.getId())).isPresent();
        }

        @Test
        @DisplayName("없는 메뉴를 빼려 하면 404다")
        void rejectsRemoveUnknownItem() throws Exception {
            mockMvc.perform(delete("/api/order-items/999999")
                            .header("X-User-Id", member.getId()))
                    .andExpect(status().isNotFound());
        }
    }
}

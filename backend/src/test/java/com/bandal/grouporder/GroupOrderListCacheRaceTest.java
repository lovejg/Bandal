package com.bandal.grouporder;

import com.bandal.TestcontainersConfiguration;
import com.bandal.grouporder.dto.GroupOrderListResponse;
import com.bandal.participation.OrderItemRepository;
import com.bandal.participation.Participation;
import com.bandal.participation.ParticipationRepository;
import com.bandal.participation.ParticipationService;
import com.bandal.pickupspot.PickupSpot;
import com.bandal.pickupspot.PickupSpotRepository;
import com.bandal.settlement.SettlementRepository;
import com.bandal.university.University;
import com.bandal.university.UniversityRepository;
import com.bandal.user.User;
import com.bandal.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

// 지우고 나서 커밋하기 전, 그 사이에 다른 사람이 목록을 열면 생기는 일
// 1. A가 참여하고 캐시를 지운다 (아직 커밋 전)
// 2. B가 목록을 연다. 캐시가 없으니 DB를 읽는데, A의 참여는 커밋 전이라 안 보인다. 옛 인원을 캐시에 담는다
// 3. A가 커밋한다. DB는 새 인원인데 캐시는 옛 인원이고, 지울 사람이 없다
// 실제로는 이 틈이 아주 짧아 가끔만 난다. 여기서는 2를 1과 3 사이에 확실히 끼워 넣어 매번 만든다
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class GroupOrderListCacheRaceTest {

    @Autowired
    ParticipationService participationService;

    @Autowired
    GroupOrderService groupOrderService;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    StringRedisTemplate redisTemplate;

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

    University hankuk;
    User viewer;
    User joiner;
    GroupOrder room;

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

        hankuk = universityRepository.save(new University("한국대학교", "hankuk.ac.kr"));
        PickupSpot dorm = pickupSpotRepository.save(new PickupSpot(hankuk, "제1기숙사 로비", null));

        userSeq = 0;
        viewer = user("구경꾼");
        joiner = user("참여자");
        User host = user("방장");

        // 방장 혼자 있는 방. 인원 1
        room = groupOrderRepository.save(new GroupOrder(host, dorm, "○○마라탕", 15_000,
                Instant.now().plus(1, ChronoUnit.HOURS), 4));
        participationRepository.save(new Participation(room, host, Instant.now()));
    }

    User user(String nickname) {
        userSeq++;
        User user = new User(hankuk, "user" + userSeq + "@hankuk.ac.kr", "hashed-password", nickname);
        ReflectionTestUtils.setField(user, "emailVerifiedAt", Instant.now());
        ReflectionTestUtils.setField(user, "bankName", "한국은행");
        ReflectionTestUtils.setField(user, "accountNumber", "110123456789");
        ReflectionTestUtils.setField(user, "accountHolder", "김민수");
        return userRepository.save(user);
    }

    long participantCount(GroupOrderListResponse list) {
        return list.rooms().getFirst().participantCount();
    }

    // B는 다른 스레드다. 같은 스레드면 A의 트랜잭션 안에서 읽어서 커밋 전 참여가 보여 버린다
    GroupOrderListResponse openListAsAnotherUser() throws Exception {
        return CompletableFuture.supplyAsync(() -> groupOrderService.findList(viewer.getId(), 0, RoomListCache.CACHED_SIZE))
                .get();
    }

    @Test
    @DisplayName("지운 뒤 커밋 전에 다른 사람이 목록을 열어도, 커밋 뒤의 목록에는 새 인원이 보인다")
    void listShowsCommittedChange() throws Exception {
        // 처음 목록. 인원 1이 캐시에 담긴다
        assertThat(participantCount(groupOrderService.findList(viewer.getId(), 0, RoomListCache.CACHED_SIZE))).isEqualTo(1);

        // A의 트랜잭션. join의 @Transactional은 바깥 트랜잭션에 합류해서, 커밋은 이 블록이 끝날 때 한 번 일어난다
        transactionTemplate.executeWithoutResult(status -> {
            participationService.join(room.getId(), joiner.getId());

            try {
                // B: 커밋 전 DB를 읽는다. A의 참여는 안 보여서 인원 1이다
                assertThat(participantCount(openListAsAnotherUser())).isEqualTo(1);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        // 여기서 A가 커밋됐다

        String key = "rooms:" + hankuk.getId();
        assertThat(redisTemplate.hasKey(key))
                .as("커밋 전에 B가 담은 옛 목록(인원 1)이 남아 있으면 안 된다")
                .isFalse();
        assertThat(participantCount(groupOrderService.findList(viewer.getId(), 0, RoomListCache.CACHED_SIZE))).isEqualTo(2);
    }

    @Test
    @DisplayName("트랜잭션이 롤백되면 목록은 그대로다")
    void rollbackKeepsList() {
        assertThat(participantCount(groupOrderService.findList(viewer.getId(), 0, RoomListCache.CACHED_SIZE))).isEqualTo(1);

        transactionTemplate.executeWithoutResult(status -> {
            participationService.join(room.getId(), joiner.getId());
            status.setRollbackOnly();
        });

        assertThat(participantCount(groupOrderService.findList(viewer.getId(), 0, RoomListCache.CACHED_SIZE))).isEqualTo(1);
    }
}

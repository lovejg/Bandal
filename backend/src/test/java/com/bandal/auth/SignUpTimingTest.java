package com.bandal.auth;

import com.bandal.TestcontainersConfiguration;
import com.bandal.auth.dto.SignUpRequest;
import com.bandal.grouporder.GroupOrderRepository;
import com.bandal.outbox.OutboxMessageRepository;
import com.bandal.participation.OrderItemRepository;
import com.bandal.participation.ParticipationRepository;
import com.bandal.pickupspot.PickupSpotRepository;
import com.bandal.settlement.SettlementRepository;
import com.bandal.university.University;
import com.bandal.university.UniversityRepository;
import com.bandal.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 가입 응답 시간만 보고 "이미 가입된 주소"인지 알 수 있는가. 새 주소와 있는 주소로 번갈아 가입해 걸린 시간을 비교한다
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "management.health.mail.enabled=false")
class SignUpTimingTest {

    // 처음 몇 번은 JIT 컴파일과 커넥션 준비로 느리다. 재는 데서 뺀다
    static final int WARMUP = 5;
    static final int ROUNDS = 30;
    // 두 중앙값의 차이가 이보다 크면 시간으로 구별된다고 본다
    static final long LIMIT_MS = 20;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    UniversityRepository universityRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    OutboxMessageRepository outboxMessageRepository;

    @Autowired
    SettlementRepository settlementRepository;

    @Autowired
    OrderItemRepository orderItemRepository;

    @Autowired
    ParticipationRepository participationRepository;

    @Autowired
    GroupOrderRepository groupOrderRepository;

    @Autowired
    PickupSpotRepository pickupSpotRepository;

    @MockitoBean
    JavaMailSender mailSender;

    University hankuk;
    int seq = 0;

    @BeforeEach
    void setUp() {
        outboxMessageRepository.deleteAll();
        settlementRepository.deleteAll();
        orderItemRepository.deleteAll();
        participationRepository.deleteAll();
        groupOrderRepository.deleteAll();
        userRepository.deleteAll();
        pickupSpotRepository.deleteAll();
        universityRepository.deleteAll();

        hankuk = universityRepository.save(new University("한국대학교", "hankuk.ac.kr"));
    }

    // 가입 한 번에 걸린 시간(밀리초). 닉네임은 매번 새로 써서 닉네임 검사에 걸리지 않게 한다
    long timeSignUp(String email) throws Exception {
        String body = objectMapper.writeValueAsString(
                new SignUpRequest(email, "password1234", "닉네임" + (seq++), hankuk.getId()));
        long start = System.nanoTime();
        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());
        return (System.nanoTime() - start) / 1_000_000;
    }

    static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    @Test
    @DisplayName("새 주소로 가입할 때와 이미 있는 주소로 가입할 때 걸리는 시간이 비슷하다")
    void sameTimeForNewAndExisting() throws Exception {
        String existing = "taken@hankuk.ac.kr";
        timeSignUp(existing);

        for (int i = 0; i < WARMUP; i++) {
            timeSignUp("warm" + i + "@hankuk.ac.kr");
            timeSignUp(existing);
        }

        // 번갈아 잰다. 한쪽을 몰아서 재면 그 사이 기계 상태(GC 등)가 한쪽에만 섞인다
        List<Long> fresh = new ArrayList<>();
        List<Long> taken = new ArrayList<>();
        for (int i = 0; i < ROUNDS; i++) {
            fresh.add(timeSignUp("new" + i + "@hankuk.ac.kr"));
            taken.add(timeSignUp(existing));
        }

        long freshMedian = median(fresh);
        long takenMedian = median(taken);
        String report = "새 주소 중앙값 " + freshMedian + "ms " + fresh + " / 있는 주소 중앙값 " + takenMedian + "ms " + taken;
        System.out.println(report);

        assertThat(Math.abs(freshMedian - takenMedian)).as(report).isLessThan(LIMIT_MS);
    }
}

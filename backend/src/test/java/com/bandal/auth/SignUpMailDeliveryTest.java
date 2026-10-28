package com.bandal.auth;

import com.bandal.TestMails;
import com.bandal.TestcontainersConfiguration;
import com.bandal.auth.dto.SignUpRequest;
import jakarta.mail.internet.MimeMessage;
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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 가입 메일이 한 번 실패해도 결국 나가는가. 스케줄러를 켜고 진짜로 기다려 본다
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "management.health.mail.enabled=false",
        // 다른 테스트는 스케줄러를 끈다. 여기서는 다시 보내는 일꾼이 저절로 돌아야 한다
        "app.scheduling.enabled=true",
        // 운영 값(5초마다, 첫 재시도 30초 뒤)이면 테스트가 너무 오래 걸린다. 짧게 줄인다
        "app.outbox.poll-delay-ms=300",
        "app.outbox.retry-base-seconds=1"
})
class SignUpMailDeliveryTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    UniversityRepository universityRepository;

    @Autowired
    UserRepository userRepository;

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

    @Autowired
    OutboxMessageRepository outboxMessageRepository;

    @BeforeEach
    void setUp() {
        // 다른 테스트가 남긴 대기 줄을 지운다. 집배원이 돌면서 주인이 지워진 줄까지 찜하면 안 된다
        outboxMessageRepository.deleteAll();
        settlementRepository.deleteAll();
        orderItemRepository.deleteAll();
        participationRepository.deleteAll();
        groupOrderRepository.deleteAll();
        userRepository.deleteAll();
        pickupSpotRepository.deleteAll();
        universityRepository.deleteAll();

        hankuk = universityRepository.save(new University("한국대학교", "hankuk.ac.kr"));
        TestMails.stub(mailSender);
    }

    @Test
    @DisplayName("메일 서버가 잠깐 멈췄다가 살아나면 인증 메일은 결국 나간다")
    void deliversAfterMailServerRecovers() throws Exception {
        // 첫 발송은 실패하고(Resend가 멈춘 순간), 그 뒤로는 성공한다(살아났다)
        doThrow(new MailSendException("메일 서버가 멈췄다"))
                .doNothing()
                .when(mailSender).send(any(MimeMessage.class));

        SignUpRequest request = new SignUpRequest("lee@hankuk.ac.kr", "password1234", "마라탕러버", hankuk.getId());
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted());

        // 실패한 한 번 + 다시 보낸 한 번. 10초 안에 두 번째가 와야 한다
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender, timeout(10_000).times(2)).send(captor.capture());

        TestMails.SentMail delivered = TestMails.SentMail.of(captor.getAllValues().get(1));
        assertThat(delivered.getTo()).containsExactly("lee@hankuk.ac.kr");
        assertThat(delivered.getText()).contains("token=");
    }
}

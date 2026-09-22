package com.bandal.dev;

import com.bandal.pickupspot.PickupSpot;
import com.bandal.pickupspot.PickupSpotRepository;
import com.bandal.university.University;
import com.bandal.university.UniversityRepository;
import com.bandal.user.User;
import com.bandal.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// 로컬에서 손으로 호출해보려면 대학, 거점, 사용자가 먼저 있어야 한다.
// 아직 가입 API가 없어서 앱이 뜰 때 심어준다. dev 프로필에서만 돈다.
@Component
@Profile("dev")
@RequiredArgsConstructor
@Slf4j
public class DevDataSeeder implements ApplicationRunner {

    private final UniversityRepository universityRepository;
    private final PickupSpotRepository pickupSpotRepository;
    private final UserRepository userRepository;

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.count() > 0) {
            return;
        }

        University hankuk = universityRepository.save(new University("한국대학교", "hankuk.ac.kr"));
        University minguk = universityRepository.save(new University("민국대학교", "minguk.ac.kr"));

        PickupSpot spot = pickupSpotRepository.save(
                new PickupSpot(hankuk, "제1기숙사 로비", "정문 쪽 계단 옆"));

        // 비밀번호는 아직 쓰지 않는다. 가입 기능을 만들 때 진짜 해시로 바뀐다
        User host = userRepository.save(new User(hankuk, "kim@hankuk.ac.kr", "not-a-real-hash", "배고파"));
        User member = userRepository.save(new User(hankuk, "lee@hankuk.ac.kr", "not-a-real-hash", "마라탕러버"));
        User outsider = userRepository.save(new User(minguk, "park@minguk.ac.kr", "not-a-real-hash", "외부인"));

        log.info("""

                ===== 개발용 데이터 =====
                거점(pickupSpotId) : {}  한국대학교 제1기숙사 로비
                방장(X-User-Id)    : {}  배고파
                참여자(X-User-Id)  : {}  마라탕러버
                외부인(X-User-Id)  : {}  외부인 (민국대학교, 참여하면 409)
                ========================
                """, spot.getId(), host.getId(), member.getId(), outsider.getId());
    }
}

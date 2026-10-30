package com.bandal.user;

import com.bandal.auth.JwtProvider;
import com.bandal.auth.RefreshTokenStore;
import com.bandal.auth.dto.TokenResponse;
import com.bandal.grouporder.CancelType;
import com.bandal.grouporder.GroupOrderRepository;
import com.bandal.grouporder.GroupOrderStatus;
import com.bandal.notification.NotificationRepository;
import com.bandal.outbox.OutboxMessageRepository;
import com.bandal.settlement.SettlementRepository;
import com.bandal.trust.TrustScoreRepository;
import com.bandal.user.dto.TrustScoreChangeResponse;
import com.bandal.user.dto.ChangeNicknameRequest;
import com.bandal.user.dto.ChangePasswordRequest;
import com.bandal.user.dto.RegisterAccountRequest;
import com.bandal.user.dto.WithdrawRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// 사용자 프로필을 고친다. 송금받을 계좌, 비밀번호, 닉네임, 탈퇴
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final GroupOrderRepository groupOrderRepository;
    private final SettlementRepository settlementRepository;
    private final NotificationRepository notificationRepository;
    private final OutboxMessageRepository outboxMessageRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final RefreshTokenStore refreshTokenStore;
    private final WithdrawnEmailHasher withdrawnEmailHasher;
    private final TrustScoreRepository trustScoreRepository;

    // 내 신뢰도 점수의 최근 변동(최대 20줄). 내 정보 화면이 "왜 바뀌었나"를 보여 준다
    @Transactional(readOnly = true)
    public List<TrustScoreChangeResponse> trustScoreChanges(Long userId) {
        return trustScoreRepository.findTop20ByUserIdOrderByIdDesc(userId).stream()
                .map(TrustScoreChangeResponse::of)
                .toList();
    }

    // 계좌 등록 및 변경
    @Transactional
    public void registerAccount(Long userId, RegisterAccountRequest request) {
        User user = userRepository.findByIdOrThrow(userId);
        if(groupOrderRepository.existsByHostIdAndStatus(userId, GroupOrderStatus.SETTLING)) {
            throw new IllegalStateException("정산 중인 방이 있어 계좌를 수정할 수 없습니다");
        }
        user.registerAccount(request.bankName(), request.accountNumber(), request.accountHolder());
    }

    // 비밀번호 변경. 다른 기기의 로그인을 끊고, 지금 기기에는 새 토큰 두 개를 돌려준다
    //
    // 현재 비밀번호가 틀리면 400이다. 401이 아닌 이유: 요청 자체는 로그인한 사람이 보냈다. 401로 답하면 화면이
    // "로그인이 풀렸다"로 읽고 토큰 갱신이나 로그인 화면으로 보낸다
    //
    // 순서가 중요하다. 버전을 먼저 올리고(revokeAll) 그 뒤에 새 토큰을 발급해야 새 토큰에 올린 버전이 적힌다.
    // 거꾸로 하면 방금 준 토큰까지 무효가 되어 이 기기도 15분 뒤 로그아웃된다
    //
    // Redis(버전)는 DB 트랜잭션 밖이다. DB 저장이 실패해 롤백되면 비밀번호는 그대로인데 다른 기기만 로그아웃된다.
    // 안전한 쪽으로 틀리는 거라 받아들인다(반대로 비밀번호만 바뀌고 다른 기기가 남는 쪽이 위험하다)
    @Transactional
    public TokenResponse changePassword(Long userId, ChangePasswordRequest request) {
        User user = userRepository.findByIdOrThrow(userId);

        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            throw new IllegalArgumentException("현재 비밀번호가 맞지 않습니다");
        }

        // 저장은 해시로. 바뀐 값은 트랜잭션이 끝날 때 UPDATE로 나간다(변경 감지)
        user.changePassword(passwordEncoder.encode(request.newPassword()));

        refreshTokenStore.revokeAll(userId);
        return new TokenResponse(jwtProvider.createAccessToken(userId), refreshTokenStore.issue(userId));
    }

    // 닉네임 변경
    // 아직 할 일이 남은 방(hasUnfinishedBusiness)이 있으면 못 바꾼다. 같은 방 사람들은 닉네임으로 서로를 알아본다
    // (방장이 담은 메뉴를 확인할 때, 거점에서 음식을 나눠 줄 때, 환불을 주고받을 때). 계좌 변경을 정산중에 막는 것과 같은 생각이다
    @Transactional
    public void changeNickname(Long userId, ChangeNicknameRequest request) {
        User user = userRepository.findByIdOrThrow(userId);
        String nickname = request.nickname().trim();

        if (hasUnfinishedBusiness(userId)) {
            throw new IllegalStateException("진행 중인 방이나 끝나지 않은 환불이 있어 닉네임을 바꿀 수 없습니다. 끝난 뒤에 바꿔주세요");
        }
        // 지금 내 닉네임이면 existsByNickname이 나 자신을 찾아 "이미 쓰인다"고 답한다. 그 경우는 넘기고
        // 엔티티가 "지금과 같다"(400)로 답하게 한다
        // 먼저 물어보는 건 대부분의 경우를 빨리 안내하려는 것이다. 동시에 같은 이름을 노리는 두 요청은 둘 다 여기를 통과할 수 있다
        if (!nickname.equals(user.getNickname()) && userRepository.existsByNickname(nickname)) {
            throw new IllegalStateException(NicknameConflict.MESSAGE);
        }

        user.changeNickname(nickname, Instant.now());

        // 변경 감지로 나가는 UPDATE는 원래 트랜잭션이 끝날 때 나간다. 그때 난 예외는 이 메서드 밖이라 잡을 수 없다.
        // 그래서 여기서 직접 내보내(flush) unique 제약에 걸리면 이 자리에서 409로 바꾼다
        try {
            userRepository.flush();
        } catch (DataIntegrityViolationException e) {
            if (NicknameConflict.isCause(e)) throw new IllegalStateException(NicknameConflict.MESSAGE);
            throw e;
        }
    }

    // 탈퇴. 줄은 지우지 않고 개인정보만 지운다(익명화)
    // 줄을 지우면 다른 사람의 지난 정산표에서 이 사람 줄이 빠져 합계가 안 맞고, 이 사람이 방장이던 방은 방장 칸이 빈다.
    // 그래서 줄은 남기고 메일, 닉네임, 계좌, 실명을 지운다. 남에게는 "탈퇴한 사용자"로 보인다(User.displayName)
    //
    // 비밀번호를 다시 받는다. 열려 있는 남의 기기에서 계정을 없애지 못하게(비밀번호 변경과 같은 이유). 틀리면 400
    // 아직 할 일이 남았으면 409. 돈을 주고받을 상대가 사라지면 안 된다
    @Transactional
    public void withdraw(Long userId, WithdrawRequest request) {
        User user = userRepository.findByIdOrThrow(userId);

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new IllegalArgumentException("비밀번호가 맞지 않습니다");
        }
        if (hasUnfinishedBusiness(userId)) {
            throw new IllegalStateException("진행 중인 방이나 끝나지 않은 환불이 있어 탈퇴할 수 없습니다. 끝난 뒤에 다시 시도해주세요");
        }

        // 비밀번호 칸은 비울 수 없다. 아무도 모르는 난수를 해시해 넣는다(평문은 어디에도 남지 않는다)
        // 메일 해시는 점수가 낮을 때만 엔티티가 남긴다. 메일을 지우기 전에 미리 만들어 넘긴다
        user.withdraw(passwordEncoder.encode(UUID.randomUUID().toString()),
                withdrawnEmailHasher.hash(user.getEmail()), Instant.now());

        // 받을 사람이 없는 알림, 보낼 주소가 없는 메일을 지운다
        notificationRepository.deleteByUserId(userId);
        outboxMessageRepository.deletePendingMailFor(userId);

        // 모든 기기의 리프레시 토큰을 무효로. 남은 액세스 토큰은 JwtAuthenticationFilter가 탈퇴자를 비로그인으로 떨어뜨려 막는다
        // Redis는 DB 트랜잭션 밖이라, DB가 롤백되면 탈퇴는 안 됐는데 로그아웃만 된다. 다시 로그인하면 되니 안전한 쪽이다
        refreshTokenStore.revokeAll(userId);
    }

    // 아직 할 일이 남았나. 닉네임 변경과 탈퇴를 막는 같은 기준이다
    // 1) 끝나지 않은 방(배달완료, 취소가 아닌 방)의 방장이거나 참여 중
    // 2) 정산중에 취소된 방에서 내가 받을 환불이나 내가 방장으로 돌려줄 환불이 대기, 보냄으로 남음
    //    방 상태는 "취소"라 1)로는 안 잡힌다. 그래서 따로 본다
    private boolean hasUnfinishedBusiness(Long userId) {
        return groupOrderRepository.existsUnfinishedFor(userId, FINISHED)
                || settlementRepository.existsUnresolvedRefundFor(userId, SETTLING_CANCELS);
    }

    // 끝난 방의 상태
    private static final List<GroupOrderStatus> FINISHED = List.of(GroupOrderStatus.DELIVERED, GroupOrderStatus.CANCELED);

    // 정산중에 취소된 방의 취소 종류(환불이 생길 수 있는 방). GroupOrder.isCanceledWhileSettling과 같은 목록이다
    private static final List<CancelType> SETTLING_CANCELS = List.of(CancelType.HOST_WHILE_SETTLING, CancelType.SETTLING_TIMEOUT);
}

package com.bandal.user;

import com.bandal.university.University;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;

// 가입한 학교 이메일로 소속 대학이 정해진다.
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "university_id", nullable = false)
    private University university;

    // 메일과 닉네임은 탈퇴하면 null이 된다. 탈퇴하지 않은 사용자는 늘 값이 있다(가입이 받는다)
    @Column(unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(unique = true)
    private String nickname;

    // 탈퇴한 시각. 값이 있으면 탈퇴한 사용자다. 줄은 지난 방과 정산표가 가리켜서 남긴다
    private Instant deletedAt;

    // 닉네임을 마지막으로 바꾼 시각. 가입 때 정한 건 바꾼 게 아니라 null이다
    private Instant nicknameChangedAt;

    private Instant emailVerifiedAt;

    @Column(nullable = false)
    private int trustScore;

    // 송금받을 계좌정보. 방장이 되려면 있어야 하고, 참여만 할 사람은 없어도 된다
    private String bankName;
    private String accountNumber;
    // 예금주 이름. 실명이라 밖으로 나갈 때는 마스킹한다
    private String accountHolder;

    public User(University university, String email, String password, String nickname) {
        this.university = university;
        this.email = email;
        this.password = password;
        this.nickname = nickname;
        this.trustScore = 50;
    }

    public void verifyEmail(Instant now) {
        if(this.emailVerifiedAt != null) return;
        this.emailVerifiedAt = now;
    }

    public boolean isEmailVerified() {
        return this.emailVerifiedAt != null;
    }

    public static final String WITHDRAWN_NAME = "탈퇴한 사용자";

    public boolean isDeleted() {
        return this.deletedAt != null;
    }

    // 남에게 보이는 이름. 방 목록, 참여자 명단, 정산표가 쓴다. 탈퇴했으면 닉네임이 비어 있어서 "탈퇴한 사용자"
    public String displayName() {
        return isDeleted() ? WITHDRAWN_NAME : this.nickname;
    }

    // 탈퇴. 줄은 남기고 사람을 알아볼 수 있는 값을 모두 지운다
    // 남는 것: id(지난 방과 정산표가 가리킨다), 소속 학교, 신뢰도 점수
    // 비밀번호는 칸을 비울 수 없어서, 아무도 모르는 값을 해시해 넣은 걸 받는다. 메일도 비워서 어차피 로그인할 길이 없지만 한 겹 더 막는다
    // 끝나지 않은 방이나 남은 환불이 있는지는 다른 표를 봐야 해서 서비스가 먼저 확인한다
    public void withdraw(String unusablePasswordHash, Instant now) {
        if (isDeleted()) return;
        this.email = null;
        this.nickname = null;
        this.nicknameChangedAt = null;
        this.password = unusablePasswordHash;
        this.bankName = null;
        this.accountNumber = null;
        this.accountHolder = null;
        this.deletedAt = now;
    }

    // 닉네임은 7일에 한 번 바꿀 수 있다. 자주 바뀌면 같은 방 사람들이 누가 누군지 헷갈린다
    public static final Duration NICKNAME_CHANGE_INTERVAL = Duration.ofDays(7);

    // 닉네임을 바꾼다. 7일 규칙은 이 사용자 자신의 값(마지막으로 바꾼 시각)만 보면 되니 엔티티가 지킨다
    // 다른 사람이 쓰는지, 진행 중인 방이 있는지는 DB를 봐야 해서 서비스가 먼저 확인한다
    // 지금 시각을 밖에서 받는다. 테스트가 "8일 뒤"를 만들 수 있게(Instant.now()를 안에서 부르면 시간을 못 바꾼다)
    public void changeNickname(String newNickname, Instant now) {
        if (newNickname == null || newNickname.isBlank()) {
            throw new IllegalArgumentException("닉네임을 적어주세요");
        }
        // 같은 이름으로 "바꾸면" 7일 기회만 날아간다. 바꾼 게 아니라고 알려 준다
        if (newNickname.equals(this.nickname)) {
            throw new IllegalArgumentException("지금 쓰는 닉네임과 같습니다");
        }
        if (nicknameChangedAt != null && now.isBefore(nicknameChangedAt.plus(NICKNAME_CHANGE_INTERVAL))) {
            throw new IllegalStateException("닉네임은 7일에 한 번 바꿀 수 있습니다");
        }
        this.nickname = newNickname;
        this.nicknameChangedAt = now;
    }

    // 비밀번호를 바꾼다. 받는 값은 이미 해시한 값이다. 해시는 PasswordEncoder가 하고, 엔티티는 평문을 모른다
    // 현재 비밀번호 확인은 서비스가 한다. 대조에도 PasswordEncoder가 필요해서다
    public void changePassword(String encodedPassword) {
        if (encodedPassword == null || encodedPassword.isBlank()) {
            throw new IllegalArgumentException("비밀번호가 비어 있습니다");
        }
        this.password = encodedPassword;
    }

    // 송금받을 계좌를 등록한다. 다시 부르면 덮어쓴다(계좌를 바꿀 수 있어야 한다)
    public void registerAccount(String bankName, String accountNumber, String accountHolder) {
        if(bankName == null || bankName.isBlank()) {
            throw new IllegalArgumentException("잘못된 은행정보입니다. 다시 입력해주세요");
        }
        if(accountNumber == null) throw new IllegalArgumentException("잘못된 계좌번호입니다. 다시 입력해주세요");
        String tempNumber = accountNumber.replaceAll("[^0-9]", ""); // - 및 공백 제거
        if(tempNumber.isBlank()) throw new IllegalArgumentException("잘못된 계좌번호입니다. 다시 입력해주세요");
        if(accountHolder == null || accountHolder.isBlank()) {
            throw new IllegalArgumentException("잘못된 예금주 정보입니다. 다시 입력해주세요");
        }
        this.bankName = bankName;
        this.accountNumber = tempNumber;
        this.accountHolder = accountHolder;
    }

    // 계좌가 등록돼 있는가. 해당 유저가 방을 만들 수 있는지 판단할 때 쓴다
    public boolean hasAccount() {
        return bankName != null && accountNumber != null;
    }

    // 예금주 이름을 마스킹한다. 박민수 -> 박*수(첫 글자와 끝 글자만 표시)
    // 참여자가 은행 앱에 뜬 예금주와 대조해 계좌번호 오타를 잡을 수 있을 만큼만 보여준다.
    public String maskedAccountHolder() {
        String name = this.accountHolder;
        int length = name.length();

        if(length == 1) {
            return "*";
        }

        if(length == 2) {
            return name.charAt(0) + "*";
        }

        return name.charAt(0) + "*".repeat(length - 2) + name.charAt(length - 1);
    }
}

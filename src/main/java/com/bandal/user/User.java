package com.bandal.user;

import com.bandal.university.University;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

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

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false, unique = true)
    private String nickname;

    private Instant emailVerifiedAt;

    @Column(nullable = false)
    private int trustScore;

    // 송금받을 계좌. 방장이 되려면 있어야 하고, 참여만 할 사람은 없어도 된다 (ADR-031)
    // 지금은 평문으로 저장한다. 암호화는 Phase 2 배포에서 시크릿 관리와 같이 다룬다
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

    // 메일 속 링크를 눌렀다. 이 시각부터 이 주소의 주인임이 확인된 것으로 본다
    public void verifyEmail(Instant now) {
        if(this.emailVerifiedAt != null) return;
        this.emailVerifiedAt = now;
    }

    public boolean isEmailVerified() {
        return this.emailVerifiedAt != null;
    }

    // 송금받을 계좌를 등록한다. 다시 부르면 덮어쓴다(계좌를 바꿀 수 있어야 한다)
    // TODO: 세 값을 채운다. 셋 다 비어 있으면 안 된다.
    //  계좌번호에 하이픈이나 공백이 섞여 들어올 수 있다. 어떻게 할지 정하자 -
    //  들어온 대로 두면 "110-123-456"과 "110123456"이 다른 값으로 저장된다
    public void registerAccount(String bankName, String accountNumber, String accountHolder) {
    }

    // 계좌가 등록돼 있는가. 방을 만들 수 있는지 판단할 때 쓴다
    public boolean hasAccount() {
        return bankName != null && accountNumber != null;
    }

    // 예금주 이름을 마스킹한다. 박민수 -> 박○○
    // 참여자가 은행 앱에 뜬 예금주와 대조해 계좌번호 오타를 잡을 수 있을 만큼만 보여준다.
    // 실명을 그대로 내보내면 닉네임 뒤의 사람이 특정된다 (ADR-031)
    // TODO: 첫 글자만 남기고 나머지를 ○로 바꾼다. 계좌가 없으면 null을 돌려준다.
    //  한 글자 이름이나 외국 이름처럼 예외적인 경우도 터지지 않아야 한다
    public String maskedAccountHolder() {
        return null;
    }
}

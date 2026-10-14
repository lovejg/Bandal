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

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
}

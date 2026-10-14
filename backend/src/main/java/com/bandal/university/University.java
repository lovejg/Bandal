package com.bandal.university;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class University {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @Column(nullable = false, unique = true)
    private String emailDomain;

    public University(String name, String emailDomain) {
        this.name = name;
        this.emailDomain = emailDomain;
    }
}

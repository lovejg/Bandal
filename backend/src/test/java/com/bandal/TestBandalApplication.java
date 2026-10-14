package com.bandal;

import org.springframework.boot.SpringApplication;

public class TestBandalApplication {

    public static void main(String[] args) {
        SpringApplication.from(BandalApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}

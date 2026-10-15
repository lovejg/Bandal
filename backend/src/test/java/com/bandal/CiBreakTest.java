package com.bandal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// CI가 깨진 PR을 막는지 보려고 일부러 실패시킨다 (ADR-057). 확인하면 지운다
class CiBreakTest {

    @Test
    void 일부러_실패한다() {
        assertThat(1 + 1).isEqualTo(3);
    }

}

package com.bandal.pickupspot;

import com.bandal.university.University;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// DB 없이 PickupSpot 생성자의 규칙만 확인한다 (ADR-036)
class PickupSpotTest {

    University university;

    @BeforeEach
    void setUp() {
        university = new University("한국대학교", "hankuk.ac.kr");
    }

    @Nested
    @DisplayName("만들 수 있는 거점")
    class Valid {

        @Test
        @DisplayName("이름과 설명으로 만들 수 있다")
        void createsWithNameAndDescription() {
            PickupSpot spot = new PickupSpot(university, "공대 7호관 앞", "정문 쪽 벤치");

            assertThat(spot.getName()).isEqualTo("공대 7호관 앞");
            assertThat(spot.getDescription()).isEqualTo("정문 쪽 벤치");
            assertThat(spot.getUniversity()).isSameAs(university);
        }

        @Test
        @DisplayName("설명은 없어도 된다")
        void descriptionIsOptional() {
            assertThatCode(() -> new PickupSpot(university, "공대 7호관 앞", null))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("이름은 30자까지 된다")
        void allowsThirtyCharacterName() {
            assertThatCode(() -> new PickupSpot(university, "가".repeat(30), null))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("설명은 100자까지 된다")
        void allowsHundredCharacterDescription() {
            assertThatCode(() -> new PickupSpot(university, "공대 7호관 앞", "가".repeat(100)))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("만들 수 없는 거점")
    class Invalid {

        @Test
        @DisplayName("학교가 없으면 만들 수 없다")
        void rejectsNullUniversity() {
            assertThatThrownBy(() -> new PickupSpot(null, "공대 7호관 앞", null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("이름이 없으면 만들 수 없다")
        void rejectsNullName() {
            assertThatThrownBy(() -> new PickupSpot(university, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("이름이 공백뿐이면 만들 수 없다")
        void rejectsBlankName() {
            assertThatThrownBy(() -> new PickupSpot(university, "   ", null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("이름이 특수기호뿐이면 만들 수 없다")
        void rejectsSymbolOnlyName() {
            // 비교용 이름이 빈 문자열이 된다. 이런 거점은 목록에서 알아볼 수도 없다
            assertThatThrownBy(() -> new PickupSpot(university, "!!! ~~~", null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("이름이 31자면 만들 수 없다")
        void rejectsTooLongName() {
            assertThatThrownBy(() -> new PickupSpot(university, "가".repeat(31), null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("설명이 101자면 만들 수 없다")
        void rejectsTooLongDescription() {
            assertThatThrownBy(() -> new PickupSpot(university, "공대 7호관 앞", "가".repeat(101)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("비교용 이름")
    class NormalizedName {

        @Test
        @DisplayName("공백을 뺀다")
        void removesSpaces() {
            PickupSpot spot = new PickupSpot(university, "공대 7호관 앞", null);

            assertThat(spot.getNormalizedName()).isEqualTo("공대7호관앞");
        }

        @Test
        @DisplayName("특수기호를 뺀다")
        void removesSymbols() {
            PickupSpot spot = new PickupSpot(university, "공대-7호관 (앞)!", null);

            assertThat(spot.getNormalizedName()).isEqualTo("공대7호관앞");
        }

        @Test
        @DisplayName("영문은 소문자로 바꾼다")
        void lowercasesLatin() {
            PickupSpot spot = new PickupSpot(university, "CU 앞", null);

            assertThat(spot.getNormalizedName()).isEqualTo("cu앞");
        }

        @Test
        @DisplayName("띄어쓰기만 다른 두 이름은 비교용 이름이 같다")
        void sameAfterNormalizing() {
            PickupSpot a = new PickupSpot(university, "공대 7호관 앞", null);
            PickupSpot b = new PickupSpot(university, "공대7호관  앞", null);

            assertThat(a.getNormalizedName()).isEqualTo(b.getNormalizedName());
        }

        @Test
        @DisplayName("화면에 보여줄 이름은 사용자가 쓴 그대로 둔다")
        void keepsOriginalName() {
            PickupSpot spot = new PickupSpot(university, "CU 앞", null);

            assertThat(spot.getName()).isEqualTo("CU 앞");
        }
    }
}

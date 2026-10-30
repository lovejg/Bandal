package com.bandal.grouporder;

// 음식 카테고리. 배달의민족 앱의 분류를 따른다(사용자가 정함). 이름 그대로 DB에 저장하고, 화면 문구는 화면이 정한다
// 고정 목록(enum)으로 둔 이유: 거의 바뀌지 않고, 바뀌면 마이그레이션 하나(check 제약)로 끝난다. 표로 두면 관리 화면까지 필요해진다
// 겹치는 경우(치킨이면서 야식)가 있어서 방 하나에 여러 개(최대 3개)를 고른다
public enum FoodCategory {
    CHICKEN,          // 치킨
    FAST_FOOD,        // 패스트푸드
    JJIM_TANG,        // 찜/탕
    BUNSIK,           // 분식
    CHINESE,          // 중식
    KOREAN,           // 한식
    PIZZA,            // 피자
    CUTLET_SASHIMI,   // 돈까스/회
    CAFE_DESSERT,     // 카페/디저트
    MEAT,             // 고기
    JOKBAL_BOSSAM,    // 족발/보쌈
    WESTERN,          // 양식
    LUNCHBOX,         // 도시락
    ASIAN,            // 아시안
    LATE_NIGHT        // 야식
}

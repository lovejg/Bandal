package com.bandal.notification.dto;

// 종 아이콘의 빨간 숫자. 0이면 화면이 숫자를 숨긴다
// 숫자 하나지만 객체({"count": 3})로 감싼다. 나중에 칸을 더해도 화면 코드가 안 깨진다
public record UnreadCountResponse(long count) {
}

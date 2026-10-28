package com.bandal.notification.dto;

import jakarta.validation.constraints.NotNull;

// 모두 읽음 요청 본문. upToId는 화면이 본 가장 최신 알림 id(목록 첫 줄의 id)
// 목록을 본 뒤에 들어온 알림은 id가 더 커서 안 읽음으로 남는다
// 비어 있으면 "어디까지"를 알 수 없어서 @NotNull로 막는다(컨트롤러의 @Valid가 검사해 400)
public record ReadAllRequest(@NotNull Long upToId) {
}

# Bandal

배달 최소주문금액을 같은 학교 사람끼리 나눠 채우는 공동구매 서비스.

1인분만 먹고 싶은데 가게의 최소주문금액이 15,000원이면, 먹지도 않을 메뉴를 억지로 더 담게 된다.
Bandal에서는 같은 수령 장소(기숙사 로비, 건물 앞)에 있는 사람들이 한 방에 모여 각자 1인분씩 담는다.
방장이 배달앱에서 대표로 주문하고, Bandal은 모집과 메뉴 취합, 정산 계산을 맡는다.

## 흐름

```
모집중 -> 마감 -> 정산중 -> 주문완료 -> 배달완료
  |        |        |
  +--------+--------+--> 취소 (정산중 취소는 환불을 끝까지 추적)
```

- 학교 이메일로만 가입한다. 가입이 곧 소속 확인이다.
- 배달비는 무조건 균등 분담. 참여자가 낼 돈 = 내 메뉴 합계 + 배달비 / 인원.
- 송금이 주문보다 먼저다. 전원의 입금이 확인돼야 방장이 결제한다. 앱은 돈을 직접 옮기지 않는다.
- 방장이 사라지면 스케줄러가 멈춘 방을 마감, 취소, 배달완료로 넘긴다.

## 구조

```
backend/    Spring Boot 4.1 · Java 21 · PostgreSQL · Redis
frontend/   React 18 · Vite · TypeScript
docs/       DECISIONS.md (설계 결정과 버린 대안), JOURNAL.md (실제로 겪은 문제)
DESIGN.md   무엇을 왜 만드는지, 도메인 모델, 상태 흐름, 단계별 계획
docker-compose.yml   로컬 인프라 (PostgreSQL, Redis, Mailpit)
```

## 로컬 실행

```bash
docker compose up -d                       # PostgreSQL 5433, Redis 6380, Mailpit 8025

cd backend && ./gradlew bootRun            # http://localhost:8080 (dev 프로필, 개발용 데이터가 들어간다)

cd frontend && npm install && npm run dev  # http://localhost:5173
```

개발용 계정은 비밀번호가 모두 `password1234`다.

| 이메일 | 설명 |
| --- | --- |
| kim@hankuk.ac.kr | 계좌가 등록돼 있어 바로 방장이 될 수 있다 |
| lee@hankuk.ac.kr | 같은 학교 참여자 |
| park@minguk.ac.kr | 다른 학교. 위 두 사람의 방이 보이지 않는다 |

테스트는 `cd backend && ./gradlew test`. Testcontainers가 PostgreSQL과 Redis를 따로 띄우므로 Docker만 켜져 있으면 된다.

## 진행 상황

Phase 1(도메인 코어와 REST API)과 최소 화면까지 끝났다. 다음은 CI, 그다음 Phase 3(동시성 재현과 해결)이다.
전체 순서는 [DESIGN.md 7번](DESIGN.md#7-단계별로-만들-것)에 있다.

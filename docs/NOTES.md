# 개념 노트

작업하다 배운 개념을 모아 둔다. 왜 그렇게 정했는지는 `DECISIONS.md`, 실제로 겪은 문제는 `JOURNAL.md`에 있다.
여기는 "그게 원래 어떻게 동작하는가"만 적는다. 면접 전에 다시 읽는 용도.

---

## 트랜잭션과 동시성

### 확인 → 행동 사이에 끼어들기 (check-then-act)

"자리 있나? → 있으면 넣는다", "같은 이름 있나? → 없으면 만든다"처럼 확인하고 행동하는 코드는, 확인과 행동 사이에 다른
요청이 끼어들 수 있다. 둘이 거의 같은 순간에 확인하면 둘 다 "괜찮다"를 보고 둘 다 행동한다.
확인 자체는 틀리지 않았다. 확인한 순간에는 정말로 자리가 있었고, 정말로 같은 이름이 없었다. 확인한 뒤 행동하기 전에
상황이 바뀐 것이다. 이 프로젝트에서 본 것: 정원 초과, 같은 이름 거점, 리프레시 토큰 재사용.

### 커밋 전의 행은 남에게 안 보인다 (READ COMMITTED)

PostgreSQL의 기본 격리 수준은 READ COMMITTED다. SELECT는 그 순간 **커밋된** 행만 본다. 다른 트랜잭션이 INSERT만 하고
아직 커밋하지 않은 행은 안 보인다. 그래서 A가 거점을 INSERT한 직후라도 커밋 전이면 B의 `exists`는 "없다"를 본다.

### 비관적 락은 같은 행을 잠그려는 쪽끼리 줄 세운다

`@Lock(PESSIMISTIC_WRITE)` 조회는 `SELECT ... FOR UPDATE`(PostgreSQL에서는 `FOR NO KEY UPDATE`)가 된다. 행에 자물쇠를
걸고, 커밋하거나 롤백할 때 푼다.
- 같은 행을 잠그려는 SELECT와 그 행을 바꾸는 UPDATE는 기다린다.
- 그냥 SELECT는 안 기다린다. 마지막으로 커밋된 값을 읽는다(MVCC: 바뀌는 중인 행의 옛 버전을 보여 준다).
- 그래서 그 행을 바꾸거나, 그 행을 보고 판단해서 무언가를 바꾸는 곳은 전부 잠가야 한다. 보여 주기만 하는 조회는 안 잠근다.
- 잠금은 그 메서드의 맨 처음 조회에서 건다. JPA는 한 트랜잭션 안에서 같은 엔티티를 1차 캐시에서 다시 주므로, 먼저 잠금
  없이 읽어 두면 나중에 잠가도 옛 값을 들고 있게 된다.

### 없는 행은 잠글 수 없다

`FOR UPDATE`는 찾은 행에만 걸린다. 두 요청이 아직 만들어지지 않은 행(같은 이름의 새 거점)을 두고 겹치면, 둘 다 행 0개를
잠그고 그냥 지나간다. 막으려면 이미 있는 다른 행(학교 행)을 잠그거나, DB 유니크 제약에 맡겨야 한다.

---

## DB 제약과 예외

### 유니크 제약

"이 칸(들)의 값이 같은 행은 하나만"이라는 DB 규칙. `@Table(uniqueConstraints = ...)`로 건다. 이름을 안 붙이면 PostgreSQL이
`테이블_칸_칸_key`로 지어 준다(예: `pickup_spot_university_id_normalized_name_key`). 서비스의 확인이 동시에 뚫려도 DB가
마지막에 막아 주므로 데이터는 깨지지 않는다.

에러 문장 `duplicate key value violates unique constraint "..."` = 넣으려는 값이 이미 있는 값과 겹쳐서 규칙을 어긴다.
INSERT가 같은 값을 커밋 대기 중인 다른 트랜잭션과 부딪치면, 먼저 그 트랜잭션이 끝날 때까지 기다렸다가 거절당한다.

### DataIntegrityViolationException

DB 에러는 올라오면서 이름이 바뀐다.

```
PostgreSQL 에러 → JDBC의 PSQLException → Hibernate의 ConstraintViolationException
  → 스프링의 DataIntegrityViolationException
```

스프링은 DB마다 다른 에러를 공통 이름으로 바꿔 준다. 이 이름은 유니크, 외래 키, NOT NULL 위반을 **전부** 포함한다.
그래서 전역 핸들러에서 통째로 409로 바꾸면 외래 키 위반 같은 우리 코드의 버그도 409로 묻힌다. 바꿀 거면 그 제약이 걸릴 수
있는 한 줄만 감싸서 바꾼다(ADR-060).

### 제약 위반 뒤의 트랜잭션은 망가진다

PostgreSQL은 트랜잭션 안에서 에러가 한 번 나면 그 트랜잭션을 망가진 상태로 둔다. 그 뒤로 쿼리를 보내면
`current transaction is aborted, commands ignored until end of transaction block`이 난다. 그래서 제약 위반을 catch한 뒤에는
DB를 더 부르지 말고 바로 던지고 끝낸다.

---

## JPA

### save는 INSERT를 바로 보내지 않을 수 있다 (flush)

JPA는 `save`한 엔티티를 먼저 영속성 컨텍스트(1차 캐시)에 담아 두고, 실제 SQL은 **flush** 때 몰아서 보낸다.
flush는 보통 트랜잭션이 커밋될 때 일어난다. `@Transactional` 메서드의 커밋은 메서드가 끝난 뒤 프록시가 한다.

그래서 `try { save(...) } catch (DataIntegrityViolationException e)`는 잡지 못할 수 있다. INSERT가 메서드가 끝난 뒤 커밋 때
나가고, 예외도 그때 try 바깥에서 나기 때문이다.

### IDENTITY일 때만 save가 바로 INSERT한다

`GenerationType.IDENTITY`는 id를 DB가 INSERT할 때 만들어 준다. JPA는 엔티티를 담아 두려면 id를 알아야 하므로,
IDENTITY일 때만은 `save` 줄에서 INSERT를 바로 보낸다. `SEQUENCE`로 바꾸면 시퀀스에서 번호만 받고 INSERT는 커밋 때로 미룬다.

### saveAndFlush

`save` + 그 자리에서 flush. "INSERT를 지금 이 줄에서 보내라"를 코드에 적는다. id 방식이 뭐든 예외가 그 줄에서 나오므로
try-catch가 확실히 잡는다. 지금은 IDENTITY라 `save`로도 잡히지만, 그건 엔티티의 id 한 줄에 기대는 것이라 엔티티를 바꾸면
서비스의 try-catch가 조용히 망가진다. 그래서 `saveAndFlush`를 쓴다(ADR-060).

---

## Redis

### 명령 하나는 쪼개지지 않는다

Redis는 명령을 하나씩 차례로 처리한다. 그래서 명령 하나 안에는 다른 요청이 끼어들 틈이 없다. "찾기(GET) → 지우기(DEL)"를
두 명령으로 하면 그 사이에 끼어들지만, `GETDEL` 하나로 하면 값을 받는 건 한 번뿐이다. 락 없이 확인과 행동을 합친 것이다
(ADR-059). Spring에서는 `opsForValue().getAndDelete`.

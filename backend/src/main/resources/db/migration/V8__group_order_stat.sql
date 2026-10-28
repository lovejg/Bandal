-- 통계 둘째 버전. 거점별 숫자를 더하던 첫 버전(V7)은 같은 글 두 번, 되감기에서 숫자가 두 배가 됐다(2026-10-27)
-- 숫자 대신 끝난 방마다 사실을 한 줄씩 두고 방 id로 덮어쓴다. 지표(성사율, 아낀 배달비, 취소 종류별 건수)는 볼 때 SQL로 센다
drop table pickup_spot_stat;

create table group_order_stat (
    -- 방 하나에 줄 하나. 같은 방의 글을 다시 받으면 이 줄을 덮어쓴다
    group_order_id bigint not null,
    pickup_spot_id bigint not null,
    -- 끝난 상태. 배달완료면 성사, 취소면 실패
    outcome varchar(255) not null
        check ((outcome in ('DELIVERED','CANCELED'))),
    -- 취소일 때만. 어느 단계에서 누가(방장, 시간 초과) 취소했나
    cancel_type varchar(255)
        check ((cancel_type in ('HOST_WHILE_RECRUITING','HOST_WHILE_CLOSED','HOST_WHILE_SETTLING','DEADLINE_UNMET','CLOSED_TIMEOUT','SETTLING_TIMEOUT'))),
    -- 정산을 시작한 방만 있다. 아낀 배달비 = 배달비 × (인원 - 1)을 볼 때 계산한다
    delivery_fee bigint,
    -- 방장 포함 참여 인원
    participant_count integer not null,
    -- 끝난 시각(배달완료 또는 취소된 때). "이번 달" 같은 기간으로 자를 때 쓴다
    finished_at timestamp(6) with time zone not null,
    -- 이 줄을 쓴 상태 변경 번호(outbox 줄 id). 이보다 작은(오래된) 글로는 덮어쓰지 않는다
    event_id bigint not null,
    primary key (group_order_id)
);

-- 대시보드는 기간으로 잘라 본다
create index idx_group_order_stat_finished on group_order_stat (finished_at);

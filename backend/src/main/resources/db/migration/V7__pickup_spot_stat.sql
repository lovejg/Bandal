-- 거점별 통계의 첫 버전. 통계 컨슈머가 끝난 방 글을 받을 때마다 숫자를 더한다(+1)
-- 같은 글을 두 번 받거나 되감아 다시 읽으면 숫자가 불어나는지 보려고 만든 순진한 버전이다
create table pickup_spot_stat (
    pickup_spot_id bigint not null,
    -- 배달완료까지 간 방 수
    delivered_count integer not null default 0,
    -- 취소된 방 수
    canceled_count integer not null default 0,
    -- 아낀 배달비 합계. 방마다 배달비 × (인원 - 1)
    saved_delivery_fee bigint not null default 0,
    primary key (pickup_spot_id)
);

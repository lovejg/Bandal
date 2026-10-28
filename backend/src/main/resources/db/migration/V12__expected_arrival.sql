-- 도착 예정 시각. 방장이 주문완료로 넘길 때 배달앱이 알려 준 시각을 적는다
-- 이 칸을 더하기 전에 주문완료된 방은 값이 없어서 null을 허용한다(새 주문은 앱이 꼭 받는다)
alter table group_order
    add column expected_arrival_at timestamp(6) with time zone;

-- 알림에도 그때의 도착 예정 시각을 담는다. "주문했어요 · 19시 40분 도착 예정", "도착 예정이 19시 55분으로 바뀌었어요"
alter table notification
    add column expected_arrival_at timestamp(6) with time zone;

-- 알림 종류에 "도착 예정이 바뀜"을 더한다. V9에서 이름 없이 만든 check라 Postgres가 붙인 이름(표_칸_check)으로 지운다
alter table notification
    drop constraint notification_kind_check;
alter table notification
    add constraint notification_kind_check
        check ((kind in ('STATUS_CHANGED','DELIVERY_FEE_CHANGED','ARRIVAL_CHANGED')));

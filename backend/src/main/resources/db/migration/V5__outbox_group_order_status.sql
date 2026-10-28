-- outbox에 메일 말고 방 상태 변경 줄도 담는다. 방 상태 줄은 받는 사람 한 명이 없고, 내용(payload)이 있다
-- 이미 있는 메일 줄은 새 칸이 비어 있으므로 새 칸은 모두 null을 허용한다

-- V4가 type 칸에 "이 두 값만 된다"는 CHECK를 걸어 두었다(enum 이름을 그대로 적는 칸이라). 새 종류가 들어갈 수 있게 다시 건다
alter table outbox_message drop constraint outbox_message_type_check;
alter table outbox_message add constraint outbox_message_type_check
    check (type in ('VERIFICATION_MAIL', 'ALREADY_REGISTERED_MAIL', 'GROUP_ORDER_STATUS_CHANGED'));

-- 방 상태 줄에는 받을 사용자 한 명이 없다
alter table outbox_message alter column user_id drop not null;

-- 방 상태 줄에만 있다. Kafka에 넣을 때 키(방 id)로 쓴다
alter table outbox_message add column group_order_id bigint;

-- 방 상태 줄에만 있다. 그 순간의 사실(상태, 참여자별 금액 등)을 JSON으로 담고, 그대로 Kafka 메시지가 된다
alter table outbox_message add column payload jsonb;

-- 종류마다 꼭 있어야 하는 칸. 메일은 받을 사용자, 방 상태는 방 id와 내용
alter table outbox_message add constraint outbox_message_target_check check (
    (type in ('VERIFICATION_MAIL', 'ALREADY_REGISTERED_MAIL') and user_id is not null)
    or (type = 'GROUP_ORDER_STATUS_CHANGED' and group_order_id is not null and payload is not null)
);

-- outbox에 "비밀번호 재설정 메일"을 더한다. type 칸에는 들어갈 수 있는 값을 적어 둔 check가 있어서 다시 건다(V5와 같은 방식)
alter table outbox_message drop constraint outbox_message_type_check;
alter table outbox_message add constraint outbox_message_type_check
    check (type in ('VERIFICATION_MAIL', 'ALREADY_REGISTERED_MAIL', 'PASSWORD_RESET_MAIL', 'GROUP_ORDER_STATUS_CHANGED'));

-- 메일 종류는 받을 사용자가 꼭 있어야 한다
alter table outbox_message drop constraint outbox_message_target_check;
alter table outbox_message add constraint outbox_message_target_check check (
    (type in ('VERIFICATION_MAIL', 'ALREADY_REGISTERED_MAIL', 'PASSWORD_RESET_MAIL') and user_id is not null)
    or (type = 'GROUP_ORDER_STATUS_CHANGED' and group_order_id is not null and payload is not null)
);

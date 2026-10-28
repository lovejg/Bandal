-- 알림에 "무엇이 바뀌었나"와 배달비를 더한다. 정산중에 방장이 배달비를 고치면 상태는 그대로인데 사람마다 낼 돈이 바뀐다
-- 이미 있는 알림 줄은 모두 상태 변경 알림이라 기본값으로 채운다
alter table notification
    add column kind varchar(255) not null default 'STATUS_CHANGED'
        check ((kind in ('STATUS_CHANGED','DELIVERY_FEE_CHANGED')));

-- 그때의 배달비. 정산을 시작하기 전 알림(마감, 모집중 취소)은 null
-- 배달비 수정 알림의 문장("배달비가 4,000원으로 바뀌었어요")에 쓴다
alter table notification
    add column delivery_fee bigint;

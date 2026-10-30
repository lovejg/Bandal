-- 방의 음식 카테고리. 방 하나에 여러 개(1~3개)라 방-카테고리 줄을 따로 둔다
-- 이 표를 넣기 전에 만든 방은 줄이 없다(카테고리 없음). 목록의 "전체"에서만 보인다
create table group_order_category (
    group_order_id bigint not null,
    category varchar(30) not null
        check (category in ('CHICKEN', 'FAST_FOOD', 'JJIM_TANG', 'BUNSIK', 'CHINESE', 'KOREAN', 'PIZZA',
                            'CUTLET_SASHIMI', 'CAFE_DESSERT', 'MEAT', 'JOKBAL_BOSSAM', 'WESTERN', 'LUNCHBOX',
                            'ASIAN', 'LATE_NIGHT')),
    primary key (group_order_id, category),
    -- 카테고리 줄은 방의 일부라 방이 지워지면 같이 지운다. 시연 데이터 스크립트처럼 SQL로 방을 지우는 곳이 이 표를 몰라도 된다
    constraint fk_group_order_category_group_order foreign key (group_order_id) references group_order (id) on delete cascade
);
-- 목록 필터는 "이 카테고리인 방이 있나"를 방마다 묻는다(EXISTS). 기본 키 (방, 카테고리)가 그 물음에 그대로 맞는다

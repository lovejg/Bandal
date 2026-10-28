-- 시연(샘플) 데이터. 시연 사진, 면접 시연, 손으로 해 보는 테스트에 쓴다. demo-seed.sh가 부른다
-- 존재하지 않는 학교 두 곳(반달대학교, 달빛대학교)과 그 학교의 계정, 거점, 방을 넣는다
--
-- 시연 학교 = 메일 도메인이 .example로 끝나는 학교
--   .example은 인터넷에서 쓸 수 없게 정해 둔 이름(RFC 2606)이라 진짜 학교와 겹치지 않고, 이 주소로는 메일을 받을 수 없다
--   그래서 누가 이 도메인으로 가입해도 인증을 못 해 구경만 한다
--   진짜 사용자는 다른 학교의 방을 볼 수 없다(목록과 참여가 학교로 나뉜다). 운영에 넣어도 진짜 사용자 눈에는 안 보인다
--   통계 대시보드는 시연 학교를 뺀다(deploy/monitoring/grafana/stats-queries.sql)
--
-- 몇 번을 돌려도 같은 모양이 된다. 시연 학교에 딸린 것만 지우고 다시 넣는다. 다른 학교의 데이터는 건드리지 않는다
-- 모집중 방의 마감은 "지금부터 몇 분 뒤"라서, 시연하기 전에 다시 돌린다. 마감이 지나면 스케줄러가 마감이나 취소로 넘긴다
-- 부르는 법: psql -v pw_hash="'<BCrypt 해시>'" < demo-seed.sql
\set ON_ERROR_STOP on

begin;

-- ===== 1. 지우기 =====
-- 지울 것의 id를 먼저 모은다. 외래 키가 걸린 순서(정산, 메뉴 → 참여 → 방 → 거점 → 사용자)대로 지운다
create temp table old_school as select id from university where email_domain like '%.example';
create temp table old_user as select id from users where university_id in (select id from old_school);
create temp table old_spot as select id from pickup_spot where university_id in (select id from old_school);
create temp table old_room as select id from group_order where pickup_spot_id in (select id from old_spot);

-- 외래 키는 없지만 시연 방과 사람을 가리키는 줄들. 남겨 두면 지운 방의 알림, 통계가 떠돈다
delete from notification where user_id in (select id from old_user) or group_order_id in (select id from old_room);
delete from group_order_stat where group_order_id in (select id from old_room) or pickup_spot_id in (select id from old_spot);
delete from outbox_message where group_order_id in (select id from old_room) or user_id in (select id from old_user);

delete from settlement where group_order_id in (select id from old_room);
delete from order_item where participation_id in (select id from participation where group_order_id in (select id from old_room));
delete from participation where group_order_id in (select id from old_room);
delete from group_order where id in (select id from old_room);
delete from pickup_spot where id in (select id from old_spot);
delete from users where id in (select id from old_user);

-- 학교 행은 지우지 않고 없을 때만 만든다. 학교 id가 바뀌지 않는다
insert into university (name, email_domain)
select v.name, v.domain
from (values ('반달대학교', 'bandal-univ.example'), ('달빛대학교', 'dalbit-univ.example')) v(name, domain)
where not exists (select 1 from university u where u.email_domain = v.domain);


-- ===== 2. 넣을 내용 =====
-- 사람이 읽고 고치기 쉽게 표로 적는다. 아래 3번이 이 표를 진짜 표로 옮긴다
-- 계정: demo<n>@<학교 도메인>. 비밀번호는 모두 같다(demo-seed.sh가 해시를 넘긴다). 모두 메일 인증과 계좌 등록을 마쳤다
-- 계좌번호는 0으로 채운 가짜다. 진짜 계좌와 겹쳐 누가 실제로 송금하는 일이 없게
create temp table d_user (school text, n int, nickname text, holder text, bank text);
insert into d_user values
    ('bandal-univ.example', 1, '야식요정',   '김다온', '토스뱅크'),
    ('bandal-univ.example', 2, '마라중독',   '이준서', '카카오뱅크'),
    ('bandal-univ.example', 3, '떡볶이장인', '박서윤', '국민은행'),
    ('bandal-univ.example', 4, '치킨은반반', '최민재', '신한은행'),
    ('bandal-univ.example', 5, '새벽라면',   '정유나', '우리은행'),
    ('dalbit-univ.example', 1, '달빛산책',   '한지우', '토스뱅크'),
    ('dalbit-univ.example', 2, '피자한조각', '오태윤', '카카오뱅크'),
    ('dalbit-univ.example', 3, '국밥한그릇', '서가람', '하나은행'),
    ('dalbit-univ.example', 4, '디저트배',   '문하율', '국민은행'),
    ('dalbit-univ.example', 5, '초밥러버',   '강도윤', '농협은행');

create temp table d_spot (school text, name text, description text);
insert into d_spot values
    ('bandal-univ.example', '기숙사 A동 로비',  '엘리베이터 앞 택배함 옆'),
    ('bandal-univ.example', '제1학생회관 앞',   '정문 쪽 계단 아래'),
    ('bandal-univ.example', '공대 3호관 입구',  '자판기 앞'),
    ('dalbit-univ.example', '중앙도서관 정문',  '회전문 오른쪽 벤치'),
    ('dalbit-univ.example', '생활관 2동 로비',  '편의점 맞은편'),
    ('dalbit-univ.example', '경영관 앞',        '버스 정류장 쪽');

-- 방. 학교마다 모집중 5개와, 흐름을 보여 줄 정산중, 주문완료, 배달완료 하나씩
-- deadline: 모집중은 지금부터 뒤, 나머지는 이미 지났다. 상태별 시각은 3번이 마감 시각에서 셈해 채운다
-- 주문완료 방은 마감 40분 전 → 정산 시작 35분 전 → 주문 15분 전 → 도착 예정 25분 뒤가 된다
create temp table d_room (school text, store text, host_n int, spot text, min_amount bigint, deadline timestamptz,
                          capacity int, status text, fee bigint);
insert into d_room values
    ('bandal-univ.example', '반달 마라탕',     1, '기숙사 A동 로비', 18000, now() + interval '50 minutes',  4, 'RECRUITING', null),
    ('bandal-univ.example', '골목 떡볶이',     3, '제1학생회관 앞',  16000, now() + interval '90 minutes',  5, 'RECRUITING', null),
    ('bandal-univ.example', '24시 치킨나라',   4, '기숙사 A동 로비', 20000, now() + interval '2 hours',     4, 'RECRUITING', null),
    ('bandal-univ.example', '분식왕 김밥',     5, '공대 3호관 입구', 15000, now() + interval '3 hours',     6, 'RECRUITING', null),
    ('bandal-univ.example', '초록 샐러드',     2, '공대 3호관 입구', 17000, now() + interval '4 hours',     3, 'RECRUITING', null),
    ('bandal-univ.example', '심야 피자',       1, '기숙사 A동 로비', 20000, now() - interval '25 minutes',  4, 'SETTLING',   3000),
    ('bandal-univ.example', '돈까스 연구소',   3, '제1학생회관 앞',  18000, now() - interval '40 minutes',  4, 'ORDERED',    2000),
    ('bandal-univ.example', '만리 반점',       2, '공대 3호관 입구', 20000, now() - interval '1 day',       4, 'DELIVERED',  4000),
    ('dalbit-univ.example', '달빛 쌀국수',     1, '생활관 2동 로비', 17000, now() + interval '40 minutes',  4, 'RECRUITING', null),
    ('dalbit-univ.example', '화덕 피자공방',   2, '중앙도서관 정문', 22000, now() + interval '75 minutes',  5, 'RECRUITING', null),
    ('dalbit-univ.example', '뜨끈 국밥집',     3, '생활관 2동 로비', 16000, now() + interval '2 hours',     4, 'RECRUITING', null),
    ('dalbit-univ.example', '오늘의 디저트',   4, '경영관 앞',       14000, now() + interval '150 minutes', 6, 'RECRUITING', null),
    ('dalbit-univ.example', '스시 한 접시',    5, '중앙도서관 정문', 25000, now() + interval '5 hours',     4, 'RECRUITING', null),
    ('dalbit-univ.example', '불맛 닭갈비',     3, '생활관 2동 로비', 20000, now() - interval '30 minutes',  4, 'SETTLING',   3500),
    ('dalbit-univ.example', '수제 버거랩',     2, '경영관 앞',       18000, now() - interval '40 minutes',  3, 'ORDERED',    3000),
    ('dalbit-univ.example', '한그릇 우동',     1, '중앙도서관 정문', 15000, now() - interval '1 day',       4, 'DELIVERED',  3000);

-- 방에 들어온 사람(방장 포함). pay는 정산중 방에서 그 사람의 송금 단계다(none: 아직, marked: 보냈어요, confirmed: 방장 확인)
-- 주문완료, 배달완료 방은 모두 확인된 뒤라 pay를 보지 않는다. 방장은 늘 확인돼 있다
create temp table d_member (store text, n int, pay text);
insert into d_member values
    ('반달 마라탕', 1, null), ('반달 마라탕', 2, null),
    ('골목 떡볶이', 3, null), ('골목 떡볶이', 4, null),
    ('24시 치킨나라', 4, null), ('24시 치킨나라', 5, null), ('24시 치킨나라', 2, null),
    ('분식왕 김밥', 5, null),
    ('초록 샐러드', 2, null), ('초록 샐러드', 1, null),
    ('심야 피자', 1, null), ('심야 피자', 2, 'confirmed'), ('심야 피자', 3, 'marked'), ('심야 피자', 5, 'none'),
    ('돈까스 연구소', 3, null), ('돈까스 연구소', 4, null), ('돈까스 연구소', 1, null),
    ('만리 반점', 2, null), ('만리 반점', 5, null), ('만리 반점', 4, null),
    ('달빛 쌀국수', 1, null), ('달빛 쌀국수', 4, null),
    ('화덕 피자공방', 2, null), ('화덕 피자공방', 1, null), ('화덕 피자공방', 5, null),
    ('뜨끈 국밥집', 3, null),
    ('오늘의 디저트', 4, null), ('오늘의 디저트', 2, null), ('오늘의 디저트', 3, null),
    ('스시 한 접시', 5, null), ('스시 한 접시', 1, null),
    ('불맛 닭갈비', 3, null), ('불맛 닭갈비', 1, 'confirmed'), ('불맛 닭갈비', 4, 'none'),
    ('수제 버거랩', 2, null), ('수제 버거랩', 5, null), ('수제 버거랩', 3, null),
    ('한그릇 우동', 1, null), ('한그릇 우동', 3, null), ('한그릇 우동', 2, null);

-- 사람마다 담은 메뉴. 모집중 방은 최소금액을 넘은 방(바로 마감 가능)과 모자란 방이 섞여 있다
create temp table d_item (store text, n int, menu text, options text, price bigint, qty int);
insert into d_item values
    ('반달 마라탕', 1, '마라탕', '2단계, 꼬치 추가', 11000, 1),
    ('반달 마라탕', 2, '마라탕', '1단계', 9500, 1),
    ('골목 떡볶이', 3, '로제 떡볶이', '중간맛', 8000, 1),
    ('골목 떡볶이', 4, '모듬 튀김', null, 4000, 1),
    ('24시 치킨나라', 4, '반반 치킨', '양념 반, 후라이드 반', 19000, 1),
    ('24시 치킨나라', 5, '치즈볼', '5개', 4500, 1),
    ('24시 치킨나라', 2, '콜라 1.25L', null, 2500, 1),
    ('분식왕 김밥', 5, '참치 김밥', null, 4500, 1),
    ('분식왕 김밥', 5, '라면', '계란 추가', 5000, 1),
    ('초록 샐러드', 2, '연어 샐러드', '드레싱 따로', 9900, 1),
    ('초록 샐러드', 1, '닭가슴살 샐러드', null, 8900, 1),
    ('심야 피자', 1, '페퍼로니 피자', 'L', 21900, 1),
    ('심야 피자', 2, '오븐 스파게티', null, 7900, 1),
    ('심야 피자', 3, '갈릭 디핑소스', null, 500, 2),
    ('심야 피자', 5, '감자튀김', null, 4900, 1),
    ('돈까스 연구소', 3, '등심 돈까스', null, 9500, 1),
    ('돈까스 연구소', 4, '치즈 돈까스', null, 10500, 1),
    ('돈까스 연구소', 1, '모밀', '곱빼기', 8000, 1),
    ('만리 반점', 2, '짜장면', null, 7000, 1),
    ('만리 반점', 5, '짬뽕', '덜 맵게', 8500, 1),
    ('만리 반점', 4, '탕수육', '소', 16000, 1),
    ('달빛 쌀국수', 1, '양지 쌀국수', '고수 빼고', 10500, 1),
    ('달빛 쌀국수', 4, '분짜', null, 11000, 1),
    ('화덕 피자공방', 2, '마르게리타', null, 15900, 1),
    ('화덕 피자공방', 1, '고르곤졸라', '꿀 추가', 14900, 1),
    ('화덕 피자공방', 5, '레몬에이드', null, 3500, 2),
    ('뜨끈 국밥집', 3, '순대국밥', '다대기 따로', 9000, 1),
    ('오늘의 디저트', 4, '딸기 케이크', '조각', 6500, 1),
    ('오늘의 디저트', 2, '아메리카노', '아이스', 3000, 2),
    ('오늘의 디저트', 3, '마카롱', '3개', 7500, 1),
    ('스시 한 접시', 5, '모둠 초밥', '12피스', 18000, 1),
    ('스시 한 접시', 1, '연어 초밥', '8피스', 14000, 1),
    ('불맛 닭갈비', 3, '닭갈비', '2인분', 22000, 1),
    ('불맛 닭갈비', 1, '볶음밥', null, 3000, 1),
    ('불맛 닭갈비', 4, '치즈 사리', null, 3000, 1),
    ('수제 버거랩', 2, '더블 치즈버거', '세트', 12900, 1),
    ('수제 버거랩', 5, '베이컨 버거', null, 9900, 1),
    ('수제 버거랩', 3, '어니언링', null, 4500, 1),
    ('한그릇 우동', 1, '튀김 우동', null, 8000, 1),
    ('한그릇 우동', 3, '카레 우동', null, 8500, 1),
    ('한그릇 우동', 2, '유부 초밥', '6개', 5000, 1);


-- ===== 3. 진짜 표로 옮기기 =====
insert into users (university_id, email, password, nickname, email_verified_at, trust_score, bank_name, account_number, account_holder)
select u.id, format('demo%s@%s', d.n, d.school), :pw_hash, d.nickname, now(), 50, d.bank,
       format('000-0000-%s%s', case when d.school like 'bandal%' then '1' else '2' end, d.n), d.holder
from d_user d join university u on u.email_domain = d.school;

-- 거점 이름 비교용 칸(normalized_name)은 앱(PickupSpot)이 영문, 숫자, 한글만 남기고 소문자로 만든 값이다. 같은 규칙으로 채운다
insert into pickup_spot (university_id, name, normalized_name, description)
select u.id, s.name, lower(regexp_replace(s.name, '[^a-zA-Z0-9가-힣]', '', 'g')), s.description
from d_spot s join university u on u.email_domain = s.school;

-- 학교 + 번호로 사용자 id를 찾는 표
create temp table d_user_id as
select d.school, d.n, us.id as user_id
from d_user d join users us on us.email = format('demo%s@%s', d.n, d.school);

-- 방. 상태마다 거쳐 온 시각을 마감 시각에서 셈해 채운다(스케줄러와 화면이 이 시각을 본다)
insert into group_order (host_id, pickup_spot_id, store_name, min_order_amount, deadline_at, capacity, participant_count, status,
                         closed_at, delivery_fee, settlement_started_at, ordered_at, expected_arrival_at, delivered_at)
select h.user_id, sp.id, r.store, r.min_amount, r.deadline, r.capacity,
       (select count(*) from d_member m where m.store = r.store), r.status,
       case when r.status <> 'RECRUITING' then r.deadline end,
       r.fee,
       case when r.status in ('SETTLING', 'ORDERED', 'DELIVERED') then r.deadline + interval '5 minutes' end,
       case when r.status in ('ORDERED', 'DELIVERED') then r.deadline + interval '25 minutes' end,
       case when r.status in ('ORDERED', 'DELIVERED') then r.deadline + interval '65 minutes' end,
       case when r.status = 'DELIVERED' then r.deadline + interval '62 minutes' end
from d_room r
join university u on u.email_domain = r.school
join d_user_id h on h.school = r.school and h.n = r.host_n
join pickup_spot sp on sp.university_id = u.id and sp.name = r.spot;

-- 가게 이름 → 방 id. 시연 방의 가게 이름은 모두 다르다
create temp table d_room_id as
select r.store, r.school, r.status, g.id as room_id, g.host_id, g.deadline_at, g.settlement_started_at, g.delivery_fee
from d_room r join group_order g on g.store_name = r.store
join pickup_spot sp on sp.id = g.pickup_spot_id join university u on u.id = sp.university_id and u.email_domain = r.school;

-- 참여. 마감(또는 지금) 30분 전부터 1분 간격으로 들어왔다
insert into participation (group_order_id, user_id, joined_at)
select ri.room_id, ui.user_id,
       least(ri.deadline_at, now()) - interval '30 minutes' + make_interval(mins => row_number() over (partition by m.store order by m.n)::int)
from d_member m
join d_room_id ri on ri.store = m.store
join d_user_id ui on ui.school = ri.school and ui.n = m.n;

insert into order_item (participation_id, menu_name, options, unit_price, quantity, edited_by_host)
select p.id, i.menu, i.options, i.price, i.qty, false
from d_item i
join d_room_id ri on ri.store = i.store
join d_user_id ui on ui.school = ri.school and ui.n = i.n
join participation p on p.group_order_id = ri.room_id and p.user_id = ui.user_id;

-- 정산표(정산을 시작한 방만). 앱의 계산과 같다: 내 메뉴 합계 + 배달비 / 인원, 나누어떨어지지 않는 몇 원은 방장이 낸다
insert into settlement (group_order_id, user_id, host, menu_total_amount, fee_share, total_amount, marked_paid_at, confirmed_paid_at)
select x.room_id, x.user_id, x.host, x.menu, x.share, x.menu + x.share,
       case when x.host or x.status <> 'SETTLING' or x.pay in ('marked', 'confirmed') then x.started + interval '3 minutes' end,
       case when x.host or x.status <> 'SETTLING' or x.pay = 'confirmed' then x.started + interval '6 minutes' end
from (
    select ri.room_id, ui.user_id, ri.status, ri.settlement_started_at as started, m.pay,
           ui.user_id = ri.host_id as host,
           (select coalesce(sum(i.price * i.qty), 0) from d_item i where i.store = m.store and i.n = m.n) as menu,
           case when ui.user_id = ri.host_id
                then ri.delivery_fee - (ri.delivery_fee / cnt.n) * (cnt.n - 1)
                else ri.delivery_fee / cnt.n end as share
    from d_member m
    join d_room_id ri on ri.store = m.store
    join d_user_id ui on ui.school = ri.school and ui.n = m.n
    join (select store, count(*) as n from d_member group by store) cnt on cnt.store = m.store
    where ri.status in ('SETTLING', 'ORDERED', 'DELIVERED')
) x;

-- 주문한 방의 실제 결제금액. 쿠폰 1,000원을 쓴 것으로 한다(정산식에는 쓰지 않는 통계용 값)
update group_order g
set total_paid_amount = (select sum(s.total_amount) from settlement s where s.group_order_id = g.id) - 1000
where g.id in (select room_id from d_room_id where status in ('ORDERED', 'DELIVERED'));

commit;

-- 넣은 결과
select u.name as school, g.status, count(*) as rooms
from group_order g join pickup_spot sp on sp.id = g.pickup_spot_id join university u on u.id = sp.university_id
where u.email_domain like '%.example'
group by u.name, g.status order by u.name, g.status;

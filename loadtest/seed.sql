-- 부하 테스트 서버 DB에 측정용 데이터를 넣는다. 운영 DB에는 절대 돌리지 않는다(seed.sh가 서버 이름을 확인한다)
-- 대학 13곳은 마이그레이션이 넣은 것을 쓰고, 나머지는 지우고 새로 만든다. 몇 번을 돌려도 같은 모양이 나온다(난수 없이 번호로 만든다)
-- 크기(학교마다 규모가 달라서 큰 학교, 작은 학교를 같이 둔다):
--   연세대 신촌: 사용자 2,000, 거점 10, 모집중 300, 지난 방 3,000
--   광운대:      사용자 200,  거점 3,  모집중 20,  지난 방 200
--   나머지 11곳: 사용자 30,   거점 1,  모집중 5,   지난 방 50
-- 모집중 방의 마감은 지금부터 2~6시간 뒤다. 그보다 길게 테스트하면 스케줄러가 마감시킨다. 그 전에 다시 넣는다
-- 부르는 법: psql -v pw_hash="'<BCrypt 해시>'" -f seed.sql
\set ON_ERROR_STOP on

begin;

truncate settlement, order_item, participation, group_order, pickup_spot, users restart identity cascade;

-- 학교별 크기
create temp table school as
select u.id as uid, u.email_domain as domain,
       case when u.name = '연세대학교 신촌캠퍼스' then 'big' when u.name = '광운대학교' then 'small' else 'other' end as kind
from university u;

alter table school add column n_users int, add column n_spots int, add column n_open int, add column n_past int;
update school set n_users = 2000, n_spots = 10, n_open = 300, n_past = 3000 where kind = 'big';
update school set n_users = 200,  n_spots = 3,  n_open = 20,  n_past = 200  where kind = 'small';
update school set n_users = 30,   n_spots = 1,  n_open = 5,   n_past = 50   where kind = 'other';

-- 사용자. 모두 메일 인증과 계좌 등록을 마쳤다(누구나 방장이 될 수 있게). 비밀번호는 모두 같다
-- 이메일: lt-<학교id>-<번호>@<도메인>. k6가 이 규칙으로 로그인한다
insert into users (university_id, email, password, nickname, email_verified_at, trust_score, bank_name, account_number, account_holder)
select s.uid, format('lt-%s-%s@%s', s.uid, i, s.domain), :pw_hash, format('lt%s_%s', s.uid, i), now(), 50,
       '테스트은행', format('000-%s-%s', s.uid, i), format('테스터%s', i)
from school s, generate_series(1, s.n_users) i;

-- 학교 안에서 i번째 사용자의 id를 찾는 표
create temp table su as
select s.uid, row_number() over (partition by s.uid order by u.id) as i, u.id as user_id
from school s join users u on u.university_id = s.uid;
create index on su (uid, i);

insert into pickup_spot (university_id, name, normalized_name, description)
select s.uid, format('거점 %s', k), format('거점%s', k), '부하 테스트용'
from school s, generate_series(1, s.n_spots) k;

create temp table ss as
select university_id as uid, row_number() over (partition by university_id order by id) as k, id as spot_id from pickup_spot;

-- 모집중 방. 방장 + 0~2명이 들어 있고 정원은 4~8명이다
create temp table open_room as
select s.uid, r, ((r - 1) % s.n_users) + 1 as host_i, ((r - 1) % s.n_spots) + 1 as spot_k,
       4 + (r % 5) as capacity, 1 + (r % 3) as cnt,
       now() + interval '2 hours' + make_interval(mins => (r * 7) % 240) as deadline
from school s, generate_series(1, s.n_open) r;

-- 지난 방. 열에 아홉은 배달 완료, 하나는 인원 미달 취소. 지난 90일에 퍼져 있고 3~5명씩 들어 있다
create temp table past_room as
select s.uid, r, ((r * 13) % s.n_users) + 1 as host_i, ((r - 1) % s.n_spots) + 1 as spot_k,
       3 + (r % 3) as cnt, (r % 10 = 0) as canceled,
       now() - make_interval(days => 1 + (r % 90), mins => (r * 11) % 600) as deadline
from school s, generate_series(1, s.n_past) r;

insert into group_order (host_id, pickup_spot_id, store_name, min_order_amount, deadline_at, capacity, participant_count, status)
select h.user_id, sp.spot_id, format('가게 %s-%s', o.uid, o.r), 20000, o.deadline, o.capacity, o.cnt, 'RECRUITING'
from open_room o
join su h on h.uid = o.uid and h.i = o.host_i
join ss sp on sp.uid = o.uid and sp.k = o.spot_k;

insert into group_order (host_id, pickup_spot_id, store_name, min_order_amount, deadline_at, capacity, participant_count, status,
                         closed_at, delivery_fee, settlement_started_at, ordered_at, delivered_at, canceled_at, cancel_type)
select h.user_id, sp.spot_id, format('지난 가게 %s-%s', p.uid, p.r), 20000, p.deadline, 6, p.cnt,
       case when p.canceled then 'CANCELED' else 'DELIVERED' end,
       case when p.canceled then null else p.deadline end,
       case when p.canceled then null else 3000 end,
       case when p.canceled then null else p.deadline + interval '10 minutes' end,
       case when p.canceled then null else p.deadline + interval '30 minutes' end,
       case when p.canceled then null else p.deadline + interval '70 minutes' end,
       case when p.canceled then p.deadline end,
       case when p.canceled then 'DEADLINE_UNMET' end
from past_room p
join su h on h.uid = p.uid and h.i = p.host_i
join ss sp on sp.uid = p.uid and sp.k = p.spot_k;

-- 참여: 방장(j=0)과 그 뒤 번호의 사용자들. 같은 방에 같은 사람이 두 번 들어가지 않는다(cnt <= 5 < 학교 인원)
insert into participation (group_order_id, user_id, joined_at)
select g.id, u.user_id, g.deadline_at - interval '1 hour' + make_interval(mins => j)
from group_order g
join pickup_spot sp on sp.id = g.pickup_spot_id
join su hu on hu.user_id = g.host_id
cross join lateral generate_series(0, g.participant_count - 1) j
join school s on s.uid = sp.university_id
join su u on u.uid = sp.university_id and u.i = ((hu.i - 1 + j) % s.n_users) + 1;

-- 시나리오 A의 방. 큰 학교, 정원 10, 방장만 있다. k6가 가게 이름으로 찾는다
insert into group_order (host_id, pickup_spot_id, store_name, min_order_amount, deadline_at, capacity, participant_count, status)
select h.user_id, sp.spot_id, '부하 테스트 핫한 치킨집', 20000, now() + interval '6 hours', 10, 1, 'RECRUITING'
from school s join su h on h.uid = s.uid and h.i = 1 join ss sp on sp.uid = s.uid and sp.k = 1
where s.kind = 'big';
insert into participation (group_order_id, user_id, joined_at)
select g.id, g.host_id, now() from group_order g where g.store_name = '부하 테스트 핫한 치킨집';

commit;

analyze;

select s.kind, count(distinct u.id) as users from school s join users u on u.university_id = s.uid group by s.kind order by s.kind;
select g.status, count(*) from group_order g group by g.status order by g.status;
select count(*) as participations from participation;
select g.id as hot_room_id, s.uid as hot_university_id from group_order g
join pickup_spot sp on sp.id = g.pickup_spot_id join school s on s.uid = sp.university_id
where g.store_name = '부하 테스트 핫한 치킨집';

-- 통계 대시보드(dashboards/bandal-stats.json)의 패널 SQL 원본. 고치면 대시보드 JSON의 rawSql도 같이 고친다
-- 테스트(StatsDashboardQueryTest)가 이 파일의 SQL을 테스트 DB에 돌려 숫자를 맞춰 보고, JSON의 SQL이 이 파일과 같은지 본다
--
-- 읽는 계정은 grafana_reader다. 읽을 수 있는 표: group_order_stat(끝난 방마다 한 줄), pickup_spot(거점), university(학교)
--   group_order_stat: group_order_id, pickup_spot_id, outcome('DELIVERED' 또는 'CANCELED'), cancel_type(취소만),
--                     delivery_fee(정산을 시작한 방만), participant_count(방장 포함), finished_at(끝난 시각), event_id
--   pickup_spot: id, university_id, name      university: id, name
--
-- $__timeFilter(열): Grafana가 대시보드 위 기간 선택기 값으로 바꿔 주는 조건이다
--   예: 최근 30일이면 finished_at between '2026-09-28T...' and '2026-10-28T...'로 바뀐다
-- 열 이름(as 뒤)은 패널에 그대로 머리글로 보인다. 패널 설정이 이 이름으로 열을 찾으니 바꾸지 않는다
--
-- 블록 하나가 패널 하나다. "-- name:" 줄로 나눈다. 블록마다 SQL 문장 하나, 끝에 세미콜론을 붙이지 않는다
-- 블록 안의 주석 줄은 테스트가 빼고 읽는다. 주석이 아닌 줄만 SQL이다


-- name: success_rate
-- 거점별 성사율. 기간 선택기 안에 끝난 방만 센다
-- 열: school(학교 이름), spot(거점 이름), delivered(배달완료 방 수), finished(끝난 방 수), rate(성사율 %, 소수 첫째 자리)
--
-- 읽는 순서:
--   1. from/join: 통계 줄마다 그 방의 거점, 거점의 학교를 옆에 붙인다(표에는 거점 id만 있다)
--   2. where: 기간 안에 끝난 방만 남긴다
--   3. group by: 같은 학교의 같은 거점끼리 한 묶음. 학교도 넣는 이유는 거점 이름이 학교마다 겹치기 때문이다("기숙사 로비")
--   4. select: 묶음마다 센다
--      count(*): 묶음의 줄 수 = 끝난 방 수
--      count(*) filter (where 조건): 묶음 안에서 조건에 맞는 줄만 센다 = 배달완료 방 수. 한 번 훑으며 두 숫자를 같이 센다
--      100.0 *: 정수끼리 나누면 소수점이 잘린다(3 / 4 = 0). 하나를 소수로 만들어 75.0이 나오게 한다
--      round(값, 1): 소수 첫째 자리까지. 묶음에는 줄이 적어도 하나라 0으로 나누는 일은 없다
--   5. order by: 끝난 방이 많은 거점부터. 같으면 학교, 거점 이름 순(새로고침할 때마다 줄 순서가 바뀌지 않게)
--   order by에서는 select에서 붙인 이름(finished, school)을 쓸 수 있다
select u.name as school,
       p.name as spot,
       count(*) filter (where s.outcome = 'DELIVERED') as delivered,
       count(*) as finished,
       round(100.0 * count(*) filter (where s.outcome = 'DELIVERED') / count(*), 1) as rate
from group_order_stat s
join pickup_spot p on p.id = s.pickup_spot_id
join university u on u.id = p.university_id
where $__timeFilter(s.finished_at)
group by u.name, p.name
order by finished desc, school, spot


-- name: saved_total
-- 지금까지 아낀 배달비. 기간 선택기와 상관없이 전부 센다(그래서 $__timeFilter가 없다)
-- 한 방에서 아낀 돈 = 배달비 × (인원 - 1). 혼자 시켰으면 배달비를 다 냈을 텐데, 나눠서 (인원 - 1)명 몫을 덜 냈다
-- 배달완료된 방만 센다. 정산중에 취소된 방은 배달비가 적혀 있어도 아무도 안 먹었다
-- 열: saved(원)
-- coalesce(값, 0): 값이 null이면 0. 배달완료 방이 하나도 없으면 sum은 0이 아니라 null을 내서 패널이 "No data"로 보인다
select coalesce(sum(delivery_fee * (participant_count - 1)), 0) as saved
from group_order_stat
where outcome = 'DELIVERED'


-- name: saved_in_range
-- 기간 선택기 안에 끝난 방에서 아낀 배달비. saved_total에 기간 조건만 더했다
-- 열: saved(원)
select coalesce(sum(delivery_fee * (participant_count - 1)), 0) as saved
from group_order_stat
where outcome = 'DELIVERED' and $__timeFilter(finished_at)


-- name: cancel_types
-- 기간 선택기 안의 취소 종류별 건수. 막대그래프로 그린다
-- 열: cancel_type(종류 이름 그대로. 대시보드가 "마감 미달"처럼 한글로 바꿔 보여 준다), count(건수)
-- 많은 것부터. 같으면 종류 이름 순
-- 취소가 없던 종류는 줄이 없다(0건 막대를 그리지 않는다)
select cancel_type, count(*) as count
from group_order_stat
where outcome = 'CANCELED' and $__timeFilter(finished_at)
group by cancel_type
order by count desc, cancel_type

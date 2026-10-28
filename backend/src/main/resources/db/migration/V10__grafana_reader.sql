-- Grafana가 통계 대시보드를 그릴 때 쓰는 읽기 전용 계정
-- Grafana의 SQL 창은 아무 쿼리나 칠 수 있다. 앱 계정을 주면 Grafana 하나가 뚫렸을 때 users(이메일, 비밀번호 해시)까지 나간다
-- 그래서 통계에 필요한 표 셋만 읽게 한다
--
-- 여기서는 로그인할 수 없는(NOLOGIN) 역할만 만든다. 비밀번호를 git에 둘 수 없어서다
-- 운영에서는 deploy/bandal/grafana-reader.sh가 비밀번호를 정하고 로그인을 켠다
-- 역할은 DB 하나가 아니라 postgres 서버 전체의 것이다. 이미 있으면(다른 DB에서 만들었거나 손으로 만들었으면) 다시 만들지 않는다
do $$
begin
    if not exists (select 1 from pg_roles where rolname = 'grafana_reader') then
        create role grafana_reader nologin;
    end if;
end
$$;

-- 이 역할로 연 연결은 기본이 읽기 전용 트랜잭션이다. 권한 밖의 실수(update)를 한 번 더 막는다
alter role grafana_reader set default_transaction_read_only = on;

-- 표를 찾으려면 스키마를 쓸 권한(usage)이 먼저 있어야 한다
grant usage on schema public to grafana_reader;

-- 끝난 방의 사실, 거점 이름, 학교 이름. users, settlement, notification 같은 표는 주지 않는다
-- 주의: 이 표를 지우고 다시 만드는 마이그레이션을 쓰면 권한도 같이 사라진다. 그때 grant를 다시 적는다
grant select on group_order_stat, pickup_spot, university to grafana_reader;

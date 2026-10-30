-- 탈퇴. 줄은 남기고(지난 방과 정산표가 가리킨다) 개인정보만 지운다
-- 탈퇴한 시각. 값이 있으면 탈퇴한 사용자다
alter table users
    add column deleted_at timestamp(6) with time zone;

-- 탈퇴하면 메일과 닉네임을 비운다. 둘 다 unique지만 Postgres의 unique는 null끼리는 겹친다고 보지 않아서 탈퇴자가 여럿이어도 된다
-- 겹치지 않는 문자열(deleted-5 같은)로 덮지 않는 이유: 그 문자열을 누가 먼저 닉네임으로 쓰면 탈퇴가 unique에 걸린다
alter table users
    alter column email drop not null;
alter table users
    alter column nickname drop not null;

-- 위에서 not null을 풀면 탈퇴하지 않은 사용자도 메일, 닉네임 없이 저장될 수 있게 된다(UserRepositoryTest 두 개가 깨져서 알았다).
-- "탈퇴하지 않았으면 둘 다 있어야 한다"를 check로 되살린다
alter table users
    add constraint ck_users_identity_unless_deleted
        check (deleted_at is not null or (email is not null and nickname is not null));

-- 닉네임을 마지막으로 바꾼 시각. 7일에 한 번만 바꿀 수 있게 이 값과 비교한다
-- 가입 때 정한 닉네임은 바꾼 것으로 치지 않아서 비어 있다(가입 직후에도 한 번 바꿀 수 있다)
alter table users
    add column nickname_changed_at timestamp(6) with time zone;

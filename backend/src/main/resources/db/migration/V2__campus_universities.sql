-- 캠퍼스 하나가 대학 행 하나다. 같은 학교의 캠퍼스들은 메일 도메인을 같이 쓴다
-- 운영에는 대학을 만드는 API가 없어서, 여기서 처음 학교들을 넣어 둔다

alter table university drop constraint uk_university_email_domain;

-- id는 DB가 매긴다(identity)라서 적지 않는다
insert into university (name, email_domain)
values
    ('서울대학교', 'snu.ac.kr'),
    ('연세대학교 신촌캠퍼스', 'yonsei.ac.kr'),
    ('연세대학교 미래캠퍼스', 'yonsei.ac.kr'),
    ('고려대학교 서울캠퍼스', 'korea.ac.kr'),
    ('고려대학교 세종캠퍼스', 'korea.ac.kr'),
    ('한양대학교 서울캠퍼스', 'hanyang.ac.kr'),
    ('한양대학교 ERICA캠퍼스', 'hanyang.ac.kr'),
    ('경희대학교 서울캠퍼스', 'khu.ac.kr'),
    ('경희대학교 국제캠퍼스', 'khu.ac.kr'),
    ('서강대학교', 'sogang.ac.kr'),
    ('이화여자대학교', 'ewhain.net'),
    ('KAIST', 'kaist.ac.kr');

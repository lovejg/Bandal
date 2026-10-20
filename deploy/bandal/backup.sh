#!/bin/bash
# DB를 덤프하고, 빈 Postgres에 실제로 복구해 본 뒤, 맞을 때만 R2(서버 밖)에 올린다
#   ./backup.sh daily               cron이 매일 새벽 4시(한국 시간)에
#   ./backup.sh predeploy-<태그>    deploy.sh가 배포 직전에
# cron 등록(서버 시계는 UTC라 19시 = 한국 4시):
#   0 19 * * * /srv/bandal/backup.sh daily >> /srv/bandal/backups/backup.log 2>&1
# 서버에는 최근 몇 개만 남긴다. R2에서 오래된 것은 버킷의 수명 규칙(14일)이 지운다
set -Eeuo pipefail
cd "$(dirname "$0")"

LABEL=${1:?백업 이름을 주세요: daily 또는 predeploy-<태그>}
# 로컬 리허설에서는 아래 셋을 바꿔서 부른다. 서버에서는 기본값 그대로
read -ra COMPOSE <<< "${COMPOSE:-docker compose}"
ENV_FILE=${ENV_FILE:-.env}
BACKUP_DIR=${BACKUP_DIR:-backups}
KEEP_LOCAL=${KEEP_LOCAL:-3}
PG_IMAGE=postgres:17
AWS_IMAGE=amazon/aws-cli:2.27.50

# .env에서 값 하나를 꺼낸다. source로 읽지 않는 건 비밀값에 셸이 해석하는 글자가 섞여도 그대로 받으려고
env_get() { grep -m1 "^$1=" "$ENV_FILE" | cut -d= -f2- || true; }

DB_USER=$(env_get DB_USERNAME)
PING_URL=$(env_get BACKUP_PING_URL)
R2_ENDPOINT=$(env_get R2_ENDPOINT)
R2_BUCKET=$(env_get R2_BUCKET)
export AWS_ACCESS_KEY_ID=$(env_get R2_ACCESS_KEY_ID)
export AWS_SECRET_ACCESS_KEY=$(env_get R2_SECRET_ACCESS_KEY)

# 감시 서비스(healthchecks.io)에 알린다. 성공 신호가 하루 넘게 없으면 그쪽에서 메일이 온다.
# 스크립트가 아예 안 돌아도(cron이 빠지거나 서버가 죽어도) 알 수 있는 건 이 방식뿐이다
ping() { [ -z "$PING_URL" ] || curl -fsS -m 10 --retry 3 -o /dev/null "$PING_URL$1" || true; }

VERIFY=bandal-backup-verify-$$
mkdir -p "$BACKUP_DIR"
BACKUP_DIR=$(cd "$BACKUP_DIR" && pwd)
NAME=bandal-$(date -u +%Y%m%d-%H%M%SZ)-$LABEL.dump
PART=$BACKUP_DIR/$NAME.part

# 어디서 끝나든 검증용 컨테이너와 덜 만든 파일을 치우고, 실패였으면 실패를 알린다.
# 설정 검사보다 먼저 건다. 설정이 빠져서 멈춘 것도 실패로 알려야 한다
finish() {
  local rc=$?
  docker rm -f "$VERIFY" > /dev/null 2>&1 || true
  rm -f "$PART"
  if [ $rc -ne 0 ]; then echo "백업 실패: $LABEL"; ping /fail; fi
}
trap finish EXIT

[ -n "$R2_BUCKET" ] && [ -n "$R2_ENDPOINT" ] || { echo "R2_BUCKET, R2_ENDPOINT가 .env에 없다. 서버 밖에 못 두는 백업은 하지 않는다"; exit 1; }

# cron과 배포 직전 백업이 겹치면 하나가 끝날 때까지 기다린다
exec 9> "$BACKUP_DIR/.lock"
flock -w 600 9

# 테이블마다 행 수와 마지막 Flyway 버전을 한 줄로. 원본과 복구본이 같은지 이걸로 비교한다
COUNT_SQL="select coalesce((select version from flyway_schema_history where success order by installed_rank desc limit 1), 'none')
  || ' ' || coalesce(string_agg(table_name || '=' || n, ' ' order by table_name), '')
  from (select table_name, (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from public.%I', table_name), false, true, '')))[1]::text as n
        from information_schema.tables where table_schema = 'public' and table_type = 'BASE TABLE') t"
src_counts() { "${COMPOSE[@]}" exec -T postgres psql -U "$DB_USER" -d bandal -Atq -v ON_ERROR_STOP=1 -c "$COUNT_SQL"; }

# 1. 덤프. pg_dump는 한 트랜잭션 안에서 읽어서 도중에 들어온 쓰기가 섞이지 않는다.
#    다만 비교할 원본 행 수는 덤프 밖에서 센다. 앞뒤로 세어서 그사이 바뀌었으면 다시 뜬다
for try in 1 2 3; do
  BEFORE=$(src_counts)
  "${COMPOSE[@]}" exec -T postgres pg_dump -U "$DB_USER" -d bandal -Fc > "$PART"
  AFTER=$(src_counts)
  [ "$BEFORE" = "$AFTER" ] && break
  echo "덤프하는 동안 행 수가 바뀌었다. 다시 뜬다($try)"
  [ $try -lt 3 ] || { echo "세 번 다 바뀌었다"; exit 1; }
done
echo "덤프: $NAME ($(du -h "$PART" | cut -f1))"

# 2. 복구해 본다. 네트워크 없는 빈 Postgres에 넣고 행 수를 원본과 비교한다
docker run -d --name "$VERIFY" --network none --memory 256m \
  -e POSTGRES_PASSWORD=verify -e POSTGRES_DB=bandal "$PG_IMAGE" > /dev/null
# 이미지가 처음 뜰 때는 초기화용 임시 서버가 소켓으로만 잠깐 떴다 내려간다. TCP(-h 127.0.0.1)로 물어야 진짜 서버를 기다린다
for _ in $(seq 60); do
  docker exec "$VERIFY" pg_isready -q -h 127.0.0.1 -U postgres -d bandal && break
  sleep 1
done
docker exec -i "$VERIFY" pg_restore -U postgres -d bandal --no-owner --no-privileges --exit-on-error < "$PART"
RESTORED=$(docker exec "$VERIFY" psql -U postgres -d bandal -Atq -v ON_ERROR_STOP=1 -c "$COUNT_SQL")
if [ "$RESTORED" != "$BEFORE" ]; then
  echo "복구본이 원본과 다르다"
  echo "  원본:   $BEFORE"
  echo "  복구본: $RESTORED"
  exit 1
fi
echo "복구 확인: $RESTORED"
mv "$PART" "$BACKUP_DIR/$NAME"

# 3. 서버 밖(R2)에 올린다. R2는 S3와 같은 방식으로 말해서 AWS CLI로 올린다.
#    region auto는 R2의 약속이다. 체크섬 두 줄은 최신 AWS CLI가 기본으로 붙이는 검사 중 R2가 모르는 것을 끈다
docker run --rm -v "$BACKUP_DIR:/backups:ro" \
  -e AWS_ACCESS_KEY_ID -e AWS_SECRET_ACCESS_KEY -e AWS_DEFAULT_REGION=auto \
  -e AWS_REQUEST_CHECKSUM_CALCULATION=when_required -e AWS_RESPONSE_CHECKSUM_VALIDATION=when_required \
  "$AWS_IMAGE" --endpoint-url "$R2_ENDPOINT" --only-show-errors s3 cp "/backups/$NAME" "s3://$R2_BUCKET/$NAME"
echo "업로드: s3://$R2_BUCKET/$NAME"

# 4. 서버에는 최근 것 몇 개만. 실수로 지운 행은 R2까지 갈 것 없이 여기서 바로 꺼낸다
ls -1t "$BACKUP_DIR"/bandal-*.dump | tail -n +$((KEEP_LOCAL + 1)) | xargs -r rm --
ping ""
echo "백업 성공: $NAME"

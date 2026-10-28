#!/bin/bash
# 묶음 작업(Spring Batch Job) 하나를 batch 컨테이너로 돌리고 끝낸다. 서버 cron이 매일 부른다
#   ./batch.sh notificationCleanupJob              오늘 날짜(한국 시간)로
#   ./batch.sh notificationCleanupJob 2026-10-29   그 날짜로. 실패한 날을 다시 돌릴 때 쓴다
#
# 날짜가 Job의 식별 파라미터다. Spring Batch는 "Job 이름 + 날짜"를 한 번의 일로 본다
#   같은 날짜로 이미 성공했으면 거절한다(cron이 두 번 불러도 하루 한 번만 돈다). 이때 종료 코드 1, 로그에 JobInstanceAlreadyCompleteException
#   (2026-10-28 리허설: 1회차 2,500줄 지우고 종료 코드 0, 같은 날 2회차는 거절되고 종료 코드 1)
#   같은 날짜로 실패했으면 실패한 청크부터 이어서 한다
# 결과: 이 스크립트의 종료 코드(0이면 성공), batch.log, DB의 실행 기록(batch_job_execution, batch_step_execution)
#   실행 기록 보기: docker compose exec postgres psql -U bandal -d bandal -c "select * from batch_job_execution order by job_execution_id desc limit 5"
# 감시: 성공하면 healthchecks.io에 신호, 실패하면 /fail(backup.sh와 같은 방식). 하루 넘게 신호가 없어도 그쪽이 메일을 보낸다
#   같은 날짜를 손으로 다시 돌려 거절당한 것도 종료 코드 1이라 /fail로 간다. 그 뒤 정상 실행이 성공 신호를 보내면 다시 초록이 된다
set -uo pipefail
cd "$(dirname "$0")"

JOB=${1:?돌릴 Job 이름을 주세요. 예: notificationCleanupJob}
DATE=${2:-$(TZ=Asia/Seoul date +%F)}
# 다른 compose 명령으로 돌릴 때(로컬 리허설) COMPOSE로 바꾼다. 다른 스크립트(restore.sh, dlt-replay.sh)와 같다
read -ra COMPOSE <<< "${COMPOSE:-docker compose}"
ENV_FILE=${ENV_FILE:-.env}
# 지금 배포된 이미지로 돌린다. .env에는 태그가 없고 배포가 .deployed-tag에 적어 둔다(restore.sh와 같다)
TAG=${IMAGE_TAG:-$(cat .deployed-tag)}

# .env에서 감시 주소를 꺼낸다. 비어 있으면(로컬 리허설) 알리지 않는다
PING_URL=$(grep -m1 '^BATCH_PING_URL=' "$ENV_FILE" 2> /dev/null | cut -d= -f2- || true)
# 알림이 실패해도 Job 결과(종료 코드)는 바꾸지 않는다
ping() { [ -z "$PING_URL" ] || curl -fsS -m 10 --retry 3 -o /dev/null "$PING_URL$1" || true; }

echo "$(date -u +%FT%TZ) 시작 $JOB date=$DATE 이미지 ${TAG:0:7}"
# run --rm: 컨테이너를 하나 만들어 돌리고 끝나면 지운다. -T: cron에는 터미널이 없다
# 마지막 인자가 Job 파라미터다. "이름=값,타입" 꼴이고 타입을 안 쓰면 문자열이 된다. Job은 LocalDate로 받는다
IMAGE_TAG=$TAG "${COMPOSE[@]}" run --rm -T -e SPRING_BATCH_JOB_NAME="$JOB" batch "date=$DATE,java.time.LocalDate"
CODE=$?
echo "$(date -u +%FT%TZ) 끝 $JOB date=$DATE 종료 코드 $CODE"
if [ "$CODE" -eq 0 ]; then ping ""; else ping /fail; fi
exit $CODE

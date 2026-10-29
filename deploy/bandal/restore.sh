#!/bin/bash
# 덤프 파일 하나로 운영 DB를 통째로 그 시점으로 되돌린다. 그 뒤에 쌓인 데이터는 사라진다
#   ./restore.sh backups/bandal-20261021-190000Z-daily.dump [이미지 태그]
# 마이그레이션이 데이터를 망가뜨린 경우에는 배포 직전 덤프와 함께 그 이전 태그를 준다.
# 태그를 안 주면 지금 버전으로 다시 띄우고, Flyway가 망가뜨린 그 마이그레이션을 또 돌린다
# R2에만 있는 파일은 먼저 받는다(docs/DECISIONS.md의 백업 ADR에 명령이 있다)
# 되돌리기 직전의 DB도 덤프해 둔다. 잘못 고른 파일로 되돌렸어도 다시 돌아올 수 있다
set -Eeuo pipefail
FILE=$(realpath "${1:?되돌릴 덤프 파일을 주세요}")
cd "$(dirname "$0")"

read -ra COMPOSE <<< "${COMPOSE:-docker compose}"
ENV_FILE=${ENV_FILE:-.env}
BACKUP_DIR=${BACKUP_DIR:-backups}
DB_USER=$(grep -m1 "^DB_USERNAME=" "$ENV_FILE" | cut -d= -f2-)
pg() { "${COMPOSE[@]}" exec -T postgres "$@"; }

# 1. 파일부터 본다. 잘렸거나 덤프가 아니면 아무것도 건드리기 전에 멈춘다
pg pg_restore -l < "$FILE" > /dev/null || { echo "읽을 수 없는 덤프다: $FILE"; exit 1; }

# 2. 앱을 멈춘다. 되돌리는 도중에 들어온 쓰기는 어느 쪽에도 남지 않는다
# worker(알림, 통계 컨슈머)도 DB에 쓴다. 이 스크립트는 worker가 생기기 전에 써서 web, backend만 멈추고 있었다
"${COMPOSE[@]}" stop web backend worker

# 3. 지금 DB를 남긴다
mkdir -p "$BACKUP_DIR"
SAFETY=$BACKUP_DIR/before-restore-$(date -u +%Y%m%d-%H%M%SZ).dump
pg pg_dump -U "$DB_USER" -d bandal -Fc > "$SAFETY"
echo "되돌리기 전 DB: $SAFETY"

# 4. DB를 지우고 새로 만들어 덤프를 넣는다. 앱이 붙잡은 연결이 남아 있어도 force가 끊는다
pg psql -U "$DB_USER" -d postgres -v ON_ERROR_STOP=1 -q \
  -c "drop database bandal with (force)" -c "create database bandal owner \"$DB_USER\""
pg pg_restore -U "$DB_USER" -d bandal --no-owner --exit-on-error < "$FILE"
echo "복구 끝: $(basename "$FILE")"

# 5. 앱을 다시 띄운다. Flyway는 덤프 안의 이력을 보고 그 뒤의 마이그레이션만 돌린다
TAG=${2:-$(cat .deployed-tag 2>/dev/null || true)}
if [ -n "${2:-}" ]; then IMAGE_TAG=$TAG "${COMPOSE[@]}" pull web backend; fi
IMAGE_TAG=$TAG "${COMPOSE[@]}" up -d --no-build --wait web backend worker
# 다른 태그로 띄웠으면 다음 배포의 "이전 버전"도 그것이다
if [ -n "${2:-}" ]; then echo "$TAG" > .deployed-tag; fi
echo "앱이 다시 떴다: ${TAG:-local}"

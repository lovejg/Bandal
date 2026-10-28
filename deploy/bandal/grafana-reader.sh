#!/bin/bash
# Grafana가 통계를 읽는 계정(grafana_reader)의 비밀번호를 정하고 로그인을 켠다. 처음 한 번, 그리고 비밀번호를 바꿀 때 부른다
#   ./grafana-reader.sh
# 역할과 읽을 수 있는 표는 마이그레이션(V10__grafana_reader.sql)이 이미 만들어 두었다. 여기서는 로그인만 켠다
# 비밀번호는 관측 묶음의 up.sh가 만든 /srv/monitoring/.env의 GRAFANA_DB_PASSWORD다. 화면에 찍지 않는다
# 다시 돌려도 된다(같은 비밀번호로 다시 정할 뿐이다)
# 새 서버에 덤프를 되돌릴 때(restore.sh): 덤프에는 이 역할에 준 권한이 들어 있다. 역할이 없는 postgres에 되돌리면
#   권한 줄에서 멈추니, 먼저 psql로 "create role grafana_reader nologin"을 한 뒤 되돌리고 이 스크립트를 돌린다
set -Eeuo pipefail
cd "$(dirname "$0")"

read -ra COMPOSE <<< "${COMPOSE:-docker compose}"
ENV_FILE=${ENV_FILE:-.env}
MONITORING_ENV=${MONITORING_ENV:-../monitoring/.env}
DB_USER=$(grep -m1 "^DB_USERNAME=" "$ENV_FILE" | cut -d= -f2-)
PASSWORD=$(grep -m1 "^GRAFANA_DB_PASSWORD=" "$MONITORING_ENV" | cut -d= -f2- || true)
if [ -z "$PASSWORD" ]; then
  echo "$MONITORING_ENV에 GRAFANA_DB_PASSWORD가 없다. 관측 묶음의 up.sh를 먼저 돌린다"
  exit 1
fi

# 비밀번호를 명령 인자(-c, -v)로 넘기면 서버의 프로세스 목록에 잠깐 보인다. 표준 입력으로 넘긴다
# 비밀번호는 up.sh가 만든 16진수라 따옴표가 들어 있지 않다
printf "alter role grafana_reader with login password '%s';\n" "$PASSWORD" \
  | "${COMPOSE[@]}" exec -T postgres psql -U "$DB_USER" -d bandal -v ON_ERROR_STOP=1 -q
echo "grafana_reader 로그인을 켰다"

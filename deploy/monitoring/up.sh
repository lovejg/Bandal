#!/bin/bash
# 서버의 /srv/monitoring에서 배포 작업이 부른다. 다시 돌려도 된다
# 네트워크가 없으면 만들고, Grafana 비밀번호가 없으면 처음 한 번 만들어 .env에 둔다(출력하지 않는다)
# 비밀번호 보기: ssh deploy@서버 'grep GRAFANA /srv/monitoring/.env'
set -euo pipefail
cd "$(dirname "$0")"

docker network inspect monitoring > /dev/null 2>&1 || docker network create monitoring > /dev/null

if ! grep -q '^GRAFANA_ADMIN_PASSWORD=' .env 2>/dev/null; then
  ( umask 077; echo "GRAFANA_ADMIN_PASSWORD=$(openssl rand -hex 16)" >> .env )
  echo "Grafana 관리자 비밀번호를 새로 만들었다(.env)"
fi

docker compose up -d --quiet-pull --wait --wait-timeout 120
# 설정 파일(prometheus/prometheus.yml)만 바뀌면 컨테이너가 다시 만들어지지 않는다. HUP 신호를 보내 다시 읽게 한다.
# compose는 이걸 "Killed"라고 찍지만 프로세스는 죽지 않고 설정만 다시 읽는다
docker compose kill -s HUP prometheus > /dev/null 2>&1
echo "관측 묶음이 떴다"

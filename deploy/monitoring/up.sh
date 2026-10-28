#!/bin/bash
# 서버의 /srv/monitoring에서 배포 작업이 부른다. 다시 돌려도 된다
# 네트워크가 없으면 만들고, Grafana 비밀번호가 없으면 처음 한 번 만들어 .env에 둔다(출력하지 않는다)
# 비밀번호 보기: ssh deploy@서버 'grep GRAFANA /srv/monitoring/.env'
set -euo pipefail
cd "$(dirname "$0")"

docker network inspect monitoring > /dev/null 2>&1 || docker network create monitoring > /dev/null
docker network inspect stats-db > /dev/null 2>&1 || docker network create --internal stats-db > /dev/null

if ! grep -q '^GRAFANA_ADMIN_PASSWORD=' .env 2>/dev/null; then
  ( umask 077; echo "GRAFANA_ADMIN_PASSWORD=$(openssl rand -hex 16)" >> .env )
  echo "Grafana 관리자 비밀번호를 새로 만들었다(.env)"
fi
# 통계 표를 읽는 계정의 비밀번호. 만든 뒤 /srv/bandal/grafana-reader.sh를 한 번 돌려 DB에도 같은 값을 넣는다
if ! grep -q '^GRAFANA_DB_PASSWORD=' .env 2>/dev/null; then
  ( umask 077; echo "GRAFANA_DB_PASSWORD=$(openssl rand -hex 16)" >> .env )
  echo "Grafana의 DB 읽기 계정 비밀번호를 새로 만들었다(.env). /srv/bandal/grafana-reader.sh를 돌려 DB에 넣는다"
fi
# 경고 메일 설정은 배포 작업이 쓴다. 없으면 경고가 조용히 안 나가니 멈춘다
[ -f .env.alerting ] || { echo ".env.alerting이 없다(배포 작업이 만든다)"; exit 1; }

docker compose up -d --quiet-pull --wait --wait-timeout 120
# 설정 파일(prometheus/prometheus.yml)만 바뀌면 컨테이너가 다시 만들어지지 않는다. HUP 신호를 보내 다시 읽게 한다.
# compose는 이걸 "Killed"라고 찍지만 프로세스는 죽지 않고 설정만 다시 읽는다
docker compose kill -s HUP prometheus > /dev/null 2>&1

# Grafana의 데이터 출처(provisioning), Loki, Alloy는 뜰 때만 설정을 읽는다. 설정 폴더가 지난번과 달라졌을 때만 다시 띄운다
# 폴더 내용의 지문(sha256)을 .config-hash-<서비스>에 적어 두고 비교한다
restart_if_changed() {
  local service=$1 dir=$2
  local now
  now=$(find "$dir" -type f -print0 | sort -z | xargs -0 sha256sum | sha256sum | cut -d' ' -f1)
  if [ "$(cat ".config-hash-$service" 2>/dev/null)" != "$now" ]; then
    docker compose restart "$service" > /dev/null
    echo "$now" > ".config-hash-$service"
    echo "$service 설정이 바뀌어 다시 띄웠다"
  fi
}
restart_if_changed grafana grafana/provisioning
restart_if_changed loki loki
restart_if_changed alloy alloy
echo "관측 묶음이 떴다"

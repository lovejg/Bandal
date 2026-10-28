#!/bin/bash
# 부하 테스트 서버의 /srv/bandal에서 돈다: ./up.sh <이미지 태그(커밋 SHA)>
# 운영의 deploy.sh는 쓰지 않는다. 배포 전 R2 백업이 있어서 R2 설정이 없는 이 서버에서는 실패한다.
# 운영과 같은 이미지 태그로 띄워야 운영 숫자와 비교할 수 있다
set -euo pipefail
cd "$(dirname "$0")"
TAG=${1:?이미지 태그를 주세요. 운영의 /srv/bandal/.deployed-tag 와 같은 값}

docker network inspect edge > /dev/null 2>&1 || docker network create edge > /dev/null
docker network inspect monitoring > /dev/null 2>&1 || docker network create monitoring > /dev/null
# 운영 compose의 postgres가 붙는 네트워크. 이 서버에는 Grafana가 없어 비어 있지만, 없으면 compose가 뜨지 않는다
docker network inspect stats-db > /dev/null 2>&1 || docker network create --internal stats-db > /dev/null

# 이 서버의 사설 IP. 포트를 여기에만 연다
export LOADTEST_BIND=$(ip -4 route get 10.0.0.1 | grep -o 'src [0-9.]*' | cut -d' ' -f2)
export IMAGE_TAG=$TAG
C="docker compose -f compose.yml -f compose.loadtest.yml"
$C pull --quiet web backend
$C up -d --no-build --quiet-pull --wait --wait-timeout 180
echo "$TAG" > .deployed-tag
echo "떴다: ${TAG:0:7}, 사설 IP $LOADTEST_BIND"

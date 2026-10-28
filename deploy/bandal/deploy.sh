#!/bin/bash
# 서버의 /srv/bandal에서 배포 작업(GitHub Actions)이 부른다: ./deploy.sh <이미지 태그(커밋 SHA)>
# 새 태그로 받아서 띄우고, 모두 healthy가 안 되면 마지막으로 성공한 태그로 되돌린다.
# 어느 쪽이든 새 태그가 실패하면 0이 아닌 값으로 끝나서 워크플로가 빨간불이 된다
set -euo pipefail
cd "$(dirname "$0")"

NEW_TAG=${1:?이미지 태그를 주세요}
# 마지막으로 성공한 태그. 처음 배포라면 없다
LAST_FILE=.deployed-tag
PREV_TAG=$(cat "$LAST_FILE" 2>/dev/null || true)
# backend는 start_period가 60초다. 그 뒤로 health 확인 몇 번이 더 돌 여유를 준다
WAIT_SECONDS=${WAIT_SECONDS:-180}

# 앱 이미지 두 개만 받는다. postgres, redis는 버전을 고정해 두어서 배포마다 받을 게 없다.
# --wait: 띄운 뒤 healthcheck가 있는 컨테이너가 healthy가 될 때까지 기다리고, 안 되면 실패로 끝난다
up() {
  IMAGE_TAG=$1 docker compose pull web backend
  IMAGE_TAG=$1 docker compose up -d --no-build --wait --wait-timeout "$WAIT_SECONDS"
}

echo "배포: ${PREV_TAG:-없음} -> $NEW_TAG"

# backend가 붙는 관측 네트워크. 관측 묶음보다 앱이 먼저 떠도 되게 여기서도 만든다
docker network inspect monitoring > /dev/null 2>&1 || docker network create monitoring > /dev/null
# Grafana가 통계 표를 읽으러 postgres에 오는 네트워크. internal이라 이 네트워크로는 인터넷에 나가지 못한다
# 처음 이 네트워크가 붙는 배포 한 번은 postgres 컨테이너가 다시 만들어진다(몇 초 끊김, 데이터는 볼륨에 그대로)
docker network inspect stats-db > /dev/null 2>&1 || docker network create --internal stats-db > /dev/null

# 배포 직전 백업. 이번 마이그레이션이 데이터를 망가뜨리면 이 덤프가 바로 앞 상태다.
# 백업이 실패하면 배포하지 않는다(set -e). 돌아갈 자리 없이 마이그레이션을 돌리지 않는다
if [ -n "$(docker compose ps -q postgres 2>/dev/null)" ]; then
  bash ./backup.sh "predeploy-${NEW_TAG:0:7}"
else
  echo "DB가 아직 없다(첫 배포). 배포 전 백업을 건너뛴다"
fi

if up "$NEW_TAG"; then
  echo "$NEW_TAG" > "$LAST_FILE"
  # 일주일 넘게 안 쓴 이미지를 지운다. 디스크가 차는 걸 막는다. 되돌릴 때는 레지스트리에서 다시 받는다
  docker image prune -af --filter "until=168h" > /dev/null || true
  echo "배포 성공: $NEW_TAG"
  exit 0
fi

echo "새 버전이 healthy가 되지 않았다. backend 로그 마지막 부분:"
IMAGE_TAG=$NEW_TAG docker compose logs --tail 40 backend || true

if [ -z "$PREV_TAG" ]; then
  echo "되돌릴 이전 버전이 없다(첫 배포)"
  exit 1
fi

echo "이전 버전으로 되돌린다: $PREV_TAG"
if up "$PREV_TAG"; then
  echo "되돌리기 성공. 지금 떠 있는 버전: $PREV_TAG"
else
  echo "되돌리기도 실패했다. 서버에 들어가서 직접 봐야 한다"
fi
exit 1

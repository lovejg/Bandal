#!/bin/bash
# 시연(샘플) 데이터를 새로 넣는다. 존재하지 않는 학교 둘(반달대학교, 달빛대학교)에 계정 5개씩, 모집중 방 5개씩과
# 정산중, 주문완료, 배달완료 방 하나씩. 내용은 demo-seed.sql에 있다
#   서버(/srv/bandal):  DEMO_PASSWORD='...' bash demo-seed.sh
#   로컬(저장소 맨 위): COMPOSE="docker compose" ENV_FILE=/dev/null DEMO_PASSWORD='...' bash deploy/bandal/demo-seed.sh
# 계정: demo1~5@bandal-univ.example, demo1~5@dalbit-univ.example. 비밀번호는 모두 DEMO_PASSWORD
# 비밀번호는 git에 두지 않는다. 운영 DB에 들어가는 계정이라, 공개된 비밀번호면 누구나 시연 계정으로 들어온다
# 모집중 방의 마감이 "지금부터 몇 분 뒤"라 시연 직전에 다시 돌린다. 몇 번을 돌려도 같은 모양이다
set -euo pipefail
cd "$(dirname "$0")"

read -ra COMPOSE <<< "${COMPOSE:-docker compose}"
ENV_FILE=${ENV_FILE:-.env}
PW=${DEMO_PASSWORD:?시연 계정 비밀번호를 DEMO_PASSWORD로 주세요}
if [ ${#PW} -lt 8 ]; then echo "비밀번호는 8자 이상이어야 로그인 화면이 받는다"; exit 1; fi
# .env가 없는 로컬은 docker-compose.yml의 계정(bandal)
DB_USER=$(grep -m1 '^DB_USERNAME=' "$ENV_FILE" 2> /dev/null | cut -d= -f2- || true)
DB_USER=${DB_USER:-bandal}

# Spring의 BCrypt와 같은 해시를 만든다. htpasswd는 $2y$로 내놓는데 알고리즘이 같아서 $2a$로 바꾼다(loadtest/seed.sh와 같다)
# 비밀번호는 명령 인자가 아니라 표준 입력(-i)으로 넘긴다. 인자로 주면 그동안 ps에 보인다
HASH=$(printf '%s' "$PW" | docker run --rm -i httpd:2.4-alpine htpasswd -niBC 10 x | cut -d: -f2 | sed 's/^\$2y\$/$2a$/')

"${COMPOSE[@]}" exec -T postgres psql -U "$DB_USER" -d bandal -q -v pw_hash="'$HASH'" < demo-seed.sql

# 방 목록 캐시를 비운다. 안 비우면 10초 동안 지운 방이 목록에 남는다
"${COMPOSE[@]}" exec -T redis redis-cli --scan --pattern 'rooms:*' < /dev/null | while read -r k; do
  "${COMPOSE[@]}" exec -T redis redis-cli del "$k" < /dev/null > /dev/null
done
echo "시연 데이터를 넣었다"

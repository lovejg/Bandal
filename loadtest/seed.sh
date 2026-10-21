#!/bin/bash
# 부하 테스트 서버의 /srv/bandal에서 돈다: ./seed.sh
# seed.sql로 측정용 데이터를 새로 넣고, Redis의 방 목록 캐시를 비운다. 측정마다 같은 데이터에서 시작하려고 매번 돌린다
set -euo pipefail
cd "$(dirname "$0")"

# 운영 DB를 지우는 사고를 막는다. 이 스크립트는 부하 테스트 서버에서만 돈다
if [ ! -f compose.loadtest.yml ]; then echo "부하 테스트 서버가 아니다(compose.loadtest.yml이 없다). 멈춘다"; exit 1; fi

C="docker compose -f compose.yml -f compose.loadtest.yml"
U=$(grep -m1 "^DB_USERNAME=" .env | cut -d= -f2-)
PW=${LOADTEST_PASSWORD:-loadtest-pass-1}
# Spring의 BCrypt와 같은 해시. htpasswd는 $2y$로 내놓는데 알고리즘이 같아서 $2a$로 바꾼다
HASH=$(docker run --rm httpd:2.4-alpine htpasswd -nbBC 10 x "$PW" | cut -d: -f2 | sed 's/^\$2y\$/$2a$/')

start=$(date +%s)
$C exec -T postgres psql -U "$U" -d bandal -q -v pw_hash="'$HASH'" < seed.sql
$C exec -T redis redis-cli --scan --pattern 'rooms:*' < /dev/null | while read -r k; do $C exec -T redis redis-cli del "$k" < /dev/null > /dev/null; done
echo "넣기 끝: $(( $(date +%s) - start ))초"

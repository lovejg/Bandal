#!/bin/bash
# 바깥 Nginx의 HTTPS 인증서를 Let's Encrypt에서 받고(issue) 갱신한다(renew).
# 도메인 주인인지는 80번 포트로 증명한다(HTTP-01): certbot이 acme/에 파일을 쓰고, Let's Encrypt가 Nginx를 통해 그 파일을 가져간다
#   ./cert.sh issue bandal.site www.bandal.site   도메인마다 처음 한 번. 첫 이름이 인증서 이름(폴더 이름)이 된다
#   ./cert.sh renew                               cron이 하루 두 번. 만료 30일 전이 된 것만 실제로 갱신한다
# cron 등록:
#   17 3,15 * * * /srv/edge/cert.sh renew >> /srv/edge/cert.log 2>&1
set -Eeuo pipefail
cd "$(dirname "$0")"

CMD=${1:?issue <도메인...> 또는 renew}
shift
# 로컬 리허설에서는 아래를 바꿔서 부른다(시험용 ACME 서버). 서버에서는 기본값 그대로
read -ra COMPOSE <<< "${COMPOSE:-docker compose}"
ACME_SERVER=${ACME_SERVER:-https://acme-v02.api.letsencrypt.org/directory}
read -ra CERTBOT_EXTRA <<< "${CERTBOT_EXTRA:-}"
CERTBOT_IMAGE=certbot/certbot:v4.0.0
# healthchecks.io 감시 주소. 백업과 같은 방식이다. Let's Encrypt는 만료 예고 메일을 보내지 않는다.
# 같은 폴더의 .env에 CERT_PING_URL=<주소> 한 줄로 둔다(서버에서 직접 만든다. git에 올리지 않는다)
PING_URL=$(grep -m1 '^CERT_PING_URL=' .env 2> /dev/null | cut -d= -f2- || true)

ping() { [ -z "$PING_URL" ] || curl -fsS -m 10 --retry 3 -o /dev/null "$PING_URL$1" || true; }
finish() {
  local rc=$?
  if [ $rc -ne 0 ]; then echo "인증서 작업 실패: $CMD"; ping /fail; fi
}
trap finish EXIT

# certbot은 컨테이너로 돌린다. 받은 인증서와 계정은 letsencrypt/에 남는다
certbot() {
  docker run --rm --network edge \
    -v "$PWD/letsencrypt:/etc/letsencrypt" -v "$PWD/acme:/var/www/acme" -v "$PWD/certs:/certs" \
    "$CERTBOT_IMAGE" "$@"
}

# certbot이 관리하는 폴더(letsencrypt/live)를 Nginx가 읽는 폴더(certs/)로 복사한다.
# Nginx가 certbot 폴더를 바로 읽지 않는 건 첫 인증서를 받기 전 자리에 임시 인증서를 둬야 해서다.
# certbot은 자기 폴더에 모르는 파일이 있으면 다른 이름(-0001)으로 새로 만든다
publish() {
  docker run --rm --entrypoint sh -v "$PWD/letsencrypt:/etc/letsencrypt" -v "$PWD/certs:/certs" "$CERTBOT_IMAGE" -c '
    for d in /etc/letsencrypt/live/*/; do
      [ -d "$d" ] || continue
      n=$(basename "$d")
      mkdir -p "/certs/$n"
      cp -L "$d/fullchain.pem" "$d/privkey.pem" "/certs/$n/"
    done'
}

# 설정을 먼저 검사하고 다시 읽게 한다. 끊기는 연결 없이 새 인증서로 바뀐다
reload() {
  "${COMPOSE[@]}" exec -T nginx nginx -t -q
  "${COMPOSE[@]}" exec -T nginx nginx -s reload
}

case $CMD in
  issue)
    [ $# -ge 1 ] || { echo "도메인을 주세요"; exit 1; }
    NAME=$1
    # 첫 인증서가 없으면 Nginx가 뜨지 않는다(설정이 그 파일을 가리킨다). 그런데 인증서를 받으려면 Nginx가 80번으로 증명 파일을
    # 내줘야 한다. 그래서 하루짜리 임시 인증서(스스로 서명한 것)를 먼저 두고 띄운다. 브라우저는 이걸 믿지 않는다
    if [ ! -f "certs/$NAME/fullchain.pem" ]; then
      echo "임시 인증서를 둔다: $NAME"
      docker run --rm --entrypoint sh -v "$PWD/certs:/certs" "$CERTBOT_IMAGE" -c "
        mkdir -p /certs/$NAME && openssl req -x509 -nodes -newkey rsa:2048 -days 1 -subj /CN=$NAME \
          -keyout /certs/$NAME/privkey.pem -out /certs/$NAME/fullchain.pem 2> /dev/null"
    fi
    mkdir -p acme
    "${COMPOSE[@]}" up -d
    reload

    DOMAINS=()
    for d in "$@"; do DOMAINS+=(-d "$d"); done
    # 계정 메일은 두지 않는다. Let's Encrypt는 만료 알림 메일을 없앴고, 감시는 healthchecks가 한다
    certbot certonly --webroot -w /var/www/acme --cert-name "$NAME" "${DOMAINS[@]}" \
      --server "$ACME_SERVER" --agree-tos --register-unsafely-without-email --non-interactive "${CERTBOT_EXTRA[@]}"
    ;;
  renew)
    certbot renew --webroot -w /var/www/acme --non-interactive "${CERTBOT_EXTRA[@]}"
    ;;
  *)
    echo "모르는 명령: $CMD"; exit 1
    ;;
esac

publish
reload
for d in certs/*/; do
  docker run --rm --entrypoint sh -v "$PWD/certs:/certs:ro" "$CERTBOT_IMAGE" -c \
    "openssl x509 -in /certs/$(basename "$d")/fullchain.pem -noout -subject -issuer -enddate | tr '\n' ' '; echo"
done
ping ""
echo "인증서 작업 끝: $CMD"

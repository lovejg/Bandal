#!/bin/bash
# 내 PC(WSL)에서 새 서버를 준비한다. 서버를 처음 받은 날, 그리고 서버를 새로 받아 다시 살리는 날 돌린다
#   bash deploy/server/bootstrap.sh <서버 공인 IP>
# 하는 일: 배포 키 만들기(없으면) → setup.sh를 서버에서 root로 실행 → 바깥 Nginx 파일 올리기 → GitHub에 넣을 값 안내
# 다시 돌려도 된다
set -Eeuo pipefail
cd "$(dirname "$0")"

IP=${1:?서버 공인 IP를 주세요}
# 로컬 리허설에서는 아래를 바꿔서 부른다
ADMIN_KEY=${ADMIN_KEY:-$HOME/.ssh/bandal_oci}
DEPLOY_KEY=${DEPLOY_KEY:-$HOME/.ssh/bandal_deploy}
KNOWN_HOSTS=${KNOWN_HOSTS:-$HOME/.ssh/known_hosts}
PORT=${SSH_PORT:-22}

# 처음 보는 서버의 호스트 키는 받아 적고(accept-new), 그 뒤로 바뀌면 접속을 거부한다.
# BatchMode: 무엇이든 물어봐야 하는 상황(키 암호 등)이면 기다리지 않고 바로 실패한다
SSH_OPTS=(-p "$PORT" -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile="$KNOWN_HOSTS" -o ConnectTimeout=10 -o BatchMode=yes)

# 내 키에 암호가 걸려 있으면 ssh-agent에 먼저 열어 두어야 한다. 안 그러면 위의 BatchMode 때문에 접속이 거절된다
if ! ssh-keygen -y -P "" -f "$ADMIN_KEY" > /dev/null 2>&1; then
  FP=$(ssh-keygen -lf "$ADMIN_KEY.pub" | awk '{print $2}')
  ssh-add -l 2> /dev/null | grep -qF "$FP" || {
    echo "$ADMIN_KEY 에 암호가 걸려 있다. 먼저 이렇게 열어 둔다:"
    echo '  eval "$(ssh-agent -s)" && ssh-add '"$ADMIN_KEY"
    exit 1
  }
fi

# 배포 키는 내 키(bandal_oci)와 따로 둔다. GitHub Secrets에 들어가는 건 이쪽이다
if [ ! -f "$DEPLOY_KEY" ]; then
  echo "== 배포 키를 만든다: $DEPLOY_KEY"
  ssh-keygen -q -t ed25519 -N "" -C bandal-deploy -f "$DEPLOY_KEY"
fi

echo "== 서버 준비(setup.sh)"
ssh "${SSH_OPTS[@]}" -i "$ADMIN_KEY" "ubuntu@$IP" "sudo bash -s -- '$(cat "$DEPLOY_KEY.pub")'" < setup.sh

echo "== 바깥 Nginx 파일 올리기(/srv/edge)"
tar -C ../edge -cz nginx.conf compose.yml cert.sh | ssh "${SSH_OPTS[@]}" -i "$DEPLOY_KEY" "deploy@$IP" 'tar -xz -C /srv/edge'

echo "== 배포 사용자로 확인"
ssh "${SSH_OPTS[@]}" -i "$DEPLOY_KEY" "deploy@$IP" \
  'echo "docker $(docker version --format "{{.Server.Version}}"), edge 네트워크 $(docker network ls -q --filter name=^edge$ | wc -l)개"; ls /srv/edge; crontab -l | grep -c srv | sed "s/^/cron /"'

echo "== 비밀번호 로그인이 막혔는지(Permission denied (publickey)가 나와야 한다)"
ssh "${SSH_OPTS[@]}" -o PubkeyAuthentication=no -o PreferredAuthentications=password,keyboard-interactive \
  -o BatchMode=yes "ubuntu@$IP" true 2>&1 | tail -1 || true

cat <<EOF

== GitHub > Settings > Environments > production 에 넣을 값
  Secret DEPLOY_SSH_KEY      : 이 파일 내용 전체  ->  cat $DEPLOY_KEY
  Secret DEPLOY_KNOWN_HOSTS  : 아래 줄 전체
$(ssh-keygen -F "$([ "$PORT" = 22 ] && echo "$IP" || echo "[$IP]:$PORT")" -f "$KNOWN_HOSTS" | grep -v '^#')
  Variable DEPLOY_HOST       : $IP
  Variable DEPLOY_USER       : deploy
EOF

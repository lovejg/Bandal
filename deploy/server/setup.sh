#!/bin/bash
# 새 서버(Ubuntu 24.04)를 Bandal을 띄울 수 있는 상태로 만든다. root로 돈다. 직접 부르지 않고 bootstrap.sh가 SSH로 보낸다
#   sudo bash setup.sh "<배포 공개 키 한 줄>"
# 몇 번을 다시 돌려도 된다. 단계마다 이미 된 것은 건너뛰거나 같은 내용으로 덮어쓴다
set -Eeuo pipefail
DEPLOY_PUBKEY=${1:?배포 공개 키를 주세요}
DEPLOY_USER=deploy
export DEBIAN_FRONTEND=noninteractive
step() { echo "== $*"; }

# cron 시각과 로그 시각을 UTC 하나로 본다(backup.sh, cert.sh의 cron 설명이 UTC 기준이다)
step "시간대 UTC"
timedatectl set-timezone UTC

# 보안 업데이트는 Ubuntu가 매일 알아서 깐다(unattended-upgrades). 재부팅은 자동으로 하지 않는다
step "패키지"
apt-get update -q
apt-get upgrade -yq
# cron은 백업과 인증서 갱신이 쓴다. 클라우드 이미지에 보통 있지만 없는 이미지도 있다(리허설 이미지에 없었다)
apt-get install -yq ca-certificates curl unattended-upgrades cron

step "Docker"
if ! command -v docker > /dev/null; then
  # Ubuntu 기본 저장소의 docker.io는 버전이 늦고 compose 플러그인이 따로라, Docker 공식 저장소에서 받는다
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update -q
  apt-get install -yq docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
fi
# 컨테이너 로그는 기본값이면 끝없이 쌓여서 언젠가 디스크를 채운다. 컨테이너마다 10MB짜리 3개까지만 남긴다
DAEMON_JSON='{ "log-driver": "local", "log-opts": { "max-size": "10m", "max-file": "3" } }'
if [ "$(cat /etc/docker/daemon.json 2> /dev/null)" != "$DAEMON_JSON" ]; then
  echo "$DAEMON_JSON" > /etc/docker/daemon.json
  systemctl restart docker
fi
systemctl enable --now docker > /dev/null 2>&1

# 메모리가 모자라는 순간 커널이 컨테이너를 죽이는(OOM) 대신 잠깐 느려지게 하는 여유분이다. 평소에는 거의 쓰지 않게 한다
step "스왑 2GB"
if ! swapon --show=NAME --noheadings | grep -q '^/swapfile$'; then
  [ -f /swapfile ] || { fallocate -l 2G /swapfile; chmod 600 /swapfile; mkswap /swapfile > /dev/null; }
  swapon /swapfile
fi
grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
echo 'vm.swappiness=10' > /etc/sysctl.d/90-bandal-swap.conf
sysctl -q -p /etc/sysctl.d/90-bandal-swap.conf

# 배포 전용 사용자. sudo는 없다. 다만 docker 그룹이라 컨테이너로 무엇이든 할 수 있어서 사실상 root와 같다(ADR에 적었다).
# 따로 두는 이유는 키를 따로 바꾸고 끊을 수 있게 하려는 것이다. 이 키가 새면 이 키만 지우면 되고, 내 키(ubuntu)는 그대로다
step "배포 사용자 $DEPLOY_USER"
id "$DEPLOY_USER" > /dev/null 2>&1 || useradd -m -s /bin/bash "$DEPLOY_USER"
usermod -aG docker "$DEPLOY_USER"
install -d -m 700 -o "$DEPLOY_USER" -g "$DEPLOY_USER" "/home/$DEPLOY_USER/.ssh"
# restrict: 포트 포워딩, 에이전트 전달, 터미널(pty)을 끈다. 배포 작업은 명령만 보내고 받는다
echo "restrict $DEPLOY_PUBKEY" > "/home/$DEPLOY_USER/.ssh/authorized_keys"
chown "$DEPLOY_USER:$DEPLOY_USER" "/home/$DEPLOY_USER/.ssh/authorized_keys"
chmod 600 "/home/$DEPLOY_USER/.ssh/authorized_keys"

step "폴더와 edge 네트워크"
install -d -o "$DEPLOY_USER" -g "$DEPLOY_USER" /srv/bandal /srv/bandal/backups /srv/edge
docker network inspect edge > /dev/null 2>&1 || docker network create edge > /dev/null

# 키로만 들어온다. 봇이 비밀번호를 아무리 넣어 봐도 시도할 문이 없다.
# sshd는 같은 설정이 여러 번 나오면 처음 것을 쓴다. 파일 이름(10-)이 클라우드 이미지 기본 설정(60-)보다 앞서서 이 값이 이긴다
step "SSH"
cat > /etc/ssh/sshd_config.d/10-bandal.conf <<'EOF'
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin no
AllowUsers ubuntu deploy
X11Forwarding no
EOF
# 설정이 틀리면 reload 전에 멈춘다. 틀린 채로 다시 읽으면 SSH로 다시 못 들어올 수 있다.
# 검사 도구는 /run/sshd가 있어야 돈다. 이 폴더는 sshd가 뜰 때 생기는데, 위의 업그레이드로 openssh가 바뀌면 sshd가 내려가 있어서 없다
install -d -m 0755 /run/sshd
sshd -t
# 24.04는 접속이 와야 sshd를 띄운다(소켓 활성화). 떠 있으면 다시 읽고, 아니면 다음 접속부터 이 설정으로 뜬다
systemctl try-reload-or-restart ssh

# 첫 배포 전에는 .deployed-tag가 없고, 첫 인증서 전에는 letsencrypt/live가 없다. 그 전에는 돌지 않는다
step "cron"
crontab -u "$DEPLOY_USER" - <<'EOF'
# 서버 시계는 UTC다. 19시 = 한국 새벽 4시
0 19 * * * [ -f /srv/bandal/.deployed-tag ] && /srv/bandal/backup.sh daily >> /srv/bandal/backups/backup.log 2>&1
17 3,15 * * * [ -d /srv/edge/letsencrypt/live ] && /srv/edge/cert.sh renew >> /srv/edge/cert.log 2>&1
EOF

step "끝"
echo "docker $(docker version --format '{{.Server.Version}}'), $(docker compose version --short), swap $(swapon --show=SIZE --noheadings)"

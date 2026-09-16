#!/usr/bin/env bash
# 손 배포. 작업지시-15 5절 3번 — CI/CD 를 만들지 않고 이 스크립트 한 번이다.
#
#   deploy/push.sh ubuntu@<서버IP>          # 빌드 → 전송 → 재기동
#   deploy/push.sh ubuntu@<서버IP> --no-build
#
# 개발 PC 에서 jar 와 프론트 번들을 만들고(무거운 일은 여기서 한다, 4절),
# 서버의 /opt/staysync 에 올린 뒤 compose 를 다시 띄운다. 서버의 docker build 는
# COPY 뿐이라 2GB 에서도 안전하다.
#
# .env 는 올리지 않는다. 서버에 한 번 손으로 만든다(확인-09).
set -euo pipefail

HOST="${1:?사용법: deploy/push.sh user@host [--no-build]}"
REMOTE=/opt/staysync
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

if [[ "${2:-}" != "--no-build" ]]; then
  echo "== 백엔드 jar"
  (cd "$ROOT" && ./gradlew --quiet :bootJar -x test)
  echo "== 프론트 번들"
  (cd "$ROOT/frontend" && npm run --silent build)
fi

echo "== 전송"
ssh "$HOST" "mkdir -p $REMOTE/app $REMOTE/web.new"
scp -q "$ROOT/build/libs/staysync.jar" "$HOST:$REMOTE/app/staysync.jar"
scp -q "$ROOT/deploy/app/Dockerfile" "$HOST:$REMOTE/app/Dockerfile"
scp -q "$ROOT/deploy/compose.yml" "$ROOT/deploy/Caddyfile" "$HOST:$REMOTE/"
scp -qr "$ROOT/frontend/dist/." "$HOST:$REMOTE/web.new/"

echo "== 재기동"
# 번들은 web/ 안의 내용을 통째로 갈아 넣는다. **디렉터리를 mv 로 바꿔치지 않는다** —
# compose 의 바인드 마운트(./web:/srv)는 inode 를 따라가서, mv 하면 Caddy 는 옛
# 디렉터리를 계속 서빙하고 다음 배포에서 그 디렉터리를 지우는 순간 404 가 된다.
# 실제로 두 번 배포 뒤 브라우저가 두 버전 전 번들을 받고 있었고 세 번째에 404 였다.
# 파일 단위로 덮지 않고 비우고 넣는 이유는 옛 해시의 청크가 남아 섞이기 때문이다.
#
# Caddyfile 은 바인드 마운트라 up -d 로는 다시 읽히지 않는다. reload 가 적용한다.
# exec 에 -w 를 주는 이유: 기본 작업 디렉터리가 컨테이너 밖이라 exec 이 거절된다.
ssh "$HOST" "cd $REMOTE \
  && mkdir -p web && rm -rf web/* && cp -a web.new/. web/ && rm -rf web.new web.old \
  && docker compose build --quiet app \
  && docker compose up -d \
  && docker compose exec -w /etc/caddy caddy caddy reload --config /etc/caddy/Caddyfile >/dev/null 2>&1 \
  && docker image prune -f >/dev/null"

echo "== 헬스체크"
ssh "$HOST" "cd $REMOTE && for i in \$(seq 1 30); do \
  s=\$(docker inspect -f '{{.State.Health.Status}}' \$(docker compose ps -q app)); \
  [ \"\$s\" = healthy ] && echo healthy && exit 0; sleep 5; done; \
  echo \"app 이 \$s 다\"; docker compose logs --tail=50 app; exit 1"

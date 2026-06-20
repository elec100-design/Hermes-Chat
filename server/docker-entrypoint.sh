#!/bin/bash
# hermes-agent per-user 컨테이너 엔트리포인트 — T-B01
# 실행 순서: .env 구성 → gateway 시작(백그라운드) → bridge 시작(포그라운드 PID 1)
set -euo pipefail

HERMES_HOME="${HERMES_HOME:-$HOME/.hermes}"
GATEWAY_PORT="${HERMES_GATEWAY_PORT:-8642}"
BRIDGE_PORT="${HERMES_BRIDGE_PORT:-8765}"
MODEL_NAME="${MODEL_NAME:-default}"
HERMES_API_KEY="${HERMES_API_KEY:-}"
BRIDGE_TOKEN="${HERMES_BRIDGE_TOKEN:-}"

echo "[entrypoint] HERMES_HOME=$HERMES_HOME  gateway=$GATEWAY_PORT  bridge=$BRIDGE_PORT"

# ~/.hermes 디렉터리 보장
mkdir -p "$HERMES_HOME"

# default 프로필 .env 설정 (API 서버 활성화)
# default 프로필은 ~/.hermes/.env — 나머지 프로필은 ~/.hermes/profiles/<name>/.env
DEFAULT_ENV="$HERMES_HOME/.env"

_has_key() {
    grep -q "^${1}=" "$DEFAULT_ENV" 2>/dev/null
}

echo "[entrypoint] Configuring default profile .env..."
touch "$DEFAULT_ENV"

# 각 키가 없을 때만 추가 (볼륨 재사용 시 덮어쓰기 방지)
_has_key "API_SERVER_ENABLED"   || echo "API_SERVER_ENABLED=true"               >> "$DEFAULT_ENV"
_has_key "API_SERVER_PORT"      || echo "API_SERVER_PORT=${GATEWAY_PORT}"        >> "$DEFAULT_ENV"
_has_key "API_SERVER_HOST"      || echo "API_SERVER_HOST=0.0.0.0"               >> "$DEFAULT_ENV"
_has_key "API_SERVER_KEY"       || echo "API_SERVER_KEY=${HERMES_API_KEY}"       >> "$DEFAULT_ENV"
_has_key "API_SERVER_MODEL_NAME"|| echo "API_SERVER_MODEL_NAME=${MODEL_NAME}"    >> "$DEFAULT_ENV"

# hermes-agent 게이트웨이 시작 (백그라운드)
# `hermes gateway restart` = Popen(start_new_session=True) 형태로 데몬 시작
echo "[entrypoint] Starting hermes gateway on port ${GATEWAY_PORT}..."
HOME="$HOME" HERMES_HOME="$HERMES_HOME" \
    hermes gateway restart > /tmp/hermes-gateway.log 2>&1 &
GATEWAY_LAUNCHER_PID=$!

# 게이트웨이 헬스 폴링 (최대 60초)
echo "[entrypoint] Waiting for gateway to be ready..."
READY=0
for i in $(seq 1 12); do
    if curl -sf "http://localhost:${GATEWAY_PORT}/health" > /dev/null 2>&1; then
        echo "[entrypoint] Gateway ready (attempt ${i})."
        READY=1
        break
    fi
    echo "[entrypoint] ... waiting (${i}/12)"
    sleep 5
done

if [ "$READY" -eq 0 ]; then
    echo "[entrypoint] WARNING: Gateway did not respond within 60s. Continuing anyway."
    echo "[entrypoint] Gateway log:"
    cat /tmp/hermes-gateway.log 2>/dev/null || true
fi

# Bridge 시작 (포그라운드 → PID 1, SIGTERM 직접 수신)
echo "[entrypoint] Starting hermes bridge on port ${BRIDGE_PORT}..."
export HERMES_BRIDGE_TOKEN="$BRIDGE_TOKEN"
exec python3 /home/hermes/hermes_bridge.py --port "$BRIDGE_PORT" --host 0.0.0.0

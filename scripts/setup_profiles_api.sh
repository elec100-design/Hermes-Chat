#!/bin/bash
# 맥미니에서 1회 실행: 모든 hermes 프로필의 API 서버를 활성화하고 고유 포트를 배정한다.
#
# 사용법:
#   ./setup_profiles_api.sh <API_KEY>
#
# 동작:
#   - default 프로필(~/.hermes/.env): 포트 8642, 0.0.0.0 바인딩 확인
#   - ~/.hermes/profiles/<name>/.env: 8643부터 순서대로 포트 배정
#   - 이미 API_SERVER_PORT가 설정된 프로필은 기존 포트 유지
#   - 설정 변경된 게이트웨이 재시작
#
# 주의: API_SERVER_HOST=0.0.0.0 은 Tailscale 등 사설망 전제. 공인망에 직접 노출 금지.

set -euo pipefail

API_KEY="${1:-}"
if [ -z "$API_KEY" ]; then
    echo "사용법: $0 <API_KEY>"
    exit 1
fi

HERMES_HOME="${HERMES_HOME:-$HOME/.hermes}"
NEXT_PORT=8643

# .env 파일에서 key를 value로 설정(있으면 교체, 없으면 추가)
set_env() {
    local file="$1" key="$2" value="$3"
    touch "$file"
    if grep -q "^${key}=" "$file"; then
        sed -i '' "s|^${key}=.*|${key}=${value}|" "$file"
    else
        echo "${key}=${value}" >> "$file"
    fi
}

get_env() {
    local file="$1" key="$2"
    grep "^${key}=" "$file" 2>/dev/null | head -1 | cut -d= -f2- || true
}

configure_profile() {
    local env_file="$1" name="$2" port="$3"
    echo "── 프로필 '${name}' → 포트 ${port}"
    set_env "$env_file" "API_SERVER_ENABLED" "true"
    set_env "$env_file" "API_SERVER_PORT" "$port"
    set_env "$env_file" "API_SERVER_HOST" "0.0.0.0"
    set_env "$env_file" "API_SERVER_KEY" "$API_KEY"
    # API_SERVER_MODEL_NAME 을 프로필 이름으로 → 앱의 자동 검색이 이름을 인식
    set_env "$env_file" "API_SERVER_MODEL_NAME" "$name"
}

# 1) default 프로필
echo "═══ default 프로필 (${HERMES_HOME})"
configure_profile "$HERMES_HOME/.env" "default" 8642

# 2) 나머지 프로필 (이름 순으로 안정적 포트 배정)
if [ -d "$HERMES_HOME/profiles" ]; then
    for dir in $(ls -d "$HERMES_HOME/profiles"/*/ 2>/dev/null | sort); do
        name="$(basename "$dir")"
        env_file="${dir}.env"
        existing="$(get_env "$env_file" "API_SERVER_PORT")"
        if [ -n "$existing" ]; then
            port="$existing"
        else
            port="$NEXT_PORT"
        fi
        configure_profile "$env_file" "$name" "$port"
        if [ "$port" -ge "$NEXT_PORT" ]; then
            NEXT_PORT=$((port + 1))
        fi
    done
fi

# 3) 게이트웨이 재시작
echo
echo "═══ 게이트웨이 재시작"
hermes gateway restart || echo "(default 재시작 실패 — 수동 확인 필요)"
if [ -d "$HERMES_HOME/profiles" ]; then
    for dir in "$HERMES_HOME/profiles"/*/; do
        name="$(basename "$dir")"
        hermes --profile "$name" gateway restart \
            || "$name" gateway restart \
            || echo "(${name} 재시작 실패 — 수동 확인 필요)"
    done
fi

echo
echo "═══ 확인"
echo "각 포트 응답 점검:"
for port in $(seq 8642 "$NEXT_PORT"); do
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "http://127.0.0.1:${port}/health" || true)
    [ "$code" = "200" ] && echo "  ✓ 포트 ${port} OK"
done
echo "완료. 아이폰 앱 설정에서 '프로필 자동 검색'을 누르세요."

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
#   - 프로필별 gateway install(서비스 등록) 후 재시작, 포트 헬스체크
#
# 주의:
#   - API_SERVER_HOST=0.0.0.0 은 Tailscale 등 사설망 전제. 공인망에 직접 노출 금지.
#   - launchd 서비스 등록(gateway install)은 SSH 세션에서 실패할 수 있다
#     (Bootstrap failed: 5). 그 경우 hermes가 백그라운드 프로세스로 폴백하는데,
#     동작은 하지만 재부팅 시 자동 시작이 안 된다 → 맥미니 로컬 터미널(화면 앞)에서
#     이 스크립트를 한 번 더 실행하면 서비스로 영구 등록된다.
#   - restart가 포그라운드로 뜨는 경우가 있어 nohup으로 띄운다. 스크립트는 절대
#     멈추지 않고, 헬스체크 결과만 보고한다.

set -uo pipefail

API_KEY="${1:-}"
if [ -z "$API_KEY" ]; then
    echo "사용법: $0 <API_KEY>"
    exit 1
fi

HERMES_HOME="${HERMES_HOME:-$HOME/.hermes}"
NEXT_PORT=8643
PROFILE_PORTS=""   # "name port" 줄 목록 (bash 3.2 호환 — 연관배열 없음)

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
    PROFILE_PORTS="${PROFILE_PORTS}${name} ${port}
"
}

# 게이트웨이 재시작 — 어떤 경우에도 스크립트를 블록하지 않는다.
restart_gateway() {
    local name="$1" port="$2"
    local cmd=(hermes)
    [ "$name" != "default" ] && cmd+=(--profile "$name")

    # 1) 서비스 등록 시도 (이미 등록돼 있으면 무해, SSH에서는 실패할 수 있음 → 무시)
    "${cmd[@]}" gateway install >/dev/null 2>&1 || true

    # 2) 재시작. 서비스 미등록 상태면 hermes가 포그라운드/백그라운드 실행으로
    #    폴백할 수 있으므로 반드시 nohup 백그라운드로 띄운다.
    nohup "${cmd[@]}" gateway restart >/dev/null 2>&1 &

    # 3) 헬스체크 폴링 (최대 20초)
    local i code
    for i in $(seq 1 20); do
        code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 1 "http://127.0.0.1:${port}/health" 2>/dev/null || true)
        if [ "$code" = "200" ]; then
            echo "  ✓ ${name} (포트 ${port}) OK"
            return 0
        fi
        sleep 1
    done
    if [ "$name" = "default" ]; then
        echo "  ✗ ${name} (포트 ${port}) 응답 없음 — 로그: ${HERMES_HOME}/logs/gateway.log"
    else
        echo "  ✗ ${name} (포트 ${port}) 응답 없음 — 로그: ${HERMES_HOME}/profiles/${name}/logs/gateway.log"
    fi
    return 1
}

# 1) default 프로필 설정
echo "═══ 설정 (${HERMES_HOME})"
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
        if [ "$port" -ge "$NEXT_PORT" ] 2>/dev/null; then
            NEXT_PORT=$((port + 1))
        fi
    done
fi

# 3) 게이트웨이 재시작 + 헬스체크
echo
echo "═══ 게이트웨이 재시작 + 확인"
FAILED=0
while IFS=' ' read -r name port; do
    [ -z "$name" ] && continue
    restart_gateway "$name" "$port" || FAILED=$((FAILED + 1))
done <<< "$PROFILE_PORTS"

echo
if [ "$FAILED" -eq 0 ]; then
    echo "완료. 아이폰 앱 설정에서 '프로필 자동 검색'을 누르세요."
else
    echo "완료 (${FAILED}개 프로필 응답 없음 — 위의 로그 경로를 확인하세요)."
    echo "팁: 'Bootstrap failed: 5'가 보였다면 맥미니 로컬 터미널에서 재실행하면 영구 서비스로 등록됩니다."
fi

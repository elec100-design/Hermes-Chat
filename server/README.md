# Hermes Bridge — 맥미니 설치 가이드

`hermes_bridge.py`는 게이트웨이 API가 제공하지 않는 기능(프로필 목록, 게이트웨이 재시작,
SOUL.md 편집, 파일 업로드, 칸반 저장소)을 보충하는 단일 파일 HTTP 서비스다.
의존성 없음 (Python 3.9+ 표준 라이브러리만).

## 1회 설치 (맥미니)

> 주의: launchd는 iCloud Drive(`~/Library/Mobile Documents/...`) 안의 스크립트를
> 실행하지 못할 수 있다 (TCC 권한 차단). 반드시 iCloud 밖으로 복사해서 실행한다.
> 브리지 코드가 업데이트되면 아래 `cp` 한 줄만 다시 실행하고 reload 하면 된다.

```bash
# 0) 저장소의 스크립트를 iCloud 밖 고정 경로로 복사
REPO="/Users/macmini/projects/HermesChat"
mkdir -p ~/.hermes/bridge
cp "$REPO/server/hermes_bridge.py" ~/.hermes/bridge/

# 1) 토큰 정하기 (앱 설정에 같은 값 입력)
export BRIDGE_TOKEN="원하는-긴-랜덤-문자열"

# 2) LaunchAgent 등록 (로그인 시 자동 시작 + 크래시 시 재시작)
mkdir -p ~/Library/LaunchAgents
cat > ~/Library/LaunchAgents/ai.hermes.bridge.plist <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>ai.hermes.bridge</string>
  <key>ProgramArguments</key><array>
    <string>/usr/bin/python3</string>
    <string>/Users/macmini/.hermes/bridge/hermes_bridge.py</string>
    <string>--port</string><string>8765</string>
  </array>
  <key>EnvironmentVariables</key><dict>
    <key>HERMES_BRIDGE_TOKEN</key><string>${BRIDGE_TOKEN}</string>
  </dict>
  <!-- 게이트웨이 재시작이 "hermes 실행파일을 찾지 못했습니다"로 실패하면 위 dict에 추가:
       <key>HERMES_BIN</key><string>$(which hermes 결과 경로)</string>
       기본으로 ~/.local/bin, /opt/homebrew/bin, /usr/local/bin은 자동 탐색한다. -->
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardErrorPath</key><string>/tmp/hermes-bridge.log</string>
</dict></plist>
EOF
launchctl unload ~/Library/LaunchAgents/ai.hermes.bridge.plist 2>/dev/null
launchctl load ~/Library/LaunchAgents/ai.hermes.bridge.plist

# 3) 확인
sleep 1
curl http://127.0.0.1:8765/health
curl -H "Authorization: Bearer $BRIDGE_TOKEN" http://127.0.0.1:8765/profiles
```

안 뜨면 진단: `cat /tmp/hermes-bridge.log` (파이썬 에러), `launchctl list | grep hermes`
(상태/종료코드), 수동 실행 테스트
`HERMES_BRIDGE_TOKEN=x /usr/bin/python3 ~/.hermes/bridge/hermes_bridge.py --port 8765`

## API 요약

| 메서드/경로 | 설명 |
|---|---|
| `GET /health` | 헬스체크 (인증 불필요) |
| `GET /profiles` | 프로필 목록 `[{name, port, api_enabled}]` |
| `POST /profiles/{name}/restart` | 해당 프로필 게이트웨이 재시작 (백그라운드 분리 실행 후 최대 10초 헬스 폴링) |
| `GET /profiles/{name}/soul` | SOUL.md 내용 `{content}` |
| `PUT /profiles/{name}/soul` | SOUL.md 저장 (body: `{"content": "..."}`, 이전본 .bak 백업) |
| `POST /upload/{profile}` | 파일 업로드 (raw body + `X-Filename` 헤더) → `{path}` |
| `GET /kanban` | 보드 목록 |
| `GET /kanban/{board}` / `PUT /kanban/{board}` | 보드 조회/전체 저장 |
| `GET /files?path=` | HERMES_HOME 하위 디렉터리 목록 (읽기전용, 숨김 파일 제외) |
| `GET /files/content?path=` | 텍스트 파일 내용 (512KB 제한, 숨김 파일 403) |
| `GET /profiles/{name}/logs?tail=200` | 최신 로그 파일 꼬리 (최대 2000줄) |

인증: `/health` 외 전부 `Authorization: Bearer <HERMES_BRIDGE_TOKEN>`.

## 보안

- Tailscale 사설망 전용. 공유기에서 8765 포트포워딩 금지.
- 프로필 이름은 실제 존재하는 디렉터리명과 대조 후에만 사용 (경로조작/명령주입 차단).
- 업로드 50MB 제한, 파일명 정규화.

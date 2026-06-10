# Hermes Bridge — 맥미니 설치 가이드

`hermes_bridge.py`는 게이트웨이 API가 제공하지 않는 기능(프로필 목록, 게이트웨이 재시작,
SOUL.md 편집, 파일 업로드, 칸반 저장소)을 보충하는 단일 파일 HTTP 서비스다.
의존성 없음 (Python 3.9+ 표준 라이브러리만).

## 1회 설치 (맥미니)

```bash
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
    <string>/Users/macmini/Library/Mobile Documents/com~apple~CloudDocs/Coding/claude/busy-meitner-lhc5os/server/hermes_bridge.py</string>
    <string>--port</string><string>8765</string>
  </array>
  <key>EnvironmentVariables</key><dict>
    <key>HERMES_BRIDGE_TOKEN</key><string>${BRIDGE_TOKEN}</string>
  </dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardErrorPath</key><string>/tmp/hermes-bridge.log</string>
</dict></plist>
EOF
launchctl unload ~/Library/LaunchAgents/ai.hermes.bridge.plist 2>/dev/null
launchctl load ~/Library/LaunchAgents/ai.hermes.bridge.plist

# 3) 확인
curl http://127.0.0.1:8765/health
curl -H "Authorization: Bearer $BRIDGE_TOKEN" http://127.0.0.1:8765/profiles
```

## API 요약

| 메서드/경로 | 설명 |
|---|---|
| `GET /health` | 헬스체크 (인증 불필요) |
| `GET /profiles` | 프로필 목록 `[{name, port, api_enabled}]` |
| `POST /profiles/{name}/restart` | 해당 프로필 게이트웨이 재시작 |
| `GET /profiles/{name}/soul` | SOUL.md 내용 `{content}` |
| `PUT /profiles/{name}/soul` | SOUL.md 저장 (body: `{"content": "..."}`, 이전본 .bak 백업) |
| `POST /upload/{profile}` | 파일 업로드 (raw body + `X-Filename` 헤더) → `{path}` |
| `GET /kanban` | 보드 목록 |
| `GET /kanban/{board}` / `PUT /kanban/{board}` | 보드 조회/전체 저장 |

인증: `/health` 외 전부 `Authorization: Bearer <HERMES_BRIDGE_TOKEN>`.

## 보안

- Tailscale 사설망 전용. 공유기에서 8765 포트포워딩 금지.
- 프로필 이름은 실제 존재하는 디렉터리명과 대조 후에만 사용 (경로조작/명령주입 차단).
- 업로드 50MB 제한, 파일명 정규화.

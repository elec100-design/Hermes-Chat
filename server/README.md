# Hermes Bridge — 맥미니 설치 가이드

`hermes_bridge.py`는 게이트웨이 API가 제공하지 않는 기능(프로필 목록/생성/삭제, 게이트웨이 재시작,
SOUL.md·모델 카탈로그·크론잡 관리, 파일 업로드/열람, 내장 칸반, 에이전트 사용량, git 작업)을 보충하는 단일 파일 HTTP 서비스다.
의존성 없음 (Python 3.9+ 표준 라이브러리만).

## 1회 설치 (맥미니)

> 주의: launchd는 iCloud Drive(`~/Library/Mobile Documents/...`) 안의 스크립트를
> 실행하지 못할 수 있다 (TCC 권한 차단). 반드시 iCloud 밖으로 복사해서 실행한다.
> 브리지 코드가 업데이트되면 바인드 주소(`100.83.59.60`, 저장소는 `0.0.0.0`)를 유지해 복사한 뒤 reload 한다 (`docs/HANDOFF.md` §2.5).

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
| `GET /profiles` | 프로필 목록 `[{name, port, api_enabled}]` — ⚠️ `port`·`api_enabled` 는 프로필 `.env` 의 옛 값. 게이트웨이가 멀티플렉스(:8642, default 외 `/p/<name>/`)인 지금은 의미 없음 (docs/PLAN.md §0.1a, T-218) |
| `POST /profiles` | 프로필 생성 (`hermes profile create <name> --clone-from default`) |
| `DELETE /profiles/{name}` | 프로필 삭제 (`hermes profile delete <name> -y`, default 거부) |
| `POST /profiles/{name}/restart` | 해당 프로필 게이트웨이 재시작 (백그라운드 분리 실행 후 최대 10초 헬스 폴링). 멀티플렉스에서 default 외 프로필 재시작 동작은 확인 필요(T-217) |
| `GET /profiles/{name}/soul` | SOUL.md 내용 `{content}` |
| `PUT /profiles/{name}/soul` | SOUL.md 저장 (body: `{"content": "..."}`, 이전본 .bak 백업) |
| `GET /profiles/{name}/model` | 현재 모델(`config.yaml`) + 카탈로그(`cache/model_catalog.json`) |
| `PUT /profiles/{name}/model` | 모델 변경 (`config.yaml` `model.default` 교체, restart 옵션) |
| `GET /profiles/{name}/cron` | 크론잡 목록 (`cron/jobs.json`) |
| `POST /profiles/{name}/cron` | 크론잡 생성 (`hermes cron create` 경유) |
| `PUT /profiles/{name}/cron/{id}` | 크론잡 수정 (`hermes cron edit`/`pause`/`resume` 경유) |
| `POST /profiles/{name}/cron/{id}/run` | 크론잡 즉시 실행 (`hermes cron run`) |
| `DELETE /profiles/{name}/cron/{id}` | 크론잡 삭제 (`hermes cron remove`) |
| `POST /upload/{profile}` | 파일 업로드 (raw body + `X-Filename` 헤더) → `{path}` |
| `GET /kanban` | 내장 칸반(`kanban.db`) 보드 목록 + 상태별 카운트 |
| `GET /kanban/{board}` | 비아카이브 태스크 목록 (sqlite 직접 읽기) |
| `GET /kanban/{board}/tasks/{id}` | 태스크 상세 + 댓글 + 이벤트 |
| `POST /kanban/boards` | 보드 생성 |
| `POST /kanban/{board}/tasks` | 태스크 생성 (`hermes kanban create` CLI 경유) |
| `POST /kanban/{board}/tasks/{id}/action` | 상태 전이/댓글 (`promote`/`block`/`unblock`/`complete`/`archive`/`comment`, CLI 경유) |
| `GET /files?path=` | HERMES_HOME 하위 디렉터리 목록 (읽기전용, 숨김 파일 제외) |
| `GET /files/content?path=` | 텍스트 파일 내용 (512KB 제한, 숨김 파일 403) |
| `GET /files/raw?path=` | 바이너리 파일 응답 (이미지 썸네일/첨부 표시용, 20MB 제한) |
| `GET /profiles/{name}/logs?tail=200` | 최신 로그 파일 꼬리 (최대 2000줄) |
| `GET /usage[?refresh=1]` | 에이전트 계정별 사용량 (`paseo usage --json`) |
| `GET /git/status?path=` | 작업 폴더 git 브랜치·변경 수·앞섬/뒤처짐·PR·배포 스크립트 조회 |
| `POST /git/{commit\|pull\|push\|pr\|merge\|deploy}` | 작업 폴더 git/gh 동작 및 리포 내 `deploy.sh` 실행 |

인증: `/health` 외 전부 `Authorization: Bearer <HERMES_BRIDGE_TOKEN>`.

## 보안

- Tailscale 사설망 전용. 공유기에서 8765 포트포워딩 금지.
- 프로필 이름은 실제 존재하는 디렉터리명과 대조 후에만 사용 (경로조작/명령주입 차단).
- 업로드 50MB 제한, 파일명 정규화.

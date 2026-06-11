#!/usr/bin/env python3
"""Hermes Bridge — 맥미니에서 hermes-agent 게이트웨이가 제공하지 않는 기능을 보충하는
초경량 HTTP 서비스. 표준 라이브러리만 사용 (의존성 없음).

게이트웨이 API(포트 8642+)가 못 하는 것들을 담당:
  - 프로필 목록/포트 조회 (~/.hermes/profiles/ 스캔)
  - 게이트웨이 재시작 (hermes gateway restart)
  - SOUL.md 읽기/쓰기
  - 파일 업로드 (채팅 첨부용 — 업로드 후 경로를 메시지에 포함)
  - 내장 칸반 조회/조작 (~/.hermes/kanban.db — 게이트웨이 디스패처·대시보드와 동일 데이터.
    읽기는 sqlite 직접, 쓰기는 `hermes kanban` CLI 경유로 이벤트·디스패치 불변식 보존)

실행:
  HERMES_BRIDGE_TOKEN=<토큰> python3 hermes_bridge.py [--port 8765] [--host 0.0.0.0]

보안: Tailscale 등 사설망 전제. 모든 요청에 Authorization: Bearer <토큰> 필요
(/health 제외). 공인망에 직접 노출하지 말 것.
"""

import json
import mimetypes
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import time
import urllib.request
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

HERMES_HOME = Path(os.environ.get("HERMES_HOME", Path.home() / ".hermes"))
PROFILES_DIR = HERMES_HOME / "profiles"
KANBAN_BOARDS_DIR = HERMES_HOME / "kanban" / "boards"
TOKEN = os.environ.get("HERMES_BRIDGE_TOKEN", "")
MAX_UPLOAD = 50 * 1024 * 1024  # 50MB
MAX_RAW_DOWNLOAD = 20 * 1024 * 1024  # 20MB — /files/raw (앱 이미지 썸네일용)

SAFE_NAME = re.compile(r"^[A-Za-z0-9._-]{1,80}$")

# launchd의 PATH는 /usr/bin:/bin 수준이라 pipx/homebrew 설치 경로를 보충해야 한다.
EXTRA_BIN_DIRS = [
    str(Path.home() / ".local" / "bin"),
    "/opt/homebrew/bin",
    "/usr/local/bin",
    str(Path.home() / "bin"),
]


def find_hermes():
    """hermes 실행파일 탐색: HERMES_BIN 환경변수 → PATH(+보충 경로)"""
    env_bin = os.environ.get("HERMES_BIN", "")
    if env_bin and Path(env_bin).is_file():
        return env_bin
    search = os.environ.get("PATH", "") + os.pathsep + os.pathsep.join(EXTRA_BIN_DIRS)
    return shutil.which("hermes", path=search)


def poll_health(port, timeout_sec):
    """게이트웨이 /health가 응답할 때까지 폴링. 클라이언트 타임아웃(15초)보다 짧게 유지할 것."""
    deadline = time.time() + timeout_sec
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(
                f"http://127.0.0.1:{port}/health", timeout=2
            ) as resp:
                if resp.status == 200:
                    return True
        except OSError:
            pass
        time.sleep(1)
    return False


def list_profile_names():
    names = ["default"]
    if PROFILES_DIR.is_dir():
        names += sorted(p.name for p in PROFILES_DIR.iterdir() if p.is_dir())
    return names


def profile_dir(name):
    if name == "default":
        return HERMES_HOME
    return PROFILES_DIR / name


def safe_subpath(rel):
    """HERMES_HOME 밖으로 못 나가게 검증. 통과하면 절대 Path, 아니면 None."""
    rel = (rel or "").lstrip("/")
    home = HERMES_HOME.resolve()
    target = (home / rel).resolve()
    if target == home or home in target.parents:
        return target
    return None


def is_hidden_path(target):
    """HERMES_HOME 기준 상대경로에 숨김 요소(.env 등)가 있으면 True — 비밀값 노출 차단."""
    rel = target.relative_to(HERMES_HOME.resolve())
    return any(part.startswith(".") for part in rel.parts)


def read_env(env_file):
    values = {}
    if env_file.is_file():
        for line in env_file.read_text(errors="replace").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, _, value = line.partition("=")
                values[key.strip()] = value.strip()
    return values


# ── 내장 칸반 (hermes-agent kanban.db) ──────────────────────────
# default 보드는 ~/.hermes/kanban.db, 그 외는 ~/.hermes/kanban/boards/<slug>/kanban.db.
# 상태값: triage|todo|scheduled|ready|running|blocked|done|archived
# ready 태스크는 게이트웨이 디스패처가 워커 프로필을 띄워 자동 실행한다.

def kanban_db_path(board):
    if board == "default":
        return HERMES_HOME / "kanban.db"
    return KANBAN_BOARDS_DIR / board / "kanban.db"


def list_kanban_boards():
    boards = ["default"]
    if KANBAN_BOARDS_DIR.is_dir():
        boards += sorted(
            p.name for p in KANBAN_BOARDS_DIR.iterdir()
            if p.is_dir() and (p / "kanban.db").is_file()
        )
    return boards


def kanban_board_display_name(board):
    meta = KANBAN_BOARDS_DIR / board / "board.json"
    if meta.is_file():
        try:
            return json.loads(meta.read_text()).get("name") or board
        except ValueError:
            pass
    return "Default" if board == "default" else board


def epoch_to_iso(ts):
    if not ts:
        return None
    return datetime.fromtimestamp(ts, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def kanban_connect_ro(db):
    """읽기전용 연결. `file:...?mode=ro` URI는 이 맥의 Xcode Python 3.9 빌드에서
    'unable to open database file'로 실패해서 query_only 프래그마로 대체한다."""
    conn = sqlite3.connect(str(db), timeout=5)
    conn.execute("PRAGMA query_only=ON")
    return conn


def kanban_read(board):
    """보드의 비아카이브 태스크를 앱 스키마로 반환."""
    db = kanban_db_path(board)
    if not db.is_file():
        return None
    conn = kanban_connect_ro(db)
    conn.row_factory = sqlite3.Row
    try:
        rows = conn.execute(
            "SELECT id, title, body, status, assignee, session_id,"
            " created_at, started_at, completed_at, last_heartbeat_at"
            " FROM tasks WHERE status != 'archived' ORDER BY created_at DESC"
        ).fetchall()
    finally:
        conn.close()
    tasks, latest = [], 0
    for r in rows:
        updated = max(
            r["created_at"] or 0, r["started_at"] or 0,
            r["completed_at"] or 0, r["last_heartbeat_at"] or 0,
        )
        latest = max(latest, updated)
        tasks.append({
            "id": r["id"],
            "title": r["title"],
            "detail": r["body"] or "",
            "status": r["status"],
            "assignee": r["assignee"] or "",
            "session_id": r["session_id"] or "",
            "created_at": epoch_to_iso(r["created_at"]),
            "updated_at": epoch_to_iso(updated),
        })
    return {
        "name": kanban_board_display_name(board),
        "board": board,
        "updated_at": epoch_to_iso(latest),
        "tasks": tasks,
    }


def kanban_counts(board):
    db = kanban_db_path(board)
    if not db.is_file():
        return {}
    conn = kanban_connect_ro(db)
    try:
        rows = conn.execute(
            "SELECT status, COUNT(*) FROM tasks GROUP BY status"
        ).fetchall()
    finally:
        conn.close()
    return {status: count for status, count in rows}


def run_kanban_cli(board, args, timeout=60):
    """쓰기 작업은 CLI 경유 — 이벤트 기록, 의존성 재계산, 디스패치 트리거를 보존한다."""
    hermes = find_hermes()
    if not hermes:
        return None, "hermes 실행파일을 찾지 못했습니다 (HERMES_BIN 설정 필요)"
    cmd = [hermes, "kanban", "--board", board] + args
    try:
        proc = subprocess.run(
            cmd, capture_output=True, text=True, timeout=timeout,
        )
    except subprocess.TimeoutExpired:
        return None, "kanban CLI timeout"
    if proc.returncode != 0:
        detail = (proc.stderr or proc.stdout or "").strip()[-500:]
        return None, f"kanban CLI failed (exit {proc.returncode}): {detail}"
    return proc.stdout, None


class Handler(BaseHTTPRequestHandler):
    server_version = "HermesBridge/1.0"

    # ── helpers ──────────────────────────────────────────────

    def send_json(self, obj, status=200):
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def fail(self, status, message):
        self.send_json({"error": message}, status)

    def send_text(self, text, status=200):
        body = text.encode()
        self.send_response(status)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def authorized(self):
        if not TOKEN:
            return True
        return self.headers.get("Authorization", "") == f"Bearer {TOKEN}"

    def check_profile(self, name):
        """프로필 이름 검증 — 실제 존재하는 디렉터리만 허용 (경로조작/명령주입 차단)"""
        if SAFE_NAME.match(name) and name in list_profile_names():
            return name
        return None

    def check_kanban_board(self, name):
        """보드 slug 검증 — 실존 보드만 허용 (경로조작/CLI 인자 주입 차단)"""
        if SAFE_NAME.match(name) and name in list_kanban_boards():
            return name
        return None

    def read_body(self, limit=MAX_UPLOAD):
        length = int(self.headers.get("Content-Length", 0))
        if length <= 0 or length > limit:
            return None
        return self.rfile.read(length)

    # ── routing ──────────────────────────────────────────────

    def do_GET(self):
        parsed = urlparse(self.path)
        parts = [p for p in parsed.path.split("/") if p]
        query = {k: v[0] for k, v in parse_qs(parsed.query).items()}

        if parts == ["health"]:
            return self.send_json({"status": "ok", "service": "hermes-bridge"})
        if not self.authorized():
            return self.fail(401, "unauthorized")

        # GET /files?path=<HERMES_HOME 기준 상대경로> — 디렉터리 목록 (읽기전용)
        if parts == ["files"]:
            target = safe_subpath(query.get("path", ""))
            if target is None or not target.is_dir():
                return self.fail(404, "directory not found")
            entries = []
            for p in sorted(target.iterdir(), key=lambda x: (not x.is_dir(), x.name.lower())):
                if p.name.startswith("."):
                    continue  # .env 등 숨김 파일은 목록에서도 제외
                try:
                    entries.append({
                        "name": p.name,
                        "is_dir": p.is_dir(),
                        "size": p.stat().st_size if p.is_file() else None,
                    })
                except OSError:
                    pass
            return self.send_json({"data": entries})

        # GET /files/content?path=<상대경로> — 텍스트 파일 내용 (512KB 제한)
        if parts == ["files", "content"]:
            target = safe_subpath(query.get("path", ""))
            if target is None or not target.is_file():
                return self.fail(404, "file not found")
            if is_hidden_path(target):
                return self.fail(403, "hidden files are not accessible")
            if target.stat().st_size > 512 * 1024:
                return self.fail(413, "file too large (512KB limit)")
            return self.send_text(target.read_text(errors="replace"))

        # GET /files/raw?path=<상대경로> — 바이너리 파일 (이미지 썸네일용, 20MB 제한, T-105)
        if parts == ["files", "raw"]:
            target = safe_subpath(query.get("path", ""))
            if target is None or not target.is_file():
                return self.fail(404, "file not found")
            if is_hidden_path(target):
                return self.fail(403, "hidden files are not accessible")
            if target.stat().st_size > MAX_RAW_DOWNLOAD:
                return self.fail(413, "file too large (20MB limit)")
            ctype = mimetypes.guess_type(target.name)[0] or "application/octet-stream"
            body = target.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        # GET /profiles/<name>/logs?tail=200 — 최신 로그 파일 꼬리 (읽기전용)
        if len(parts) == 3 and parts[0] == "profiles" and parts[2] == "logs":
            name = self.check_profile(parts[1])
            if not name:
                return self.fail(404, "unknown profile")
            try:
                tail = max(1, min(int(query.get("tail", "200")), 2000))
            except ValueError:
                tail = 200
            candidates = []
            for base in (profile_dir(name) / "logs", HERMES_HOME / "logs"):
                if base.is_dir():
                    candidates += [p for p in base.glob("*.log") if p.is_file()]
            if not candidates:
                return self.fail(404, "no log files found")
            newest = max(candidates, key=lambda p: p.stat().st_mtime)
            lines = newest.read_text(errors="replace").splitlines()[-tail:]
            return self.send_text(f"# {newest.name}\n" + "\n".join(lines))

        if parts == ["profiles"]:
            result = []
            for name in list_profile_names():
                env = read_env(profile_dir(name) / ".env")
                result.append({
                    "name": name,
                    "api_enabled": env.get("API_SERVER_ENABLED", "false").lower() == "true",
                    "port": int(env.get("API_SERVER_PORT", "8642") or 8642),
                })
            return self.send_json({"data": result})

        if len(parts) == 3 and parts[0] == "profiles" and parts[2] == "soul":
            name = self.check_profile(parts[1])
            if not name:
                return self.fail(404, "unknown profile")
            soul = profile_dir(name) / "SOUL.md"
            content = soul.read_text(errors="replace") if soul.is_file() else ""
            return self.send_json({"profile": name, "content": content})

        # GET /kanban — 보드 목록 (slug + 표시명 + 상태별 카운트)
        if parts == ["kanban"]:
            result = []
            for board in list_kanban_boards():
                try:
                    counts = kanban_counts(board)
                except sqlite3.Error:
                    counts = {}
                result.append({
                    "board": board,
                    "name": kanban_board_display_name(board),
                    "counts": counts,
                })
            return self.send_json({"data": result})

        # GET /kanban/<board> — 비아카이브 태스크 전체 (앱 스키마)
        if len(parts) == 2 and parts[0] == "kanban":
            board = self.check_kanban_board(parts[1])
            if not board:
                return self.fail(404, "unknown board")
            try:
                data = kanban_read(board)
            except sqlite3.Error as e:
                return self.fail(500, f"kanban db error: {e}")
            if data is None:
                return self.fail(404, "unknown board")
            return self.send_json(data)

        return self.fail(404, "not found")

    def do_POST(self):
        parts = [p for p in self.path.split("?")[0].split("/") if p]
        if not self.authorized():
            return self.fail(401, "unauthorized")

        # POST /profiles/<name>/restart
        if len(parts) == 3 and parts[0] == "profiles" and parts[2] == "restart":
            name = self.check_profile(parts[1])
            if not name:
                return self.fail(404, "unknown profile")
            hermes = find_hermes()
            if not hermes:
                return self.fail(
                    500,
                    "hermes 실행파일을 찾지 못했습니다. "
                    "LaunchAgent plist의 EnvironmentVariables에 HERMES_BIN=<hermes 절대경로>를 추가하세요.",
                )
            cmd = [hermes, "gateway", "restart"] if name == "default" \
                else [hermes, "--profile", name, "gateway", "restart"]
            port = int(read_env(profile_dir(name) / ".env").get("API_SERVER_PORT", "8642") or 8642)
            try:
                # 서비스 미설치 시 restart가 포그라운드로 돌 수 있어 분리 실행 후 헬스 폴링.
                subprocess.Popen(
                    cmd,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                    start_new_session=True,
                )
            except Exception as e:  # noqa: BLE001
                return self.fail(500, f"restart failed: {e}")
            time.sleep(2)  # 기존 프로세스 종료 여유
            healthy = poll_health(port, timeout_sec=8)
            output = (
                f"재시작 완료 — 포트 {port} 헬스체크 성공"
                if healthy
                else f"재시작 요청됨 — 포트 {port}가 아직 응답하지 않습니다. 10~20초 후 세션 목록을 새로고침해 보세요."
            )
            return self.send_json({"profile": name, "ok": True, "output": output})

        # POST /upload/<profile>  (raw body + X-Filename 헤더)
        if len(parts) == 2 and parts[0] == "upload":
            name = self.check_profile(parts[1])
            if not name:
                return self.fail(404, "unknown profile")
            filename = self.headers.get("X-Filename", "upload.bin")
            filename = re.sub(r"[^A-Za-z0-9._\-가-힣]", "_", filename)[-80:] or "upload.bin"
            data = self.read_body()
            if data is None:
                return self.fail(400, "empty or oversized body")
            dest_dir = profile_dir(name) / "uploads"
            dest_dir.mkdir(parents=True, exist_ok=True)
            dest = dest_dir / f"{int(time.time())}_{filename}"
            dest.write_bytes(data)
            return self.send_json({"path": str(dest), "size": len(data)}, 201)

        # POST /kanban/boards  {"name": "표시명", "slug"?: "slug"}
        if parts == ["kanban", "boards"]:
            raw = self.read_body(4096)
            if raw is None:
                return self.fail(400, "empty body")
            try:
                payload = json.loads(raw)
                name = str(payload.get("name") or "").strip()
            except (ValueError, TypeError):
                return self.fail(400, 'expected JSON {"name": ...}')
            if not name:
                return self.fail(400, "name is empty")
            slug = str(payload.get("slug") or "").strip()
            if not slug:
                import re as _re
                slug = _re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-") or "board"
            if not SAFE_NAME.match(slug):
                return self.fail(400, "invalid slug (alphanumeric, ., -, _ only, max 80)")
            if slug in list_kanban_boards():
                return self.fail(409, f"board '{slug}' already exists")
            hermes = find_hermes()
            if not hermes:
                return self.fail(500, "hermes 실행파일을 찾지 못했습니다 (HERMES_BIN 설정 필요)")
            try:
                proc = subprocess.run(
                    [hermes, "kanban", "boards", "create", slug, "--name", name],
                    capture_output=True, text=True, timeout=30,
                )
            except subprocess.TimeoutExpired:
                return self.fail(500, "CLI timeout")
            if proc.returncode != 0:
                detail = (proc.stderr or proc.stdout or "").strip()[:300]
                return self.fail(500, f"CLI failed: {detail}")
            return self.send_json({"board": slug, "name": name, "ok": True}, 201)

        # POST /kanban/<board>/tasks  {"title", "detail"?, "assignee"?, "status"?}
        # status: "ready"(기본 — 부모 없는 태스크는 곧바로 디스패처가 실행)
        #         "triage"(스페시파이어가 스펙 구체화 후 진행) | "blocked"(보류, 사람 개입 대기)
        if len(parts) == 3 and parts[0] == "kanban" and parts[2] == "tasks":
            board = self.check_kanban_board(parts[1])
            if not board:
                return self.fail(404, "unknown board")
            data = self.read_body(1024 * 1024)
            if data is None:
                return self.fail(400, "empty body")
            try:
                payload = json.loads(data)
                title = str(payload["title"]).strip()
            except (ValueError, KeyError, TypeError):
                return self.fail(400, "expected JSON {\"title\": ...}")
            if not title:
                return self.fail(400, "title is empty")
            status = payload.get("status", "ready")
            if status not in ("ready", "triage", "blocked"):
                return self.fail(400, "status must be ready|triage|blocked")
            args = ["create", title, "--json", "--created-by", "ios-app"]
            detail = str(payload.get("detail") or "").strip()
            if detail:
                args += ["--body", detail]
            assignee = str(payload.get("assignee") or "").strip()
            if assignee:
                if not self.check_profile(assignee):
                    return self.fail(400, "unknown assignee profile")
                args += ["--assignee", assignee]
            if status == "triage":
                args += ["--triage"]
            elif status == "blocked":
                args += ["--initial-status", "blocked"]
            stdout, err = run_kanban_cli(board, args)
            if err:
                return self.fail(500, err)
            try:
                task = json.loads(stdout)
            except ValueError:
                task = {}
            return self.send_json({"board": board, "ok": True, "task": task}, 201)

        # POST /kanban/<board>/tasks/<id>/action  {"action": ..., "reason"?}
        if len(parts) == 5 and parts[0] == "kanban" and parts[2] == "tasks" \
                and parts[4] == "action":
            board = self.check_kanban_board(parts[1])
            if not board:
                return self.fail(404, "unknown board")
            task_id = parts[3]
            if not SAFE_NAME.match(task_id):
                return self.fail(400, "invalid task id")
            data = self.read_body(64 * 1024)
            if data is None:
                return self.fail(400, "empty body")
            try:
                payload = json.loads(data)
                action = payload["action"]
            except (ValueError, KeyError, TypeError):
                return self.fail(400, "expected JSON {\"action\": ...}")
            reason = str(payload.get("reason") or "").strip()
            if action == "promote":
                args = ["promote", task_id] + ([reason] if reason else [])
            elif action == "block":
                args = ["block", task_id, reason or "iOS 앱에서 보류"]
            elif action == "unblock":
                args = ["unblock", task_id] + (["--reason", reason] if reason else [])
            elif action == "complete":
                args = ["complete", task_id] + (["--result", reason] if reason else [])
            elif action == "archive":
                args = ["archive", task_id]
            elif action == "comment":
                if not reason:
                    return self.fail(400, "comment requires reason text")
                args = ["comment", task_id, reason, "--author", "ios-app"]
            else:
                return self.fail(400, "unknown action")
            _, err = run_kanban_cli(board, args)
            if err:
                return self.fail(500, err)
            return self.send_json({"board": board, "task": task_id, "ok": True})

        return self.fail(404, "not found")

    def do_PUT(self):
        parts = [p for p in self.path.split("?")[0].split("/") if p]
        if not self.authorized():
            return self.fail(401, "unauthorized")

        # PUT /profiles/<name>/soul  {"content": "..."}
        if len(parts) == 3 and parts[0] == "profiles" and parts[2] == "soul":
            name = self.check_profile(parts[1])
            if not name:
                return self.fail(404, "unknown profile")
            data = self.read_body(2 * 1024 * 1024)
            if data is None:
                return self.fail(400, "empty body")
            try:
                content = json.loads(data)["content"]
            except (ValueError, KeyError):
                return self.fail(400, "expected JSON {\"content\": ...}")
            soul = profile_dir(name) / "SOUL.md"
            if soul.is_file():
                soul.with_suffix(".md.bak").write_text(soul.read_text(errors="replace"))
            soul.write_text(content)
            return self.send_json({"profile": name, "ok": True})

        return self.fail(404, "not found")

    def log_message(self, fmt, *args):
        sys.stderr.write("[bridge] %s\n" % (fmt % args))


def main():
    host, port = "0.0.0.0", 8765
    args = sys.argv[1:]
    for i, arg in enumerate(args):
        if arg == "--port" and i + 1 < len(args):
            port = int(args[i + 1])
        if arg == "--host" and i + 1 < len(args):
            host = args[i + 1]
    if not TOKEN:
        print("경고: HERMES_BRIDGE_TOKEN 미설정 — 인증 없이 동작합니다.", file=sys.stderr)
    print(f"Hermes Bridge listening on {host}:{port} (HERMES_HOME={HERMES_HOME})")
    ThreadingHTTPServer((host, port), Handler).serve_forever()


if __name__ == "__main__":
    main()

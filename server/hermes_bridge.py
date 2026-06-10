#!/usr/bin/env python3
"""Hermes Bridge — 맥미니에서 hermes-agent 게이트웨이가 제공하지 않는 기능을 보충하는
초경량 HTTP 서비스. 표준 라이브러리만 사용 (의존성 없음).

게이트웨이 API(포트 8642+)가 못 하는 것들을 담당:
  - 프로필 목록/포트 조회 (~/.hermes/profiles/ 스캔)
  - 게이트웨이 재시작 (hermes gateway restart)
  - SOUL.md 읽기/쓰기
  - 파일 업로드 (채팅 첨부용 — 업로드 후 경로를 메시지에 포함)
  - 칸반 보드 저장소 (~/.hermes/kanban/*.json — Hermes 에이전트와 앱이 공유)

실행:
  HERMES_BRIDGE_TOKEN=<토큰> python3 hermes_bridge.py [--port 8765] [--host 0.0.0.0]

보안: Tailscale 등 사설망 전제. 모든 요청에 Authorization: Bearer <토큰> 필요
(/health 제외). 공인망에 직접 노출하지 말 것.
"""

import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

HERMES_HOME = Path(os.environ.get("HERMES_HOME", Path.home() / ".hermes"))
PROFILES_DIR = HERMES_HOME / "profiles"
KANBAN_DIR = HERMES_HOME / "kanban"
TOKEN = os.environ.get("HERMES_BRIDGE_TOKEN", "")
MAX_UPLOAD = 50 * 1024 * 1024  # 50MB

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

        if parts == ["kanban"]:
            KANBAN_DIR.mkdir(parents=True, exist_ok=True)
            boards = sorted(p.stem for p in KANBAN_DIR.glob("*.json"))
            return self.send_json({"data": boards})

        if len(parts) == 2 and parts[0] == "kanban":
            if not SAFE_NAME.match(parts[1]):
                return self.fail(400, "invalid board name")
            board = KANBAN_DIR / f"{parts[1]}.json"
            if not board.is_file():
                return self.fail(404, "unknown board")
            try:
                return self.send_json(json.loads(board.read_text()))
            except ValueError:
                return self.fail(500, "corrupt board file")

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

        # PUT /kanban/<board>  (보드 전체 JSON 교체)
        if len(parts) == 2 and parts[0] == "kanban":
            if not SAFE_NAME.match(parts[1]):
                return self.fail(400, "invalid board name")
            data = self.read_body(5 * 1024 * 1024)
            if data is None:
                return self.fail(400, "empty body")
            try:
                board = json.loads(data)
            except ValueError:
                return self.fail(400, "invalid JSON")
            KANBAN_DIR.mkdir(parents=True, exist_ok=True)
            path = KANBAN_DIR / f"{parts[1]}.json"
            tmp = path.with_suffix(".json.tmp")
            tmp.write_text(json.dumps(board, ensure_ascii=False, indent=2))
            tmp.replace(path)
            return self.send_json({"board": parts[1], "ok": True})

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

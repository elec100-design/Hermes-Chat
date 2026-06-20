#!/usr/bin/env python3
"""cloud_gateway.py — HermesChat SaaS 클라우드 게이트웨이 (T-B03/T-B05)

역할: Supabase JWT 검증 + 사용자별 hermes-agent 컨테이너 라우팅 프록시 + 플랜 제한
표준 라이브러리만 사용 (hermes_bridge.py 동일 원칙).

환경변수:
  SUPABASE_JWT_SECRET  Supabase 프로젝트 > Settings > API > JWT Secret (필수)
  SUPABASE_URL         Supabase 프로젝트 URL (https://xxx.supabase.co) — 플랜 조회용
  SUPABASE_SERVICE_KEY Supabase Service Role Key — users 테이블 읽기용
  GATEWAY_SECRET       per-user 컨테이너 API Key 파생 시드, 64자 랜덤 권장 (필수)
  HERMES_IMAGE         컨테이너 이미지 (기본: hermes-agent:latest)
  DOCKER_NETWORK       컨테이너 네트워크 (기본: hermes-internal)
  GATEWAY_PORT         리스닝 포트 (기본: 8080)
  GATEWAY_HOST         리스닝 호스트 (기본: 0.0.0.0)
  USAGE_DB_PATH        메시지 카운트 SQLite 경로 (기본: /data/gateway_usage.db)

엔드포인트:
  GET    /health          헬스체크 (인증 불필요)
  POST   /auth/login      JWT 검증 + 컨테이너 프로비저닝 (blocking, 최대 90s)
  GET    /status          사용자 컨테이너 상태
  GET    /usage           이번 달 메시지 사용량 + 플랜 정보
  DELETE /account         컨테이너 + 볼륨 완전 삭제 (되돌릴 수 없음)
  /bridge/*               컨테이너 Hermes Bridge(포트 8765)로 프록시
                            프로필 생성 시 플랜 프로필 수 제한 적용
  *                       컨테이너 hermes-agent 게이트웨이(포트 8642)로 프록시
                            POST .../chat/stream 시 메시지 제한 적용

플랜 제한 (T-B05):
  free  — 1 프로필, 월 200 메시지
  basic — 3 프로필, 무제한 메시지  (₩9,900/월)
  pro   — 10 프로필, 무제한 메시지 (₩29,900/월)

보안:
  - JWT 서명 검증: HS256, hmac.compare_digest (상수 시간)
  - user_id UUID 형식 검증
  - docker CLI는 항상 리스트로 (shell=False → 명령 주입 차단)
  - GATEWAY_SECRET / JWT_SECRET 은 로그에 절대 출력하지 않음
"""

import base64
import hashlib
import hmac
import http.client
import json
import os
import re
import sqlite3
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from datetime import date
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

# ── 설정 ──────────────────────────────────────────────────────────────────────

JWT_SECRET = os.environ.get("SUPABASE_JWT_SECRET", "")
GATEWAY_SECRET = os.environ.get("GATEWAY_SECRET", "")
SUPABASE_URL = os.environ.get("SUPABASE_URL", "").rstrip("/")
SUPABASE_SERVICE_KEY = os.environ.get("SUPABASE_SERVICE_KEY", "")
HERMES_IMAGE = os.environ.get("HERMES_IMAGE", "hermes-agent:latest")
DOCKER_NETWORK = os.environ.get("DOCKER_NETWORK", "hermes-internal")
GATEWAY_PORT = int(os.environ.get("GATEWAY_PORT", "8080"))
GATEWAY_HOST = os.environ.get("GATEWAY_HOST", "0.0.0.0")
USAGE_DB_PATH = os.environ.get("USAGE_DB_PATH", "/data/gateway_usage.db")

CONTAINER_GATEWAY_PORT = 8642   # hermes-agent 게이트웨이 내부 포트
CONTAINER_BRIDGE_PORT = 8765    # Hermes Bridge 내부 포트

# UUID 형식 검증 (Supabase sub 필드)
UUID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

# chat/stream 경로 패턴 (메시지 카운트 대상)
CHAT_STREAM_RE = re.compile(r"^/api/sessions/[^/]+/chat/stream$")

# T-B05 플랜별 제한
PLAN_LIMITS: dict[str, dict] = {
    "free":  {"profiles": 1,  "monthly_messages": 200},
    "basic": {"profiles": 3,  "monthly_messages": None},   # None = 무제한
    "pro":   {"profiles": 10, "monthly_messages": None},
}

# SSE 응답 타임아웃
PROXY_TIMEOUT_STREAM = 300
PROXY_TIMEOUT_DEFAULT = 30

# 플랜 캐시 (Supabase 부하 감소)
PLAN_CACHE_TTL = 300  # 5분
_plan_cache: dict[str, tuple[str, float]] = {}
_plan_cache_lock = threading.Lock()

# SQLite 쓰기 직렬화
_usage_lock = threading.Lock()


# ── JWT 검증 (stdlib HS256) ───────────────────────────────────────────────────

def verify_jwt(token: str) -> tuple[dict | None, str | None]:
    """Supabase JWT(HS256)를 stdlib만으로 검증. (payload, None) 또는 (None, error)."""
    if not JWT_SECRET:
        return None, "SUPABASE_JWT_SECRET not configured"
    try:
        parts = token.split(".")
        if len(parts) != 3:
            return None, "malformed token"
        h, p, s = parts

        msg = f"{h}.{p}".encode()
        expected = base64.urlsafe_b64encode(
            hmac.new(JWT_SECRET.encode(), msg, hashlib.sha256).digest()
        ).rstrip(b"=")
        if not hmac.compare_digest(expected, s.encode()):
            return None, "invalid signature"

        pad = 4 - len(p) % 4
        payload = json.loads(base64.urlsafe_b64decode(p + "=" * pad))

        if payload.get("exp", 0) < time.time():
            return None, "token expired"
        if payload.get("role") not in ("authenticated", "service_role"):
            return None, "unauthorized role"

        return payload, None
    except Exception as e:  # noqa: BLE001
        return None, f"jwt parse error: {e}"


def user_id_from_payload(payload: dict) -> str | None:
    uid = payload.get("sub", "")
    return uid if UUID_RE.match(uid) else None


# ── per-user 키 파생 ──────────────────────────────────────────────────────────

def derive_key(user_id: str, purpose: str) -> str:
    """GATEWAY_SECRET + user_id + purpose → 결정론적 고유 키 (SHA-256 hex)."""
    return hmac.new(
        GATEWAY_SECRET.encode(),
        f"{purpose}:{user_id}".encode(),
        hashlib.sha256,
    ).hexdigest()


# ── T-B05: 플랜 조회 (Supabase REST API + 메모리 캐시) ───────────────────────

def fetch_user_plan(user_id: str) -> str:
    """Supabase users.plan 조회. 5분 캐시 적용. 실패 또는 미설정이면 'free' 반환."""
    with _plan_cache_lock:
        cached = _plan_cache.get(user_id)
        if cached and cached[1] > time.time():
            return cached[0]

    plan = "free"
    if SUPABASE_URL and SUPABASE_SERVICE_KEY:
        try:
            url = f"{SUPABASE_URL}/rest/v1/users?select=plan&id=eq.{user_id}&limit=1"
            req = urllib.request.Request(url, headers={
                "apikey": SUPABASE_SERVICE_KEY,
                "Authorization": f"Bearer {SUPABASE_SERVICE_KEY}",
            })
            with urllib.request.urlopen(req, timeout=5) as resp:
                data = json.loads(resp.read())
            plan = (data[0].get("plan") if data else None) or "free"
        except Exception:  # noqa: BLE001
            pass  # 네트워크 실패 → free 강등

    with _plan_cache_lock:
        _plan_cache[user_id] = (plan, time.time() + PLAN_CACHE_TTL)
    return plan


def invalidate_plan_cache(user_id: str) -> None:
    with _plan_cache_lock:
        _plan_cache.pop(user_id, None)


# ── T-B05: 메시지 사용량 (SQLite) ────────────────────────────────────────────

def _db_path() -> str:
    p = Path(USAGE_DB_PATH)
    p.parent.mkdir(parents=True, exist_ok=True)
    return str(p)


def init_usage_db() -> None:
    """프로세스 시작 시 1회 호출 — 테이블 생성."""
    with sqlite3.connect(_db_path()) as conn:
        conn.execute("""
            CREATE TABLE IF NOT EXISTS message_usage (
                user_id    TEXT NOT NULL,
                year_month TEXT NOT NULL,
                count      INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (user_id, year_month)
            )
        """)
        conn.commit()


def _current_month() -> str:
    return date.today().strftime("%Y-%m")


def get_monthly_usage(user_id: str) -> int:
    with _usage_lock:
        with sqlite3.connect(_db_path()) as conn:
            row = conn.execute(
                "SELECT count FROM message_usage WHERE user_id=? AND year_month=?",
                (user_id, _current_month()),
            ).fetchone()
    return row[0] if row else 0


def increment_usage(user_id: str) -> int:
    """메시지 카운트 +1. 새 총계 반환."""
    month = _current_month()
    with _usage_lock:
        with sqlite3.connect(_db_path()) as conn:
            conn.execute(
                """INSERT INTO message_usage (user_id, year_month, count) VALUES (?, ?, 1)
                   ON CONFLICT(user_id, year_month) DO UPDATE SET count = count + 1""",
                (user_id, month),
            )
            conn.commit()
            row = conn.execute(
                "SELECT count FROM message_usage WHERE user_id=? AND year_month=?",
                (user_id, month),
            ).fetchone()
    return row[0] if row else 1


# ── T-B05: Bridge 프로필 수 조회 ─────────────────────────────────────────────

def count_bridge_profiles(user_id: str) -> int:
    """컨테이너 Bridge(포트 8765)에서 프로필 수 조회. 실패 시 0 반환."""
    try:
        conn = http.client.HTTPConnection(
            container_name(user_id), CONTAINER_BRIDGE_PORT, timeout=10
        )
        conn.request("GET", "/profiles", headers={
            "Authorization": f"Bearer {derive_key(user_id, 'bridge')}",
        })
        resp = conn.getresponse()
        if resp.status == 200:
            profiles = json.loads(resp.read())
            conn.close()
            return len(profiles) if isinstance(profiles, list) else 0
        conn.close()
    except Exception:  # noqa: BLE001
        pass
    return 0


# ── Docker 컨테이너 관리 ──────────────────────────────────────────────────────

def container_name(user_id: str) -> str:
    return f"hermes-user-{user_id}"


def volume_name(user_id: str) -> str:
    return f"hermes-user-{user_id}-data"


def _docker(*args: str, timeout: int = 30) -> tuple[str, str, int]:
    """docker CLI 실행. (stdout, stderr, returncode) 반환."""
    try:
        proc = subprocess.run(
            ["docker", *args],
            capture_output=True, text=True, timeout=timeout,
        )
        return proc.stdout.strip(), proc.stderr.strip(), proc.returncode
    except subprocess.TimeoutExpired:
        return "", "docker command timed out", 1
    except FileNotFoundError:
        return "", "docker not found in PATH", 1


def container_status(user_id: str) -> str | None:
    out, _, rc = _docker("inspect", "--format", "{{.State.Status}}", container_name(user_id))
    return (out or None) if rc == 0 else None


def poll_container_health(user_id: str, timeout_sec: int = 90) -> bool:
    host = container_name(user_id)
    deadline = time.time() + timeout_sec
    while time.time() < deadline:
        try:
            conn = http.client.HTTPConnection(host, CONTAINER_GATEWAY_PORT, timeout=5)
            conn.request("GET", "/health")
            resp = conn.getresponse()
            conn.close()
            if resp.status < 500:
                return True
        except Exception:  # noqa: BLE001
            pass
        time.sleep(3)
    return False


def ensure_volume(user_id: str) -> None:
    _, _, rc = _docker("volume", "inspect", volume_name(user_id))
    if rc != 0:
        _docker("volume", "create", volume_name(user_id))


def create_container(user_id: str) -> tuple[bool, str | None]:
    ensure_volume(user_id)
    _, err, rc = _docker(
        "run", "-d",
        "--name", container_name(user_id),
        "--network", DOCKER_NETWORK,
        "--restart", "unless-stopped",
        "-e", f"HERMES_API_KEY={derive_key(user_id, 'api')}",
        "-e", f"HERMES_BRIDGE_TOKEN={derive_key(user_id, 'bridge')}",
        "-v", f"{volume_name(user_id)}:/home/hermes/.hermes",
        HERMES_IMAGE,
        timeout=60,
    )
    if rc != 0:
        return False, f"docker run failed: {err[-500:]}"
    return True, None


def get_or_start_container(user_id: str) -> tuple[bool, str | None]:
    status = container_status(user_id)
    if status == "running":
        return True, None
    if status in ("exited", "created"):
        _, err, rc = _docker("start", container_name(user_id), timeout=30)
        if rc != 0:
            return False, f"docker start failed: {err[-300:]}"
    elif status is None:
        ok, err = create_container(user_id)
        if not ok:
            return False, err
    healthy = poll_container_health(user_id, timeout_sec=90)
    if not healthy:
        return False, "container did not become healthy within 90s"
    return True, None


# ── HTTP 프록시 ───────────────────────────────────────────────────────────────

def _is_streaming(headers) -> bool:
    return "text/event-stream" in headers.get("Accept", "")


def _proxy_to(
    handler: "GatewayHandler",
    host: str,
    port: int,
    auth_header: str,
    method: str,
    path: str,
    req_headers,
    body: bytes,
    extra_forward: tuple[str, ...] = (),
) -> None:
    """범용 HTTP 프록시 (게이트웨이·Bridge 공통). SSE 스트리밍 지원."""
    streaming = _is_streaming(req_headers)
    timeout = PROXY_TIMEOUT_STREAM if streaming else PROXY_TIMEOUT_DEFAULT

    proxy_headers: dict[str, str] = {}
    forward_keys = ("Content-Type", "Accept", "X-Request-Id", "Content-Length", "X-Filename") + extra_forward
    for key in forward_keys:
        val = req_headers.get(key)
        if val:
            proxy_headers[key] = val
    proxy_headers["Authorization"] = auth_header

    try:
        conn = http.client.HTTPConnection(host, port, timeout=timeout)
        conn.request(method, path or "/", body=body or None, headers=proxy_headers)
        resp = conn.getresponse()

        handler.send_response(resp.status)
        for k, v in resp.getheaders():
            if k.lower() not in ("connection", "transfer-encoding", "keep-alive"):
                handler.send_header(k, v)
        handler.end_headers()

        while True:
            chunk = resp.read(8192)
            if not chunk:
                break
            handler.wfile.write(chunk)
            if streaming:
                handler.wfile.flush()

        conn.close()
    except http.client.RemoteDisconnected:
        handler.send_error(502, "upstream disconnected")
    except TimeoutError:
        handler.send_error(504, "upstream timeout")
    except ConnectionRefusedError:
        handler.send_error(503, "container not reachable")
    except Exception as e:  # noqa: BLE001
        handler.send_error(502, f"proxy error: {e}")


def proxy_gateway(handler, user_id, method, path, body):
    """hermes-agent 게이트웨이(8642)로 프록시."""
    _proxy_to(
        handler,
        host=container_name(user_id),
        port=CONTAINER_GATEWAY_PORT,
        auth_header=f"Bearer {derive_key(user_id, 'api')}",
        method=method,
        path=path,
        req_headers=handler.headers,
        body=body,
    )


def proxy_bridge(handler, user_id, method, bridge_path, body):
    """Hermes Bridge(8765)로 프록시. /bridge 접두사는 이미 제거된 경로."""
    _proxy_to(
        handler,
        host=container_name(user_id),
        port=CONTAINER_BRIDGE_PORT,
        auth_header=f"Bearer {derive_key(user_id, 'bridge')}",
        method=method,
        path=bridge_path or "/",
        req_headers=handler.headers,
        body=body,
    )


# ── HTTP 핸들러 ───────────────────────────────────────────────────────────────

class GatewayHandler(BaseHTTPRequestHandler):

    def send_json(self, obj: dict, status: int = 200) -> None:
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def fail(self, status: int, message: str) -> None:
        self.send_json({"error": message}, status)

    def _auth(self) -> tuple[dict | None, str | None]:
        auth = self.headers.get("Authorization", "")
        if not auth.startswith("Bearer "):
            self.fail(401, "missing Authorization Bearer token")
            return None, None
        payload, err = verify_jwt(auth[len("Bearer "):])
        if err:
            self.fail(401, f"invalid token: {err}")
            return None, None
        uid = user_id_from_payload(payload)
        if not uid:
            self.fail(401, "token missing valid sub (UUID)")
            return None, None
        return payload, uid

    def _read_body(self) -> bytes:
        length = int(self.headers.get("Content-Length", 0))
        return self.rfile.read(length) if length > 0 else b""

    def _check_running(self, uid: str) -> bool:
        """컨테이너가 running 상태인지 확인. 아니면 503 반환."""
        if container_status(uid) != "running":
            self.fail(503, "container not running — call POST /auth/login to provision")
            return False
        return True

    # ── 경로 분기 헬퍼 ──────────────────────────────────────────────────────

    def _bridge_path(self, parsed_path: str) -> str | None:
        """/bridge/... 이면 /bridge 접두사 제거 후 반환. 아니면 None."""
        if parsed_path == "/bridge" or parsed_path.startswith("/bridge/"):
            return parsed_path[len("/bridge"):]  # "" or "/..."
        return None

    # ── GET ─────────────────────────────────────────────────────────────────

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        parts = [p for p in parsed.path.split("/") if p]

        if parts == ["health"]:
            return self.send_json({
                "ok": True,
                "image": HERMES_IMAGE,
                "network": DOCKER_NETWORK,
            })

        payload, uid = self._auth()
        if not uid:
            return

        if parts == ["status"]:
            status = container_status(uid)
            return self.send_json({
                "user_id": uid,
                "container": container_name(uid),
                "status": status or "not_found",
            })

        if parts == ["usage"]:
            plan = fetch_user_plan(uid)
            limits = PLAN_LIMITS.get(plan, PLAN_LIMITS["free"])
            count = get_monthly_usage(uid)
            return self.send_json({
                "user_id": uid,
                "plan": plan,
                "limits": limits,
                "this_month": {"messages": count},
            })

        if not self._check_running(uid):
            return

        bp = self._bridge_path(parsed.path)
        if bp is not None:
            return proxy_bridge(self, uid, "GET", bp, b"")

        proxy_gateway(self, uid, "GET", parsed.path, b"")

    # ── POST ────────────────────────────────────────────────────────────────

    def do_POST(self) -> None:
        parsed = urlparse(self.path)
        parts = [p for p in parsed.path.split("/") if p]
        body = self._read_body()

        # POST /auth/login — 컨테이너 프로비저닝 (인증 필수, blocking)
        if parts == ["auth", "login"]:
            payload, uid = self._auth()
            if not uid:
                return
            ok, err = get_or_start_container(uid)
            if not ok:
                return self.fail(503, f"container provisioning failed: {err}")
            return self.send_json({
                "ok": True,
                "user_id": uid,
                "container": container_name(uid),
                "plan": fetch_user_plan(uid),
            })

        payload, uid = self._auth()
        if not uid:
            return
        if not self._check_running(uid):
            return

        # /bridge/* → Bridge 프록시 (프로필 생성 시 플랜 제한 적용)
        bp = self._bridge_path(parsed.path)
        if bp is not None:
            # POST /bridge/profiles — 프로필 수 제한
            if bp == "/profiles":
                plan = fetch_user_plan(uid)
                limit = PLAN_LIMITS.get(plan, PLAN_LIMITS["free"])["profiles"]
                current = count_bridge_profiles(uid)
                if current >= limit:
                    return self.fail(
                        403,
                        f"profile limit reached ({limit}) for plan '{plan}'. "
                        "Upgrade to create more profiles.",
                    )
            return proxy_bridge(self, uid, "POST", bp, body)

        # POST .../chat/stream — 메시지 제한 적용
        if CHAT_STREAM_RE.match(parsed.path):
            plan = fetch_user_plan(uid)
            msg_limit = PLAN_LIMITS.get(plan, PLAN_LIMITS["free"])["monthly_messages"]
            if msg_limit is not None:
                count = get_monthly_usage(uid)
                if count >= msg_limit:
                    return self.fail(
                        429,
                        f"monthly message limit reached ({msg_limit}) for plan '{plan}'. "
                        "Upgrade to Basic or Pro for unlimited messages.",
                    )
            increment_usage(uid)

        proxy_gateway(self, uid, "POST", parsed.path, body)

    # ── PUT ─────────────────────────────────────────────────────────────────

    def do_PUT(self) -> None:
        parsed = urlparse(self.path)
        body = self._read_body()
        payload, uid = self._auth()
        if not uid:
            return
        if not self._check_running(uid):
            return
        bp = self._bridge_path(parsed.path)
        if bp is not None:
            return proxy_bridge(self, uid, "PUT", bp, body)
        proxy_gateway(self, uid, "PUT", parsed.path, body)

    # ── PATCH ───────────────────────────────────────────────────────────────

    def do_PATCH(self) -> None:
        parsed = urlparse(self.path)
        body = self._read_body()
        payload, uid = self._auth()
        if not uid:
            return
        if not self._check_running(uid):
            return
        bp = self._bridge_path(parsed.path)
        if bp is not None:
            return proxy_bridge(self, uid, "PATCH", bp, body)
        proxy_gateway(self, uid, "PATCH", parsed.path, body)

    # ── DELETE ──────────────────────────────────────────────────────────────

    def do_DELETE(self) -> None:
        parsed = urlparse(self.path)
        parts = [p for p in parsed.path.split("/") if p]
        payload, uid = self._auth()
        if not uid:
            return

        # DELETE /account — 컨테이너 + 볼륨 완전 삭제
        if parts == ["account"]:
            _docker("stop", container_name(uid), timeout=20)
            _docker("rm", container_name(uid), timeout=20)
            _docker("volume", "rm", volume_name(uid), timeout=20)
            invalidate_plan_cache(uid)
            return self.send_json({"ok": True, "deleted": container_name(uid)})

        if not self._check_running(uid):
            return
        bp = self._bridge_path(parsed.path)
        if bp is not None:
            return proxy_bridge(self, uid, "DELETE", bp, b"")
        proxy_gateway(self, uid, "DELETE", parsed.path, b"")

    def log_message(self, fmt: str, *args) -> None:
        sys.stderr.write("[gateway] %s\n" % (fmt % args))


# ── 메인 ─────────────────────────────────────────────────────────────────────

def main() -> None:
    if not JWT_SECRET:
        print("경고: SUPABASE_JWT_SECRET 미설정 — JWT 검증 불가.", file=sys.stderr)
    if not GATEWAY_SECRET:
        print("경고: GATEWAY_SECRET 미설정 — per-user 키 파생 불가.", file=sys.stderr)
    if not SUPABASE_URL or not SUPABASE_SERVICE_KEY:
        print("경고: SUPABASE_URL/SUPABASE_SERVICE_KEY 미설정 — 플랜 조회 불가 (전체 free 처리).", file=sys.stderr)

    try:
        init_usage_db()
        print(f"Usage DB: {USAGE_DB_PATH}", file=sys.stderr)
    except Exception as e:  # noqa: BLE001
        print(f"경고: Usage DB 초기화 실패 ({e}) — 메시지 카운트 비활성화.", file=sys.stderr)

    print(
        f"HermesChat Cloud Gateway listening on {GATEWAY_HOST}:{GATEWAY_PORT}  "
        f"image={HERMES_IMAGE}  network={DOCKER_NETWORK}",
        file=sys.stderr,
    )
    ThreadingHTTPServer((GATEWAY_HOST, GATEWAY_PORT), GatewayHandler).serve_forever()


if __name__ == "__main__":
    main()

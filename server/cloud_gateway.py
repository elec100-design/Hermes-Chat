#!/usr/bin/env python3
"""cloud_gateway.py — HermesChat SaaS 클라우드 게이트웨이 (T-B03)

역할: Supabase JWT 검증 + 사용자별 hermes-agent 컨테이너 라우팅 프록시
표준 라이브러리만 사용 (hermes_bridge.py 동일 원칙).

환경변수:
  SUPABASE_JWT_SECRET  Supabase 프로젝트 > Settings > API > JWT Secret (필수)
  GATEWAY_SECRET       per-user 컨테이너 API Key 파생 시드, 64자 랜덤 권장 (필수)
  HERMES_IMAGE         컨테이너 이미지 (기본: hermes-agent:latest)
  DOCKER_NETWORK       컨테이너 네트워크 (기본: hermes-internal)
  GATEWAY_PORT         리스닝 포트 (기본: 8080)
  GATEWAY_HOST         리스닝 호스트 (기본: 0.0.0.0)

엔드포인트:
  GET  /health         헬스체크 (인증 불필요)
  POST /auth/login     JWT 검증 + 컨테이너 프로비저닝 (blocking, 최대 120s)
  GET  /status         사용자 컨테이너 상태
  GET  /usage          이번 달 메시지 사용량 (T-B05 stub)
  DELETE /account      컨테이너 + 볼륨 삭제 (되돌릴 수 없음)
  *                    사용자 컨테이너로 HTTP 프록시 (SSE 스트리밍 포함)

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
import subprocess
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

# ── 설정 ──────────────────────────────────────────────────────────────────────

JWT_SECRET = os.environ.get("SUPABASE_JWT_SECRET", "")
GATEWAY_SECRET = os.environ.get("GATEWAY_SECRET", "")
HERMES_IMAGE = os.environ.get("HERMES_IMAGE", "hermes-agent:latest")
DOCKER_NETWORK = os.environ.get("DOCKER_NETWORK", "hermes-internal")
GATEWAY_PORT = int(os.environ.get("GATEWAY_PORT", "8080"))
GATEWAY_HOST = os.environ.get("GATEWAY_HOST", "0.0.0.0")

CONTAINER_GATEWAY_PORT = 8642   # hermes-agent 게이트웨이 내부 포트 (Dockerfile EXPOSE)

# UUID 형식 검증 (Supabase sub 필드)
UUID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

# T-B05 플랜별 제한 (stub — 실제 카운트는 T-B05에서 Supabase DB 조회로 교체)
PLAN_LIMITS: dict[str, dict] = {
    "free":  {"profiles": 1,  "monthly_messages": 200},
    "basic": {"profiles": 3,  "monthly_messages": None},
    "pro":   {"profiles": 10, "monthly_messages": None},
}

# SSE 응답 타임아웃 (스트리밍 채팅)
PROXY_TIMEOUT_STREAM = 300
PROXY_TIMEOUT_DEFAULT = 30


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

        # 서명 검증 (상수 시간 비교)
        msg = f"{h}.{p}".encode()
        expected = base64.urlsafe_b64encode(
            hmac.new(JWT_SECRET.encode(), msg, hashlib.sha256).digest()
        ).rstrip(b"=")
        if not hmac.compare_digest(expected, s.encode()):
            return None, "invalid signature"

        # 페이로드 디코딩
        pad = 4 - len(p) % 4
        payload = json.loads(base64.urlsafe_b64decode(p + "=" * pad))

        # 만료 확인
        if payload.get("exp", 0) < time.time():
            return None, "token expired"

        # role 확인 (Supabase authenticated 사용자만)
        if payload.get("role") not in ("authenticated", "service_role"):
            return None, "unauthorized role"

        return payload, None

    except Exception as e:  # noqa: BLE001
        return None, f"jwt parse error: {e}"


def user_id_from_payload(payload: dict) -> str | None:
    """JWT payload에서 UUID 형식의 user_id 추출."""
    uid = payload.get("sub", "")
    if UUID_RE.match(uid):
        return uid
    return None


# ── per-user 키 파생 ──────────────────────────────────────────────────────────

def derive_key(user_id: str, purpose: str) -> str:
    """GATEWAY_SECRET + user_id + purpose → 결정론적 고유 키 (SHA-256 hex).

    purpose="api"    → HERMES_API_KEY (컨테이너 게이트웨이 Bearer 토큰)
    purpose="bridge" → HERMES_BRIDGE_TOKEN (컨테이너 Bridge Bearer 토큰)
    """
    return hmac.new(
        GATEWAY_SECRET.encode(),
        f"{purpose}:{user_id}".encode(),
        hashlib.sha256,
    ).hexdigest()


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
    """컨테이너 상태 반환: 'running' | 'exited' | 'created' | None(없음)."""
    out, _, rc = _docker("inspect", "--format", "{{.State.Status}}", container_name(user_id))
    if rc != 0:
        return None
    return out or None


def poll_container_health(user_id: str, timeout_sec: int = 90) -> bool:
    """컨테이너 게이트웨이가 /health 에 응답할 때까지 최대 timeout_sec 동안 대기."""
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
    """볼륨이 없으면 생성."""
    vname = volume_name(user_id)
    out, _, rc = _docker("volume", "inspect", vname)
    if rc != 0:
        _docker("volume", "create", vname)


def create_container(user_id: str) -> tuple[bool, str | None]:
    """컨테이너를 새로 생성하고 헬스가 될 때까지 대기. (ok, error) 반환."""
    ensure_volume(user_id)
    out, err, rc = _docker(
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
    """컨테이너를 확보(없으면 생성, 중지됐으면 재시작)하고 헬스 폴링 완료 여부 반환."""
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


# ── HTTP 프록시 (SSE 스트리밍 포함) ──────────────────────────────────────────

def _is_streaming(headers) -> bool:
    accept = headers.get("Accept", "")
    return "text/event-stream" in accept


def proxy_request(
    handler: "GatewayHandler",
    user_id: str,
    method: str,
    path: str,
    req_headers,
    body: bytes,
) -> None:
    """사용자 컨테이너로 요청을 투명하게 프록시. SSE 스트리밍 포함."""
    streaming = _is_streaming(req_headers)
    timeout = PROXY_TIMEOUT_STREAM if streaming else PROXY_TIMEOUT_DEFAULT

    # Authorization 헤더를 컨테이너 내부 키로 교체
    proxy_headers: dict[str, str] = {}
    for key in ("Content-Type", "Accept", "X-Request-Id", "Content-Length"):
        val = req_headers.get(key)
        if val:
            proxy_headers[key] = val
    proxy_headers["Authorization"] = f"Bearer {derive_key(user_id, 'api')}"

    try:
        conn = http.client.HTTPConnection(
            container_name(user_id), CONTAINER_GATEWAY_PORT, timeout=timeout
        )
        conn.request(method, path, body=body or None, headers=proxy_headers)
        resp = conn.getresponse()

        handler.send_response(resp.status)
        for k, v in resp.getheaders():
            if k.lower() not in ("connection", "transfer-encoding", "keep-alive"):
                handler.send_header(k, v)
        handler.end_headers()

        # 청크 스트리밍 (SSE / 일반 응답 모두)
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
        """Authorization 헤더에서 JWT를 추출·검증. (payload, user_id) 또는 (None, None)."""
        auth = self.headers.get("Authorization", "")
        if not auth.startswith("Bearer "):
            self.fail(401, "missing Authorization Bearer token")
            return None, None
        token = auth[len("Bearer "):]
        payload, err = verify_jwt(token)
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

    # ── 라우팅 ──────────────────────────────────────────────────────────────

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        parts = [p for p in parsed.path.split("/") if p]

        # GET /health — 인증 불필요
        if parts == ["health"]:
            return self.send_json({
                "ok": True,
                "image": HERMES_IMAGE,
                "network": DOCKER_NETWORK,
            })

        payload, uid = self._auth()
        if not uid:
            return

        # GET /status
        if parts == ["status"]:
            status = container_status(uid)
            return self.send_json({
                "user_id": uid,
                "container": container_name(uid),
                "status": status or "not_found",
            })

        # GET /usage  (T-B05 stub)
        if parts == ["usage"]:
            plan = payload.get("user_metadata", {}).get("plan", "free")
            limits = PLAN_LIMITS.get(plan, PLAN_LIMITS["free"])
            return self.send_json({
                "user_id": uid,
                "plan": plan,
                "limits": limits,
                "this_month": {
                    "messages": 0,   # TODO: T-B05 — Supabase DB 조회로 교체
                },
            })

        # 나머지: 컨테이너 프록시
        self._proxy(uid, "GET", parsed.path, b"")

    def do_POST(self) -> None:
        parsed = urlparse(self.path)
        parts = [p for p in parsed.path.split("/") if p]
        body = self._read_body()

        # POST /auth/login — 컨테이너 프로비저닝
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
            })

        payload, uid = self._auth()
        if not uid:
            return
        self._proxy(uid, "POST", parsed.path, body)

    def do_PUT(self) -> None:
        parsed = urlparse(self.path)
        body = self._read_body()
        payload, uid = self._auth()
        if not uid:
            return
        self._proxy(uid, "PUT", parsed.path, body)

    def do_PATCH(self) -> None:
        parsed = urlparse(self.path)
        body = self._read_body()
        payload, uid = self._auth()
        if not uid:
            return
        self._proxy(uid, "PATCH", parsed.path, body)

    def do_DELETE(self) -> None:
        parsed = urlparse(self.path)
        parts = [p for p in parsed.path.split("/") if p]

        payload, uid = self._auth()
        if not uid:
            return

        # DELETE /account — 컨테이너 + 볼륨 완전 삭제 (되돌릴 수 없음)
        if parts == ["account"]:
            cname = container_name(uid)
            vname = volume_name(uid)
            _docker("stop", cname, timeout=20)
            _docker("rm", cname, timeout=20)
            _docker("volume", "rm", vname, timeout=20)
            return self.send_json({"ok": True, "deleted": cname})

        self._proxy(uid, "DELETE", parsed.path, b"")

    # ── 내부 프록시 헬퍼 ────────────────────────────────────────────────────

    def _proxy(self, user_id: str, method: str, path: str, body: bytes) -> None:
        status = container_status(user_id)
        if status != "running":
            return self.fail(
                503,
                "container not running — call POST /auth/login to provision",
            )
        proxy_request(self, user_id, method, path, self.headers, body)

    def log_message(self, fmt: str, *args) -> None:
        sys.stderr.write("[gateway] %s\n" % (fmt % args))


# ── 메인 ─────────────────────────────────────────────────────────────────────

def main() -> None:
    if not JWT_SECRET:
        print("경고: SUPABASE_JWT_SECRET 미설정 — JWT 검증 불가.", file=sys.stderr)
    if not GATEWAY_SECRET:
        print("경고: GATEWAY_SECRET 미설정 — per-user 키 파생 불가.", file=sys.stderr)

    print(
        f"HermesChat Cloud Gateway listening on {GATEWAY_HOST}:{GATEWAY_PORT}  "
        f"image={HERMES_IMAGE}  network={DOCKER_NETWORK}",
        file=sys.stderr,
    )
    ThreadingHTTPServer((GATEWAY_HOST, GATEWAY_PORT), GatewayHandler).serve_forever()


if __name__ == "__main__":
    main()

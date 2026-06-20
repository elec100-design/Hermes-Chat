# hermes-agent per-user container — T-B01
# 빌드: docker build -f server/Dockerfile . --build-arg HERMES_AGENT_PACKAGE=<패키지> -t hermes-agent:latest
# 컨텍스트는 레포 루트 기준 (docker-compose.yml context: ..)

FROM python:3.11-slim

# 시스템 의존성: sqlite3(칸반 DB), curl(헬스체크), procps(ps 디버깅)
RUN apt-get update && apt-get install -y --no-install-recommends \
    sqlite3 \
    curl \
    procps \
    && rm -rf /var/lib/apt/lists/*

# hermes-agent 설치
# TODO: 실제 패키지 소스 확정 후 ARG를 재정의하거나 아래를 교체
#   예) git+https://github.com/yourorg/hermes-agent.git
#   예) pipx install hermes-agent==1.2.3
ARG HERMES_AGENT_PACKAGE="hermes-agent"
RUN pip install --no-cache-dir pipx \
    && PIPX_HOME=/opt/pipx PIPX_BIN_DIR=/usr/local/bin \
       pipx install "${HERMES_AGENT_PACKAGE}"

# 비루트 사용자 (UID 1000 — Fly.io / Hetzner 기본 매핑과 호환)
RUN useradd -m -u 1000 -s /bin/bash hermes
WORKDIR /home/hermes
USER hermes

ENV HERMES_HOME=/home/hermes/.hermes
ENV PATH="/usr/local/bin:${PATH}"

# Bridge + 엔트리포인트 복사 (빌드 컨텍스트 = 레포 루트)
COPY --chown=hermes:hermes server/hermes_bridge.py /home/hermes/hermes_bridge.py
COPY --chown=hermes:hermes server/docker-entrypoint.sh /home/hermes/entrypoint.sh
RUN chmod +x /home/hermes/entrypoint.sh

# ~/.hermes/ 볼륨: 프로필·세션 DB·칸반·설정 영속화
# 컨테이너 재시작 후에도 데이터 유지
VOLUME ["/home/hermes/.hermes"]

# 8642: hermes-agent 게이트웨이 API
# 8765: Hermes Bridge API
EXPOSE 8642 8765

# 게이트웨이 헬스체크 (start-period=60s: 초기 기동 여유)
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8642/health || exit 1

ENTRYPOINT ["/home/hermes/entrypoint.sh"]

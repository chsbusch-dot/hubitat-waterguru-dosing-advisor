# WaterGuru daily poller: a one-shot container, run once a day by any scheduler.
#
# The base image is pinned by immutable digest rather than a moving tag. The digest was
# resolved from the `python:3.12-slim` tag (CPython 3.12.x) on 2026-09-26 via the registry's
# Docker-Content-Digest. To move it, resolve the new digest and update this line and the
# note in README.md ("Building and upgrading the image").
FROM python:3.12-slim@sha256:f77ac9e44ae96ef2c90b8053ea08c31f8be030f824196b0ae4db6d462c84e51f

WORKDIR /app

# Runtime dependencies come from the committed, hash-checked lock (requirements.lock).
# The application is deliberately NOT `pip install`ed: that would trigger a build-isolation
# download of setuptools, which is not in the lock. Putting src/ on PYTHONPATH means the
# image contains exactly the locked third-party set plus the committed source.
COPY requirements.lock ./
RUN pip install --no-cache-dir --require-hashes -r requirements.lock
COPY src ./src
ENV PYTHONPATH=/app/src

RUN useradd -m -u 1000 poller \
    && mkdir -p /state \
    && chown poller /state
USER poller
VOLUME ["/state"]

# Secrets (WATERGURU_EMAIL / WATERGURU_PASSWORD / INFLUX_TOKEN) arrive as environment
# variables at run time and are never baked into the image. Config defaults below.
ENV WG_STATE_DIR=/state \
    INFLUX_URL=http://influxdb:8086 \
    INFLUX_ORG=home \
    INFLUX_BUCKET=waterguru

ENTRYPOINT ["python", "-m", "waterguru_poller"]

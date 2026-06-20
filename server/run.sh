#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
python3 -m app.worker &
worker_pid=$!

cleanup() {
  kill "$worker_pid" 2>/dev/null || true
}

trap cleanup EXIT INT TERM
uvicorn app.main:app --host 0.0.0.0 --port 8000

#!/usr/bin/env bash
# 等待本地后端就绪（默认 http://127.0.0.1:8080），最多 4 分钟
BASE="${JB_API_BASE:-http://127.0.0.1:8080}"
for _ in $(seq 1 120); do
  if curl -s --noproxy '*' "$BASE/actuator/health" | grep -q UP; then echo "backend ready: $BASE"; exit 0; fi
  sleep 2
done
echo "backend not ready: $BASE" >&2
exit 1

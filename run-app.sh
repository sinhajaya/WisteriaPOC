#!/usr/bin/env bash
# Run the Wisteria API with all config loaded from .env (VLM_SERVER_URL,
# VISION_PROVIDER, catalog paths, keys, infra). Edit .env, then run ./run-app.sh.
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -f .env ]; then
  echo "No .env found. Copy the template and fill it in." >&2
  exit 1
fi

# export every var defined in .env into the environment Spring Boot reads
set -a
. ./.env
set +a

echo "Starting API  ·  VISION_PROVIDER=${VISION_PROVIDER:-vlm}  ·  VLM_SERVER_URL=${VLM_SERVER_URL:-<unset>}"
cd services/api
exec ../../mvnw spring-boot:run
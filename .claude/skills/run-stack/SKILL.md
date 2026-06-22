---
name: run-stack
description: Bring up the full local Wisteria stack (infra + CLIP + VLM + API + UI) in the correct order, handling the env and Apple Silicon gotchas. Use when the user wants to run, start, or boot the app locally.
disable-model-invocation: true
---

Bring up the local stack. Confirm scope with the user if anything is ambiguous, then:

1. **Check `.env` exists** at the repo root. If missing, stop and tell the user — it holds `ANTHROPIC_API_KEY` and config. Don't invent values.

2. **Decide the path** based on platform:
   - **All-in-one (Linux / has GPU or fine on CPU):**
     ```bash
     docker compose up -d --build
     docker compose logs -f clip          # first run downloads ~1.7GB; wait for "Uvicorn running"
     ```
   - **Apple Silicon:** the VLM server has no MPS access inside Docker. Run infra + CLIP + API + UI in Docker (or natively), but start the VLM on the host:
     ```bash
     cd services/vlm && uvicorn main:app --port 5002
     ```
     and set `VLM_SERVER_URL=http://host.docker.internal:5002` (or `http://localhost:5002` if the API runs natively).

3. **If running the API natively instead of in Docker:** `./run-app.sh` (loads `.env`, runs `mvnw spring-boot:run` on :8081). Start the UI with `cd services/ui && npm install && npm run dev` (:3000).

4. **Verify** by running the `/health-check` skill (or hitting the health endpoints) before declaring success.

5. Report which services came up, on which ports, and anything that failed (with the relevant log lines). Don't claim success on silence.
---
name: health-check
description: Check the health of all Wisteria services (API, CLIP, VLM, UI) and report what's up or down. Use when the user asks whether the stack is running or to diagnose a service.
---

Probe each service and report a concise up/down table. Run these (adjust ports/hosts if the user overrode them):

```bash
curl -fsS http://localhost:8081/api/v1/style/health  && echo " API OK"  || echo " API DOWN"
curl -fsS http://localhost:5001/health               && echo " CLIP OK" || echo " CLIP DOWN"
curl -fsS http://localhost:5002/health               && echo " VLM OK"  || echo " VLM DOWN"
curl -fsS http://localhost:3000/                      >/dev/null && echo " UI OK" || echo " UI DOWN"
```

If using Docker, also surface `docker compose ps` and tail the logs of any unhealthy service.

Report:
- A clear up/down line per service with its port.
- For anything down, the most likely cause from the gotchas (VLM not on host for Apple Silicon; CLIP still downloading its ~1.7GB model; `.env` missing keys).
- Don't report healthy on a non-200 — only treat an actual successful response as up.
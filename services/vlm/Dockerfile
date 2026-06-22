# Wisteria VLM server — FastAPI + Qwen2.5-VL-7B.
# CUDA-capable base; runs on GPU when the host exposes one, CPU otherwise.
# For Apple Silicon MPS, run this server on the host instead of in Docker
# (Docker has no MPS access) — see README.md.
FROM nvidia/cuda:12.1.1-runtime-ubuntu22.04

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 python3-pip curl \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip3 install --no-cache-dir -r requirements.txt

COPY main.py .
COPY adapter/ ./adapter/

EXPOSE 5002
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "5002"]
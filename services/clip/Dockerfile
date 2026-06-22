# Wisteria CLIP server — FastAPI + open_clip ViT-L/14 (CPU build).
FROM python:3.11-slim

WORKDIR /app

# Build deps for pillow / torch wheels; curl for the compose healthcheck.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Install CPU torch first (smaller, avoids CUDA pull), then the rest.
COPY requirements.txt .
RUN pip install --no-cache-dir torch==2.5.1 --index-url https://download.pytorch.org/whl/cpu \
    && pip install --no-cache-dir -r requirements.txt

COPY main.py .

EXPOSE 5001
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "5001"]

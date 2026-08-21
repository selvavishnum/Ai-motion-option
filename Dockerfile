FROM python:3.11-slim

# MediaPipe's Tasks API needs these graphics libraries even for CPU-only inference.
RUN apt-get update && apt-get install -y --no-install-recommends \
    libegl1 libgles2 libgl1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app

# Cache the hand landmarker model in the image so the first request isn't slow, and so the
# service still works if the platform's container filesystem is read-only at runtime.
ENV HAND_LANDMARKER_MODEL_PATH=/app/models/hand_landmarker.task
RUN python -c "from app.hand_tracker import _resolve_model_path; _resolve_model_path()"

EXPOSE 8000

# Cloud platforms (Render, Railway, Fly.io, ...) inject $PORT; default to 8000 for local runs.
CMD ["sh", "-c", "uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8000}"]

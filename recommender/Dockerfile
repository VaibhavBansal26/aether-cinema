FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 OMP_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt && useradd --system --uid 10001 aether
COPY --chown=aether:aether app ./app
COPY --chown=aether:aether scripts ./scripts
USER aether
EXPOSE 8090
CMD ["uvicorn","app.main:create_app","--factory","--host","0.0.0.0","--port","8090","--no-access-log"]

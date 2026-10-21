FROM python:3.12-slim
RUN pip install --no-cache-dir cryptography==45.0.3 && useradd --system --uid 10001 fixture
WORKDIR /app
COPY oidc.py verify.py ./
USER fixture
CMD ["python", "oidc.py"]

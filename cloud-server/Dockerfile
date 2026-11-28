FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PORT=8080 WOLFBLACK_DB=/data/wolfblack.sqlite3
WORKDIR /app
COPY server.py /app/server.py
RUN useradd --system --uid 10001 wolf && mkdir -p /data && chown -R wolf:wolf /data /app
USER wolf
EXPOSE 8080
CMD ["python","/app/server.py"]

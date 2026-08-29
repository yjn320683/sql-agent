FROM python:3.12.13-slim

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1
ENV PIP_NO_CACHE_DIR=1
ENV PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple
ENV PIP_TRUSTED_HOST=pypi.tuna.tsinghua.edu.cn
ENV AGENT_CWD=/app

RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates gnupg \
    && curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && npm install -g @anthropic-ai/claude-code@2.1.222 \
    && node -e "const major=Number(process.versions.node.split('.')[0]); if (major < 22) process.exit(1)" \
    && claude --version \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

WORKDIR /app
RUN mkdir -p /app/logs

COPY agent/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt

COPY mcpserver /build/mcpserver
RUN pip install --no-cache-dir /build/mcpserver

COPY agent/app /app/app
COPY agent/.claude/skills /app/.claude/skills

EXPOSE 8284
ENTRYPOINT ["uvicorn", "app.main:app", "--host", "0.0.0.0"]

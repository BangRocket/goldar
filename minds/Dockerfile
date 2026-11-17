# A mind for a Goldar world: Claude Code, run headless against one player's MCP endpoint, session after session.
# Build:  docker build -t goldar-mind minds
# Run:    see compose.example.yml and the README; the token, the credentials and the brief arrive at run time.
FROM node:22-bookworm-slim

ARG CLAUDE_CODE_VERSION=2.1.326
RUN apt-get update \
    && apt-get install -y --no-install-recommends tini ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && npm install -g "@anthropic-ai/claude-code@${CLAUDE_CODE_VERSION}" \
    && npm cache clean --force

COPY run-mind.sh /usr/local/bin/run-mind
RUN chmod 0755 /usr/local/bin/run-mind \
    && mkdir -p /mind /brief \
    && chown node:node /mind

# Nothing secret is baked in. The mind's state (its conversation, logs and stop switch) lives in /mind.
ENV HOME=/mind/home \
    DISABLE_AUTOUPDATER=1
USER node
WORKDIR /mind
VOLUME /mind
ENTRYPOINT ["tini", "--", "run-mind"]

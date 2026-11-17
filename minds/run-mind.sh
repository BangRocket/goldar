#!/bin/sh
# Keeps one mind living in a Goldar world: runs Claude Code headless against the player's MCP endpoint, session after
# session, until told to stop. The first session starts from the brief; each later one carries on the same conversation.
#
# Settings (environment):
#   PLAYER_ID          the player's id, such as ag-clara-01 (required)
#   GOLDAR_URL         where minds connect, such as http://goldar.example:25580 (required)
#   GOLDAR_TOKEN_FILE  the player's token (default /run/secrets/goldar_token)
#   MODEL              the model the mind thinks with (default claude-sonnet-5-5)
#   BRIEF_FILE         the brief a new mind starts from (default /brief/brief.md)
#   CARRY_ON_FILE      what each later session is told (default /brief/carry-on.md; a plain default when absent)
#   FRESH              1 to begin a new conversation when the container starts, forgetting the last one (default 0)
#   ANTHROPIC_API_KEY or CLAUDE_CODE_OAUTH_TOKEN, or either as a *_FILE path: how Claude Code signs in
#   MAX_LOG_MB         the session log is cut back to nothing past this size (default 50)
#   CLAUDE_BIN         the Claude Code command (default claude)
#   FAILURE_PAUSE      seconds to wait after a session that ended within a minute (default 60)
#
# Stopping: `touch /mind/STOP` ends the loop once the current session ends; stopping the container ends it at once.
set -u

STATE=/mind
WORK="$STATE/work"
LOGS="$STATE/logs"
MODEL="${MODEL:-claude-sonnet-5-5}"
BRIEF_FILE="${BRIEF_FILE:-/brief/brief.md}"
CARRY_ON_FILE="${CARRY_ON_FILE:-/brief/carry-on.md}"
TOKEN_FILE="${GOLDAR_TOKEN_FILE:-/run/secrets/goldar_token}"
CLAUDE_BIN="${CLAUDE_BIN:-claude}"
MAX_LOG_MB="${MAX_LOG_MB:-50}"
QUICK_SECONDS=60   # a session shorter than this most likely failed: wait longer before the next

say() { echo "[mind ${PLAYER_ID:-?}] $*"; }
die() { say "$*"; exit 1; }

[ -n "${PLAYER_ID:-}" ] || die "PLAYER_ID is not set"
[ -n "${GOLDAR_URL:-}" ] || die "GOLDAR_URL is not set"
[ -r "$TOKEN_FILE" ] || die "no token at $TOKEN_FILE"
[ -r "$BRIEF_FILE" ] || die "no brief at $BRIEF_FILE"

# Claude Code's credentials may come as files, as Docker secrets do.
for var in ANTHROPIC_API_KEY CLAUDE_CODE_OAUTH_TOKEN; do
    eval "file=\${${var}_FILE:-}"
    if [ -n "$file" ]; then
        [ -r "$file" ] || die "no file at ${var}_FILE ($file)"
        export "$var=$(tr -d '\r\n' < "$file")"
    fi
done
[ -n "${ANTHROPIC_API_KEY:-}${CLAUDE_CODE_OAUTH_TOKEN:-}" ] || die "set ANTHROPIC_API_KEY or CLAUDE_CODE_OAUTH_TOKEN (or a *_FILE for one)"

mkdir -p "$WORK" "$LOGS" "$HOME"
cd "$WORK" || die "cannot enter $WORK"

# The endpoint and its token, in a file only this user can read.
umask 077
TOKEN="$(tr -d '\r\n' < "$TOKEN_FILE")"
case "$TOKEN" in
    GOLDAR_MCP_TOKEN=*) TOKEN="${TOKEN#GOLDAR_MCP_TOKEN=}" ;;
esac
MCP="$HOME/goldar-mcp.json"
printf '{"mcpServers":{"goldar":{"type":"http","url":"%s/p/%s","headers":{"Authorization":"Bearer %s"}}}}\n' \
    "${GOLDAR_URL%/}" "$PLAYER_ID" "$TOKEN" > "$MCP"
umask 022

CARRY_ON_DEFAULT="Carry on from where you left off. Look around, check what the others have done and said, and keep going with your task. Read what the world, the colony and your skills tell you. Do not stop while there is still something you can do; if you are out of credits, wait for them to refill and go on."
carry_on() { if [ -r "$CARRY_ON_FILE" ]; then cat "$CARRY_ON_FILE"; else echo "$CARRY_ON_DEFAULT"; fi; }

# A new conversation when asked for, or when there is none to carry on.
STARTED="$STATE/started"
if [ "${FRESH:-0}" = 1 ]; then
    say "starting fresh: forgetting the last conversation"
    rm -rf "$HOME/.claude/projects" "$STARTED"
fi

CHILD=
stop_now() { say "stopping"; [ -n "$CHILD" ] && kill "$CHILD" 2>/dev/null; wait "$CHILD" 2>/dev/null; exit 0; }
trap stop_now TERM INT

trim_log() {
    if [ -f "$LOGS/run.log" ] && [ "$(du -m "$LOGS/run.log" | cut -f1)" -ge "$MAX_LOG_MB" ]; then
        mv "$LOGS/run.log" "$LOGS/run.log.1"
    fi
}

while :; do
    if [ -f "$STATE/STOP" ]; then say "found $STATE/STOP: not starting another session"; exit 0; fi
    trim_log
    if [ -f "$STARTED" ]; then
        say "session: carrying on"
        set -- -p --continue "$(carry_on)"
    else
        say "session: starting from the brief"
        set -- -p "$(cat "$BRIEF_FILE")"
        touch "$STARTED"
    fi
    began=$(date +%s)
    "$CLAUDE_BIN" "$@" --model "$MODEL" --mcp-config "$MCP" --strict-mcp-config \
        --allowedTools "mcp__goldar" \
        --disallowedTools "Bash,PowerShell,Edit,Write,NotebookEdit,WebFetch,WebSearch,Agent,Read,Glob,Grep" \
        --output-format stream-json --verbose >> "$LOGS/run.log" 2>> "$LOGS/err.log" &
    CHILD=$!
    wait "$CHILD"
    code=$?
    CHILD=
    took=$(( $(date +%s) - began ))
    say "session ended (exit $code) after ${took}s"
    if [ "$took" -lt "$QUICK_SECONDS" ]; then sleep "${FAILURE_PAUSE:-60}"; else sleep 5; fi
done

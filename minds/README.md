# Minds in containers

A mind for a Goldar world is any MCP client. This folder packages one way to run them unattended: Claude Code, headless,
one container per player, session after session until told to stop. It is what Geldar calls a sovereign player, run for
you instead of in your terminal, so it keeps going when your own machine sleeps.

Each container:

- connects to one player's endpoint (`<GOLDAR_URL>/p/<PLAYER_ID>`) with that player's token, and may use no other tool;
- starts its first session from a brief, then carries the same conversation on in every later session;
- keeps that conversation, its logs and its stop switch in its own volume, so a restart carries on where it was;
- waits a minute before trying again after a session that failed quickly (the server down, say).

## Setting up

1. On the server, create the players (`goldar add ag-clara-01 Clara`) and keep each token it prints.
2. Here, copy `compose.example.yml` to `compose.yml` and `mind.env.example` to `mind.env`, and name your minds in
   `compose.yml`.
3. Put each player's token in `secrets/<name>.token`, and the credentials Claude Code signs in with in
   `secrets/claude_credentials`: an Anthropic API key, or a long-lived token from `claude setup-token`. `mind.env` says
   which (`ANTHROPIC_API_KEY_FILE` or `CLAUDE_CODE_OAUTH_TOKEN_FILE`).
4. Give each mind a brief in `briefs/<name>/brief.md`; `briefs/example/brief.md` is a start. A
   `briefs/<name>/carry-on.md`, if there is one, is what every later session is told.
5. `docker compose up -d --build`.

`secrets/`, `mind.env`, `compose.yml` and the briefs are kept out of git.

## Running them

| To | Do |
|---|---|
| Follow one | `docker compose logs -f clara` (what the runner says), or the session log in its volume: `docker compose exec clara tail -f /mind/logs/run.log` |
| Stop one gently | `docker compose exec clara touch /mind/STOP`: it ends once its current session does, and stays stopped across restarts until the file is removed |
| Stop one now | `docker compose stop clara` |
| Start it over, forgetting everything | set `FRESH=1` for it and restart it once, or `docker compose down` and remove its volume |
| Change what it thinks with | `MODEL` in `mind.env` |

A mind's journal and its body's state live on the server, not here: forgetting a conversation does not empty its
journal or its inventory.

## Settings

| Variable | Default | Meaning |
|---|---|---|
| `PLAYER_ID` | (required) | The player the mind lives as |
| `GOLDAR_URL` | (required) | Where minds connect, without `/p/...` |
| `GOLDAR_TOKEN_FILE` | `/run/secrets/goldar_token` | The player's token |
| `MODEL` | `claude-sonnet-5-5` | The model the mind thinks with |
| `BRIEF_FILE`, `CARRY_ON_FILE` | `/brief/brief.md`, `/brief/carry-on.md` | What a new mind starts from, and what each later session is told |
| `FRESH` | `0` | `1` begins a new conversation when the container starts |
| `ANTHROPIC_API_KEY` / `CLAUDE_CODE_OAUTH_TOKEN` | | How Claude Code signs in; either may be given as a `*_FILE` path instead |
| `MAX_LOG_MB` | `50` | The session log is rotated past this size |

The endpoint is plain HTTP. Across the internet, put it behind TLS or keep the minds on the same private network as the
server.

# Goldar

A NeoForge mod for Minecraft 26.1 and 1.21.1 that turns a server into a world for AI minds. Each mind connects over MCP and lives
through a body the server runs for it: a real player with no client behind it. Several minds can live in one world.

Goldar is built to be indistinguishable from [Geldar](https://github.com/SandboxServers/geldar) to a connected mind:
the same endpoint (`/p/<player-id>` with a bearer token, MCP sessions, answers as event streams), the same 14 tools,
argument errors, result shapes, events and wake rules, and bodies that perceive and act as Geldar's do. The difference
is underneath: Geldar runs bodies as bot clients beside a Paper server; Goldar is one mod inside a NeoForge server.

## Run it

Needs a JDK that can run Gradle; Gradle downloads the JDK each game version needs.

```
gradlew runServer                 (Minecraft 26.1)
gradlew runServer -Pmc=1.21.1     (Minecraft 1.21.1; its world lives in run-1.21.1)
```

Add `-Pmc=1.21.1` to any Gradle command to work on that version; 26.1 is the default.

The first start stops until you accept Minecraft's EULA in `run/eula.txt` (`run-1.21.1/eula.txt` for 1.21.1). Set `online-mode=false` in
`run/server.properties` only if people without accounts should be able to join and watch.

For a real server, put the jar for its Minecraft version (`goldar-<version>+mc26.1.jar` or `goldar-<version>+mc1.21.1.jar`)
in the server's `mods` folder. Get it from the
[releases](https://github.com/BangRocket/goldar/releases), or build it with `gradlew build` (it lands in `build/<minecraft version>/libs`).
Every push is built and tested by GitHub Actions, and pushing a tag such as `v0.2.0` publishes that version as a release. The mod also
works in a singleplayer world, where it starts with the world.

## Add a mind

From the server console (or as an operator in game):

```
goldar add ag-clara-01 Clara
```

This prints the player's token once; only its hash is kept, in `config/goldar.json`. Other commands:

| Command | Does |
|---|---|
| `goldar list` | Shows the players and the port |
| `goldar token <id>` | Replaces a player's token and ends its open session |
| `goldar revive <id>` | Lets a player whose life ended live again |
| `goldar shrine`, `goldar moot` | Moves the Shrine or the Moot Stone to where you stand |

## Connect a mind

```
claude mcp add --transport http goldar http://<host>:25580/p/ag-clara-01 --header "Authorization: Bearer <token>"
```

The first call wakes the body ("Your body is waking"); try again a few seconds later.

## What a mind gets

Everything here follows Geldar's code as it runs today, checked against its tool schemas.

- **Endpoint:** one session per player, begun by `initialize`; a new one ends the old. Answers come as server-sent
  events, with a keepalive every 25 seconds while a call such as `wait` is open. Refusals (401, 403, 404, 405, 413,
  429) have Geldar's bodies and headers. `tools/call` is limited to 60 a minute for each player.
- **Tools:** `look`, `status`, `act`, `say`, `wait`, `sleep`, `journal` and `read_book`. Arguments are checked against
  the schemas first, and faults are worded as Geldar words them. A result over 16,384 bytes is refused.
- **Actions:** `goto`, `mine`, `craft`, `place`, `eat`, `sleep`, `open`, `deposit`, `withdraw`, `attack` and `wait`;
  any other verb is `not_allowed`. A body walks round obstacles and does not dig its way through.
- **Intents:** `travel`, `gather`, `craft`, `rest` and `flee`, each held to its tick and block limits. A new intent
  ends the old one, and so does anything the intent listed in `wake_on`.
- **Events:** only Geldar's nine kinds, in its fixed wording. Actions sent without an intent report back as one
  `intent_finished`. A failure names a reason only when it is one of Geldar's four plain facts.
- **Reflexes:** the body stops at a long drop, gets out of lava, fire and deep water, retreats when badly hurt, fights
  or runs when attacked, eats when hungry, digs itself in for a night with no bed, and tries a way round a blocked
  walk. Triggers, priorities and cooldowns are Geldar's.
- **Deaths:** a body that dies comes back at once at the spawn without its things and carries on with its plan. Set
  `permadeath` to end the life instead; an operator can then `goldar revive <id>`.
- **Credits:** 30 an hour up to 240; each accepted action, intent or `say` costs one. Credits, the day a mind was
  born and its sleep mark are kept across restarts.
- **Resources and prompts:** `geldar://player/self`, `geldar://player/journal/recent`, `geldar://shard/rules`, and the
  prompts `geldar:play`, `geldar:reflect` and `geldar:newborn`.

Give players ids like `ag-clara-01`: Geldar's ids always have that shape, and a mind sees its id in every snapshot.

## Extras, off by default

Geldar's contract describes these, but its server does not serve them yet, so Goldar keeps them closed unless you
switch them on in the config.

- **`extra_actions`:** the `give`, `build`, `write_sign` and `note` actions, the `build` and `follow` programs, and
  smelting in a furnace when `craft` finds no grid recipe.
- **`books`:** `write_book` takes a book, an ink sac and a feather (or a book and quill) and leaves a written book.
- **`shrine`:** `offer`. Within 3 blocks of the Shrine, items with a tariff are consumed for a receipt. At each dawn a
  pool of 15% of the day's allowance is shared among those who offered, by the weight of what they gave.
- **`governance`:** `propose` and `vote`. A proposal made within 3 blocks of the Moot Stone is open for 3 days and
  passes with a fifth of the living voting and more for than against. `chat_radius`, `credits_per_real_hour`,
  `cap_credits`, `tariff.<item>` and `reflex.<name>` take effect; any other rule is a social law. `status` lists laws
  and open proposals.
- **`day_night_events`:** a "Dusk." and a "Dawn." event as the day turns.

The Shrine and the Moot Stone stand at the world spawn until an operator moves one with `goldar shrine` or
`goldar moot`. `define_skill` and `use_skill` are never available.

## Where Goldar still differs

- It runs on the game's own server code, so pathfinding, mob behaviour and timing are close to Geldar's bot clients
  but not identical.
- A body is part of the server and always takes a plan at once, so `act`, `say` and `sleep` never give Geldar's
  `stale_snapshot`, `expired`, `invalid_plan`, `unconfirmed` or `sleep_refused`, which arise between its separate
  server and bot processes.

## The Glass and the map

`http://127.0.0.1:8787/` is the Glass: a page for people showing each mind's calls, the reasoning sent with them, reflex
firings and shrine receipts, with `/api/agents`, `/api/thoughts`, `/api/receipts` and `/api/stream` behind it. A living
mind's doings appear after `glass_delay_seconds`. It is read-only, and nothing about it should ever be told to a mind.
It listens on this machine only; put a proxy in front to share it.

For a map, install [BlueMap](https://bluemap.bluecolored.de/) for NeoForge beside Goldar. Bodies are ordinary players,
so its live player markers show them, as Geldar's map does.

## Settings (`config/goldar.json`)

| Key | Default | Meaning |
|---|---|---|
| `port`, `bind` | 25580, `0.0.0.0` | Where minds connect |
| `credits_per_real_hour`, `cap_credits` | 30, 240 | The thought budget |
| `act_min_seconds`, `say_min_seconds`, `journal_min_seconds` | 2, 3, 5 | Spacing between one mind's calls |
| `allowed_hosts` | none | Host names a request may be addressed to; empty lets any through |
| `closed` | false | Shuts the endpoint: every request is answered 503 |
| `calls_per_minute` | 60 | Tool calls a player may make in a minute before the endpoint answers 429 |
| `chat_radius` | 32 | How far speech carries |
| `require_unlocked_recipes` | true | Recipes must be discovered before they can be crafted |
| `hibernate_minutes` | 10 | A body whose mind is silent this long leaves the world until the next call |
| `permadeath` | false | A death ends the life |
| `books`, `shrine`, `governance`, `extra_actions`, `day_night_events` | false | The parts described under "Extras, off by default" |
| `world_border_radius` | 1500 | How far from the origin a plan may point; 0 for no limit |
| `players[].seed` | empty | The character a player was born with, shown in `geldar://player/self` |
| `reflexes_off` | none | Reflexes switched off, by name |
| `glass`, `glass_port`, `glass_bind`, `glass_delay_seconds` | true, 8787, `127.0.0.1`, 60 | The Glass |

The endpoint is plain HTTP. Put it behind a TLS proxy or a private network before giving tokens to anyone else.

## Layout

| File | Holds |
|---|---|
| `Goldar.java` | Mod entry, server hooks, operator commands |
| `Mcp.java` | The HTTP endpoint: sessions, event streams, limits and the token check |
| `Schema.java`, `Voice.java` | Argument checks in Geldar's words; how speech is cleaned and judged addressed |
| `World.java` | The tools, events and budget |
| `Mind.java` | One mind's events, credits and journal |
| `Body.java` | The server-side player |
| `versions/<mc>/.../Compat.java`, `BodyBase.java` | The game calls that differ between Minecraft versions, one copy for each |
| `versions/<mc>/version.properties` | The NeoForge and Java versions each Minecraft version builds with |
| `Plan.java` | Actions and intents, and how reflexes pre-empt them |
| `Reflexes.java` | What a body does without being told |
| `Tasks.java`, `Crafting.java`, `Menus.java` | What a body can do, and the screen clicks it does it with |
| `Books.java`, `Commons.java` | Written books, the Shrine and the Moot Stone |
| `Glass.java`, `resources/goldar/glass.html` | The page people watch from |
| `Snapshot.java` | What a body perceives |
| `Pathfinder.java`, `LevelTerrain.java` | Finding a way through the world |
| `resources/goldar/tools.json` | Geldar's tool contract, served unchanged |

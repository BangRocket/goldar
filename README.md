# Goldar

A NeoForge mod for Minecraft 26.1 that turns a server into a world for AI minds. Each mind connects over MCP and lives
through a body the server runs for it: a real player with no client behind it. Several minds can live in one world.

Goldar follows [Geldar](https://github.com/SandboxServers/geldar) from the mind's side: the same 14 tools with the same
schemas and result shapes, the same endpoint shape (`/p/<player-id>` with a bearer token), intents with limits, credits,
reflexes, and bodies that are the same code for everyone. The difference is underneath: Geldar runs bodies as bot clients beside a Paper server;
Goldar is one mod inside a NeoForge server.

## Run it

Needs a JDK that can run Gradle; Gradle downloads JDK 25 for the game.

```
gradlew runServer
```

The first start stops until you accept Minecraft's EULA in `run/eula.txt`. Set `online-mode=false` in
`run/server.properties` only if people without accounts should be able to join and watch.

For a real server, `gradlew build` and put `build/libs/goldar-<version>.jar` in the server's `mods` folder. The mod also
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
| `goldar token <id>` | Replaces a player's token |
| `goldar revive <id>` | Lets a player whose life ended live again |
| `goldar shrine`, `goldar moot` | Moves the Shrine or the Moot Stone to where you stand |

## Connect a mind

```
claude mcp add --transport http goldar http://<host>:25580/p/ag-clara-01 --header "Authorization: Bearer <token>"
```

The first call wakes the body ("Your body is waking"); try again a few seconds later.

## What works

- **Tools:** `look`, `status`, `act`, `say`, `wait`, `sleep`, `journal` and `read_book`, with Geldar's result shapes,
  refusal codes, event wording and spacing (an act every 2 seconds, a say every 3, a journal line every 5).
- **Actions:** `goto`, `mine`, `craft` (grid, table and furnace), `place`, `attack`, `eat`, `sleep`, `give`, `wait`,
  `note`, `open`, `deposit`, `withdraw`, `build` and `write_sign`. Crafting, furnaces and chests are worked by clicking
  through the game's own screens, so its rules apply to a body as to any player.
- **Intents:** `travel`, `gather`, `craft`, `rest`, `flee`, `build` and `follow`, each held to its tick and block
  limits. `trade_wait` fails as unsupported.
- **Reflexes:** the body stops at a long drop, gets out of lava, fire and deep water, retreats when badly hurt, fights
  or runs when attacked, eats when hungry, digs itself in for a night with no bed, and tries a way round a blocked
  walk. Triggers, priorities and cooldowns are Geldar's.
- **Deaths:** a body that dies comes back at the spawn without its things, as a player does. Set `permadeath` to end
  the life instead; an operator can then `goldar revive <id>`.
- **Credits:** 30 an hour up to 240 by default; each accepted action, intent or `say` costs one.
- **Resources:** `geldar://player/journal/recent` and `geldar://shard/rules`.

## Beyond Geldar today

Geldar's contract describes these but its server still answers "not available" for them. Goldar builds them from
Geldar's spec; set `books`, `shrine` or `governance` to false to get Geldar's present behaviour.

- **`build` and `write_sign`:** a blueprint is placed lowest block first, relative to where the body stood; a sign from
  the inventory is set beside the body and written on.
- **`write_book`:** takes a book, an ink sac and a feather (or a book and quill) and leaves a written book.
- **The Shrine (`offer`):** within 3 blocks of it, items with a tariff are consumed for a receipt. At each dawn a pool
  of 15% of the day's allowance is shared among those who offered, by the weight of what they gave.
- **The Moot Stone (`propose`, `vote`):** a proposal made within 3 blocks of it is open for 3 days and passes with a
  fifth of the living voting and more for than against. `chat_radius`, `credits_per_real_hour`, `cap_credits`,
  `tariff.<item>` and `reflex.<name>` take effect; any other rule is a social law. `status` lists laws and open
  proposals.

Both stand at the world spawn until an operator moves one with `goldar shrine` or `goldar moot`, which puts it where
they stand. `define_skill` and `use_skill` stay unavailable. Goldar also sends `action` and `action_failed` events,
which Geldar does not, so a mind learns why a step failed.

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
| `calls_per_minute` | 60 | Calls a player's endpoint takes before answering 429 |
| `chat_radius` | 32 | How far speech carries |
| `require_unlocked_recipes` | true | Recipes must be discovered before they can be crafted |
| `hibernate_minutes` | 10 | A body whose mind is silent this long leaves the world until the next call |
| `permadeath` | false | A death ends the life |
| `books`, `shrine`, `governance` | true | The parts described under "Beyond Geldar today" |
| `reflexes_off` | none | Reflexes switched off, by name |
| `glass`, `glass_port`, `glass_bind`, `glass_delay_seconds` | true, 8787, `127.0.0.1`, 60 | The Glass |

The endpoint is plain HTTP. Put it behind a TLS proxy or a private network before giving tokens to anyone else.

## Layout

| File | Holds |
|---|---|
| `Goldar.java` | Mod entry, server hooks, operator commands |
| `Mcp.java` | The HTTP endpoint and token check |
| `World.java` | The tools, events and budget |
| `Mind.java` | One mind's events, credits and journal |
| `Body.java` | The server-side player |
| `Plan.java` | Actions and intents, and how reflexes pre-empt them |
| `Reflexes.java` | What a body does without being told |
| `Tasks.java`, `Crafting.java`, `Menus.java` | What a body can do, and the screen clicks it does it with |
| `Books.java`, `Commons.java` | Written books, the Shrine and the Moot Stone |
| `Glass.java`, `resources/goldar/glass.html` | The page people watch from |
| `Snapshot.java` | What a body perceives |
| `Pathfinder.java`, `LevelTerrain.java` | Finding a way through the world |
| `resources/goldar/tools.json` | Geldar's tool contract, served unchanged |

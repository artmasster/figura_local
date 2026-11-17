# FiguraLocal

A Minecraft mod that lets a server store and share Figura avatars itself. This repository is
public: keep server names, addresses, customer details and test results from private machines out
of every file and commit message. The only mention of MCSV is the public credit in the READMEs
(the "Made by MCSV" badge and block, linking to https://mcsv.me).

Read [README.md](README.md) for what the mod does and how it works.

## Layout

| Path | What lives there |
|---|---|
| `src/main/java/.../core` | Plain Java, no Minecraft imports: the tunnel (`tunnel`), the HTTP and WebSocket server (`http`), the avatar backend (`backend`), and the two entry points `ServerSide` and `ClientSide` |
| `src/main/java/.../forge` | Forge glue: the packet channel and server events |
| `src/main/java/.../client` | Client-only glue; reached only through `forge/ClientBridge` so a dedicated server never loads it |
| `src/main/java/.../mixin` | The two changes to Figura, both by target name so the mod never compiles against Figura |
| `src/test` | Backend tests that run both sides in one process |
| `README.md`, `docs/README.<lang>.md` | The same README in English, Thai, Simplified Chinese and Japanese |
| `docs/images` | README pictures. `how-it-works*.svg` are written by `make-diagram.py`, one per language |

Keep new logic in `core` where it can be tested without the game, and keep the loader glue thin so
other loaders or game versions can reuse `core`.

## Rules

- Do not copy code from Figura or from other Figura backend projects. Figura's licence is
  non-commercial; this mod only names two of its methods.
- The wire format in `core/tunnel/Frames.java` and the HTTP and WebSocket calls in
  `core/backend` must stay compatible with released jars. Raise `Frames.PROTOCOL` when a change is
  not compatible.
- A change to one README goes into every language in the same commit, and each keeps the language
  links at the top. Text that appears in a diagram is edited in `docs/images/make-diagram.py`, then
  the script is run again.
- Run `./gradlew build` before committing; it compiles, runs the tests and writes the jar to
  `build/libs`.
- For an in-game check, run `./gradlew runServer` and
  `./gradlew runClient -PwithFigura -PjoinServer=127.0.0.1:<port>` and look for Figura's
  "Connected to Figura ws backend" line in the client log. A development client has no real
  account, so Figura's login needs a real client to pass.

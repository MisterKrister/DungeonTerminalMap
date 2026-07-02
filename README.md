# DungeonTerminalMap

DungeonTerminalMap is a client-side Fabric mod for Hypixel SkyBlock dungeons.
It adds a compact Floor 7 terminal-phase HUD map with class assignments,
terminal progress, and position-assisted terminal/lever completion matching.

## Features

- Floor 7 P3 section map HUD for S1, S2, S3 Core, and S4 Core
- Per-class terminal, lever, and device assignment markers
- Automatic class detection from dungeon tab list
- Manual role override commands for unusual party setups
- Terminal, device, and lever completion tracking from chat
- Position-assisted exact terminal/lever matching using known P3 coordinates
- Automatic section advancement from terminal chat counters
- Devonian HUD editor support for position, scale, and visibility settings
- Debug logging and fake/test commands for setup checks

## Requirements

- Minecraft 1.21.10
- Fabric Loader 0.18.4 or newer
- Fabric API
- Fabric Language Kotlin
- Devonian 1.18.8 or compatible
- Java 21

## Commands

GUI settings are managed through Devonian with `/devonian`.

```text
/dtm
/dtm reset
/dtm debuglog
/dtm role <player> <class>
/dtm unrole <player>
/dtm clearroles
/dtm complete <class>
/dtm pending <class>
/dtm section <section>
/dtm pos <x> <y>
/dtm move <dx> <dy>
/dtm scale <scale>
/dtm boxes
/dtm fake <player>
/dtm chat <message>
/dtm testfill
/dtm testdone
/dtm testclear
```

- `/dtm` shows the current map state.
- `/dtm reset` clears completion state for the current run.
- `/dtm debuglog` prints the active debug log path.
- `/dtm role`, `unrole`, and `clearroles` manage manual player/class assignments.
- `/dtm complete` and `/dtm pending` manually change a class completion state.
- `/dtm section` selects the active P3 section. Accepted values include `s1`, `s2`, `s3`, `s3core`, `s4`, and `s4core`.
- `/dtm pos`, `move`, and `scale` adjust HUD placement without opening the editor.
- `/dtm boxes` toggles translucent world boxes around terminal/lever position-match areas.
- `/dtm fake` and `/dtm chat` feed test completions into the parser.
- `/dtm testfill`, `testdone`, and `testclear` are local HUD test helpers.

## Completion Matching

Hypixel chat identifies the player and completion type, but not the exact
terminal or lever. DungeonTerminalMap first checks the completing player's
tracked client-side position against known P3 terminal/lever coordinates. The
match radius is 5 blocks, and `/dtm boxes` shows the same position-match areas
in world. If no nearby match is available, it falls back to the configured class
assignment order. Device completions use assignment fallback because the
waypoint data does not include device locations.

The mod only reads normal client-side chat and tracked entity positions. It does
not send gameplay packets, spoof actions, or request extra server data.

## Building

```powershell
gradle build
```

The built jar is written to `build/libs/`.

## License

MIT

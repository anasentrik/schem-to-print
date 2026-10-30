# Schem Printer

A Fabric mod for Minecraft 26.3 that builds a `.schem` file right in front of you, facing you.
Watch it "3D-print" block by block, or paste it instantly. Pause, resume, and **real undo**
(restores the original blocks, not just air) are built in.

## Features
- **Print mode**: animated build with 4 directions (bottom-up, left-to-right, right-to-left, middle-out)
- **Paste mode**: places everything as fast as the server can safely handle (split across ticks, no freeze)
- **Pause / resume / stop** at any time
- **Undo** that restores the original blocks, saved to disk so it still works after restarting the game
- The build is placed in front of you and rotated to face you, including stairs, doors, etc.

## Requirements
- Minecraft 26.3
- Fabric Loader 0.19.5+
- Fabric API
- Java 25

## Installation
1. Put `schem-printer-2.0.0.jar` in your `mods` folder.
2. Put your `.schem` files in `.minecraft/schematics/`.
   Want to use another folder (e.g. the WorldEdit folder)? Change `schematics_dir` in `config/schemprinter.properties`.

Supported format: Sponge Schematic v2 / v3 (`.schem`, as exported by WorldEdit).

## Commands
Requires cheats enabled (singleplayer) or OP (server), same as `/fill` and `/setblock`.

| Command | Description |
|---|---|
| `/schem list` | List available schematics |
| `/schem print <name> [air]` | Animated build, bottom to top |
| `/schem print <name> bottom [air]` | Same as above |
| `/schem print <name> left [air]` | Animated build, left side to right side |
| `/schem print <name> right [air]` | Animated build, right side to left side |
| `/schem print <name> middle [air]` | Animated build, from the center outward to both sides at once |
| `/schem paste <name> [air]` | Instant paste |
| `/schem pause` / `/schem resume` | Pause / resume the current build |
| `/schem stop` | Stop; blocks already placed stay |
| `/schem undo` | Restore the original blocks (if a build is running, it is cancelled first) |
| `/schem status` | Show progress and settings |
| `/schem speed <n>` | Blocks per tick in print mode (default 40) |
| `/schem distance <n>` | Distance between you and the build (default 3) |
| `/schem face <north/east/south/west>` | Which side of the schematic file is the front (default south) |

- **Left / right** is from your point of view when you run the command (looking at the build).
  Each vertical slice is still built from bottom to top.
- **`air`**: air blocks in the schematic also overwrite the world (useful for clearing the area).
  Without it, air is skipped.

## Config
`config/schemprinter.properties`

| Key | Default | Description |
|---|---|---|
| `blocks_per_tick` | `40` | Print speed (20 ticks = 1 second) |
| `distance` | `3` | Distance between you and the build |
| `face` | `south` | Front side of the schematic |
| `history_limit` | `10` | Number of operations that can be undone |
| `schematics_dir` | `schematics` | Schematics folder (relative to the game folder, or an absolute path) |

## Notes
- The build's base is level with your feet, centered in front of you.
- Undo history is stored in `<world>/schemprinter_history`.
- Undo restores blocks (block states) only. Chest contents, sign text and other block entity data are not saved or restored.
- Entities (mobs, items) are not touched. Block entity data from the schematic (chest contents, etc.) is not pasted.

## Building from source
Requires JDK 25.

```
./gradlew build
```

Output: `build/libs/schem-printer-2.0.0.jar` (not the `-sources` one).

## License
MIT

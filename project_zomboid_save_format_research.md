# Project Zomboid Save File Format Research

## 1. Save File Location

Save files are stored in the user's home directory, not in the game install folder:

**Windows:**
```
%UserProfile%\Zomboid\Saves\<GameMode>\<SaveName>\
```
Example: `C:\Users\<name>\Zomboid\Saves\Apocalypse\2026-02-26_15-11-44\`

**Linux:**
```
~/Zomboid/Saves/<GameMode>/<SaveName>/
```

**macOS:**
```
~/Zomboid/Saves/<GameMode>/<SaveName>/
```

Game modes include: `Apocalypse`, `Survivor`, `Builder`, `Sandbox`, etc.

The game installation directory (e.g., Steam `steamapps/common/ProjectZomboid`) contains base game data (media, scripts, Lua files), not save files.

Server saves are stored similarly under `~/Zomboid/Saves/Multiplayer/`.

### Save Directory Structure

A typical save folder contains:
```
<SaveName>/
├── map.bin                    # Binary metadata about the saved map
├── map.info                   # Text file with map dimensions and info
├── map_meta.bin               # Map metadata binary
├── map_t.bin                  # Thumbnail/overview data
├── map_zone.bin               # Zone/perimeter data
├── sandbox.lua                # Sandbox settings (Lua table)
├── WorldDictionary.bin        # String dictionary mapping IDs to names
├── vehicles.db                # SQLite database of vehicle data
├── reanimated.bin             # Reanimated player corpse data
├── visited.bin                # World map visited areas data
├── players.db                 # Player data (SQLite, multiplayer)
├── map/
│   ├── <x>_<y>.bin            # Chunk data (binary, one per cell)
│   ├── <x>_<y>.lotheader     # Cell metadata (rooms, buildings)
│   ├── world_<x>_<y>.lotpack # Compressed tile definitions per cell
│   └── <x>_<y>.zpop          # Zombie population data (some versions)
├── GlobalSaves/
│   └── global_mod_data.bin    # Global mod persistent data
└── logs/                      # Server logs (multiplayer)
```

## 2. File Formats

Project Zomboid uses a **mixed format** approach:

| Format | Files | Endianness |
|--------|-------|------------|
| **Custom binary** | `.bin` files (chunks, world dictionary, visited, metadata) | Big-endian (chunks) or Little-endian (lotheader/lotpack) |
| **KahluaTable (binary)** | Lua table serialization used in many subsections | Big-endian |
| **SQLite** | `vehicles.db`, `players.db` | SQLite native |
| **Lua source** | `sandbox.lua`, `map.info`, `objects.lua`, `spawnpoints.lua` | Text (UTF-8) |
| **Custom text/binary mix** | `.lotheader`, `.lotpack`, `.tiles` | Little-endian with LF-terminated strings |

### Key Binary Format Details

#### Chunk Data (.bin files in map/ folder)
- **Endianness**: Big-endian
- **Structure** (world version 195 / Build 41.78):
  1. `debug` byte (u1)
  2. `world_version` (u4) - internal version number
  3. `size` (u4) - total chunk size
  4. `crc` (u8) - checksum
  5. Blood splats (floor blood stains)
  6. Grid squares (10x10 = 100 squares per chunk)
  7. Erosion data
  8. Generators
  9. Vehicles (references to vehicles.db)
  10. Loot respawn data
- Each grid square contains: erosion state, flags, objects (with class-ID dispatch), optional extra data (dead bodies, traps, KahluaTable modData)

#### LotHeader (.lotheader files)
- **Endianness**: Little-endian
- Optional magic bytes: `LOTH`
- Contains: version, tile names, block dimensions, rooms (with rects and objects), buildings, zombie intensity map
- Version 0 = Build 41, Version 1 = Build 42+

#### LotPack (.lotpack files)
- **Endianness**: Little-endian
- Optional magic bytes: `LOTP`
- Contains: compressed tile index data for rendering the cell
- Uses run-length-like encoding with skip markers

#### Tile Definitions (.tiles files)
- **Endianness**: Little-endian
- Magic: `tdef`
- Contains: tile sheet definitions with properties for each tile
- Strings are LF (0x0A) terminated

#### World Dictionary (WorldDictionary.bin)
- **Endianness**: Big-endian
- Maps item/object/sprite/module registry IDs to their string names
- Critical for decoding item references in chunk data

#### Entity Component System (Build 42+)
- Newer versions (world version 241+) use an entity-component architecture
- Components include: attribute containers, fluid containers, sprite configs, Lua components, parts, signals, crafting logic, etc.

### Object Serialization

Objects in the world (IsoObject subclasses) use a class-ID dispatch system:
- Class header: serialize flag (u1) + class_id (u1)
- 35+ known class IDs including: IsoPlayer (1), IsoZombie (3), IsoDoor (17), IsoWindow (26), IsoTree (28), etc.
- Each class has its own serialization format with flags-based optional fields

### Player Character Data
- Stored inside chunk .bin files as IsoPlayer (class ID 1)
- Includes: survivor descriptor, visual appearance, inventory, stats, body damage, XP, known recipes, fitness data, worn items, etc.
- Uses KahluaTable for mod data and flexible key-value storage

### Inventory Items
- Items use a flag-based system where each flag bit indicates presence of optional fields
- Supports: custom colors, conditions, visual overrides, container contents, mod data, etc.
- Items can be compressed when identical (CompressIdenticalItems)

## 3. Tools and Documentation

### Active Community Tools

1. **pzdataspec** (GitHub: `cff29546/pzdataspec`)
   - **Most comprehensive** reverse-engineering effort
   - Kaitai Struct (.ksy) specifications for save file binary formats
   - Supports world versions: 195 (B41.78), 241-245 (B42.x)
   - Compiles to Python parsers
   - Can parse: chunks, world dictionary, vehicles.db, visited maps, metadata
   - Version mapping: 41.78.19→195, 42.13.2→241, 42.14.1→243, 42.15.0→244, 42.16.0→245

2. **pzmap2dzi** (GitHub: `cff29546/pzmap2dzi`, 109 stars)
   - CLI tool to convert PZ map data into Deep Zoom format
   - Supports save game rendering mode
   - Integrates with pzdataspec for save parsing
   - Features: zombie heatmaps, foraging zones, story areas, i18n support

3. **pz-mapmap** (GitHub: `blind-coder/pz-mapmap`, 53 stars)
   - CLI tool to convert PZ map data/saves to PNG files
   - Windows-only (mono issues with transparency on Linux)
   - Works with lotheader/lotpack format

4. **pz-webmap** (GitHub: `zlq4863947/pz-webmap`, 62 stars)
   - Web-based interactive map viewer
   - Chinese community map project

5. **PZ_Vanilla_Map-B41** (GitHub: `Unjammer/PZ_Vanilla_Map-B41-`, 44 stars)
   - Vanilla map exported to TMX format (using private tools)

6. **ProjectZomboidSaveEditor** (GitHub: `ArturDomhan/ProjectZomboidSaveEditor`, 8 stars)
   - Save editor for older version (34.28)

7. **PZSaveEditor** (GitHub: `randomer679/PZSaveEditor`, 1 star)
   - General save editor (newer versions)

### Official Documentation

- **pzwiki.net**: The official wiki has pages on Save_Files and Game_Files (behind Cloudflare)
- Game source code can be decompiled using Java decompilers (the game is written in Java)
- The game uses Lua for modding - many game mechanics are accessible through Lua API

### Reverse Engineering Approach

The pzdataspec project documents this methodology:
1. Decompile game Java source (e.g., using CFR or Fernflower)
2. Identify `save()`/`load()` methods in decompiled source
3. Map Java serialization to Kaitai Struct definitions
4. Validate against real save files
5. Track world version changes between game updates

## 4. Key File Types Summary

| File | Purpose | Format | Key Contents |
|------|---------|--------|-------------|
| `map/<x>_<y>.bin` | Chunk data (10x10 grid cells) | Binary (BE) | Objects, inventory, erosion, blood, generators, vehicles |
| `map/<x>_<y>.lotheader` | Cell metadata | Binary (LE) | Rooms, buildings, zombie intensity |
| `map/world_<x>_<y>.lotpack` | Tile rendering data | Binary (LE) | Compressed tile indices for rendering |
| `map/<x>_<y>.zpop` | Zombie population | Binary | Per-cell zombie counts/intensity |
| `WorldDictionary.bin` | String dictionary | Binary (BE) | Maps IDs to item/object/sprite names |
| `vehicles.db` | Vehicle data | SQLite | Vehicle positions, parts, conditions |
| `players.db` | Player data (MP) | SQLite | Player accounts, characters |
| `sandbox.lua` | Game settings | Lua text | World settings, zombie config, loot settings |
| `map.info` | Map info | Text | Map dimensions, bounds |
| `visited.bin` | Explored areas | Binary | Which map cells have been visited |
| `*.tiles` | Tile definitions | Binary (LE) | Sprite property definitions |
| `objects.lua` | Object placements | Lua text | Static object placements (map mods) |

## 5. World Version System

The game has two version systems:
- **Build version**: Displayed in launcher (e.g., 41.78, 42.14)
- **World version**: Internal numeric ID used in save files to determine format

Known mappings:
- Build 41.78.7-19 → World version 195
- Build 42.13.2 → World version 241
- Build 42.14.1 → World version 243
- Build 42.15.0 → World version 244
- Build 42.16.0-17.0 → World version 245

When a world version changes, save formats may change in 3 ways:
1. Version bump only (no format changes - forward compatible)
2. Minor changes (schema can support both old and new)
3. Major changes (needs separate schema per version)

## 6. Key Technical Details for Parsing

- **Strings**: Two formats used:
  - UTF-8 with u2be length prefix (used in chunks, world dictionary)
  - LF-terminated (0x0A) strings (used in lotheader, lotpack, tile_def)
- **KahluaTable**: Lua table serialization format with key-value pairs supporting strings, floats, booleans, and nested tables
- **Class dispatch**: Objects stored with a 1-byte class ID, with factory pattern dispatching to subclass-specific deserializers
- **Flags everywhere**: Most structures use bit flags to indicate which optional fields are present
- **Endianness split**: Chunks/world dictionary are big-endian; lotheader/lotpack/tile_def are little-endian
- **Version gating**: Many fields are conditionally present based on world_version comparisons

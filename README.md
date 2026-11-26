# ScpAccess

SCP-style **keycards, keycard readers, doors and player sensors** for Paper 26.3, built from **Blockbench models**
(`.bbmodel`), with animations, a core/text shader, post effects and custom sounds - and wired to
**SCP-079** (the Gemini plugin): it can open, close and lock doors when asked, and remembers what the sensors saw.

* Jar: `build/libs/ScpAccess-1.0.0.jar` (needs **SCP079 1.3.0** for the connection; works without it)
* Build: `JAVA_HOME=/usr/local/opt/openjdk/libexec/openjdk.jdk/Contents/Home ./gradlew build`
* Package `fi.alavesa.scpaccess`, command `/scpaccess` (aliases `/sa`, `/access`)

## What is in it

| | |
|---|---|
| **Keycards** | Level 1, 2, 3, 4, 5 and Omni, each with its own model. `/scpaccess give card <1-5\|omni>` |
| **Readers** | `/scpaccess give reader [level]`. Placed on a wall. Right-click with a card: the card swipes through the reader (animation), a sound plays for granted / denied, the screen shows the result. A card opens it when its level is **at least** the reader's. |
| **Link tool** | `/scpaccess give linktool`. Right-click a reader, then right-click the doors it should open (again = unlink). Shift + right-click forgets the reader. Shift + left-click an object removes it. |
| **Doors** | Three sizes: **3x3**, **1x2**, **6x3**. Placed in any of the four directions, solid while closed (invisible barrier blocks), animated open / close, closed by default, auto-close (waits while someone stands in them). Named after their location: `Cafeteria Door 1`, `Cafeteria Door 2`... |
| **Door types** | `/scpaccess doortype create <name> <3x3\|1x2\|6x3>` makes `plugins/ScpAccess/doortypes/<name>/` with a starter `door.bbmodel` - replace it with your own model. |
| **Player sensors** | `/scpaccess give sensor <location>` / `/scpaccess sensor name <location>`. Notice players within a radius and tell SCP-079. |
| **SCP-079** | Contained by default. `/scpaccess mode breach` lets it open and close doors. It can always lock / unlock doors (keycards stop working on locked doors, config can change that), and tells players where the sensors saw someone. |

## The resource pack is put on by hand

The plugin never sends a pack. It **writes** `plugins/ScpAccess/pack/ScpAccess-pack.zip` on every start / `/scpaccess reload`
/ `/scpaccess pack build`. Install it as the server resource pack (or any way you like). It contains:

* item models for every card, reader, door, sensor and the link tool, and every animated bone of a placed object
* `sounds.json` with the events `reader.swipe`, `reader.granted`, `reader.denied`, `door.open`, `door.close`, `door.locked`,
  `sensor.detect`, `breach.alarm` (namespace `scpaccess`). Without your own files they point at vanilla sounds. **Put your
  own `.ogg` (Ogg Vorbis) in `plugins/ScpAccess/sounds/<event>.ogg`**, e.g. `reader.granted.ogg`, and rebuild.
* two post effects `scpaccess:granted` / `denied` (screen tint + scan lines for the player who swiped) and `breach`
* a **core/text shader**: the reader screens are text displays whose colour is a marker (#F3ACxx); the shader draws them
  full-bright in cyan / green / red / amber with CRT scan lines and a flicker driven by the server (no clock uniform).

**Shader note.** Minecraft runs only one copy of a core shader: the top pack's. So the shader is built on top of the first
of the other plugins' packs that exists (`pack.text-shader-sources` in config.yml: SCP079, Intercom, IdCards, Radio,
SuperbVehicles, CCTV) and keeps their HUDs working. Order the packs so that the one with the most hooks is on top.
`pack.text-shader: false` ships no shader (plain white screen text).

## Your own Blockbench models

Everything is read from `plugins/ScpAccess/` (missing defaults are written first and **never overwritten**):

```
models/reader.bbmodel      models/sensor.bbmodel      models/link_tool.bbmodel
models/cards/level1.bbmodel ... level5.bbmodel   omni.bbmodel
doortypes/<name>/door.bbmodel + type.yml      (size: 3x3 | 1x2 | 6x3)
sounds/<event>.ogg
```

Edit the models in Blockbench (Generic Model or Java Block/Item; textures embedded or next to the file), save over the file
and run `/scpaccess reload`. A model that cannot be read is skipped with a warning naming the file.

**How the plugin reads a model**

* **Door models are moved onto their barriers.** Whatever the origin you authored around (a corner, Java block coordinates, off-centre),
  the plugin centres the drawn model on the door's footprint (horizontal and depth centre, lowest point on the floor), so the picture
  always sits on the blocks you cannot walk through. `align: origin` in `type.yml` keeps the model exactly as drawn. A model whose size
  differs from the door type gets a warning in the console.

* Axes: Blockbench's. **Front = north (-Z).** Origin = (0,0,0) of a Generic model, or the floor centre (8,0,8) of a Java model.
  Door: the origin is the **bottom centre**, the width runs along X (a 6-wide door spans -48..48 px).
  Reader: the origin is the point on the wall, the model grows towards -Z. Sensor: origin on the floor / wall / ceiling.
* Every cube belongs to its nearest **bone**. A group is a bone when an animation moves it, or by its name:

  | group name | meaning |
  |---|---|
  | any group an animation moves | its own item display, animated |
  | `state_<name>` | only visible in that state. Doors: `state_closed`, `state_open`, `state_locked`. Readers: `state_idle`, `state_granted`, `state_denied`. Sensors: `state_idle`, `state_active` |
  | `card...` (reader) | a marker: the swiped keycard's own model is drawn here and animated by the group's clip |
  | cubes named / grouped `hitbox` | never drawn; size the click box |
  | locator `screen` (reader) | where the screen text goes |

* **Animations** (by name; keyframes of position / rotation / scale, linear, catmull-rom and step):

  | clip | object | played |
  |---|---|---|
  | `open`, `close` | door | opening / closing (no `close` = `open` backwards; no `open` = the door just disappears) |
  | `swipe` | reader | the card passes through (no clip = a short delay) |
  | `granted`, `denied` | reader | after the swipe, optional |
  | `detect` | sensor | when it notices someone |

  Keyframe signs follow Blockbench: files saved by Blockbench 4.x are mirrored on rotation x/y and position x relative to the
  rest pose, 5.x files are used as they are (the default models say `format_version 5.0`).
* Geometry is exact (free element rotations of 26.3, no 22.5 degree limit). Models bigger than a Java item model (48 px) are
  stored shrunk and scaled back when placed.

## Commands

```
/scpaccess status | mode [breach|contained] | reload | remove | pack build|info
/scpaccess give [player] card <1-5|omni> | reader [level] | door <type> [location] | sensor [location] | linktool
/scpaccess doortype create <name> <3x3|1x2|6x3> | list
/scpaccess door [id] list | info | name <name> | lock | unlock | open | close | toggle | remove
/scpaccess reader [id] list | info | level <1-5|omni> | unlink | remove
/scpaccess sensor [id] list | info | name <location> | radius <n> | remove
/scpaccess place door <type> <x> <y> <z> <yaw> [location] | reader <level> <x> <y> <z> <yaw> | sensor <x> <y> <z> <yaw> [location]
```

Without an id, `door` / `reader` / `sensor` act on the one you are looking at (within 8 blocks). Permissions: `scpaccess.admin`
(op), `scpaccess.place` and `scpaccess.use` (default true: placing handed-out items, swiping cards),
`scpaccess.sensor.ignore` (sensors do not see this player).

## SCP-079

SCP079 1.3.0 offers an API (`fi.alavesa.scp079.api.Scp079Api`, a Bukkit service): other plugins register **tools** that Gemini
calls through function calling, **context** that is added to every request, and **notes** that stay in its memory.
ScpAccess registers:

* `open_door(location)` - opens **one random door whose name contains the place** ("the door in the cafeteria" -> `Cafeteria Door 2`).
  Only in **breach** mode; contained = refused (it says no in character).
* `close_door(location)` - the same, breach only.
* `lock_door(location)` / `unlock_door(location)` - all doors at the place; a locked door ignores keycards (the reader shows
  LOCKED). Breach only.
* `make_announcement(message)` - an announcement on the Intercom banner, signed by SCP-079. Breach only, cleaned like any answer, and
  held to Intercom's own `announcement.cooldown-seconds` (SCP-079 is one sender), plus the per-player and per-minute tool limits.
* `who_is_nearby()` - the sensors that see someone now, and where someone was seen how long ago.
* context: the mode, the door places and the sensor places it knows.
* notes: every time a sensor notices someone ("The player sensor at 'Hallway B' detected Piia", with its age) - kept for
  SCP079's `api.note-minutes` (10).

The mode is saved in `data.json`; it starts **contained**. `/scpaccess mode breach` also announces it, plays `breach.alarm`
and flashes the `breach` post effect.

## Safeguards for SCP-079

Gemini is asked for its strictest filters (SCP079 `safety.threshold`), the SCP079 plugin adds safety rules to every request in code
(players' text is untrusted, no sexual / hateful / dangerous content, no links, commands or pings) and checks every answer again.
For the doors, ScpAccess adds: `scp079.protected-doors` (regexes over door names it can never touch), `scp079.max-actions-per-minute`
(12, all players together), `scp079.lock-max-seconds` (locks it sets lift again after 300 s; locks you set by hand never expire),
plus SCP079's per-player tool cooldown and logging of every tool call. Tool arguments are cleaned and capped at 60 characters.

## Verified / NOT verified

Verified: model parsing, animation maths, door geometry, name matching, pack contents and the Gemini function-calling loop
(unit tests, SCP079 and ScpAccess); boot on Paper 26.3 with SCP079 1.3.0 (0 exceptions); from the console: placing doors /
reader / sensor, barriers appearing and vanishing, SCP-079's tools in both modes (contained refuses, breach opens one random
matching door, lock / unlock, "hallway a" does not open "Hallway B"), swipes granted / denied / locked, auto-close; glslang
validates the merged text shader and the post shader; on a **real 26.3 client** (pack installed by hand, merged on top of
SCP079's): 3x3 door, 1x2 door, reader with its cyan LVL 3 screen, sensor all render facing the right way, and the door open
animation slides the leaves into the posts.

NOT verified by a person / client: placing from items by right-click, the link tool clicks, shift + left-click removal,
the card model flying through the reader and the green / red screen + post-effect flash, sounds, the sensor detecting a real
player, 6x3 door look, a real Gemini conversation calling the tools, walking into barriers. After upgrading from an earlier
config, check `pack.text-shader-sources` (SCP079 is `SCP079/SCP079-pack.zip`) and re-run `/scpaccess pack build` after SCP079 rebuilds its pack.

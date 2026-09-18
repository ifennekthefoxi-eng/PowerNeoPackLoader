# Power Neo Pack Loader

A NeoForge library that registers blocks from **packs** — data folders a player can add, edit and
remove — instead of from hardcoded Java fields.

You write the block class. The library finds it, reads every pack, and registers one real block,
item, block entity and renderer per block definition it finds, plus the blockstate/model/lang JSON
they need and their creative tabs.

* **Minecraft** 1.21.1 · **NeoForge** 21.1.x · Java 21
* GeckoLib is an optional dependency — animated pack blocks work when it's installed, everything
  else works when it isn't.

---

## Quick start

**1. Depend on it** (see [Installing](#installing) for the repository setup):

```gradle
dependencies {
    implementation "com.fennek.powerneopackloader:powerneopackloader:1.0.0"
}
```

```toml
# neoforge.mods.toml
[[dependencies.yourmod]]
modId = "powerneopackloader"
type = "required"
versionRange = "[1.0.0,)"
ordering = "AFTER"
side = "BOTH"
```

**2. Write a block class** and annotate it:

```java
@PackBlock
public class MyEngineBlock extends Block {
    public MyEngineBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }
}
```

**3. Call the library once**, from your mod constructor:

```java
@Mod(MOD_ID)
public class MyMod {
    public static final String MOD_ID = "mymod";

    public MyMod(IEventBus modBus, ModContainer container) {
        PowerPackLoaderApi.load(MOD_ID, modBus);
    }
}
```

That's the whole integration. There is no class list to maintain, no `DeferredRegister`, no
registry event handler, and no per-block boilerplate.

**4. Ship a pack** under `src/main/resources/assets/mymod/default_packs/my_pack/`:

```
my_pack/
├── pack.meta.json                      { "name": "My Pack", "id": "my_pack" }
├── data/my_pack/blocks/v8_engine.json  { "name": "V8 Engine", "class": "MyEngineBlock" }
└── assets/my_pack/
    ├── models/block/v8_engine.json
    └── textures/block/v8_engine.png
```

On first launch it is extracted to `<game dir>/powerpackloader/mymod/packs/my_pack/`, where players
can edit it or drop more packs beside it. The block registers as `mymod:my_pack_v8_engine` and
appears in its own creative tab.

---

## How a pack is laid out

```
<game dir>/powerpackloader/<modId>/<folder>/
└── <any folder name, or a .zip>/
    ├── <meta file>                          pack identity — required
    ├── data/<packId>/blocks/<blockId>.json  one file per block — required
    ├── data/<packId>/collisions/<blockId>.json   optional collision shape
    └── assets/<packId>/
        ├── blockstates/<blockId>.json       optional, overrides auto-generation
        ├── models/block/<blockId>.json
        ├── models/item/<blockId>.json
        ├── textures/block/<blockId>.png
        └── lang/en_us.json                  optional, else a fallback is generated
```

`<folder>` defaults to `packs` and the meta file to `pack.meta.json`.

**`<blockId>` is always the block JSON's filename**, never its `"name"` field. Every asset path
above is keyed off it, so `blocks/v8_engine.json` needs `models/block/v8_engine.json`, whatever
display name it carries.

**The pack's `id` is its asset namespace.** A pack's assets are exposed to Minecraft under its own
id, so two packs can both ship `textures/block/engine.png` without colliding, and neither can
overwrite the other's.

### The meta file

```json
{ "name": "My Pack", "id": "my_pack", "extend_original": false }
```

A folder without one is not a pack and is skipped. Give each loader its own meta filename (e.g.
`engine.meta.json`) if your mod runs several, so a pack can't be picked up by the wrong one.

**`extend_original`** (optional, default false) declares the pack to be part of the mod's own
content rather than an addition beside it. Its blocks then register as `<modId>:<blockId>` — no pack
prefix.

That is how a mod moves a block it used to register in Java into a pack **without changing its
id**, so existing worlds, recipes, tags and loot tables keep working: as far as the game is
concerned it is the same block it always was. Remove the Java registration when you do this —
the library cannot see it (nothing is in the block registry yet while packs are being read), so
leaving both in place is a duplicate-registration crash.

### Name collisions

A pack block never overwrites one that already exists. The name falls through:

1. `<blockId>` — only tried for an `extend_original` pack
2. `<packId>_<blockId>` — the ordinary name
3. `<packId>_<blockId>_1`, `_2`, `_3`, … — counting up until one is free

Every candidate is tested by the same rule, including names that are themselves a previous
fallback, so the chain can't dead-end. Two packs both extending the original with a `v8_engine`
give `v8_engine` to whichever is read first and `otherpack_v8_engine` to the second; a pack whose
own prefixed name happens to be `otherpack_v8_engine` then gets `otherpack_v8_engine_1`. Anything
past step 1 is logged as a warning naming both packs.

"Already taken" means taken by another **pack block**. Blocks a mod registers by other means aren't
visible at that point — see `extend_original` above.

### A block definition

```json
{
  "name": "V8 Engine",
  "class": "MyEngineBlock",
  "geckolib": false,
  "directional": true,

  "properties": {
    "strength": 4.0,
    "explosion_resistance": 6.0,
    "light": 7,
    "sound": "metal",
    "map_color": "color_gray",
    "requires_tool": true,
    "no_occlusion": true,
    "dynamic_shape": true,
    "no_collision": false,
    "friction": 0.6,
    "speed_factor": 1.0,
    "jump_factor": 1.0,
    "push_reaction": "normal",
    "replaceable": false,
    "random_ticks": false,
    "ignited_by_lava": false,
    "no_loot_table": false,
    "force_solid": true
  },

  "power": 320
}
```

Only `class` is required. `directional` forces `facing=` variants on or off in the generated
blockstate; left out, the library looks for vanilla's `HORIZONTAL_FACING` property on the block
class (including inherited), which catches blocks that have it without extending
`HorizontalDirectionalBlock` — most modded machine base classes, Create's included.

Everything in `properties` is optional and falls back to the loader's
defaults (`strength 2.0/3.0`, `light 0`, `no_occlusion` and `dynamic_shape` on). `sound` and
`map_color` accept any vanilla `SoundType` / `MapColor` constant name, case-insensitively; an
unrecognised one warns and keeps the default rather than crashing.

Any other field — `power` above — is yours, read through `PackBlockContext`.

---

## Reading a block's own data

Add a second constructor parameter and the library hands the block its definition:

```java
@PackBlock
public class MyEngineBlock extends Block {
    private final int power;

    public MyEngineBlock(BlockBehaviour.Properties properties, PackBlockContext context) {
        super(properties);
        this.power = context.getInt("power", 100);
    }
}
```

One Java class can therefore back any number of distinct pack blocks. `PackBlockContext` also
carries `packId()`, `blockId()`, `registryName()`, the pack folder, and typed readers
(`getString`, `getFloat`, `getBoolean`, `getObject`, …).

`PackBlockContext.current()` returns the same object anywhere during construction — including from
inside a `super(...)` argument, where a constructor parameter can't be reached yet.

---

## Block entities, items and renderers

Declare the whole family on the block:

```java
@PackBlock(
        entity   = MyEngineBlockEntity.class,
        item     = MyEngineItem.class,
        renderer = MyEngineRenderer.class)
public class MyEngineBlock extends Block implements EntityBlock { ... }
```

…or annotate each class separately, which is what you need when the block class isn't yours:

```java
@PackBlockEntity(MyEngineBlock.class) public class MyEngineBlockEntity extends BlockEntity { ... }
@PackBlockItem(MyEngineBlock.class)   public class MyEngineItem extends BlockItem { ... }
@PackRenderer(MyEngineBlockEntity.class) public class MyEngineRenderer implements BlockEntityRenderer<…> { ... }
```

**Constructors the library will use**, first match wins:

| Class | Signatures tried |
|---|---|
| Block | `(Properties, PackBlockContext)` → `(Properties)` → `()` |
| BlockItem | `(Block, Item.Properties, PackBlockContext)` → `(Block, Item.Properties)` |
| BlockEntity | `(BlockEntityType<?>, BlockPos, BlockState)` → `(BlockPos, BlockState)` |
| Renderer | `(BlockEntityRendererProvider.Context)` |

Non-public constructors are fine.

### Two things that are easy to get wrong

**Every pack block gets its own `BlockEntityType`.** It has to: `BlockEntity`'s constructor
validates the block it is placed on against its type's valid-blocks list, so one shared type across
several pack blocks crashes on the first placement. This is why a block entity class should take
`(BlockEntityType<?>, BlockPos, BlockState)` — a class reusing a vanilla entity whose 2-arg
constructor hardcodes `BlockEntityType.CHEST` would otherwise silently get the wrong type.

**A block entity with no renderer renders as nothing.** Registering a `BlockEntityType` and
registering a renderer for it are separate registries in vanilla, and a freshly-created type never
inherits an existing registration. The block places, ticks, saves and opens perfectly while drawing
literally nothing — which looks like a model problem and isn't. If your block's look comes from a
`BlockEntityRenderer`, it needs `@PackRenderer`. Blocks drawn from a normal baked model need
nothing.

Renderer classes may be client-only: the library reads the annotation from NeoForge's scan data as a
string and only resolves it on the client.

### Optional dependencies

If a block class touches another mod **anywhere — including inside a method body** — declare it:

```java
@PackBlock(requiredMods = "geckolib", entity = MyGeoBlockEntity.class)
public class MyGeoBlock extends Block implements EntityBlock { ... }
```

The class is then skipped entirely when that mod is absent, and a pack json naming it logs the
ordinary "unknown block class" error, so the rest of the pack still loads.

Declaring it matters more than it looks. The library can load such a class fine — resolving a class
doesn't run its method bodies — but the JVM *verifies* a class the first time it is instantiated,
and verification resolves the types its methods mention in order to type-check them. So a block that
merely returns `new MyGeoBlockEntity(...)` from `newBlockEntity` throws `NoClassDefFoundError` from
inside the registry event, which FML reports as a fatal error that takes **the whole game** down
rather than that one block. `requiredMods` is the only point early enough to avoid that.

---

## What the library generates for you

Nothing a pack supplies is ever overwritten; generation only fills gaps. Output goes to
`powerpackloader/<modId>/generated/<folder>_generated/`, wiped and rebuilt on every scan.

* **Blockstate** — a pack's own `blockstates/<blockId>.json` wins outright. Otherwise one is
  generated pointing at `models/block/<blockId>.json`, with `facing=` variants if the block class is
  a `HorizontalDirectionalBlock`. GeckoLib blocks get an elementless model instead, so vanilla's
  missing-model checkerboard doesn't draw on top of the renderer's output.
* **Item model** — from `models/item/<blockId>.json`, else parented to the block model, else
  `builtin/entity` for a GeckoLib `GeoItem`, else a flat icon from
  `textures/block/<blockId>.png`.
* **Lang** — a fallback `itemGroup.<packId>` title, only when the pack ships no `lang/` folder of
  its own.

---

## Getting at the registered blocks

Pack blocks have no `public static final` field to reference — they don't exist until a pack is
read. Look them up instead:

```java
PowerPackLoaderRegistry.get(MOD_ID, "my_pack", "v8_engine")
        .map(PowerPackLoaderRegistry.PackBlockEntry::block)
        .ifPresent(block -> ...);

for (var entry : PowerPackLoaderRegistry.byMod(MOD_ID)) { ... }
```

Entries hand back `DeferredBlock`/`DeferredItem` holders, safe to keep from the moment registration
is queued — call `.get()` when you use them, not when you look them up.

To read a pack's own files (configs, custom data, images), use `ResourceLocator`:

```java
ResourceLocator locator = new ResourceLocator(loader);
locator.getJson("my_pack/data/my_pack/tuning.json").ifPresent(json -> ...);
```

---

## Configuring the loader

`PowerPackLoaderApi.load(modId, modBus)` uses every default. When you need something else:

```java
PowerPackLoaderApi.loader(MOD_ID, modBus)
        .folder("engine_packs")               // default: "packs"
        .meta("engine.meta.json")             // default: "pack.meta.json"
        .defaultPacks("my_pack", "other")     // default: every folder in assets/<modId>/default_packs/
        .creativeTabs(CreativeTabMode.PER_PACK) // default: BOTH (per-pack tabs + one combined)
        .load();
```

Call it once per loader — a mod wanting separate folders for, say, engines and body panels calls it
twice with different `folder(...)` values. Always from the mod constructor: registration is queued
onto the mod event bus.

`CreativeTabMode.NONE` turns tab creation off entirely, for a mod placing pack blocks into its own
tabs via `BuildCreativeModeTabContentsEvent` and `PowerPackLoaderRegistry`.

### Default packs

Folders under `assets/<modId>/default_packs/` are extracted on first run and re-extracted whenever
the mod ships a changed version, backing the player's previous copy up to
`<game dir>/powerpackloader_backup/` first. Treat them as the mod's packs; a player's own packs
belong in their own folders.

---

## Diagnosing

`/pnpl blocks [mod_id]` lists every block the library registered, grouped by pack, with its registry
id and whether it got a block entity. It separates the three failure modes cleanly:

| Symptom | Meaning |
|---|---|
| Pack absent from the list | The meta file is missing or has no `id`, or the folder isn't in the loader's directory |
| Pack listed but a block missing | Its JSON had no `class`, or named a class the library couldn't find — check the log for `Unknown or invalid block class` |
| Block listed but invisible in world | It registered fine. The problem is its model, blockstate, or a missing `@PackRenderer` |

Also: `/pnpl <loader> packs` and `/pnpl <loader> inspect <pack> [subfolder]`.

The log names every discovered class at startup (`Discovered pack block class 'X' -> ...`), so a
class that isn't listed was never found by the annotation scan.

---

## How discovery works

Annotated classes are found through NeoForge's own mod-file annotation scan — the same index behind
`@Mod` and `@EventBusSubscriber`, built with ASM while mod files are located, long before any mod
constructor runs. So discovery costs nothing at runtime, works identically in a dev workspace and a
production jar, and **never loads a class just to find it**.

The scan is scoped to the mod file of the id you pass to `load(...)`, so two mods can both ship a
`@PackBlock` named `EngineBlock` without either seeing the other's.

Class resolution is guarded per class against `Throwable`, not just exceptions: a block class
extending a type from a mod the player didn't install fails with `NoClassDefFoundError`, and must
skip that one class with a warning rather than take startup down. This is what makes optional
dependencies work — the GeckoLib example block in this repo registers on installs without GeckoLib,
just without its entity, item and renderer.

---

## Known limitations

* **Collision shapes are server-side.** `data/<packId>/collisions/` is datapack content, loaded
  through `AddReloadListenerEvent`. On a dedicated server the client has no copy, so custom-shaped
  blocks draw a full-cube selection outline there. Single-player and LAN are unaffected.
* **Packs are read once, at startup.** Adding or editing a pack needs a game restart — blocks are
  registry entries, and registries are frozen after mod loading.
* `LoadableModelHorizontalDirectionalBlock` is the only block class the library ships; it is a
  directional block with pack-driven collision, meant as a starting point rather than a base class
  you must extend. Any `Block` subclass works.

---

## Installing

The library publishes to a local Maven repository:

```bash
cd PowerNeoPackLoader
./gradlew publish          # -> PowerNeoPackLoader/repo/
```

In the consuming mod's `build.gradle`:

```gradle
repositories {
    maven { url = uri("../PowerNeoPackLoader/repo") }
}

dependencies {
    implementation "com.fennek.powerneopackloader:powerneopackloader:1.0.0"
}
```

For a runtime-only drop-in, the built jar from `build/libs/` goes in the `mods` folder like any
other mod.

---

## API reference

| Type | Purpose |
|---|---|
| `PowerPackLoaderApi` | Entry point — `load(modId, bus)` and `loader(modId, bus)` |
| `PackLoaderBuilder` | Per-loader settings |
| `@PackBlock` | Marks a block class as loadable from packs |
| `@PackBlock(requiredMods = ...)` | Skip a class unless those mods are loaded |
| `@PackBlockEntity` / `@PackBlockItem` / `@PackRenderer` | Standalone forms of `@PackBlock`'s members |
| `PackBlockContext` | A block's pack identity and its own JSON |
| `PowerPackLoaderRegistry` | Look up what was registered |
| `ResourceLocator` | Read a pack's files (JSON, text, images, bytes, properties) |
| `CreativeTabMode` | `NONE` / `PER_PACK` / `COMBINED` / `BOTH` |
| `RendererPicker.GLOBAL` | Register a renderer that can't be declared with an annotation |

---

## Building

```bash
./gradlew build       # jar in build/libs/
./gradlew runClient   # dev client, with the example pack
```

Mappings are Mojang's official names, covered by their own license:
<https://github.com/NeoForged/NeoForm/blob/main/Mojang.md>

Docs: <https://docs.neoforged.net/> · Discord: <https://discord.neoforged.net/>

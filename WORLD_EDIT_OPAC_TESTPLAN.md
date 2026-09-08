# Everforge WorldEdit/OpenPAC Guard - Runtime Test Plan

Target stack:

- Minecraft 1.21.1
- NeoForge 21.1.250
- WorldEdit 7.3.8
- Open Parties and Claims 0.30.3
- Everforge Mod 0.3.3

## Already confirmed

`//set` across an OpenPAC claim boundary changes only chunks where the editing
player has OpenPAC build access. Chunks returning no access remain untouched.

## Test rules

Use a disposable test area spanning:

- at least one chunk where the test player has build access
- at least one adjacent chunk where the same player has no build access

Do not use production structures.

## Tests

### T1 - //set

Status: CONFIRMED

Expected:
- allowed chunks changed
- forbidden chunks unchanged

### T2 - //replace

Example:

```text
//replace minecraft:stone minecraft:glass
```

Expected:
- replacements only in allowed chunks
- forbidden chunks unchanged

### T3 - //copy + //paste

Copy any small harmless structure, then paste it across the claim boundary.

Expected:
- blocks are created/changed only in allowed chunks
- forbidden chunks unchanged

### T4 - //undo

After T2 or T3:

```text
//undo
```

Expected:
- undo is also filtered by the player's current OpenPAC access
- no block in a currently forbidden chunk is modified

Important implication:
If access changed between the original edit and undo, current OpenPAC rights
win.

### T5 - biome mutation

Use a small disposable area only.

Expected:
- biome changes only in allowed chunks
- forbidden chunks unchanged

### T6 - entities

Status: OPEN

Entity creation/removal is not yet explicitly guarded by 0.3.3. Test entity
WorldEdit commands separately before treating entity protection as complete.

## Completion criteria

The block/biome guard can be treated as stable after T2-T5 pass.

Entity protection remains a separate feature until explicitly implemented and
tested.


# 0.3.4 entity protection tests

## Why /butcher bypassed 0.3.3

WorldEdit's utility entity commands do not remove entities through
`setBlock()`/`setBiome()`. `/butcher` and `/remove` create an EditSession,
call `getEntities(...)`, then run an `EntityVisitor` over the returned
entities and invoke the removal function.

0.3.4 therefore protects entity operations in two directions:

1. `getEntities()` and `getEntities(Region)` only expose entities located in
   OpenPAC-allowed chunks to WorldEdit.
2. `createEntity(...)` returns `null` when the destination chunk is denied.

## E1 - /butcher removal

Put one hostile mob in an allowed chunk and one in an adjacent denied chunk,
both within the selected radius.

```text
/butcher 5
```

Expected:
- mob in allowed chunk removed
- mob in denied chunk remains

## E2 - /remove removal

If convenient, repeat with a specific entity type and small radius.

Expected:
- only entities in allowed chunks are removed

## E3 - entity paste / creation

Use a clipboard/schematic containing an entity, with entity pasting enabled,
and paste across the claim boundary.

Expected:
- entities are created only in allowed chunks
- denied chunks receive no newly created entities

Block behavior must remain unchanged from the already confirmed 0.3.3 tests.

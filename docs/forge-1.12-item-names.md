# Item names on the forge 1.12.2 line

What the flattening did, and what luna does about it.

The 1.13 flattening turned every coloured and wooden variant into an item of
its own. `lime_concrete` on a modern backend is `concrete` with damage 5 here.
Rather than fork every config per game line - `servers.yml` and a shop's
`items.yml` are *cluster* config, shared by every backend - luna keeps the
modern vocabulary everywhere and translates on this line only, in
`LegacyItemNames`.

**You do not need this table to write config.** Name items the modern way and
they work on every backend. It is here so the translation is auditable, and so
a name that renders as a barrier can be looked up.

Every target below was checked against the 1.12.2 registry extracted from the
decompiled sources - 250 blocks and 207 items - not written from memory.

## Three colour orders, not one

This is the part that bites. Sixteen colours, three different orderings:

| colour | block damage | dye damage | banner damage |
|---|---|---|---|
| white | 0 | 15 | 15 |
| orange | 1 | 14 | 14 |
| magenta | 2 | 13 | 13 |
| light_blue | 3 | 12 | 12 |
| yellow | 4 | 11 | 11 |
| lime | 5 | 10 | 10 |
| pink | 6 | 9 | 9 |
| gray | 7 | 8 | 8 |
| light_gray | 8 | 7 | 7 |
| cyan | 9 | 6 | 6 |
| purple | 10 | 5 | 5 |
| blue | 11 | 4 | 4 |
| brown | 12 | 3 | 3 |
| green | 13 | 2 | 2 |
| red | 14 | 1 | 1 |
| black | 15 | 0 | 0 |

White is block 0 but dye 15. Red is block 14 but dye 1. A single shared
"colour index" would be quietly wrong for two of the three.

## The full mapping

179 names translate; the rest pass through unchanged.

| modern | 1.12.2 |
|---|---|
| `white_concrete` | `concrete:0` |
| `orange_concrete` | `concrete:1` |
| `magenta_concrete` | `concrete:2` |
| `light_blue_concrete` | `concrete:3` |
| `yellow_concrete` | `concrete:4` |
| `lime_concrete` | `concrete:5` |
| `pink_concrete` | `concrete:6` |
| `gray_concrete` | `concrete:7` |
| `light_gray_concrete` | `concrete:8` |
| `cyan_concrete` | `concrete:9` |
| `purple_concrete` | `concrete:10` |
| `blue_concrete` | `concrete:11` |
| `brown_concrete` | `concrete:12` |
| `green_concrete` | `concrete:13` |
| `red_concrete` | `concrete:14` |
| `black_concrete` | `concrete:15` |
| `white_concrete_powder` | `concrete_powder:0` |
| `orange_concrete_powder` | `concrete_powder:1` |
| `magenta_concrete_powder` | `concrete_powder:2` |
| `light_blue_concrete_powder` | `concrete_powder:3` |
| `yellow_concrete_powder` | `concrete_powder:4` |
| `lime_concrete_powder` | `concrete_powder:5` |
| `pink_concrete_powder` | `concrete_powder:6` |
| `gray_concrete_powder` | `concrete_powder:7` |
| `light_gray_concrete_powder` | `concrete_powder:8` |
| `cyan_concrete_powder` | `concrete_powder:9` |
| `purple_concrete_powder` | `concrete_powder:10` |
| `blue_concrete_powder` | `concrete_powder:11` |
| `brown_concrete_powder` | `concrete_powder:12` |
| `green_concrete_powder` | `concrete_powder:13` |
| `red_concrete_powder` | `concrete_powder:14` |
| `black_concrete_powder` | `concrete_powder:15` |
| `white_wool` | `wool:0` |
| `orange_wool` | `wool:1` |
| `magenta_wool` | `wool:2` |
| `light_blue_wool` | `wool:3` |
| `yellow_wool` | `wool:4` |
| `lime_wool` | `wool:5` |
| `pink_wool` | `wool:6` |
| `gray_wool` | `wool:7` |
| `light_gray_wool` | `wool:8` |
| `cyan_wool` | `wool:9` |
| `purple_wool` | `wool:10` |
| `blue_wool` | `wool:11` |
| `brown_wool` | `wool:12` |
| `green_wool` | `wool:13` |
| `red_wool` | `wool:14` |
| `black_wool` | `wool:15` |
| `white_carpet` | `carpet:0` |
| `orange_carpet` | `carpet:1` |
| `magenta_carpet` | `carpet:2` |
| `light_blue_carpet` | `carpet:3` |
| `yellow_carpet` | `carpet:4` |
| `lime_carpet` | `carpet:5` |
| `pink_carpet` | `carpet:6` |
| `gray_carpet` | `carpet:7` |
| `light_gray_carpet` | `carpet:8` |
| `cyan_carpet` | `carpet:9` |
| `purple_carpet` | `carpet:10` |
| `blue_carpet` | `carpet:11` |
| `brown_carpet` | `carpet:12` |
| `green_carpet` | `carpet:13` |
| `red_carpet` | `carpet:14` |
| `black_carpet` | `carpet:15` |
| `white_stained_glass` | `stained_glass:0` |
| `orange_stained_glass` | `stained_glass:1` |
| `magenta_stained_glass` | `stained_glass:2` |
| `light_blue_stained_glass` | `stained_glass:3` |
| `yellow_stained_glass` | `stained_glass:4` |
| `lime_stained_glass` | `stained_glass:5` |
| `pink_stained_glass` | `stained_glass:6` |
| `gray_stained_glass` | `stained_glass:7` |
| `light_gray_stained_glass` | `stained_glass:8` |
| `cyan_stained_glass` | `stained_glass:9` |
| `purple_stained_glass` | `stained_glass:10` |
| `blue_stained_glass` | `stained_glass:11` |
| `brown_stained_glass` | `stained_glass:12` |
| `green_stained_glass` | `stained_glass:13` |
| `red_stained_glass` | `stained_glass:14` |
| `black_stained_glass` | `stained_glass:15` |
| `white_stained_glass_pane` | `stained_glass_pane:0` |
| `orange_stained_glass_pane` | `stained_glass_pane:1` |
| `magenta_stained_glass_pane` | `stained_glass_pane:2` |
| `light_blue_stained_glass_pane` | `stained_glass_pane:3` |
| `yellow_stained_glass_pane` | `stained_glass_pane:4` |
| `lime_stained_glass_pane` | `stained_glass_pane:5` |
| `pink_stained_glass_pane` | `stained_glass_pane:6` |
| `gray_stained_glass_pane` | `stained_glass_pane:7` |
| `light_gray_stained_glass_pane` | `stained_glass_pane:8` |
| `cyan_stained_glass_pane` | `stained_glass_pane:9` |
| `purple_stained_glass_pane` | `stained_glass_pane:10` |
| `blue_stained_glass_pane` | `stained_glass_pane:11` |
| `brown_stained_glass_pane` | `stained_glass_pane:12` |
| `green_stained_glass_pane` | `stained_glass_pane:13` |
| `red_stained_glass_pane` | `stained_glass_pane:14` |
| `black_stained_glass_pane` | `stained_glass_pane:15` |
| `white_terracotta` | `stained_hardened_clay:0` |
| `orange_terracotta` | `stained_hardened_clay:1` |
| `magenta_terracotta` | `stained_hardened_clay:2` |
| `light_blue_terracotta` | `stained_hardened_clay:3` |
| `yellow_terracotta` | `stained_hardened_clay:4` |
| `lime_terracotta` | `stained_hardened_clay:5` |
| `pink_terracotta` | `stained_hardened_clay:6` |
| `gray_terracotta` | `stained_hardened_clay:7` |
| `light_gray_terracotta` | `stained_hardened_clay:8` |
| `cyan_terracotta` | `stained_hardened_clay:9` |
| `purple_terracotta` | `stained_hardened_clay:10` |
| `blue_terracotta` | `stained_hardened_clay:11` |
| `brown_terracotta` | `stained_hardened_clay:12` |
| `green_terracotta` | `stained_hardened_clay:13` |
| `red_terracotta` | `stained_hardened_clay:14` |
| `black_terracotta` | `stained_hardened_clay:15` |
| `white_dye` | `dye:15` |
| `orange_dye` | `dye:14` |
| `magenta_dye` | `dye:13` |
| `light_blue_dye` | `dye:12` |
| `yellow_dye` | `dye:11` |
| `lime_dye` | `dye:10` |
| `pink_dye` | `dye:9` |
| `gray_dye` | `dye:8` |
| `light_gray_dye` | `dye:7` |
| `cyan_dye` | `dye:6` |
| `purple_dye` | `dye:5` |
| `blue_dye` | `dye:4` |
| `brown_dye` | `dye:3` |
| `green_dye` | `dye:2` |
| `red_dye` | `dye:1` |
| `black_dye` | `dye:0` |
| `white_banner` | `banner:15` |
| `orange_banner` | `banner:14` |
| `magenta_banner` | `banner:13` |
| `light_blue_banner` | `banner:12` |
| `yellow_banner` | `banner:11` |
| `lime_banner` | `banner:10` |
| `pink_banner` | `banner:9` |
| `gray_banner` | `banner:8` |
| `light_gray_banner` | `banner:7` |
| `cyan_banner` | `banner:6` |
| `purple_banner` | `banner:5` |
| `blue_banner` | `banner:4` |
| `brown_banner` | `banner:3` |
| `green_banner` | `banner:2` |
| `red_banner` | `banner:1` |
| `black_banner` | `banner:0` |
| `light_gray_shulker_box` | `silver_shulker_box` |
| `white_bed` | `bed:0` |
| `orange_bed` | `bed:1` |
| `magenta_bed` | `bed:2` |
| `light_blue_bed` | `bed:3` |
| `yellow_bed` | `bed:4` |
| `lime_bed` | `bed:5` |
| `pink_bed` | `bed:6` |
| `gray_bed` | `bed:7` |
| `light_gray_bed` | `bed:8` |
| `cyan_bed` | `bed:9` |
| `purple_bed` | `bed:10` |
| `blue_bed` | `bed:11` |
| `brown_bed` | `bed:12` |
| `green_bed` | `bed:13` |
| `red_bed` | `bed:14` |
| `black_bed` | `bed:15` |
| `terracotta` | `hardened_clay` |
| `cobweb` | `web` |
| `magma_block` | `magma` |
| `grass_block` | `grass` |
| `oak_door` | `wooden_door` |
| `player_head` | `skull:3` |
| `skeleton_skull` | `skull:0` |
| `firework_rocket` | `fireworks` |
| `enchanted_golden_apple` | `golden_apple:1` |
| `lapis_lazuli` | `dye:4` |
| `bone_meal` | `dye:15` |
| `ink_sac` | `dye:0` |
| `cocoa_beans` | `dye:3` |
| `melon_slice` | `melon` |
| `oak_planks` | `planks:0` |
| `spruce_planks` | `planks:1` |
| `dark_oak_planks` | `planks:5` |
| `birch_sapling` | `sapling:2` |

## Unchanged

These are already correct on both lines: `arrow`, `barrier`, `black_shulker_box`, `blue_shulker_box`, `book`, `brown_shulker_box`, `chest`, `clock`, `compass`, `cyan_shulker_box`, `emerald`, `gray_shulker_box`, `green_shulker_box`, `iron_block`, `light_blue_shulker_box`, `lime_shulker_box`, `magenta_shulker_box`, `map`, `orange_shulker_box`, `paper`, `pink_shulker_box`, `purple_shulker_box`, `red_shulker_box`, `redstone`, `repeater`, `white_shulker_box`, `yellow_shulker_box`.

## Adding to it

An unmapped name passes through untouched, so a config may always name a
1.12.2 item directly (`dye:1`, `concrete:5`) when it wants to. If a screen shows
a barrier where it should show an item, the name is either not in this table or
does not exist on this version; the fallback is deliberate, so one wrong button
does not stop a menu opening.

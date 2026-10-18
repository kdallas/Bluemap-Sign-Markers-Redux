
## BlueMap Sign Markers Redux

This plugin is a fork of [IMMC BlueMap Sign Markers](https://github.com/IMMC-dev/Bluemap-Sign-Markers) which forked [Easy BlueMap Sign Markers](https://github.com/deimiczny/easy-bmapmarkers), which itself is a fork of [BlueMapSignMarkers](https://modrinth.com/plugin/bluemapsignmarkers) 

### Changes in this fork

- **Renamed to `SignMarkersRedux`**, including the in-game chat prefix. Upstream called the
  plugin `IMMCBluemapSignMarkers`.
- **Builds for two Paper targets from one branch** - Paper 1.21.x on Java 21 and Paper 26.x
  on Java 25, with no second JDK needed for either. See [Building](#building).
- **Sign API modernised** - `SignChangeEvent#getLine`/`#setLine` are deprecated in favour of
  the Adventure `Component`-based `line(int)` accessors, which the plugin now uses.
- **Build brought up to Gradle 9** - bumped the foojay toolchain resolver, regenerated the
  wrapper, and removed an execution-time `project` reference that becomes an error in Gradle 10.

### Inherited from upstream

- **Custom icons** - any icon file present in `/bluemap/web/markers/` is used as-is, instead
  of the plugin requiring one of its built-in names. This is the feature the fork chain added,
  and the reason this fork exists.
- **Paper and Folia support.** No other platform (e.g. Fabric) is planned.

The usage instructions and icon list below come from the upstream readme and still apply.


## Building

Requires a JDK 25 toolchain (Gradle downloads one via the foojay resolver if absent).
No JDK 21 install is needed - the 1.21 build compiles with `--release 21` on JDK 25.

- `./gradlew build` - builds the 1.21.x target (paper-api 1.21.11, BlueMap API 2.7.7)
- `./gradlew build -PmcTarget=26` - builds the 26.x target (paper-api 26.3, BlueMap API 2.8.1)

Artifacts land in `build/libs` as `SignMarkersRedux-<version>.<build>-mc1.21.jar` and
`SignMarkersRedux-<version>.<build>-mc26.3.jar`, where `<build>` is the git commit count,
suffixed with `-dirty` when the tree has uncommitted changes. The build number appears in
the jar name only - `plugin.yml` keeps reporting the plain `<version>`.

They coexist, so run the two builds without `clean` between them if you want both.
`clean` removes both.

Everything that differs between the two targets lives in one table at the top of
`build.gradle.kts`; adding a third target means adding one entry there.


## How to use
Place any sign in the game. Fill the sign as follows:

- **1st line**: `[marker_icon_name]`
- **2nd line:** text
- **3rd line:** text
- **4th line:** text

### Icon resolution

Icons live in `/bluemap/web/markers/`. They may sit directly in that folder or in one level of
sub-folder (`web/markers/set05/`). PNG, SVG and WebP all work. The bundled sets use the same
lookup as your own icons: the plugin copies them into `web/markers/set1/` and `set2/` on start,
and an existing file is never overwritten.

Lookup order:

1. **Format** - `.svg`, then `.webp`, then `.png`. Format outranks folder, so a `portal.svg` in
   `set2/` beats a `portal.png` in the root.
2. **Folder** - the `markers/` root first, then its sub-folders in case-insensitive natural
   order (`set1`, `set2`, ..., `set10`), stopping at the first file hit.

So within one folder `[portal]` uses `portal.svg` if present, then `portal.webp`, then
`portal.png`; two files of the same format are settled by folder.

- `[portal.svg]` uses that exact file. An explicit name always wins over the format order, so
  `[portal.png]` picks the PNG even when an SVG of the same name is present.
- `[set2/tree]` searches only `set2/`, skipping the folder order; the usual format order still
  applies within it. The prefix must be a direct sub-folder of `markers/`; if that folder or file
  is missing, the fallback icon is used rather than trying elsewhere.
- `[portal]` still matches a file whose own name contains a dot (`my.icon.png` for `[my.icon]`)
  after the extension probes, as it did before SVG support.
- If nothing matches, the bundled `ban_white` icon is used.
- The token is case-insensitive, but file and folder names must be lower-case on case-sensitive
  filesystems.

Format is compared before folder, so an icon in `web/markers/` only wins over a bundled file of
the *same* format: a root `portal.png` does not shadow a bundled `portal.webp`. To replace a
bundled icon outright, put the same format in the root, or use an SVG or WebP there.

An SVG should carry explicit `width` and `height` attributes; those are used exactly as written.
A `viewBox` is only consulted when neither is available, and is scaled down so its longest edge is
at most 50px - viewBox numbers are usually far larger than an icon renders, so they are not used
as pixel sizes directly. PNG and WebP sizes are read from their headers, so their anchors are the
icon's centre.

### Adding your own icon set

Create a folder inside `/bluemap/web/markers/` and put icons in it - no registration, restart or
configuration needed:

```text
web/markers/custom1/portal.webp
web/markers/custom1/harbour.svg
```

`[portal]` and `[harbour]` then resolve from `custom1/`. To bypass the folder order entirely,
prefix the name with the folder: `[custom1/harbour]`. A few consequences of the lookup order
above:

- Only direct sub-folders of `markers/` are searched; `custom1/deep/icon.png` is not found.
- A folder's name is its position in the tie-break. `custom1` sorts before `set1`, so it wins
  same-format ties against the bundled sets. A name that sorts after them (e.g. `zcustom`) only
  wins ties where no bundled folder holds that icon.
- To override one bundled icon rather than a whole set, either put the file directly in
  `web/markers/` in the same format, or use a higher-priority format anywhere (`portal.svg` beats
  `portal.webp`, which beats `portal.png`).
- The plugin only copies its own `set1`/`set2` into `web/markers/` and never overwrites an
  existing file, so your folders are safe. Delete a bundled `setN/` folder to have it recreated
  from the jar on the next start.

Icons are resolved when a sign is placed, so a newly added file is used by the next sign without
any reload. For an existing marker, `/signmarkers regenerate <id or label>` re-reads its sign and
resolves the icon again.

## Example

The example below creates a marker on your map with the `star` icon assigned.

First, you build the sign

![sign1](example1.png)

After you complete it, the first line will switch to `> map marker <`. This indicates that placing
the marker was successful.

![sign2](example2.png)

After a while, you will see the marker on your BlueMap

![sign3](example3.png)

And when you click it, the popup containing your sign description will appear

![sign4](example4.png)

## Commands

`/signmarkers` manages a world's marker set. Signs create and remove markers on their own; these
are for everything else - most usefully a marker imported from a BlueMap config, which has no sign
in the world and therefore no block to break.

| Command | |
| --- | --- |
| `/signmarkers list` | List every marker in your world, flagging which have no sign behind them |
| `/signmarkers remove <id or label>` | Delete one marker |
| `/signmarkers place-sign <id or label>` | Place a sign at the marker's position, making it sign-backed |
| `/signmarkers reapply [id or label]` | Rebuild marker popups with the current template - all of them, or just one |
| `/signmarkers regenerate [id or label]` | As `reapply`, but also re-reads the sign at each marker's position |

`remove` and `place-sign` accept either the marker id or its label. Labels are matched
case-insensitively and may contain spaces; if two markers share a label the command refuses and
lists the matching ids rather than guessing which one you meant.

`place-sign` refuses to place over a non-air block, so it cannot overwrite a build. It then re-keys
the marker to the id that a block break looks for - which is what makes breaking the new sign
remove the marker rather than leaving it behind.

`reapply` and `regenerate` differ in where they get their data. `reapply` re-renders what the
marker already stores. `regenerate` first looks for a sign at the marker's position and, if there
is one, takes the icon, label and text from it - which is the only way to pick up a sign that was
edited after the marker was created, since that edit is otherwise ignored once line 0 holds the
plugin's placeholder. Where no sign exists both behave the same.

Requires the `signmarkers.admin` permission, granted to operators by default.

## Popup template

`plugins/SignMarkersRedux/template.html` controls the HTML shown when a marker is clicked. It is
written on first start and never overwritten, so edits survive upgrades. The file is re-read each
time a marker is created, so changes take effect on the next sign without a restart.

Values are substituted into `{{ name }}` placeholders and are **always HTML-escaped** - the
template is trusted markup, the sign text is not.

| Placeholder | Value |
| --- | --- |
| `{{ label }}` | the joined label, as the marker itself shows it |
| `{{ label1 }}` `{{ label2 }}` `{{ label3 }}` | the individual sign lines |
| `{{ x }}` `{{ y }}` `{{ z }}` | coordinates |
| `{{ position }}` | coordinates pre-joined as `x y z` |
| `{{ timestamp }}` | when the marker was created |
| `{{ author }}` | who created it |
| `{{ icon }}` | icon path, ready for use in `<img src="...">` |
| `{{ world }}` | world name |

Unknown placeholders are left as written, so a typo shows up in the popup rather than silently
vanishing.

The rendered HTML is stored on the marker at creation, so editing the template only affects
markers made afterwards. Run `/signmarkers reapply` to rebuild the existing ones in your world -
all of them, or one by id or label.

Author and date are always set to whoever runs the command and the current time, so a reapplied
marker records who last rendered it rather than its original creator. Only the individual sign
lines are restored from the marker; imported markers, which never had them, fall back to the
joined label.

The default template styles itself with BlueMap's theme variables (`--theme-fg-light`,
`--theme-bg-hover`, `--theme-bg-light`), so the popup follows the webapp's light or dark theme. Note
that a `<style>` block in the template applies document-wide while the popup is open, and is
repeated in every marker's stored detail - fine for a few dozen markers, worth trimming for
hundreds.

## Markers included by default

These are the names that are available automatically for the markers, plus the corresponding
icons. Just use any of the values between brackets - `[` and `]`, e.g. `[portal]` - to place the
marker on the **BlueMap**. It does not matter if you use lowercase or uppercase; the plugin will
handle it.

### Marker names

#### Set 1 - `set1/`

| Name              | Icon                                                                     |     | Name              | Icon                                                                     |     | Name              | Icon                                                                     |
| ----------------- | ------------------------------------------------------------------------ | --- | ----------------- | ------------------------------------------------------------------------ | --- | ----------------- | ------------------------------------------------------------------------ |
| `ancient_city`    | ![ancient_city](src/main/resources/markers/set1/ancient_city.webp)       |     | `ban_purple`      | ![ban_purple](src/main/resources/markers/set1/ban_purple.webp)           |     | `target_point`    | ![target_point](src/main/resources/markers/set1/target_point.webp)       |
| `ban_black`       | ![ban_black](src/main/resources/markers/set1/ban_black.webp)             |     | `ban_red`         | ![ban_red](src/main/resources/markers/set1/ban_red.webp)                 |     | `target_x`        | ![target_x](src/main/resources/markers/set1/target_x.webp)               |
| `ban_blue`        | ![ban_blue](src/main/resources/markers/set1/ban_blue.webp)               |     | `ban_white`       | ![ban_white](src/main/resources/markers/set1/ban_white.webp)             |     | `treasure_x`      | ![treasure_x](src/main/resources/markers/set1/treasure_x.webp)           |
| `ban_brown`       | ![ban_brown](src/main/resources/markers/set1/ban_brown.webp)             |     | `ban_yellow`      | ![ban_yellow](src/main/resources/markers/set1/ban_yellow.webp)           |     | `tree`            | ![tree](src/main/resources/markers/set1/tree.webp)                       |
| `ban_cyan`        | ![ban_cyan](src/main/resources/markers/set1/ban_cyan.webp)               |     | `camp`            | ![camp](src/main/resources/markers/set1/camp.webp)                       |     | `trial`           | ![trial](src/main/resources/markers/set1/trial.webp)                     |
| `ban_gray`        | ![ban_gray](src/main/resources/markers/set1/ban_gray.webp)               |     | `desert_temple`   | ![desert_temple](src/main/resources/markers/set1/desert_temple.webp)     |     | `village_desert`  | ![village_desert](src/main/resources/markers/set1/village_desert.webp)   |
| `ban_green`       | ![ban_green](src/main/resources/markers/set1/ban_green.webp)             |     | `mansion`         | ![mansion](src/main/resources/markers/set1/mansion.webp)                 |     | `village_plains`  | ![village_plains](src/main/resources/markers/set1/village_plains.webp)   |
| `ban_light_blue`  | ![ban_light_blue](src/main/resources/markers/set1/ban_light_blue.webp)   |     | `mineshaft`       | ![mineshaft](src/main/resources/markers/set1/mineshaft.webp)             |     | `village_savanna` | ![village_savanna](src/main/resources/markers/set1/village_savanna.webp) |
| `ban_light_gray`  | ![ban_light_gray](src/main/resources/markers/set1/ban_light_gray.webp)   |     | `monument`        | ![monument](src/main/resources/markers/set1/monument.webp)               |     | `village_snow`    | ![village_snow](src/main/resources/markers/set1/village_snow.webp)       |
| `ban_lime`        | ![ban_lime](src/main/resources/markers/set1/ban_lime.webp)               |     | `ocean_ruins`     | ![ocean_ruins](src/main/resources/markers/set1/ocean_ruins.webp)         |     | `village_taiga`   | ![village_taiga](src/main/resources/markers/set1/village_taiga.webp)     |
| `ban_magenta`     | ![ban_magenta](src/main/resources/markers/set1/ban_magenta.webp)         |     | `pickaxe`         | ![pickaxe](src/main/resources/markers/set1/pickaxe.webp)                 |     | `witch_hut`       | ![witch_hut](src/main/resources/markers/set1/witch_hut.webp)             |
| `ban_orange`      | ![ban_orange](src/main/resources/markers/set1/ban_orange.webp)           |     | `portal`          | ![portal](src/main/resources/markers/set1/portal.webp)                   |     |                   |                                                                          |
| `ban_pink`        | ![ban_pink](src/main/resources/markers/set1/ban_pink.webp)               |     | `spawner`         | ![spawner](src/main/resources/markers/set1/spawner.webp)                 |     |                   |                                                                          |

#### Set 2 - `set2/`

| Name           | Icon                                                               |     | Name           | Icon                                                               |     | Name           | Icon                                                               |
| -------------- | ------------------------------------------------------------------ | --- | -------------- | ------------------------------------------------------------------ | --- | -------------- | ------------------------------------------------------------------ |
| `anchor`       | ![anchor](src/main/resources/markers/set2/anchor.webp)             |     | `down`         | ![down](src/main/resources/markers/set2/down.webp)                 |     | `pointup`      | ![pointup](src/main/resources/markers/set2/pointup.webp)           |
| `bank`         | ![bank](src/main/resources/markers/set2/bank.webp)                 |     | `drink`        | ![drink](src/main/resources/markers/set2/drink.webp)               |     | `portal`       | ![portal](src/main/resources/markers/set2/portal.webp)             |
| `basket`       | ![basket](src/main/resources/markers/set2/basket.webp)             |     | `exclamation`  | ![exclamation](src/main/resources/markers/set2/exclamation.webp)   |     | `purpleflag`   | ![purpleflag](src/main/resources/markers/set2/purpleflag.webp)     |
| `bed`          | ![bed](src/main/resources/markers/set2/bed.webp)                   |     | `factory`      | ![factory](src/main/resources/markers/set2/factory.webp)           |     | `queen`        | ![queen](src/main/resources/markers/set2/queen.webp)               |
| `beer`         | ![beer](src/main/resources/markers/set2/beer.webp)                 |     | `fire`         | ![fire](src/main/resources/markers/set2/fire.webp)                 |     | `redflag`      | ![redflag](src/main/resources/markers/set2/redflag.webp)           |
| `bighouse`     | ![bighouse](src/main/resources/markers/set2/bighouse.webp)         |     | `flower`       | ![flower](src/main/resources/markers/set2/flower.webp)             |     | `right`        | ![right](src/main/resources/markers/set2/right.webp)               |
| `blueflag`     | ![blueflag](src/main/resources/markers/set2/blueflag.webp)         |     | `gear`         | ![gear](src/main/resources/markers/set2/gear.webp)                 |     | `ruby`         | ![ruby](src/main/resources/markers/set2/ruby.webp)                 |
| `bomb`         | ![bomb](src/main/resources/markers/set2/bomb.webp)                 |     | `goldmedal`    | ![goldmedal](src/main/resources/markers/set2/goldmedal.webp)       |     | `scales`       | ![scales](src/main/resources/markers/set2/scales.webp)             |
| `bookshelf`    | ![bookshelf](src/main/resources/markers/set2/bookshelf.webp)       |     | `goldstar`     | ![goldstar](src/main/resources/markers/set2/goldstar.webp)         |     | `shield`       | ![shield](src/main/resources/markers/set2/shield.webp)             |
| `bricks`       | ![bricks](src/main/resources/markers/set2/bricks.webp)             |     | `greenflag`    | ![greenflag](src/main/resources/markers/set2/greenflag.webp)       |     | `sign`         | ![sign](src/main/resources/markers/set2/sign.webp)                 |
| `bronzemedal`  | ![bronzemedal](src/main/resources/markers/set2/bronzemedal.webp)   |     | `hammer`       | ![hammer](src/main/resources/markers/set2/hammer.webp)             |     | `silvermedal`  | ![silvermedal](src/main/resources/markers/set2/silvermedal.webp)   |
| `bronzestar`   | ![bronzestar](src/main/resources/markers/set2/bronzestar.webp)     |     | `heart`        | ![heart](src/main/resources/markers/set2/heart.webp)               |     | `silverstar`   | ![silverstar](src/main/resources/markers/set2/silverstar.webp)     |
| `building`     | ![building](src/main/resources/markers/set2/building.webp)         |     | `house`        | ![house](src/main/resources/markers/set2/house.webp)               |     | `skull`        | ![skull](src/main/resources/markers/set2/skull.webp)               |
| `cake`         | ![cake](src/main/resources/markers/set2/cake.webp)                 |     | `key`          | ![key](src/main/resources/markers/set2/key.webp)                   |     | `star`         | ![star](src/main/resources/markers/set2/star.webp)                 |
| `camera`       | ![camera](src/main/resources/markers/set2/camera.webp)             |     | `king`         | ![king](src/main/resources/markers/set2/king.webp)                 |     | `sun`          | ![sun](src/main/resources/markers/set2/sun.webp)                   |
| `cart`         | ![cart](src/main/resources/markers/set2/cart.webp)                 |     | `left`         | ![left](src/main/resources/markers/set2/left.webp)                 |     | `temple`       | ![temple](src/main/resources/markers/set2/temple.webp)             |
| `caution`      | ![caution](src/main/resources/markers/set2/caution.webp)           |     | `lightbulb`    | ![lightbulb](src/main/resources/markers/set2/lightbulb.webp)       |     | `theater`      | ![theater](src/main/resources/markers/set2/theater.webp)           |
| `chest`        | ![chest](src/main/resources/markers/set2/chest.webp)               |     | `lighthouse`   | ![lighthouse](src/main/resources/markers/set2/lighthouse.webp)     |     | `tornado`      | ![tornado](src/main/resources/markers/set2/tornado.webp)           |
| `church`       | ![church](src/main/resources/markers/set2/church.webp)             |     | `lock`         | ![lock](src/main/resources/markers/set2/lock.webp)                 |     | `tower`        | ![tower](src/main/resources/markers/set2/tower.webp)               |
| `coins`        | ![coins](src/main/resources/markers/set2/coins.webp)               |     | `map`          | ![map](src/main/resources/markers/set2/map.webp)                   |     | `tree`         | ![tree](src/main/resources/markers/set2/tree.webp)                 |
| `comment`      | ![comment](src/main/resources/markers/set2/comment.webp)           |     | `minecart`     | ![minecart](src/main/resources/markers/set2/minecart.webp)         |     | `truck`        | ![truck](src/main/resources/markers/set2/truck.webp)               |
| `compass`      | ![compass](src/main/resources/markers/set2/compass.webp)           |     | `offlineuser`  | ![offlineuser](src/main/resources/markers/set2/offlineuser.webp)   |     | `up`           | ![up](src/main/resources/markers/set2/up.webp)                     |
| `construction` | ![construction](src/main/resources/markers/set2/construction.webp) |     | `orangeflag`   | ![orangeflag](src/main/resources/markers/set2/orangeflag.webp)     |     | `walk`         | ![walk](src/main/resources/markers/set2/walk.webp)                 |
| `cross`        | ![cross](src/main/resources/markers/set2/cross.webp)               |     | `papaj`        | ![papaj](src/main/resources/markers/set2/papaj.webp)               |     | `warning`      | ![warning](src/main/resources/markers/set2/warning.webp)           |
| `cup`          | ![cup](src/main/resources/markers/set2/cup.webp)                   |     | `pinkflag`     | ![pinkflag](src/main/resources/markers/set2/pinkflag.webp)         |     | `world`        | ![world](src/main/resources/markers/set2/world.webp)               |
| `cutlery`      | ![cutlery](src/main/resources/markers/set2/cutlery.webp)           |     | `pirateflag`   | ![pirateflag](src/main/resources/markers/set2/pirateflag.webp)     |     | `wrench`       | ![wrench](src/main/resources/markers/set2/wrench.webp)             |
| `diamond`      | ![diamond](src/main/resources/markers/set2/diamond.webp)           |     | `pointdown`    | ![pointdown](src/main/resources/markers/set2/pointdown.webp)       |     | `yellowflag`   | ![yellowflag](src/main/resources/markers/set2/yellowflag.webp)     |
| `dog`          | ![dog](src/main/resources/markers/set2/dog.webp)                   |     | `pointleft`    | ![pointleft](src/main/resources/markers/set2/pointleft.webp)       |     |                |                                                                    |
| `door`         | ![door](src/main/resources/markers/set2/door.webp)                 |     | `pointright`   | ![pointright](src/main/resources/markers/set2/pointright.webp)     |     |                |                                                                    |

`ban_white` is the fallback when a sign names an icon that cannot be resolved.

The set 1 icons come from the vanilla Minecraft map icons and the
[Map Icons Resource Pack](https://modrinth.com/resourcepack/map-icons), used under its MIT licence.
<br/>
The set 2 icons are modified versions of the original [Dynmap](https://github.com/webbukkit/dynmap)
assets, used under the Apache 2.0 licence.

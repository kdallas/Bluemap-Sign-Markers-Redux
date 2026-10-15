
## BlueMap Sign Markers Redux

This plugin is a fork of [IMMC BlueMap Sign Markers](https://github.com/IMMC-dev/Bluemap-Sign-Markers) which forked [Easy BlueMap Sign Markers](https://github.com/deimiczny/easy-bmapmarkers), which itself is a fork of [BlueMapSignMarkers](https://modrinth.com/plugin/bluemapsignmarkers) 

**IMMC-dev's readme below, until I have something to add.**

This fork re-adds support for custom icons uploaded to the `/bluemap/web/markers/` directory, and removes the strict requirement to match the exact icons the plugin provides.

Compatible with Paper. No other software compatibility (e.g. Fabric) is planned or will be accomodated since this is a bespoke fork for the Immortal MC server.


## Building

Requires a JDK 25 toolchain (Gradle downloads one via the foojay resolver if absent).
No JDK 21 install is needed - the 1.21 build compiles with `--release 21` on JDK 25.

- `./gradlew build` - builds the 1.21.x target (paper-api 1.21.11, BlueMap API 2.7.7)
- `./gradlew build -PmcTarget=26` - builds the 26.x target (paper-api 26.3, BlueMap API 2.8.1)

Artifacts land in `build/libs` as `SignMarkersRedux-<version>-mc1.21.jar` and
`SignMarkersRedux-<version>-mc26.3.jar`. They coexist, so run the two builds without
`clean` between them if you want both. `clean` removes both.

Everything that differs between the two targets lives in one table at the top of
`build.gradle.kts`; adding a third target means adding one entry there.


## How to use
Place any sign in the game. Fill the sign as follows:

- **1st line**: `[marker_icon_name]`
- **2nd line:** text
- **3rd line:** text
- **4th line:** text

## Markers Included by Default
These are the names that are available automatically for the markers, plus the corresponding icons. Just use any of the values between brackets - `[` and `]`, e.g. `[portal]` - to place the marker on the **BlueMap**.
It does not matter if you use lowercase or uppercase. The plugin will handle it. If you

| Name             | Icon                                                                    |     | Name              | Icon                                                                     |
| ---------------- | ----------------------------------------------------------------------- | --- | ----------------- | ------------------------------------------------------------------------ |
| `ban_black`      | ![ban_black](src/main/resources/markers/icons/ban_black.png)            |     | `mansion`         | ![mansion](src/main/resources/markers/icons/mansion.png)                 |
| `ban_blue`       | ![ban_blue](src/main/resources/markers/icons/ban_blue.png)              |     | `mineshaft`       | ![mineshaft](src/main/resources/markers/icons/mineshaft.png)             |
| `ban_brown`      | ![ban_brown](src/main/resources/markers/icons/ban_brown.png)            |     | `monument`        | ![monument](src/main/resources/markers/icons/monument.png)               |
| `ban_cyan`       | ![ban_cyan](src/main/resources/markers/icons/ban_cyan.png)              |     | `ocean_ruins`     | ![ocean_ruins](src/main/resources/markers/icons/ocean_ruins.png)         |
| `ban_gray`       | ![ban_gray](src/main/resources/markers/icons/ban_gray.png)              |     | `pickaxe`         | ![pickaxe](src/main/resources/markers/icons/pickaxe.png)                 |
| `ban_green`      | ![ban_green](src/main/resources/markers/icons/ban_green.png)            |     | `portal`          | ![portal](src/main/resources/markers/icons/portal.png)                   |
| `ban_light_blue` | ![ban_light_blue](src/main/resources/markers/icons/ban_light_blue.png)  |     | `spawner`         | ![spawner](src/main/resources/markers/icons/spawner.png)                 |
| `ban_light_gray` | ![ban_light_gray](src/main/resources/markers/icons/ban_light_gray.png)  |     | `target_point`    | ![target_point](src/main/resources/markers/icons/target_point.png)       |
| `ban_lime`       | ![ban_lime](src/main/resources/markers/icons/ban_lime.png)              |     | `target_x`        | ![target_x](src/main/resources/markers/icons/target_x.png)               |
| `ban_magenta`    | ![ban_magenta](src/main/resources/markers/icons/ban_magenta.png)        |     | `treasure_x`      | ![treasure_x](src/main/resources/markers/icons/treasure_x.png)           |
| `ban_orange`     | ![ban_orange](src/main/resources/markers/icons/ban_orange.png)          |     | `tree`            | ![tree](src/main/resources/markers/icons/tree.png)                       |
| `ban_pink`       | ![ban_pink](src/main/resources/markers/icons/ban_pink.png)              |     | `trial`           | ![trial](src/main/resources/markers/icons/trial.png)                     |
| `ban_purple`     | ![ban_purple](src/main/resources/markers/icons/ban_purple.png)          |     | `village_desert`  | ![village_desert](src/main/resources/markers/icons/village_desert.png)   |
| `ban_red`        | ![ban_red](src/main/resources/markers/icons/ban_red.png)                |     | `village_plains`  | ![village_plains](src/main/resources/markers/icons/village_plains.png)   |
| `ban_white`      | ![ban_white](src/main/resources/markers/icons/ban_white.png)            |     | `village_savanna` | ![village_savanna](src/main/resources/markers/icons/village_savanna.png) |
| `ban_yellow`     | ![ban_yellow](src/main/resources/markers/icons/ban_yellow.png)          |     | `village_snow`    | ![village_snow](src/main/resources/markers/icons/village_snow.png)       |
| `camp`           | ![camp](src/main/resources/markers/icons/camp.png)                      |     | `village_taiga`   | ![village_taiga](src/main/resources/markers/icons/village_taiga.png)     |
| `desert_temple`  | ![desert_temple](src/main/resources/markers/icons/desert_temple.png)    |     | `witch_hut`       | ![witch_hut](src/main/resources/markers/icons/witch_hut.png)             | 


Where these images are from the vanilla Minecraft map icons, or the [Map Icons Resource Pack](https://modrinth.com/resourcepack/map-icons).

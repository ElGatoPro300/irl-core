# IRL Core

The shared engine behind IRLights and IRL-editor: light SSBOs and registry, shadow orchestration, GPU caches and the `.irlights` shaderpack patcher. Core has no BBS/CML dependency.

One source tree builds eight Minecraft targets: **1.20.1, 1.20.4, 1.21.1, 1.21.4, 1.21.11, 26.1, 26.2 and 26.3**. Version-specific native calls live in `src/platform`; shared algorithms live in `src/main/java`.

## Build

Use JDK 25 to run the complete matrix:

```powershell
./gradlew.bat buildAll
```

All eight installable JARs appear in `build/libs/`. Intermediate outputs stay in `build/targets/<mc>/`. A normal `build` targets 1.21.1; select another target explicitly:

```powershell
./gradlew.bat build '-Pmc=1.21.4'
./gradlew.bat publishToMavenLocal '-Pmc=1.21.4'
```

Java bytecode follows the selected Minecraft target: Java 17 for 1.20.x, Java 21 for 1.21.x, Java 25 for 26.x. Every JAR is restricted to its exact Minecraft version and has a unique version suffix, for example `1.1.9-1.21.4`.

## Consumption

IRLights can consume the matching release JAR through its local `lib/<mc>` dependency directory. Update its selected Core version when adopting a new release. Maven consumers use `org.qualet:irl-core:1.1.9-<mc>` and bundle the matching artifact through Loom `include`. Yarn targets publish intermediary artifacts; 26.x targets publish official names.

Environment bindings stay in the host through `PatcherHost`, `ShadowConfig` and `ShadowCasterSource`. The shader patcher validates before writing, supports zipped packs, dry-run and rollback. The registry retains its std430 buffer layout, binding 7 and maximum 2048 lights per frame.

See [multiversion architecture and validation](docs/multiversion.md) for adapter boundaries, commands and the remaining world-render acceptance checks.

## Related projects

- [IRLights addon](https://github.com/quaIett/bbs-irlights-addon)
- [IRL-editor](https://github.com/quaIett/irl-editor)

Released under the [MIT License](LICENSE). Copyright 2026 qualet.

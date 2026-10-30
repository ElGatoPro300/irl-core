# Shared IRL Core

## Scope and design

One canonical light registry, SSBO pipeline, shader patcher, shadow scheduler and block-cache policy serve eight Minecraft targets. The public org.qualet.irl API remains the consumer boundary; this migration does not introduce CML or editor dependencies into Core.

Engine boundaries live in src/platform: native camera/dispatcher setup, block shape/material/model access, buffer and matrix operations, cached OpenGL state calls and Iris sampler registration. The legacy shadow backend serves 1.20.1 through 1.21.4; the raw OpenGL backend serves 1.21.11 and 26.x. Both retain their existing caster contracts. The modern batch accepts real model triangles; legacy consumers use their immediate buffers. Shader algorithms and atlas layouts are preserved.

The source bridge rewrites Minecraft symbols from the pinned 1.21.11 Yarn mapping to official names using Mercury/Lorenz. Generated sources are build outputs. Native 26.x model material and UV access stays explicit. No runtime reflection or source copies per release are required.

## Build

Run Gradle with JDK 25 for the complete matrix:

```powershell
./gradlew.bat buildAll
./gradlew.bat build '-Pmc=1.21.4'
./gradlew.bat publishToMavenLocal '-Pmc=1.21.4'
```

The default build targets 1.21.1. Eight installable artifacts are collected in build/libs; target-specific intermediate outputs stay in build/targets/<mc>. Maven versions include the Minecraft suffix, preventing one target from overwriting another. Java bytecode is 17, 21 or 25 according to gradle/minecraft-targets.json. Metadata restricts loading to the exact Minecraft target.

## Consumer migration and risks

IRLights and IRL-editor continue to consume a separate Core dependency. New artifact versions (1.1.9-<mc>) must be selected explicitly by each consumer. Existing consumer repositories and local libraries are not overwritten by this migration. Consumers with older Loom versions may need to update their build tooling before accepting a Core artifact produced by Loom 1.17.21; IRL-editor has not been migrated or built here. Older branch artifacts remain available; branches/worktrees are not deleted. Visual verification must cover opaque/cutout blocks, entity silhouettes, moving lights, cache invalidation and shader reload with Iris on/off. Successful compilation alone does not establish rendering parity.

## Verification

The complete build matrix passes. Artifact verification checks exact Minecraft metadata, Java bytecode, mapping namespace and packaged classes, and rejects bundled Minecraft/Iris/smoke dependencies. The public/protected binary API comparison preserves all 434 old contracts on legacy targets and 443 on modern targets.

Existing checks pass: block cache, overlay membership, caster selection, VRAM budget, pipeline/sampler layout, GPU uploads and profile registry. The pipeline verifier accepts explicit patch roots; its defaults inspect the fourteen patches shipped by the two sibling repositories present in this workspace.

Isolated copies of IRLights build against Core 1.1.9 for 1.20.1, 1.21.1, 1.21.11 and 26.3, and their nested Core bytes match the selected release artifact. The original IRLights checkout and its library folders remain untouched.

The native fixture launches real Minecraft clients, waits for resource loading to finish, checks native camera and STB image-resize calls, draws an opaque block into a spot depth tile twice (including the cached-VBO path), reads back valid depth pixels, and checks framebuffer/viewport restoration. These are menu/GPU checks, not full in-world visual acceptance tests. Smoke classes and Fabric/Iris/Sodium test dependencies are opt-in and are excluded from release artifacts.

```powershell
python tools/verify-targets.py
python tools/verify-api.py --reference-root ../BBS-CML-IRLIGHTS/lib
python tools/test-clients.py
python tools/test-clients.py --mc 1.20.1 --with-iris
```

Configure matching Java runtime paths through the ignored gradle/local-runtime.properties, with entries such as runJavaHome.1.20.1=<Java 17 home>, runJavaHome.1.21.1=<Java 21 home> and runJavaHome.26.3=<Java 25 home>, or pass -PrunJavaHome for one selected target.

Known development-environment limitation: the pinned Iris 1.7.x/Sodium pair on 1.20.1 and 1.20.4 fails before the Core fixture runs. Iris's MixinSodiumBufferBuilder selector remains method_1344()V while the development Sodium class uses named methods. Production dependency JARs are not patched to hide this failure. The default fixture matrix therefore runs the two 1.20.x targets without Iris/Sodium; --with-iris reproduces the legacy development launch. Six newer targets use Iris/Sodium with shaderpacks disabled. The complete eight-target native fixture matrix passes, including clean shutdown after resource loading.

In-world cutout textures, real entity silhouettes, shaders enabled, shader reload and host integration still require visual acceptance testing. Build/startup/GPU checks alone do not establish those behaviors.

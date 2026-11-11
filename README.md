# NoopsNet Core

Shared client/server foundation with persistent homes and spawn commands.

Official NoopsNet source: https://noops.world/

## Build

Use JDK 21. The Gradle wrapper downloads pinned Minecraft/loader development dependencies.

```sh
./gradlew -p . build --console=plain
```

Commands: `/spawn`, `/sethome [name]`, `/home [name]`, `/delhome [name]`. One home per UUID; independent 10-second travel cooldowns. Homes live in `<world>/data/noopsnet_core/homes.json`. Run `./gradlew verifyGameTests` for native command tests.

## Licensing and provenance

Original code is MPL-2.0; see [LICENSE](LICENSE). Artwork and third-party components are covered separately in [NOTICE.md](NOTICE.md). [Published artifact provenance](PROVENANCE.json) identifies the original pack binaries covered by our permission grant. Historical binaries may retain an older All Rights Reserved metadata label; the explicit grant in NOTICE.md also applies to those identified versions.

This repository starts with a reviewed source snapshot. It intentionally contains no private server history, player data, accounts or deployment configuration. The source snapshot differs from the original binary build in license metadata and standalone build wiring; its build is not claimed byte-identical.

# Repository Guidelines

Graphene is a modern, client-side, Chromium-based UI library for Minecraft 1.21.11 that runs on the Fabric mod loader.
Its goal is to provide a simple yet powerful API for mod developers to create rich, web-based user interfaces in Minecraft using JCEF.

## Overview

The repository is structured as follows:

```text
/
├── references/                         # Unpacked dependency sources for browsing and reference.
│   ├── fabric/
│   ├── minecraft/
│   └── <lib-name>/
├── src/
│   ├── client/                         # Client core API and resources (main library code).
│   │   ├── java/
│   │   │   └── tytoo/grapheneui/
│   │   │       ├── api/                # Public, supported API surface for consumers.
│   │   │       └── internal/           # Internal implementation details.
│   │   └── resources/
│   ├── debug/                          #  Debug mod used for manual testing, such as opening a UI.
│   └── test/                           # Unit tests using JUnit 6.
├── .gitignore
├── build.gradle.kts
├── gradle.properties
└── settings.gradle.kts
```

## General Coding Conventions

- Target Java 21, use 4-space indentation, and keep packages under `tytoo.grapheneui*`.
- Prefer explicit types over `var`, and use descriptive names instead of one-letter identifiers.
- Keep member order consistent in Java classes: static constants, static fields, instance fields, constructors, overridden
  methods, public methods, protected and private helper methods, then getters and setters at the bottom.
- Import types instead of using fully qualified names inside method bodies.
- When adding shared utilities, express behavior through clear method names and arguments rather than abstract hierarchies.
- Avoid comments unless documentation is explicitly requested.
- Keep edits minimal and consistent with the surrounding style; avoid unrelated refactors or formatting-only changes.
- Assume contributors use IntelliJ IDEA, and keep code free of IDE warnings.
- If requirements are unclear or infeasible, ask for clarification before proceeding.

## Java 21 Expectations

- Assume Java 21 at runtime; use only stable features and avoid preview or incubator APIs.
- Use modern Java 21 standard-library utilities, such as Streams, Optional, and records, when they improve clarity.
- Use descriptive names such as `ignored` for intentionally unused variables, parameters, and caught exceptions.
- When intentionally ignoring a caught exception, keep a short explanatory comment in the catch block.
- Maintain explicit, readable control flow; avoid clever constructs that harm comprehension.

## Minecraft Integration Rules

- The codebase targets Fabric for Minecraft 1.21.11 with official Mojang mappings.
- Use modern Fabric and Minecraft methods, such as `Identifier.fromNamespaceAndPath(String string, String string2)`, and up-to-date rendering APIs.
- Route Minecraft client singleton access through `tytoo.grapheneui.internal.mc.*` helpers instead of calling `Minecraft.getInstance()` directly.
- Place new assets, mixin configs, and JSON metadata within `src/client/resources/` or `src/debug/resources/`, keeping identifiers
  in the `GrapheneCore.ID` or `GrapheneDebugClient.ID` namespace as appropriate.

## Dependencies & External Sources

- Fabric Loader and Fabric API are versioned in `gradle.properties`; Fabric Loom integrates official Mojang mappings
  into the client source set and remaps game classes during packaging.Keep these aligned with Minecraft `1.21.11` before updating APIs.
- `me.tytoo:jcefgithub` is this project's own JCEF library, published on GitHub Packages; browse its unpacked sources in `references/`.
- Library sources are fetched through the `sourceDeps` configuration in `build.gradle.kts` and unpacked per library using `./gradlew unpackSources`
  into `references/<library>`. Use these sources to explore library APIs.

## Testing & Verification

- Run `./gradlew check` to catch linting errors and warnings.
- Do not run long-running Gradle tasks, such as game launches, yourself. Provide the exact command for the user instead, for example, `./gradlew runDebugClient`.
- Document manual validation steps and remaining risks before completing work.

## PRs and Commits

- For PRs, use the `.github/pull_request_template.md` template.
- For commits, follow the conventional commit format.

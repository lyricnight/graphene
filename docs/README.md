# Graphene Documentation

Graphene is a client-side Chromium UI library for Fabric mods on Minecraft `1.21.11`. These docs move from setup to
advanced integration.

**Baseline**

| Requirement   | Version                         |
|---------------|---------------------------------|
| Java          | `21`                            |
| Minecraft     | `1.21.11`                       |
| Fabric Loader | `0.18.4`                        |
| Fabric API    | `0.141.3+1.21.11`               |
| GPU           | NVIDIA GeForce GT 720 or better |

**Reading order**

| Step | Page                                    | Covers                                        |
|------|-----------------------------------------|-----------------------------------------------|
| 1    | [Overview](overview.md)                 | Concepts, runtime model, typical flow         |
| 2    | [Installation](installation.md)         | Maven coordinates, Gradle setup, registration |
| 3    | [Quickstart](quickstart.md)             | First web screen                              |
| 4    | [Bridge](bridge.md)                     | Java <-> JavaScript messaging                 |
| 5    | [Input Capture](input-capture.md)       | Cursor and Escape capture for web UIs         |
| 6    | [Assets and URLs](assets-and-urls.md)   | App, classpath, and HTTP asset routes         |
| 7    | [Lifecycle](lifecycle.md)               | Runtime, widget, screen, and bridge cleanup   |
| 8    | [Debugging](debugging.md)               | DevTools, debug screens, logging              |
| 9    | [Advanced Surface](advanced-surface.md) | Direct `BrowserSurface` control               |
| 10   | [Troubleshooting](troubleshooting.md)   | Common failures and fixes                     |
| 11   | [Testing](testing.md)                   | Unit and in-game validation                   |

**Migration notes**

- [Shared Runtime API](shared-runtime-api.md) - shared runtime registration and merge behavior.

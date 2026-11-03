# true-sim UI

The UI toolkit of true-sim: HUDs, menus, settings screens, in-world and VR panels, editor tools. Engine-agnostic: the
modules depend only on the JDK and true-sim's own repositories (build-logic, common-*, scene); `ui-text-native` adds
LWJGL's FreeType, HarfBuzz and msdfgen bindings. The engine depends on this repository and implements its interfaces
(`UiRenderer`, `UiCommandSource`, `PointerSource`, `Clipboard`, `TextInputHost`) in its `engine-ui` module.

The design, its decisions and what is built: [docs/ui.md](docs/ui.md).

| Module | Contents |
|---|---|
| `ui-core` | Elements as facades over packed arrays (`UiStore`, `Element`), the draw list (`DrawList`, `Primitive`), UI textures, abstract `UiCommand`s and pointers, the semantics tree, tunables, the interfaces an engine implements. |
| `ui-style` | Typed style keys and sheets, selectors, the resolution cache, live tokens, the Sass-like `.tss` language, the default theme. |
| `ui-layout` | Measure and arrange: stack, flex, grid, border, anchors, overlay; pixel snapping. |
| `ui-text` | Font, shaping and rasterizer interfaces, itemisation, BiDi, line breaking, text layout, the glyph atlas; a JDK-only fallback. |
| `ui-text-native` | FreeType, HarfBuzz and msdfgen (MTSDF) through LWJGL; the build-time atlas tool. |
| `ui-controls` | Panels, labels and images, their decorations, and the frame that styles, lays out and records them. |
| `ui-testkit` | Synthetic input, draw-list goldens. |

## Building

```
./gradlew build
```

Sibling checkouts of `build-logic`, `common-*` and `scene` are used as included builds when present (override with
`-Ptruesim.<repo>=<path>`); otherwise the published packages are used.

# UI toolkit: design and build status

A UI toolkit for true-sim: HUDs, menus, settings screens, in-world and VR panels, editor tools, charts, 3D gizmos, a
developer console and generated inspectors. It keeps what makes jMonkeyEngine's Lemur pleasant (versioned data, value
renderers, component stacks, styles by element id and style name, one event model for GUI and 3D) and fixes what makes
it slow or dated (scene-graph node per element, per-event allocation, throttled picking, no virtualization beyond the
visible window, Groovy styles, bitmap fonts, no state selectors).

Group A1 (stages 0 to 3) is built, with the parts left listed in "Build status" at the end; later groups are not. Decisions are numbered UI1 to UI31 (section 9); sections 4 to 7 are written for the recommended
options and say where an option changes them. Research for this draft: Lemur's source (cited per file), the public
documentation of the toolkits in section 3, and a reading of the true-sim repositories: engine `CLAUDE.md`,
`docs/application-api.md` (§1 to §7), `docs/frame-graph-v2.md` (§4.8), `docs/pitfalls.md` (headings), engine
`graph/Anchor.java`, `render/DisplayTransform.java`, `platform/PlatformListener.java`, `xr/*`, `README.md` (materials),
`common-state` (`ChangeClock`, `Versioned*`, `Watch`, `Derived`, `ChangeLog`, `ChangeCursor`), scene `core`
(`SceneGraph`, `SpatialIndex`, `SceneLayer`, `CameraRelative`), scene `docs/audio.md` (the design-doc pattern this
follows).

Project rules applied throughout: data and engine-agnostic logic outside the engine (scene, and now the `ui` repository), native code and management (GPU, plugins, sync) in the
engine; anything a player or developer may tune is a setting; competing techniques are benchmarked and both offered
only when they trade off; nothing may add latency; nothing allocates per frame in steady state; one branch and PR per
repository per stage group, CI and release once at the end of the group.

---

## 1. Lemur in depth

Lemur is a jME3 UI library by Paul Speed (Simsilica). Core: `src/main/java/com/simsilica/lemur/**`. The list,
selector, value-renderer and versioned-collection code lives in the extension `extensions/LemurProto`, the property
panel in `extensions/LemurProps`.
Repository: https://github.com/jMonkeyEngine-Contributions/Lemur (default branch `master`, last push 2025-09).

### 1.1 Versioning: cheap change detection, polled

- `core/VersionedObject<T>`: `getVersion()`, `getObject()`, `createReference()`.
- `core/VersionedHolder<T>`: a value with a `long version` that grows on `setObject`. `updateObject` changes the value
  only when it is not equal, and `incrementVersion()` marks changes made in place.
  https://github.com/jMonkeyEngine-Contributions/Lemur/blob/master/src/main/java/com/simsilica/lemur/core/VersionedHolder.java
- `core/VersionedReference<T>`: the consumer's side. It remembers `lastVersion`. `update()` compares the two versions,
  stores the new one and returns whether it changed; `needsUpdate()` only compares. There are no listeners, no events
  and no allocation, and it is not thread-safe. `update()` reads the version twice, so a change between the two reads
  is absorbed without being reported.
  https://github.com/jMonkeyEngine-Contributions/Lemur/blob/master/src/main/java/com/simsilica/lemur/core/VersionedReference.java
- `LemurProto/core/VersionedList<T>`: an `AbstractList` with one version for the whole list (`set`, `add`, `remove`
  bump it). `wrap(List)` misses changes made to the wrapped list directly. There are no element versions, so any
  change makes every consumer redo everything.
  https://github.com/jMonkeyEngine-Contributions/Lemur/blob/master/extensions/LemurProto/src/main/java/com/simsilica/lemur/core/VersionedList.java
- Also `VersionedSet` and `VersionedReferenceList`.

**Where it is used.** These classes are versioned and polled once per frame in `updateLogicalState`:
- `RangedValueModel` (sliders, the scroll position of `ListBox`);
- `CheckboxModel`;
- `SelectionModel`;
- `DocumentModel` (text fields);
- `ListBox` (model, selection, index: the frame is mostly three `update()` calls);
- `Selector` (model, selection, and the selected item as a `VersionedHolder`);
- `PropertyPanel` (one reference per editor; it is itself a `VersionedObject`).

The pattern decouples data from UI with no observer bookkeeping. The data can be changed from game code at any rate,
and the UI catches up once per frame.

**true-sim already has a stronger version of this:** `common-state` (`ChangeClock`, `Versioned`, `Watch`,
`VersionedValue/Int/Double/Long/Boolean`, `VersionedList/Map/Set`, the arrays, `Derived`, `ChangeCursor`, `ChangeLog`).
- One clock per world stamps every change, so one number (`ChangeCursor.mark()`) covers any number of objects.
- `VersionedList` has versions per element and a structure version, and visits only the changed indices through a
  ring `ChangeLog`.
- `Derived` is pull-based computed state.
- `Watch.update()` allocates nothing.
- Owner-thread checks catch changes from the wrong thread.

This is exactly the data layer the UI needs. The toolkit should build on it, not copy Lemur's classes (UI30).

### 1.2 Value renderers: any type in any control

- `LemurProto/ValueRenderer<T>`:
  - `configureStyle(ElementId, String style)`;
  - `Panel getView(T value, boolean selected, Panel existing)`, which returns a new view or reconfigures `existing`.
  - The source contrasts it with `ValueEditor<T>`: a renderer is reused across many values, an editor serves one
    value.
  https://github.com/jMonkeyEngine-Contributions/Lemur/blob/master/extensions/LemurProto/src/main/java/com/simsilica/lemur/ValueRenderer.java
- `value/DefaultValueRenderer<T>`:
  - turns a value into text with `Function<T,String>` (the default is `String.valueOf`) and puts it in a `Label`;
  - reuses `existing` by casting it to `Label`, an unchecked cast that fails for any other panel type;
  - ignores `selected`;
  - lets the renderer's own element id and style win over the control's (`configureStyle` fills only what is unset).
  https://github.com/jMonkeyEngine-Contributions/Lemur/blob/master/extensions/LemurProto/src/main/java/com/simsilica/lemur/value/DefaultValueRenderer.java
- `ListBox<T>`:
  - a `GridPanel` of one column, a `Slider` and a separate "selector" panel that draws the highlight over the selected
    cell;
  - the renderer is always called with `selected=false`, because selection is an overlay, not a cell state;
  - cells come from `GridModelDelegate.getCell(row, col, existing)`, and only the visible window has cells;
  - `refreshSelector` allocates (`clone()`, `add`).
  https://github.com/jMonkeyEngine-Contributions/Lemur/blob/master/extensions/LemurProto/src/main/java/com/simsilica/lemur/ListBox.java
- `Selector<T>` (the drop-down):
  - one `ValueRenderer` draws both the closed view (`resetView` → `getView(selected, false, view)`) and the cells of
    the pop-up `ListBox`;
  - opens through `PopupState`;
  - an `AutoCloseListener` counts frames to notice that the selector was removed.
  https://github.com/jMonkeyEngine-Contributions/Lemur/blob/master/extensions/LemurProto/src/main/java/com/simsilica/lemur/Selector.java
- Weaknesses:
  - no registry by type, so every control is wired by hand;
  - one view type per renderer, with no "kind" for mixed cells;
  - `selected` is the only cell state (no focused, hovered, index, column);
  - recycling depends on the grid.

### 1.3 Component model

- `Panel` is a jME `Node`. Its `GuiControl` holds a `ComponentStack` with the named layers `background`, `border` and
  `insets`. Subclasses add more, e.g. `Label` adds `text`, `icon` and `shadow`.
  https://github.com/jMonkeyEngine-Contributions/Lemur/blob/master/src/main/java/com/simsilica/lemur/Panel.java
- `ComponentStack` keeps the named layers in `layerOrder` plus unnamed top components. Changing it invalidates the
  parent.
  https://github.com/jMonkeyEngine-Contributions/Lemur/blob/master/src/main/java/com/simsilica/lemur/core/ComponentStack.java
- `GuiControl` sizing:
  - the preferred size is the layout's size, then each component from top to bottom adds its share (insets, border,
    text);
  - `setSize` hands each component `reshape(offset, size)` from the outside in, then gives the rest to the layout;
  - invalidation goes up to the root, and the root revalidates in `controlUpdate`.
  https://github.com/jMonkeyEngine-Contributions/Lemur/blob/master/src/main/java/com/simsilica/lemur/core/GuiControl.java
- Components: `QuadBackgroundComponent`, `TbtQuadBackgroundComponent` (9-slice, "three by three"),
  `InsetsComponent`/`DynamicInsetsComponent`, `IconComponent`, `TextComponent` (BitmapFont), `TextEntryComponent`,
  `ColoredComponent` (alpha).
- What is good: a control is "background + border + insets + content", and each layer can be swapped by a style
  (`background = new QuadBackgroundComponent(...)`). Decoration is composition, not subclassing.
- What is costly:
  - every component is one or more jME `Geometry` objects with its own `Material`, so a button is several draws unless
    jME's batching is used;
  - layout values are `Vector3f` objects, cloned freely;
  - the scene-graph update and culling walks every UI node every frame.

### 1.4 Styles

- `style/Styles` keeps one `StyleTree` per style name. `getAttributes(elementId, style)` merges, in order:
  - the tree's match for the element id;
  - the style's `default` element;
  - the `root` style.

  The result is cached per `style:elementId` in `attributeMap`, and any `getSelector` call clears the whole cache.
- `applyStyles(object, elementId, style)` reflects over `@StyleAttribute` setters (indexed once per class), clones
  `Cloneable` values (components, lists, maps) and invokes the setters. `@StyleDefaults` static methods seed the
  defaults per class.
  https://github.com/jMonkeyEngine-Contributions/Lemur/blob/master/src/main/java/com/simsilica/lemur/style/Styles.java
- Element ids are dotted paths (`slider.thumb.button`) that match by tail. Selectors come in three forms: `(style)`,
  `(id, style)` and `(parent, child, style)`, the last being "contains". The most specific rule wins attribute by
  attribute (Lemur wiki, Styling: https://github.com/jMonkeyEngine-Contributions/Lemur/wiki/Styling).
- Authoring:
  - Groovy scripts (`selector('button', 'glass') { color = color(0.5, 0.75, 0.75, 0.85) }`), loaded by
    `BaseStyles.loadStyleResources`;
  - or the Java API: `styles.getSelector("glass").set("fontSize", 14)`, with string keys and untyped values.
- Missing:
  - state selectors (hover, pressed, focused, disabled), which Lemur expresses with commands and effects instead;
  - transitions;
  - type checking;
  - hot reload without rebuilding the controls.

  Styles are applied once at construction, so changing a style later does not restyle existing elements.

### 1.5 Layouts

- `SpringGridLayout`: a grid on a main and a minor axis with `FillMode` per axis (None, Even, Proportional, First,
  Last, ForcedEven), the work-horse.
- `BorderLayout`: north, south, east, west and center.
- `BoxLayout`: one axis, with a fill mode.

All three measure with `getPreferredSize` and then distribute with `reshape`. There is no wrapping, no flex-basis, no
min/max sizes beyond preferred, no anchors and no virtualization in the layout itself (`GridPanel` does it for lists).

### 1.6 Input, picking and focus

- **One event model for GUI and 3D.** `PickEventSession` keeps collision roots per viewport and layer (GUI before
  scene by default):
  - a GUI root (bucket `Gui`) gets a ray straight down -Z at the cursor's x and y; a 3D root gets
    `cam.getWorldCoordinates(cursor, 0..1)`;
  - hits come from `root.collideWith(ray, results)`, sorted by distance;
  - for each hit it walks up the parents to the first node with a `CursorEventControl` or `MouseEventControl`
    (bubbling to the nearest listener);
  - delivery runs near to far until a listener consumes the event;
  - a press captures the target, so a drag keeps going outside it;
  - hover enter and exit come from comparing hit targets.
  https://github.com/jMonkeyEngine-Contributions/Lemur/blob/master/src/main/java/com/simsilica/lemur/event/PickEventSession.java

  Any spatial, a 3D model or a GUI panel, becomes interactive by adding the control. The user values this (UI4 and
  UI5).
- Weaknesses of the picking:
  - every move allocates a `Vector2f`, a `CollisionResults`, events and rays;
  - collision is jME's per-triangle `collideWith` over whole subtrees, which is expensive in big scenes;
  - one session per pointer, with no pointer ids;
  - `BasePickState.update` throttles dispatch to about 60 Hz (`sampleFrequency`), which adds latency and is forbidden
    here.
  https://github.com/jMonkeyEngine-Contributions/Lemur/blob/master/src/main/java/com/simsilica/lemur/event/BasePickState.java
- **Focus.** `FocusManagerState` holds the single focus. `FocusNavigationState` maps `InputMapper` functions
  (`F_NEXT`, `F_PREV`, `F_X_AXIS`, `F_Y_AXIS`, `F_ACTIVATE`; group `UI_NAV`) to `TraversalDirection`, asks the nearest
  `FocusTraversal` container (`getRelativeFocus`), and bubbles to the parent container when that one has no answer.
  It has no repeat logic of its own.
  https://github.com/jMonkeyEngine-Contributions/Lemur/blob/master/src/main/java/com/simsilica/lemur/focus/FocusNavigationState.java
- **Input mapping.** Lemur's `input/InputMapper` (`FunctionId`, groups, analog and state listeners) is a small action
  system, the same idea as true-sim's D57 to D87.

### 1.7 Commands, effects and animation

- `Command<S>` and `CommandMap` per control: `Button` raises Click, Down, Up, HoverStart and HoverEnd; `ListBox` raises
  its `ListAction`s.
- `effect/EffectControl`:
  - named effects per spatial;
  - each effect has a **channel**, and a new effect on a busy channel replaces the running one;
  - **fast-forward** starts the new tween at the old one's remaining percentage, so open interrupted by close reverses
    smoothly.

  Effects are named by convention (`EFFECT_OPEN`/`CLOSE`, `activate`, `press`, ...) and set from styles.
  https://github.com/jMonkeyEngine-Contributions/Lemur/blob/master/src/main/java/com/simsilica/lemur/effect/EffectControl.java
- `anim/Tween`, `Tweens` (sequence, parallel, delay, smoothStep, sine), `SpatialTweens`, `PanelTweens` (alpha),
  `AnimationState`.

### 1.8 Property panel

`LemurProps/props/PropertyPanel` binds bean properties (through the `Introspector`) or public fields by reflection:
- a boolean gets a `Checkbox`;
- a number gets a `Label`, a value `Label` and a `Slider` with min, max and step;
- an enum gets a slider over its ordinals.

UI → bean: each editor's model is watched with a `VersionedReference` and written back every frame. Bean → UI is not
detected: the caller must call `refresh()`. Reflection runs per access.
https://github.com/jMonkeyEngine-Contributions/Lemur/blob/master/extensions/LemurProps/src/main/java/com/simsilica/lemur/props/PropertyPanel.java

### 1.9 Text

jME `BitmapFont` (AngelCode .fnt bitmaps) at a fixed size. Scaling blurs, and there is no shaping, no BiDi, no
fallback and no SDF.

### 1.10 Keep and avoid

| Keep | Avoid |
|---|---|
| Polled versioning (`update()` once per frame), here through `common-state` with element versions | One version per list; `wrap` that misses changes |
| `ValueRenderer.getView(value, state, existing)` with reuse; one renderer for a combo's closed view and its cells | An unchecked cast on reuse; selection as an overlay only; no registry by type |
| Component stacks (background, border, insets, content) swappable by style | A scene-graph node and material per component; `Vector3f` cloning |
| Element ids as dotted paths, style names, tail matching, "contains" selectors, attribute-wise precedence | Groovy; string-keyed untyped attributes; styles applied once at construction; a cleared-all cache |
| One pick and event model for GUI and 3D; capture on press; bubbling to the nearest listener | Per-event allocation; triangle `collideWith` over subtrees; one session per pointer; 60 Hz throttling |
| Effects with channels and fast-forward reversal | Effects with string names only, unchecked |
| Focus traversal delegated to containers, bubbling outward | One global focus (local multiplayer needs one per player) |

---

## 2. What modern toolkits do better (for a game or engine UI)

| Toolkit | Lesson for true-sim |
|---|---|
| **Godot 4** Control and Theme (https://docs.godotengine.org/en/stable/classes/class_control.html, `class_theme.html`) | Anchors and offsets for HUDs that scale with the screen. Size flags (fill, expand, ratio). `Theme` items typed by data type (color, constant, font, font_size, icon, stylebox) per control type, with **type variations** (a style name like Lemur's). `StyleBoxFlat` draws rounded corners, borders, shadows and anti-aliasing in one box; `StyleBoxTexture` is 9-slice. Focus neighbours can be set explicitly per direction. |
| **Unity UI Toolkit** (https://docs.unity3d.com/Manual/UIElements.html) | Retained tree (`VisualElement`). USS (a CSS subset) with classes, pseudo-classes (`:hover`, `:focus`, `:checked`) and transitions; UXML templates. Yoga flexbox layout. Runtime data binding (`dataSource`, `binding-path`, converters, an update trigger by version: `IDataSourceViewHashProvider`, which is Lemur's versioning again). `ListView`/`MultiColumnListView`/`TreeView` virtualize with `makeItem` and `bindItem` (Lemur's `getView(existing)`) and fixed or dynamic item height. UI Builder and a debugger. |
| **NoesisGUI** (XAML, https://www.noesisengine.com/docs/) | Binding with `INotifyPropertyChanged`, converters, validation; `DataTemplate` chosen by data type (a registry of value renderers); `VirtualizingStackPanel` with container recycling; storyboards; vector rendering on the GPU with path tessellation and a small batch count. The proof that full XAML binding and templating can run in a game at frame rate. |
| **RmlUi** (https://mikke89.github.io/RmlUiDoc/) | HTML and CSS for games, with a data-binding model (data models with dirty variables), decorators (images, gradients, nine-patch, shaders) and per-element transforms. Lesson: designers like CSS; a CSS-like file is a good authoring format; decorators are style-selected component stacks. |
| **Dear ImGui** (https://github.com/ocornut/imgui) | Immediate mode for tools: no retained state in user code, ids by hashed labels, `ListClipper` virtualization, tables, docking and multi-viewport, keyboard and gamepad navigation, multi-select with box select (1.91). A tiny draw list (`ImDrawList`: vertices, indices, commands per texture and clip). Lesson: what a tool needs (section 5.9) and how cheap a draw list can be. Weakness for games: no styling, layout or animation model, and every frame is rebuilt. |
| **Slint / Flutter** (https://slint.dev, https://flutter.dev) | Retained and reactive: properties with dependency tracking (Slint), widgets rebuilt as cheap descriptions and diffed onto a retained render tree (Flutter). Both paint with a GPU renderer that batches by atlas. Both have semantics trees for accessibility (AccessKit in Slint; Flutter's `Semantics`). Lesson: dependency-tracked derived properties (`Derived`), and a separate semantics tree. |
| **Text** | MSDF and MTSDF (Chlumsky, https://github.com/Chlumsky/msdfgen, https://github.com/Chlumsky/msdf-atlas-gen): sharp corners at any scale, and MTSDF's alpha channel holds a true SDF for outlines, glows and soft shadows. Small sizes (under about 12 px) look softer than hinted bitmaps, which is why many engines keep a raster path for small text. HarfBuzz (https://harfbuzz.github.io) for shaping (ligatures, Arabic joining, Indic reordering, kerning by GPOS). BiDi (UAX #9; the JDK has `java.text.Bidi`). Line breaking (UAX #14; `java.text.BreakIterator`). Font fallback chains. LWJGL ships bindings for FreeType, HarfBuzz and msdfgen (https://www.lwjgl.org/customize; not yet in true-sim's build). |
| **SDF UI shapes** | One shader for a rounded box with four radii, border and fill. Soft shadows from the closed-form blurred rounded rect (Evan Wallace, https://madebyevan.com/shaders/fast-rounded-rectangle-shadows/). Inigo Quilez's 2D SDFs for other shapes (https://iquilezles.org/articles/distfunctions2d/). |
| **Batching** | Vertex pulling from one storage buffer of instances: one draw per layer, with bindless textures so atlases never split batches. Clipping in the shader against a clip-rect index instead of scissor changes. This is the approach of Zed's GPUI (https://zed.dev/blog/videogame) and many engine UIs. |
| **Virtualization** | Only visible rows exist, plus a margin. Recycling by kind. Heights fixed (O(1) offset) or measured (a prefix-sum tree of known and estimated heights). |
| **Retained vs immediate** | Retained wins for games (styling, animation, data binding, accessibility, low CPU when idle). Immediate wins for quick tools. Several engines offer an immediate façade over a retained core (Unity's IMGUI next to UI Toolkit; egui keeps state between frames). |
| **Localisation and accessibility** | Text as translation keys with arguments (ICU MessageFormat-like); a locale change re-lays out everything. Pseudo-localisation for testing. Accessibility trees (AccessKit, https://github.com/AccessKit/accesskit, with a C API) mapping roles, names, values and actions to the OS screen readers. Game-specific settings: text scale, contrast, colour-blind palettes, hold-to-press alternatives. |

---

## 3. What true-sim already has to build on

- **engine-app**: `Application`, `AppConfig`, `Plugin` with `PluginKey`, startup `Stage`s, `Engine`, phases (`FIXED`,
  `UPDATE`, `ANIMATE`, `LATE`), clocks per world (application-api.md §1 and §6). The UI is a plugin (`Ui.plugin()`),
  and its clock is the real-time clock: the UI never pauses, like the audio UI bus (`Audio.ui()`, D60, D131).
- **Input**: only platform events exist.
  - `PlatformListener` has key events with characters, `text(window, String)`, `textEditing(window, text, start,
    length)` for IME composition, mouse (moved with deltas, button with click count, wheel), gamepad, joystick, touch
    (`TouchPhase`), pen, sensors.
  - XR input comes through `Headset.input()`, with `XrPose`, `XrButton`, `XrAxis`, `XrTouch` and `Hand`.
  - The action system (D55 to D87) is designed, not built: no InputManager, no actions, no contexts. The UI needs a
    subset of it (stage 0).
- **Frame graph**:
  - `Anchor.UI` (rank 500) is "what is drawn over the final image: UI, debug overlays", after `TONE_MAP` (400).
  - frame-graph-v2 §4.8 plans a `Presenter` that owns "the UI pass", the keys `PRE_UI` (for frame generation) and
    `UI`, and `new Presenter(window).compose(main).ui(hud)`.
- **HDR**: `DisplayTransform` is the presenter's `OutputTransform`. It does the tone curve and the encoding.
  `display.paperWhite` (200 nits default, 40 to 1000) is "how bright SDR white (scene color 1.0) shows on an HDR
  display". Its javadoc names the UI.

  *Observation, not verified by running:* `DisplayTransform` applies the tone curve and the encoding in one pass. If
  the UI were drawn at `Anchor.UI` into the view's linear colour before that pass, it would go through the ACES curve
  and lose its exact colours. The UI should be drawn into its own `UI` image and composited inside the encode pass
  after the curve (section 6.6).
- **Settings**: `Setting` (bool, integer, decimal, text, choice), layers, constraints, `SettingGroup` trees with
  translation-key labels (D9, D11), tiers, `Availability`. UI tunables become settings, and the settings screen and the
  console are generated from this registry.
- **Data and versioning**: `common-state` (section 1.1). The scene graph stamps changes on a `ChangeClock`
  (`SceneGraph(ChangeClock)`), and `SpatialIndex` follows its change log with a `ChangeCursor`.
- **Picking**: `SpatialIndex.raycast(origin, dir, maxDistance, layerMask, hit)` exists. It covers static trees per
  group, a dynamic refitted BVH and 32 `SceneLayer` masks, and allocates nothing when querying. `CameraRelative`
  exists for large-world rendering (D37: positions are doubles).
- **Materials**: `[MaterialParameters]` Slang structs generate Java records with `DEFAULTS`, `[Range]` checks,
  withers and per-field constants (README "One source of truth, in Slang"; `[Range(0, 16)]`, `[Color]` in
  `render/src/test/slang/test/*.slang`). This is a ready-made metadata source for generated editors.
- **Pipelines**: `vulkan/Pipeline.java` now has blend factors (the 2026-10-31 review's "no blending" is outdated in
  that respect).
- **Logging**: SLF4J (`org.slf4j.Logger` in engine modules). The console's log view needs a sink in whatever binding
  the application uses.
- **Assets**: path assets (D16), hot reload of folder mounts (D25, built late), `drainCompleted` at frame start.
  Fonts, style sheets and 9-slice images become asset types.
- **Pattern to copy**: scene `docs/audio.md`. Game-facing state lives in a scene store with a change log (`Sounds`,
  `Effects`), the engine plugin syncs it to native and GPU work, and bindings live in engine-app (AU11, AU16).

---

## 4. Architecture

### 4.1 Repository and modules (UI25, UI2)

**Rules applied here:**
- The user decided that the toolkit gets **its own repository**.
- The project's rule is "data and engine-agnostic logic vs native and management".
- The user prefers **no external libraries**, but LWJGL's bindings count as allowed. The UI repository's modules depend
  only on the JDK and true-sim's own repositories (build-logic, common-*, scene). The one exception is the optional
  `ui-text-native` module, which uses LWJGL's FreeType, HarfBuzz, msdfgen and stb at the engine's LWJGL version, 3.4.3
  (section 6.3, UI31).

**Name** (UI25): **`ui`**, as GitHub `true-sim/ui`, with modules `ui-*` and packages `com.truesim.ui.*`. It sits beside
`engine`, `scene`, `build-logic` and `common-*`. The name follows the `scene` repository (`scene-core`, ...).
Alternatives:
- `scene-ui`: suggests it is part of the scene repository, which it is not;
- `common-ui`: the `common-*` repositories are small leaf libraries that depend on nothing of ours, but the UI depends
  on scene for the scene bridge.

**Dependency direction, no cycles:**

```
build-logic
common-math, common-state, common-jobs, common-collision      (leaf repositories)
        ↑
scene   (scene-core, scene-assets, scene-meshbuild, …)
        ↑
ui      (ui-core … ui-scene)        never depends on engine
        ↑
engine  (engine-input, engine-ui, …)  depends on ui and scene
```

- The `ui` repository never imports engine code. The engine implements the interfaces `ui` declares:
  - `UiRenderer` (consumes draw lists);
  - `PointerSource` (pointers from devices);
  - `UiCommandSource` (abstract UI commands from input actions);
  - `Clipboard`;
  - `TextInputHost` (IME rect, start and stop text input);
  - optionally `TextShaper` and `GlyphRasterizer` implementations backed by native libraries.
- `ui-core` and most modules need no scene code. Only `ui-scene` (and `ui-gizmo`, for transforms) depend on
  `scene-core`.

**Modules of `ui`** (each published on its own, as scene does, so a server or tool takes only what it needs):

| Module | Depends on | Contents |
|---|---|---|
| `ui-core` | common-state, common-math | Element store and facades, versioned binding, lenses, converters and validators, `ValueRenderer`/`ValueEditor` registry, event model and routing (pointers, capture and bubble, focus scopes, drag sessions, abstract `UiCommand`s), animation (tweens, effect channels, transitions), the draw-list format and recording, semantics (accessibility) tree, the interfaces the engine implements. |
| `ui-style` | ui-core | `StyleKey`, `StyleSheet`, selectors, specificity, resolution cache, live tokens; the Sass-like `.tss` language: lexer, parser, evaluator (variables, nesting, mixins, functions, arithmetic with units, conditionals, loops, extend), compiler to `StyleSheet`, error reporting; the default themes as `.tss` resources. |
| `ui-layout` | ui-core | Measure and arrange; stack, flex, grid, border, anchors, overlay, virtualizing (fixed and Fenwick heights); pixel snapping; RTL mirroring. |
| `ui-text` | ui-core, common-jobs | Interfaces `FontSource`, `TextShaper`, `GlyphRasterizer`, `MsdfGenerator`; itemisation (scripts, `java.text.Bidi`, fallback chains); line breaking and grapheme clusters by `java.text.BreakIterator`; text layout, caret and selection; glyph cache and atlas bookkeeping; the editing engine (undo, IME composition model); a small JDK-only fallback (cmap, advances, `kern`, coverage raster) for headless tests. |
| `ui-text-native` | ui-text, LWJGL 3.4.3 (freetype, harfbuzz, msdfgen, stb, nanovg for NanoSVG) | FreeType fonts and outlines, HarfBuzz shaping, msdfgen MTSDF generation, stb rect packing, SVG icons; a `tools` source set and Gradle task that pre-bakes atlases at build time. |
| `ui-spell-en` | ui-text | SCOWL's Hunspell en_US and en_GB-ise dictionaries, unmodified with their licences (SCOWL's MIT-like terms, Kuenning's BSD for the affix files; `CREDITS.md`): `EnglishDictionaries.american()`, `british()`, `checker()`. Optional for its size (1 MB). |
| `ui-controls` | ui-core, ui-style, ui-layout, ui-text | Every control of section 5.2 to 5.7, including collections and virtualization, data views (sorted, filtered), selection models, menus, pop-ups, dialogs, dock space. |
| `ui-charts` | ui-controls | Chart model, axes, series, ring buffers, decimation (min-max pyramid, LTTB), hit queries, interaction, chart drawing as primitives. |
| `ui-gizmo` | ui-core, scene-core, common-math | Gizmo shapes, ray tests, drag solving in doubles, snapping, pivots, undo events, view cube. |
| `ui-props` | ui-controls | Property metadata model and annotations, the builder pipeline, collection editors, lenses for records. |
| `ui-props-processor` | (build time) | The annotation processor that generates metadata and lenses. |
| `ui-console` | ui-controls | Console model and widget: commands, typed arguments, completion, history, log ring (fed through an interface). |
| `ui-profiler` | ui-charts | The profiler's views over a recording the engine fills: the frame graph overlay, the timeline, flame graph, zone table and counters; exports to its own format and Chrome trace JSON. |
| `ui-a11y` | ui-core | The platform-independent half of OS accessibility bridges: semantics snapshots (`AccessibleTree`), assistive technology's requests run on the UI thread, change events (`AccessibilityBridge`). |
| `ui-a11y-windows` | ui-a11y (JDK FFM only) | Windows UI Automation: a COM provider written with FFM answering `WM_GETOBJECT` for a window (`WindowsAccessibility`). |
| `ui-scene` | ui-core, scene-core | The bridge to scene graphs: the `Interactive` component, routing the scene's picking model (scene-core's `ScenePicker` over `Pickable` components, D46) into UI elements, world-panel nodes. |
| `ui-testkit` | ui-core | Synthetic pointers and commands, draw-list golden helpers, a headless frame loop, allocation checks (test scope). |

**Engine side** (native code, GPU, plugins, sync; depends on `ui`):

| Module | Contents |
|---|---|
| `engine-input` | The action system (D55 to D87; the UI's subset first, stage 0): actions, groups, bindings as settings, contexts, players, text capture. Device kinds from plugins (D55). |
| `engine-ui` | The `Ui` plugin (key, settings, wiring): `UiActions` (section 10) and their mapping to `UiCommand`s; `PointerSource`s for mouse, touch, pen and XR rays; the renderer (frame-graph technique writing the presenter's `UI` key, the primitive buffer and dirty-range upload, Slang shaders for SDF shapes, MTSDF text, nine-slice, lines and charts, composition after the tone curve); bindless atlas pages filled by `ui-text-native` on job threads; world panels (render to texture through views, or direct in a 3D view); optional GPU picking (ID buffer, TLAS ray query); clipboard and IME through `engine-platform`; the SLF4J sink for the console. |

**Publishing and CI.** The same as the other repositories:
- `settings.gradle.kts` includes `../build-logic`, `../common-*` and `../scene` as included builds when present
  (scene's pattern);
- plugins `truesim.settings` and `truesim.java-library-conventions`, which bring `truesim.publishing-conventions`;
- packages at `https://maven.pkg.github.com/true-sim/ui`;
- `.github/workflows/ci.yml` (build, tests, allocation tests, golden data tests, no GPU needed) and `publish.yml`
  (release on tag), copied from scene's;
- GPU goldens and benchmarks run in the engine's CI and `engine-bench`.

**How stages map to PRs.** "CI once per group" means one branch and PR per repository per stage group, and CI and
release once at the end. Merges and releases follow the dependency direction:
1. `common-state` (if the group touches it);
2. `scene` (if it does);
3. `ui`;
4. `engine`.

Each release is tagged only after its PR's verified merge, on that PR's merge commit, and the next repository's PR
moves to the released version before its own CI. Section 13 names the PRs for each group.

### 4.2 Element tree and data model

- **Elements.** One `final` `Element` class per slot over a store, like `Node` in D45. The hot data lives in
  structure-of-arrays inside `UiStore`, indexed by slot with a generation:
  - layout inputs and outputs (rects as floats, in units of the panel);
  - resolved-style index, state flags (hover, pressed, focus, disabled, selected, checked), z-order;
  - the range of primitives the element owns in the draw list;
  - versions.

  Controls (`Button`, `Slider<T>`, `ListView<T>`, ...) are thin typed facades over an element plus the control's own
  model objects. There are no Java objects per primitive and no vectors per element (UI8).
- **Versioning.** All models are `common-state` types stamped by the UI's `ChangeClock` or the game's world clock:
  `VersionedValue<T>`, `VersionedDouble`, `VersionedList<T>`, `VersionedSet`.
  - Controls hold a `Watch` or a `ChangeCursor` mark and poll once per frame in the UI update.
  - A list view visits only changed indices through `VersionedList.changesSince`; a structural change re-binds the
    visible window only.
  - The element store stamps its own changes (layout dirty, style dirty, draw dirty), so the frame's work is
    proportional to what changed.
  - Additions needed in `common-state`: a `VersionedTree<T>` (a parent and children model with changes per node, for
    tree views) and a `VersionedRingBuffer` of floats or doubles for chart streaming (a ring with a head stamp and a
    change log of appended ranges).
- **Binding.** `bind(control.value(), model)` connects two-way:
  - UI → model writes on edit;
  - model → UI is a `Watch` per binding, checked each frame;
  - converters (`Converter<A,B>`) and validators (`Validator<T>` with messages for display) are plain functions.
  - Unlike Lemur's `PropertyPanel`, model-side changes are seen without `refresh()`.
  - Records are immutable, so binding to a record field goes through a **lens** (`Lens<R,F>`: `get(R)`, `with(R, F)`),
    generated with the record's metadata (section 5.11), and writes the new record into the versioned holder.
- **Threading.** One UI thread (the main thread, D5). Data from other threads arrives at frame start
  (`ChangeClock.ownedBy`).

### 4.3 Value renderers and editors

```java
public interface ValueRenderer<T> {
    /** A kind per value, for recycling mixed cells; the default is one kind. */
    default int kind(T value) { return 0; }
    /** A new view for a value of this kind. */
    View create(int kind, ViewContext context);
    /** Shows value in view (a view this renderer created for the same kind): no allocation in steady state. */
    void bind(View view, T value, CellState state);
    /** The view no longer shows a value: release references, stop effects. */
    default void unbind(View view) {}
}
```

- `CellState` is a reused object holding:
  - selected, focused, hovered, pressed, disabled, editing;
  - index, column, depth (for trees), expanded;
  - the cell's element id path, so styles can select `list.item:selected`.
- `ValueEditor<T>` adds `edit(View, VersionedValue<T>)` and commit/cancel for the one value being edited.
- **Registry by type.** `ValueRenderers` resolves in this order:
  1. the exact class;
  2. records by metadata;
  3. superclasses and interfaces;
  4. `Enum`, `Number`, `CharSequence`, `Boolean`, color and vector types;
  5. `String.valueOf`.

  Each control can override, and styles can choose renderers by name (section 4.4).
- The same renderer draws a combo's closed view and its pop-up cells (Lemur's `Selector`), a list's cells, a table's
  cells (per column), a tree's nodes and a property panel's read-only values.
- **Recycling.** A pool per renderer and kind, filled by the virtualized containers (section 5.6).

### 4.4 Styling (UI1)

**The model** keeps Lemur's semantics and adds what is missing:
- Element ids are dotted paths that match by tail (`slider.thumb.button` is matched by `button`, `thumb.button`, ...),
  and "contains" (ancestor) selectors.
- **Style names** (Lemur's `style`, Godot's type variation, a CSS class) choose a theme or variant: `glass`, `hud`,
  `editor`. Attributes inherit through the style's `default` element and then the `root` style, as in Lemur.
- Additions:
  - **state pseudo-classes** (`:hover :pressed :focused :disabled :selected :checked :expanded :editing`);
  - **property selectors** for generated editors (section 5.11): `[type=Transform]`, `[annotation=Range]`,
    `[property=roughness]`, `[group=Lighting]`;
  - **transitions** per attribute;
  - **variables and tokens** (`$accent`) so a palette swap is one change.
- Precedence is computed once into a specificity, attribute by attribute (Lemur's "first match wins per attribute"),
  then pseudo-class rules, then inline values set in code.

**Typed attributes.** `StyleKey<T>` constants declared by the control or component that reads them, like `Setting` and
`PluginKey`:

```java
public static final StyleKey<Paint> BACKGROUND = StyleKey.of("background", Paint.class, Paint.NONE);
public static final StyleKey<Float> FONT_SIZE = StyleKey.decimal("fontSize", 14f).range(1, 512);
public static final StyleKey<LayoutStrategy> PROPS_LAYOUT = StyleKey.of("props.layout", LayoutStrategy.class, FORM);
```

Values are immutable records: `Paint` (solid, gradient, image, nine-slice, SDF box), `Border`, `Shadow`, `Insets`,
`FontSpec`, `Transition`, `EditorChoice`. No cloning is needed (Lemur's `clone`), and there is no reflection.

**Everything is stylable (a rule for the whole toolkit).** No control holds a presentational value in code. That
covers:
- sizes, colours, fonts, spacing, radii, borders, shadows, icons;
- state looks (focus, hover, pressed, disabled, selected);
- transitions and effects;
- generated property layouts and collection editors;
- charts (axes, grid, ticks, series colours, markers, line styles, fills, legend, tooltips, crosshair, highlight);
- gizmos (colours, sizes, handle shapes, hover and active states, occlusion fade);
- the console;
- debug overlays.

Each such value is a `StyleKey` declared by the control that reads it, with a neutral fallback only so a missing theme
is visible rather than a crash. **The default theme is itself a style sheet** (`ui-style` resources), loaded like any
other. Code supplies only behaviour: a new control, a new editor kind, a new decoration or shader effect. Styles then
select it by name. Every stage's done criteria include "all presentation from styles; a test theme restyles it".

**Authoring formats** (UI1). All of them compile to the same immutable, typed `StyleSheet`:

- **(a) typed Java builders.** Variables are Java constants, and mixins and functions are Java methods
  (`Mixins.raised(rule, depth)`, `Colors.lighten(ACCENT, 0.1)`):

  ```java
  sheet.rule(sel("button").style("glass").state(HOVER))
       .set(BACKGROUND, Paint.box(lighten(GLASS, 0.08)).radius(RADIUS_M).border(1, EDGE))
  ```

  They are compile-time checked, with IDE completion, and safe to refactor. Hot reload is limited to the JVM's class
  reload.

- **(b) our own Sass-like language (`.tss`, "true-sim style sheets").** It is written in `ui-style` with no
  dependencies, loaded as an asset, hot reloaded (D25, or a development file watch until D25 exists).

  ```scss
  @use "tokens";                       // partials: _tokens.tss
  $radius-m: 6px;
  $glass: rgba(#20303a, 0.85);

  @mixin raised($depth: 1) {
    shadow: 0 ($depth * 2px) ($depth * 6px) rgba(black, 0.35);
  }
  %pill { radius: 999px; padding: 4px 12px; }

  button {
    font: $font-ui; font-size: 14px;
    background: box($glass) radius($radius-m) border(1px, tokens.$edge);
    @include raised(1);
    transition: background 120ms ease-out, scale 80ms;
    &:hover   { background: box(lighten($glass, 8%)); }
    &:pressed { scale: 0.97; }
    &.danger  { @extend %pill; background: box(mix(tokens.$accent, red, 30%)); }
    @if $compact { padding: 2px 6px; } @else { padding: 6px 12px; }
  }
  slider > thumb.button { size: 18px 18px; }

  props[type=Transform]        { props-layout: compact; }
  props [annotation=Range]     { editor: slider; }
  props [property=color]       { editor: swatch; label: above; }
  props list[element=Waypoint] { collection: master-detail; ops: add remove reorder duplicate; empty-text: "No waypoints"; virtualize-above: 200; }
  chart axis.y                 { grid: 1px rgba(tokens.$fg, 0.1); tick-font-size: 11px; }
  gizmo handle.x               { color: tokens.$axis-x; &:hover { color: lighten(tokens.$axis-x, 20%); } }

  :root { --accent: #3fa7ff; }        // live token: overridable at run time
  button.primary { background: box(var(--accent)); }
  ```

  Features:
  - variables;
  - maps and lists of tokens;
  - nesting with `&`;
  - `@mixin`/`@include` with arguments and defaults;
  - `@use`/`@import` with partials and namespaces;
  - `@extend` and `%placeholders`;
  - `@if`/`@else`, `@each` (for example over a token map to generate `button.$name` variants);
  - `@function`/`@return`;
  - arithmetic with units: `px`, `em`, `%`, `deg`, `ms`/`s`, unitless, with conversions and unit errors;
  - colour functions: `rgba`, `hsl`, `oklch`, `lighten`, `darken`, `mix`, `alpha`/`fade`, `saturate`, `contrast`.

  Everything is evaluated **at load time** into the typed sheet, so nothing is evaluated per frame. Errors give the
  file, line, column, the rule's selector and an include/mixin trace. A bad reload keeps the previous sheet and shows
  the error in the console and overlay.

**Live tokens.** `--name` tokens (or `$name !live`) survive compilation as token slots: a value depending on one is a
small compiled expression over token slots, for example `lighten(var(--accent), 8%)`.
- `Theme.set(ACCENT, color)` or switching a token set (light or dark, high contrast) bumps the token table's version.
- Only resolved styles depending on the changed tokens re-resolve, once, on the next UI update, and the affected
  elements redraw.

**Typed parts in the format.** A property's value is parsed by its `StyleKey`'s `StyleType`: colour, length, paint,
font, enumerations such as `collection: master-detail`, lists such as `ops: add remove reorder`, and durations. Unknown
keys are an error, with a "did you mean" suggestion. Controls register their keys, so a new control's keys are usable
in files at once.

**Embedding an existing parser instead** (UI1 option d):
- No maintained pure-Java Sass compiler exists. jsass wraps libsass through JNI, and libsass is deprecated. Dart Sass
  needs a Dart executable through its embedded protocol.
- CSS-only parsers (ph-css: Apache-2.0, about 1 MB, with an object tree per rule; CSSParser; jStyleParser: LGPL) lack
  mixins, functions and nesting. We would still write the evaluator, and they bring a dependency the user does not
  want.

Parsing allocates only at load, so allocation is not the issue: scope, licence and the dependency rule are. An own
lexer, parser and evaluator for this subset is about 3 to 5 thousand lines of Java with tests, which is affordable.

**Recommendation:** (a) and (b) together.
- Java builders for tests and for code-defined variants.
- `.tss` for themes, including the default theme, which ships as `.tss` so the "everything stylable" rule is tested by
  the theme itself.
- Both produce the same `StyleSheet`, and Java mixins and functions mirror the `.tss` built-ins.

**Performance.** Resolution never happens per frame:
- an element's resolved style is an index into a `ResolvedStyle` cache keyed by (control type, id path, style name,
  state bits, property-selector facts);
- states are a small bit set, so the variants are few and computed once;
- each sheet is `Versioned`, so a reload or a token change bumps the version, and each element re-resolves once, on
  its next update, through a `ChangeCursor` mark;
- resolved values are flat arrays of slots (one per `StyleKey` index), read by index.

The **same selector model serves controls, generated property layouts and collection editors** (UI1 and UI17).
- A property row is an element whose id path and selector facts come from its metadata:
  `props.row[type=float][annotation=Range][property=roughness][group=Surface]`.
- A collection editor adds `[element=Waypoint]`, `[collection=list|set|map|array]` and context facts (inside an
  inspector, a dialog, VR).
- These are ordinary `StyleKey`s resolved and cached like colours:
  - layout strategy, editor kind, label placement, spacing, order overrides, visibility;
  - collection kind, operations shown, icons and their placement, empty-state text, virtualization threshold.

Facts are attribute-match selectors (`[name=value]`) evaluated when the element is built, so they cost nothing per
frame.

### 4.5 Component stacks and drawing

- An element's visuals are a **stack of layers**, as in Lemur: `shadow`, `background`, `border`, `content` (text,
  icon, image, custom), `overlay` (focus ring, selection), plus `insets`/`padding` as layout.
- Each layer is chosen by style (`background: box(...)` or `nine-slice(image, 8 8 8 8)`).
- A layer is not a scene-graph node. It **records primitives** into the element's range of the draw list when the
  element's draw version is behind its style or layout version.
- Custom layers implement `Decoration.record(DrawSink, rect, ResolvedStyle)`, RmlUi's "decorator".

### 4.6 Layout (UI14)

- Two passes, measure then arrange, with caches per element (the measure result is cached by constraints and versions).
  Only dirty subtrees run.
- Layouts:
  - `Stack` (Box);
  - `Flex` (a CSS flexbox subset: direction, wrap, grow/shrink/basis, align, justify, gap);
  - `Grid` (tracks: fixed, auto, fraction, min/max; spans; Lemur's `SpringGridLayout` fill modes map onto fraction
    tracks);
  - `Border` (Lemur's);
  - `Anchors` (Godot-style anchors plus offsets, for HUDs);
  - `Overlay` (layers on top of each other);
  - `Virtualizing` (section 5.6).
- Units: panel units with a scale (`Window.contentScale`-aware, `ui.scale` setting), percent, and font-relative `em`.
  Min and max sizes. Aspect ratio.
- Pixel snapping at the end of arrange, for crisp 1 px borders at any scale (2D only; world panels are not snapped).
- Right-to-left mirroring for RTL locales, by flipping start and end.

**Own layout or Yoga** (lwjgl-yoga 3.4.3 is available and allowed). Yoga gives:
- proven CSS flexbox semantics;
- absolute positioning;
- its own measure caching.

It lacks:
- grid, anchors and virtualization (those stay ours anyway);
- a single store: its node tree lives in native memory, so every element keeps a `YGNodeRef` in sync with ours.

Every style change and every measure callback of text crosses into native code. Measure callbacks are upcalls from C
into Java, costly per text leaf.

Plan:
- Write our own flex, grid, anchors, border, stack and virtualizing layouts.
- Use **Yoga as the conformance oracle in `ui-layout` tests**: random and fixture trees laid out by both, compared.
- **Benchmark both** on a 2,000-element HUD and a 500-row form, with full relayout and incremental relayout.
- Offer a Yoga-backed `Flex` only if it wins clearly and does not lose on incremental updates.

### 4.7 Input, pointers, picking and focus (UI4, UI5, UI21, UI22)

**Pointers.** A `Pointer` has:
- an id, a kind (mouse, touch finger, pen, gamepad virtual cursor, XR ray, gaze) and an owner `Player` (D63);
- a ray every frame (a screen pointer is a point on a screen layer plus the camera ray through it; an XR pointer is the
  aim pose from `PoseAction`);
- button states, scroll and modifiers.

Pointers are created by **pointer sources**:
- mouse;
- touch, one per finger, read from devices directly (D56);
- pen;
- XR controllers, through the `ui.pointer` actions;
- gamepad cursor (optional).

They are kept in a fixed-size array, and a pointer's events are reused objects.

**Pick order per pointer, each frame, once, at frame start after input sampling (D59). No throttling.**
1. **Screen UI layers** (front to back): a 2D hit test on the element tree. Rect checks walk only interactive elements
   in a uniform grid per layer, rebuilt when layout changes (versioned), then the precise shape (rounded rect SDF,
   alpha mask optional). Cost: microseconds.
2. **World UI panels**: the ray against each panel's plane (panels are few, kept in a list or in `SpatialIndex` on a
   `UI_PANEL` layer), then the local 2D point, then step 1 on that panel's tree.
3. **Scene objects** (changed 2026-11-04: picking moved out of the UI): the scene's engine-independent picking model,
   scene-core's `ScenePicker` over `Pickable` components (D46), which the engine's scene interaction uses too. Only
   pickable nodes are tested, so **objects that are not pickable cost nothing** beyond the index's box tests; a frame's
   picks are kept per ray, so the UI and the engine picking one pointer cast its ray once.
4. **Narrow phase**, chosen per `Pickable` (UI5; an `Interactive` sets one unless its node has one):
   - `BOUNDS`: free;
   - `MESH`: the ray against a per-mesh triangle BVH on the CPU (scene-core's `PickMesh`, built lazily, kept with the
     mesh asset by scene-render's `PickMeshes`), or the node's renderable's mesh (`RenderablePicking`), skinned meshes
     posed on the CPU against their joints as they are this frame (no delay);
   - `CUSTOM`: a test of the game's own;
   - GPU ID picking (`GpuPicking` in the engine) stays an opt-in with its frame of delay, never the default.
5. Results are merged by distance. Screen layers win by layer order, as in Lemur where GUI goes before the scene; then
   gizmo handles, then world panels and scene objects by distance.

The pointer's target is the nearest hit with a listener, found by walking up the element tree or the scene-graph
parents (Lemur's `findHitTarget`). A pickable node hit stands for its nearest scene-graph ancestor (or itself) with an
`Interactive`, so a model's parts route to the model; one with none is not the UI's (the engine's scene interaction
routes it to its own listeners). Tooltips, context menus and drag and drop between the UI and the world stay UI
features of `Interactive`.

**Events**: enter, exit, move, press, release, click (with count, so double-click), long press, context (right click,
long press, or the context action), wheel and scroll, drag start, drag, drag end, drop enter, drop over, drop leave,
drop, focus in and out, key, text, navigate, plus custom commands.

**Routing**, DOM-like: capture (root to target), target, bubble (target to root), stopped by `consume()`. Then:
- if a UI hit was not consumed, the next hit along the ray gets it (Lemur's pass-through, kept);
- a press captures the pointer, so the pressed target gets move and release until release, even off it;
- hover is tracked per pointer.

The same event classes and listener interfaces serve UI elements, world panels' elements and plain scene objects.
`scene.add(node, Interactive.of(listener).tooltip(...).contextMenu(menu).draggable(payload))`.

**Drag and drop.** `DragSession` holds a typed payload (`DataFormat<T>` keys), a source, a preview (UI element or 3D
ghost) and allowed operations (move, copy, link). Drop targets are found through the same picking, so dragging from an
inventory list onto a 3D object, or from the 3D world onto a slot, is one mechanism.

**Focus** (UI21):
- focus scopes per player, so each local player has a focus and a highlight;
- a navigation graph: containers answer `relative(from, direction)` (Lemur's `FocusTraversal`, with bubbling), the
  default is a spatial search (nearest in the direction, weighted, as Godot and ImGui do), and explicit neighbours can
  be set per element or style;
- tab order;
- focus memory per container;
- modal scopes (dialogs, pop-ups) trap focus;
- repeats come from the action system's repeat filter (start delay and rate as settings).

**Text capture**: a focused text field pushes the text context (D61). The platform `text`/`textEditing` events give
committed text and the IME composition (shown underlined in the field). The IME candidate window position is set
through SDL's text-input rect (to add to `engine-platform`).

### 4.8 Animation and effects

- `Tween` and `Animation` with the UI clock.
- Effects named by typed keys (`Effects.OPEN`, `PRESS`, `HOVER`, `FOCUS`), each on a **channel**, with Lemur's
  fast-forward so an interrupted open reverses smoothly.
- Style **transitions** animate resolved attribute changes (colour, size, offset, opacity) with easing.
- The animated values are written into the element's SoA overrides and stamped, so nothing allocates.

---

## 5. Controls and high-level widgets

### 5.1 Reference catalogue (Dear ImGui) and what retained toolkits add

Dear ImGui (`imgui.h`, `imgui_demo.cpp`, docking branch; https://github.com/ocornut/imgui) is the completeness
reference.

| Area | Dear ImGui |
|---|---|
| Text | `Text`, `TextColored`, `TextDisabled`, `TextWrapped`, `LabelText`, `BulletText`, `SeparatorText`, `TextLink` |
| Buttons | `Button`, `SmallButton`, `InvisibleButton`, `ArrowButton`, `ImageButton` |
| Toggles | `Checkbox`, `CheckboxFlags`, `RadioButton` |
| Progress | `ProgressBar` |
| Combo | `BeginCombo`/`Combo` with custom previews |
| Lists | `ListBox`, `Selectable`, multi-select with box select, ctrl and shift (1.91) |
| Drags and sliders | `DragFloat`/`Int`/`N`/`Range`, `SliderFloat`/`Int`/`N`/`Angle`, `VSlider` |
| Input | `InputText` (password flag, hint, multiline, callbacks, filters, read-only), `InputFloat`/`Int`/`Double`/`N` with step buttons, `InputScalar` |
| Colour | `ColorEdit3`/`4` (RGB, HSV, hex), `ColorPicker` (bar or wheel, alpha), `ColorButton` |
| Trees | `TreeNode`, `CollapsingHeader`, `SetNextItemOpen` |
| Tables | sort specs, multi-sort, resizable and reorderable columns, hide, freeze rows and columns, row background, clipper |
| Menus | `MainMenuBar`, `MenuBar`, `BeginMenu`, `MenuItem` with shortcuts and check marks |
| Pop-ups | `OpenPopup`, modals, context pop-ups for items, windows and void |
| Tooltips | `SetTooltip`, rich tooltips, delay and stationary rules |
| Tabs | `TabBar`/`TabItem`: reorderable, closable, a list pop-up, fitting or scrolling |
| Windows | child windows, docking (dock spaces, nodes, splitting, tab bars), multi-viewport |
| Plots | `PlotLines`, `PlotHistogram` |
| Images | `Image` |
| Navigation and other | drag and drop with typed payloads, keyboard and gamepad navigation, shortcuts and key routing, clipboard, `BeginDisabled`, `ListClipper` |

Retained toolkits add:
- virtualized lists, trees and tables over data views with **sorting, filtering and grouping** (WPF `CollectionView`,
  Unity `MultiColumnListView`);
- data binding with converters and validation;
- templates by data type;
- themes and resources;
- transitions and storyboards;
- layout systems;
- localisation and accessibility trees;
- design tools.

### 5.2 Basic controls

`Label` (rich spans: colour, weight, inline icons, links), `Image`, `Icon`, `Button` (text, icon, both; repeat),
`ToggleButton`, `Checkbox` (tri-state), `RadioButton` with groups, `Switch`, `Slider<T>` (horizontal, vertical, range,
ticks, logarithmic), `Drag<T>` (ImGui-style drag values), `ProgressBar` (determinate, indeterminate, radial), `Spinner`
(Lemur's `SequenceModel`), `Separator`, `Spacer`, `Badge`.

### 5.3 Text input

- `TextField` (single line, hint, filters, max length);
- `PasswordField` (mask; never put on the clipboard; no IME prediction);
- `TextArea` (multi-line, wrapping, scrolling);
- `NumberField<T>` (parse and format by locale, units, step buttons, drag on label, expressions optional);
- `SearchField`.

Shared editing engine:
- caret, selection, word and line movement by `BreakIterator`, undo and redo (coalesced);
- clipboard through SDL;
- IME composition;
- BiDi caret movement (visual or logical per setting).

### 5.4 Selection controls

- `ComboBox<T>`/drop-down: a value renderer for the closed view and the cells (Lemur's `Selector`), optional search,
  virtualized pop-up.
- `ListBox<T>`, `SegmentedControl`, `ChipGroup`.

### 5.5 Containers

- `Panel`, `ScrollPane` (both axes, kinetic scrolling for touch, scroll bars that auto-hide, scroll by gamepad and
  focus);
- `SplitPane`, `TabPane` (reorderable, closable, overflow menu), `Expander`/`RollupPanel` (Lemur);
- `GroupBox`;
- `Window` (title bar, move, resize, minimise to dock);
- `Dialog`/modal, `Popup`, `Tooltip` (delay, follow, rich content), `ContextMenu`, `MenuBar`/`Menu`/`MenuItem` (check,
  radio, sub-menus, shortcut text from the action's binding glyph, D62);
- `Toast`/notification.

### 5.6 Collections with virtualization

- `ListView<T>`, `TreeView<T>` (a `VersionedTree` model, lazy children, expand state), `TableView<T>` (columns with a
  renderer and editor per column, sort descriptors, filter predicates, column resize, reorder, hide, freeze, grouping
  headers).
- **Virtualizing layout**: only rows inside the viewport plus a margin get views, recycled by renderer kind.
  - Fixed row height: O(1) row → offset.
  - Variable height: a Fenwick tree of measured and estimated heights, O(log n) both ways.

  Scrolling never touches off-screen rows.
- **Data views**: `SortedView` and `FilteredView` over a `VersionedList`, updated incrementally from the list's
  change log (an index map sorted once, then fixed up per changed element), so a 1,000,000-row model with a few
  changes per frame stays cheap.
- **Selection models**: single, multiple, range; keyboard (shift, ctrl), box select (ImGui 1.91); versioned.

### 5.7 Specialised controls

- `ColorPicker`: wheel or box, HSV/RGB/hex/OKLCH, alpha, HDR intensity (values above 1 for emissive), eyedropper from
  the screen, palette swatches.
- `GradientEditor` and `CurveEditor` (for animation and effects data).
- `DockSpace`: dock nodes as a split tree, tab stacks, drag to dock with drop overlays, undock to floating windows,
  layout saved as data. Multi-viewport (OS windows) is later, through the presenter's several windows.
- `Toolbar`, `StatusBar`, `Breadcrumb`, `Calendar`/date picker (low priority).

### 5.8 Charts (section 7)

### 5.9 3D gizmos (section 8)

### 5.10 Developer console

- **Model** (`ui-console`):
  - `ConsoleCommand` registered by plugins and games with typed arguments (`Arg.integer("count").range(1, 100)`,
    `Arg.choice(...)`, `Arg.asset(type)`, `Arg.node()`, `Arg.setting()`), descriptions and permissions (developer
    tier, cheats flag);
  - a parser with quoting;
  - completion for command names, argument values (enums, choices, asset paths, node names, setting names) and
    argument hints (signature shown while typing);
  - history saved per app id;
  - aliases, and scripts (exec of a file).
- **Settings as variables**: every registered `Setting` is a console variable.
  - `render.bloom PHYSICAL` sets it, `render.bloom` prints the value, the default, the range and the availability
    (D13).
  - Completion lists choices.
  - Values go through the constraint overlay (D14), so a locked or unsupported value says why.
- **Log view**:
  - an SLF4J sink into a ring buffer (fixed capacity, allocation-free append of preformatted records, coalescing
    repeats);
  - filters by level and logger;
  - search (incremental, over the ring);
  - virtualized display;
  - click a logger name to filter, click a source position to copy it.
- **UI**: a `Console` widget (log `ListView` + `TextField` + completion pop-up), toggled by `ui.console.toggle`
  (default grave/tilde; gamepad: both sticks clicked; XR: menu button held). It works in 2D (drops down from the top)
  and as a world panel in VR (anchored to the left hand or in front, with a virtual keyboard).
- References: the Quake and Source consoles (ConVars, ConCommands, completion), Unreal's console (cvars with help,
  `?`), the ImGui demo's `ExampleAppConsole` (history, completion callback, filter).

### 5.11 Property builder: annotation-based meta API (UI17, UI19, UI20)

**Metadata, one source.** `PropertyModel<T>`, per type, holds an ordered list of `Property<T,V>`:
- name, value type, getter, and setter or lens (records: `with` copy);
- annotations as typed facts.

It is built from three sources, merged by priority:
1. **Generated material records**: the Slang `[MaterialParameters]` build already emits records with `[Range]` checks,
   withers and per-field constants. It also emits their `PropertyModel`, so materials need no Java annotations.
2. **Settings**: `Setting` ranges, choices, labels (translation keys), `SettingGroup` and tiers map to properties, so
   the settings screen is generated.
3. **Annotated Java records and classes**:
   - `@Label`, `@Tooltip`, `@Range(min,max)`, `@Step`, `@Unit("m/s")`;
   - `@Color` (with `hdr`), `@Angle`, `@Slider` versus `@Field`;
   - `@Multiline`, `@Password`, `@EnumStyle(RADIO|DROPDOWN|SEGMENTED)`;
   - `@Hidden`, `@ReadOnly`, `@Group("Lighting")`, `@Order(n)`, `@Foldout`;
   - `@ShowIf("enabled")`, `@Validate(Validator.class)`, `@AssetType(...)`;
   - custom annotations registered with an `AnnotationHandler`.

**Generation of metadata** (UI19):
- **(a) an annotation processor** at build time writes `FooProperties` (metadata, lenses, accessors as direct method
  calls). There is no reflection at run time, and it is the same approach as the Slang records.
- **(b) reflection on first use, cached** (`MethodHandle`s and `RecordComponent` accessors). It works for types not
  compiled with the processor (mods, scripts).

Recommendation: both. (a) is the default; (b) is the fallback when no generated class is found. Benchmark the first
build and access costs (expected: (a) is free at start, (b) costs tens of microseconds per type once, and per-access
costs are equal after JIT through `MethodHandle`s). If the benchmark shows no meaningful difference, keep only (b) for
simplicity.

**Builder pipeline**, every step replaceable:

```
PropertyModel → Filter (visibility: @Hidden, tier, @ShowIf, style 'visible')
             → Order (@Order, style 'order', groups)
             → Group (@Group, @Foldout → sections)
             → EditorChoice (per property: style 'editor' > per-annotation factory > per-type editor registry > fallback)
             → LayoutStrategy (FORM label|field, GRID, INSPECTOR with foldouts, COMPACT; style 'props-layout')
             → Bind (lenses into VersionedValue; validation)
             → Element tree (styled with ids like props.row[type=…][annotation=…][property=…])
```

- `PropertyBuilder.forType(Transform.class).with(stepOverrides).build(model)`.
- Users can insert, replace or remove steps (`builder.replace(Steps.LAYOUT, myLayout)`), register `EditorFactory` per
  type or per annotation, or bypass the pipeline for a type with a hand-written `ValueEditor`.
- **Styles choose** the layout strategy, the editor, label placement, spacing, order overrides and visibility through
  the shared selector model (section 4.4), so a theme restyles every inspector without code. Code hooks remain for
  what styles cannot express.
- **Live binding**: a property editor watches its source (`Watch` on the versioned holder or the node component), so
  edits made elsewhere (undo, scripts, network) show at once.

**Rule: anything presentational is expressible in styles; code is only for new behaviour.** A new editor kind or
layout strategy is registered by name, then styles select it. Priority, highest first:
1. inline code overrides for one instance;
2. style rules;
3. annotations;
4. registered defaults per type.

Annotations are hints that a theme can override, as the style rules sit above them.

**Collections** (lists, sets, maps, arrays, and nested records inside them; UI18):
- **Editor kinds** (registered `CollectionEditorKind`s, selected by the style key `collection`):
  - `table`: inline, one column per property of the element type, from its `PropertyModel`;
  - `foldouts`: each item a collapsible nested property panel;
  - `master-detail`: a list plus the selected item's panel;
  - `chips`: tags, for sets of enums and strings;
  - `compact`: "12 items" plus a pop-up editor;
  - `map`: key and value columns, keys validated as unique.
- **Operations**, shown or hidden by style (`ops: add remove reorder duplicate clear context-menu`), each with icon,
  placement (`ops-placement: header | row-end | hover`) and confirmation (`confirm: clear`):
  - **add**: a factory (`@Factory`, the record's `DEFAULTS`, a no-argument constructor) or a **type chooser** for
    sealed or polymorphic element types (the permitted subclasses from metadata, labelled by `@Label`);
  - **remove**;
  - **reorder** by drag handles (the drag and drop of section 4.7) or up and down;
  - **duplicate** (a deep copy through the metadata);
  - **clear**;
  - a **context menu** per item (the same operations plus custom ones registered by code).
- **Validation**: `@MinSize`, `@MaxSize`, `@Unique`, and per-element validators. The style decides how violations
  show. Operations that would break a rule are disabled with a tooltip giving the reason.
- **Annotations as defaults**: `@ListStyle(MASTER_DETAIL)`, `@Addable`, `@Removable`, `@Reorderable`, `@MinSize`,
  `@MaxSize`, `@ElementLabel("name")`. Styles override them; code can supply a completely custom
  `CollectionEditor<C>` for a type, property or annotation, which styles can then select by name.
- **Immutability**: an edit produces a new list (or set, map, array) and, through the lens chain (record → field →
  element → field), a new root record written into the versioned holder. Persistent-structure sharing is not needed
  at UI edit rates. Copies are made on edit, never per frame.
- **Undo hooks**: every operation emits an `Edit` (before, after, path) to an `EditListener`, so the editor's undo
  stack merges it. Drags and continuous slider edits coalesce into one edit per gesture.
- **Virtualization**: above a style-set threshold (`virtualize-above`), table, foldout and master-detail lists use
  the virtualizing layout of section 5.6.
- **Change tracking**: an item's panel rebinds only when its element changed. Old and new lists are compared by
  identity per index; for a `VersionedList`, its per-element versions are used.

**Inspectors built on it**: scene node components (D46 component stores give types and values), materials, settings
(the settings screen), the UI's own elements (a UI debugger), and game records.

---

## 6. Rendering

### 6.1 Draw list as data

- Each element records **primitives** into its range of a per-layer `DrawList`, recorded in `ui-core`, uploaded by
  `engine-ui`. A primitive is a 64-byte record, packed as `int`s and `float`s in a `MemorySegment`-backed buffer:
  - kind;
  - rect (or line endpoints);
  - clip index;
  - colours or gradient;
  - radii, border and shadow parameters;
  - bindless texture index and UV rect;
  - a transform index.
- Kinds: SDF box (fill, border, four radii, soft shadow, inset shadow, linear and radial gradient), MSDF/MTSDF glyph,
  image, nine-slice, line segment (AA, joins, dashes), arc and circle (SDF), triangle fan (rare: custom shapes),
  custom (a style-chosen shader effect index).
- **Only dirty ranges are rewritten and uploaded**: a frame where nothing changed uploads nothing.
  - Ranges are compacted when fragmentation passes a threshold.
  - Animations that change only opacity or transform write the per-element transform table (one 32-byte entry), not
    the primitives.

### 6.2 GPU path (UI15)

- **Vertex pulling with instancing**: `SV_VertexID` → primitive index and corner. There are no vertex buffers, and one
  pipeline per blend mode.
- Bindless textures (the engine's), so atlases and images never split batches.
- Clipping in the shader: the clip-rect table holds rounded clip rects too, so scroll panes and rounded containers
  clip without scissor or stencil changes.
- The result is about **one draw per UI layer**.
- The alternative to benchmark: one pipeline per primitive kind, sorted by kind within a layer, which gives simpler
  shaders and fewer divergent branches but more draws.

### 6.3 Text

**Dependency rule.** Anything LWJGL offers counts as allowed. The engine pins LWJGL 3.4.3 (`build.gradle.kts`:
`lwjglVersion = "3.4.3"`), and Maven Central has 3.4.3 of these modules:
- `lwjgl-freetype`, `lwjgl-harfbuzz`, `lwjgl-msdfgen`;
- `lwjgl-stb` (truetype, rect_pack, image);
- `lwjgl-yoga`, `lwjgl-nanovg` (which also carries NanoSVG);
- `lwjgl-par`, `lwjgl-meshoptimizer`, `lwjgl-ktx`.

https://repo1.maven.org/maven2/org/lwjgl/ (checked 2026-11-03). Other external libraries stay out. The JDK's
`java.text.Bidi` and `java.text.BreakIterator` count as the platform; since JDK 20 the character instance follows
extended grapheme clusters (JDK-8291660).

**Where the LWJGL text code lives** (UI31). Text is engine-agnostic logic. The native libraries are neither GPU nor
engine management, so they go in an **optional module of the `ui` repository, `ui-text-native`** (lwjgl core,
freetype, harfbuzz, msdfgen, stb). The other modules stay JDK-only:
- `ui-text` defines `FontSource`, `TextShaper`, `GlyphRasterizer` and `MsdfGenerator`;
- it does the itemisation, line layout, caret and editing in Java;
- it contains a small Java fallback (cmap, advances, `kern` pairs, a coverage rasteriser), so headless tests run
  without natives.

Reasons for this placement:
- one repository owns and tests all text behaviour, with goldens in its own CI, where LWJGL natives are platform jars
  that are easy to pull;
- tools and servers can lay out text without the engine;
- `engine-ui` simply depends on `ui-text-native`.

The alternative is to put the native implementations in `engine-ui`. The `ui` repository would stay JDK-only, but
shaping goldens would move to the engine's CI and tools would need the engine.

**The pieces:**
- **Fonts and outlines** (UI10): FreeType through `lwjgl-freetype`. It reads TrueType, CFF/CFF2, variable fonts and
  colour bitmaps, and it hints, which helps small raster text. stb_truetype is lighter (no hinting, no CFF2), and our
  own reader would cost about 2.5 thousand lines. FreeType is recommended.
- **Shaping** (UI9): HarfBuzz through `lwjgl-harfbuzz`, for every script from the start (Arabic, Indic, Southeast
  Asian, ligatures, marks, features), with fonts given to HarfBuzz through its FreeType integration or blob loading.
  Writing our own would take months for complex scripts. The Java fallback shaper (simple scripts) is kept for
  headless tests.
- **MSDF/MTSDF** (UI11):
  - **at run time**, through `lwjgl-msdfgen` on `common-jobs` threads, on demand, from FreeType outlines (dynamic
    glyph sets: CJK, user text, names);
  - **at build time**, a `tools` source set of `ui-text-native` (a Gradle task through build-logic) pre-bakes the
    atlases a game ships (Latin plus the game's text), so the first frame has no generation;
  - both use the same code, and the disk cache unifies them;
  - our own Java generator (about 2 to 3 thousand lines) is not needed.
- **Atlas packing**: `stb_rect_pack` (skyline), or a few hundred lines of our own skyline packer. Decide by the
  benchmark; both are trivial.
- **Atlases**:
  - pages are bindless textures (engine);
  - about 32 to 48 px per glyph with a pixel range of 4 to 8 (settings);
  - a placeholder for one frame while a glyph is generated, with a generation budget per frame (setting).
- **Small text** (UI16, decided): both techniques behind the setting `ui.text.smallText` (`HINTED`, the default, or
  `MTSDF`) with `ui.text.hintedBelow` (13 px). `HINTED` draws screen-space glyphs below that size as FreeType's
  light-hinted coverage at the exact size (1/64 px), rendered at four horizontal phases per pixel so pens stay where
  shaping put them (to a quarter pixel) while quads sit on whole pixels; text at or above it, world-space panels and
  text with an outline or weight offset (effects only the distance field has) draw MTSDF. Raster glyphs live in the
  same atlas pages, keyed by face, glyph, size and phase, generated on the same lane under the same budget and
  placeholder rule; the GLYPH header flag `FLAG_RASTER` makes the shader take texture alpha times colour alpha.
  Layout never depends on the choice: switching re-records without reflowing.
- **Icons**: SVG through NanoSVG (`lwjgl-nanovg`) parsed into paths at load, converted into msdfgen shapes, and put
  in the same MTSDF atlas, so icons scale crisply and tint like text. NanoVG's renderer itself is OpenGL and is not
  used.
- **Itemisation**: script runs by `Character.UnicodeScript` (or HarfBuzz's script functions), BiDi runs and reordering
  by `java.text.Bidi`, font fallback chains per locale and script, line breaking by `BreakIterator`.
- Shaping runs only when the text, font, size or locale version changes. Results (glyph ids, advances, clusters) are
  cached per text element, and layout and drawing reuse them.
- **Effects** from MTSDF: outline, glow, soft drop shadow, bold by threshold, all in the glyph shader with style
  attributes.

### 6.4 World-space and VR panels (UI12)

The same element tree, rendered in one of two ways:
- **(a) render to texture**: the panel renders into a texture with a bindless slot (frame-graph-v2: "3D UI" through
  views into textures), then a scene material samples it. It is lit and fogged like the scene, cheap per panel when
  static (re-rendered only when its draw list changes), with mipmaps for distance. It is limited by texture resolution
  and blurs at angles.
- **(b) direct**: the primitives are drawn in the 3D view as transparent geometry with a transform per panel, depth
  tested. MSDF stays sharp at any distance and angle. It costs per frame per view (both eyes in VR, through multiview)
  and needs sorting with other transparency.

Both are offered; the panel chooses (a trade-off of sharpness and cost). VR legibility rules go in the defaults: a
minimum angular size, about 1.5 to 2 m panel distance, and curved panels optional.

### 6.5 Effects

- Opacity and transform per element (animated).
- Shadows and glows (SDF).
- Gradients.
- **Backdrop blur** (frosted glass): it samples `PRE_UI`, downsampled and blurred once per frame (dual Kawase), only
  when some visible element uses it.
- Custom shader effects: a style names a Slang effect function from a registry (dissolve, scanlines, hologram).

### 6.6 HDR and composition

- The UI renders into its own `UI` image (premultiplied alpha, linear, where 1.0 means paper white), at the window's
  resolution.
- The output transform composites it **after** the tone curve, in the display's linear space, scaled to
  `display.paperWhite` on HDR (and to 1.0 on SDR), then encodes.
- An optional `ui.brightness` setting is relative to paper white.
- HDR-intense UI elements (glows above white) are allowed by style (`intensity: 2.0`) and clamp to the peak.
- `PRE_UI` is kept for frame generation (frame-graph-v2 §4.8).
- No extra frame of latency: the UI records after game update in the same frame, and its pass is in the same graph.

---

## 7. Charts

**References**: ImPlot (https://github.com/epezent/implot):
- plot types: line, shaded, scatter, bars (vertical, horizontal, stacked/grouped), error bars, stems, stairs, pie,
  heatmap, 1D and 2D histograms, images, digital, text, infinite lines;
- axes: up to 3 X and 3 Y, linear, log, symlog, custom, time with microseconds;
- interaction: zoom, pan, box select, query ranges, drag points, lines and rects, tags, annotations;
- subplots with linked axes, legend toggles, 16 colormaps.

ImPlot's README notes that millions of points are not expected to be smooth and that 16-bit indices glitch dense
plots. uPlot (https://github.com/leeoniya/uPlot) is a model of fast time series (min-max decimation, cursor sync across
charts). ECharts (https://echarts.apache.org) has a rich catalogue and LTTB sampling. SciChart (https://www.scichart.com)
does GPU real-time charting of millions of points. Grafana is the model for time-series dashboards (shared crosshair,
tooltips, legends as tables).

**Model** (`ui-charts`):
- `Chart` holds `Axis` objects. There can be any number, each X or Y, at any side, with these scales: linear, log,
  symlog, time (double seconds or `Instant`), category.
- Axis ranges are `Versioned` objects, so **linking axes** across charts is sharing one range object; a crosshair is
  shared the same way.
- Series reference axes by id.
- Each axis, series, legend entry and annotation layer can be enabled and disabled.
- Data sources: `VersionedFloatArray`/`DoubleArray` (exist), `VersionedRingBuffer` (new, for streaming), and getters
  (`IntToDoubleFunction`) for computed data.
- Series types:
  - line, step/stairs, digital;
  - area, stacked area, shaded range (between two series or a band);
  - bars (grouped, stacked, horizontal);
  - scatter with markers;
  - histogram 1D and 2D (binned on change, or on the GPU);
  - heatmap (a texture with a colormap LUT);
  - pie and donut;
  - candlestick/OHLC, error bars, stems;
  - infinite lines (horizontal and vertical), text and annotations, images.
- **Decimation** (UI23):
  - **min-max per pixel column** over a **min-max pyramid** (a segment tree per series, appended incrementally for
    ring buffers): exact envelopes for lines at any zoom, O(pixels · log n) per redraw;
  - **LTTB** (Steinarsson 2013) for representative subsets, for scatter and markers;
  - both offered, because they trade off: an exact envelope versus the visual shape with markers.
- **Hit queries**:
  - nearest point per series (sorted X: binary search; scatter: a uniform grid built on change);
  - bars and pie slices by geometry;
  - all on the CPU, without allocation.
- **Interaction**:
  - hover crosshair, nearest point highlight and tooltips (one per series or merged, Grafana-style);
  - legend click to toggle and hover to highlight;
  - wheel zoom per axis or both;
  - pan by drag;
  - box zoom and box select (with modifiers);
  - axis drag and axis-only zoom;
  - auto-fit and reset (`ui.charts.reset`);
  - drag points, lines and rects (ImPlot's drag tools) for editing data and query ranges;
  - selectable axes (click an axis to make it the zoom target);
  - subplot grids with linked rows and columns.

**GPU**:
- Lines are instanced segments expanded in the vertex shader: SDF anti-aliasing, miter joins with a limit and bevel
  fallback, round caps, dashes by cumulative length.
- Markers are SDF shapes, instanced.
- Areas are triangle strips generated from the decimated envelope.
- Data is uploaded as decimated vertices (CPU decimation) or as raw columns with a compute decimation pass (UI24:
  benchmark both at 1M to 10M points).
- Precision: values are stored as doubles on the CPU, uploaded relative to the axis origin as floats.

**Streaming**: the ring buffer's head moves, and the X axis scrolls (follow mode). The min-max pyramid appends in
O(log n) per point, and only the new pixel columns are re-decimated.

---

## 8. 3D gizmos

**References**:
- ImGuizmo (https://github.com/CedricGuillemet/ImGuizmo): `Manipulate(view, projection, operation, mode, matrix,
  deltaMatrix, snap, localBounds, boundsSnap)` with operations TRANSLATE, ROTATE, SCALE, BOUNDS (box scaling) and
  UNIVERSAL, modes LOCAL and WORLD, `ViewManipulate` (the view cube), `IsOver` and `IsUsing`.
- Unity Handles and transform tools (pivot or center, local or global, vertex snapping with V, surface snapping).
- Godot's Node3D gizmo (plane handles, local space, snap with the Ctrl key).
- Blender (G/R/S modal transforms with axis keys and typed values, pivot modes: median point, individual origins,
  active element, 3D cursor, bounding box center; snapping to increment, vertex, edge, face).

**Design** (`ui-gizmo` for the math, `engine-ui` for drawing):
- **Handles**:
  - translate: axis arrows, plane squares, a screen-plane centre;
  - rotate: axis rings, a view ring, a free trackball;
  - scale: axis cubes, uniform centre;
  - bounds: box corner and face handles;
  - universal: all combined.
- **Picking**: the pointer ray against analytic shapes (capsules, tori, quads), with a screen-space tolerance in
  pixels. This is part of the same pointer pipeline, and gizmos come before scene objects.
- **Screen-constant size**: scaled per view by distance × tan(fov/2) × setting.
- **Depth**: drawn on top with the occluded parts faded, a style choice. The fade is decided per pixel by the
  renderer against the scene's depth of the frame being drawn (`Gizmo.depthTest`, `Primitive.FLAG_DEPTH`), or per
  part on the CPU (`GizmoOcclusion`) where the renderer has no depth for the view.
- **Math in doubles** (D37) with camera-relative drawing (`CameraRelative`), so no jitter far from the origin.
- **Space**: local, world, parent, view. **Pivot**: median, individual origins, active, cursor, bounds centre. The
  selection is multi-node.
- **Snapping**: increments per operation (settings), held modifier `ui.gizmo.snap` toggles it, a typed numeric value
  while dragging (Blender), vertex and surface snap later through `SpatialIndex` and mesh BVHs.
- **Undo hooks**: `GizmoListener.begin(initialTransforms)`, `update(delta)`, `commit(final)`, `cancel()` (restores
  the initial transforms). The editor turns commit into one undo command.
- **View cube**: a UI element with an SDF-projected cube, click to align, drag to orbit.
- **XR**:
  - controller ray picking of handles;
  - direct grab (a hand inside the handle);
  - two-handed scale and rotate;
  - gizmo size by angular size;
  - snapping by a held grip or trigger threshold (actions in `ui.gizmo`).
- Gizmos operate on scene `Node` transforms through the scene API, so physics and networking see ordinary changes.

---

## 9. Decisions to make (most important first)

Each row gives the options and a recommendation. The stages in section 13 show where each decision is needed.

| # | Decision | Options | Recommendation |
|---|---|---|---|
| UI1 | **Styling: model and authoring** (no Groovy; everything stylable) | (a) typed Java builders only; (b) our own Sass-like `.tss` only; (c) both, compiled to the same typed `StyleSheet`; (d) embed an existing CSS/Sass parser | **(c)**. The model is Lemur's: element id paths with tail matching, style names, "contains" selectors, precedence attribute by attribute. It adds state pseudo-classes, attribute selectors for generated inspectors and collections, live tokens and transitions. `.tss` has variables, nesting, mixins, partials, functions, unit arithmetic, conditionals, loops and extend, all evaluated at load. Styles resolve once per (type, id path, style, state bits, facts), are cached and versioned, and are never looked up per frame. Live tokens re-theme at run time. (d) is rejected: no maintained pure-Java Sass exists, CSS-only parsers lack Sass features and add a dependency. Java builders come in stage 2, `.tss` in stage 3 (moved up because the default theme is a `.tss` file). |
| UI2 | **Repository split and dependency direction** | (a) `ui` depends on common-* and scene; the engine depends on `ui` and implements its interfaces (`UiRenderer`, `PointerSource`, `UiCommandSource`, `Clipboard`, `TextInputHost`); (b) `ui` depends on the engine | **(a)**: no cycles, GPU-free tests, usable by tools and servers. |
| UI3 | **Retained vs immediate** | (a) retained only; (b) a retained core plus an immediate façade for tools; (c) retained, plus Dear ImGui for tools | **(b)**: one renderer, style system, HDR and VR path. (c) adds an external library and a second rendering path. |
| UI4 | **One event model for UI and scene objects** | (a) one model: pointers with rays, DOM-like capture and bubble through the UI tree and the scene parents, an `Interactive` component with the full set of interactions (click, double click, hover, press, drag and drop, focus, wheel, context menu, tooltip); (b) separate models | **(a)**: the user's requirement, proven by Lemur. Cost targets in section 11. |
| UI5 | **Scene picking narrow phase** | (a) CPU: `SpatialIndex` on an interactive layer, then bounds or a lazy mesh triangle BVH; (b) GPU ID buffer; (c) GPU ray query on the TLAS | **(a) by default**: no latency, no readback, zero cost for objects without listeners. Benchmark (b) and (c) in stage 4. Offer them only for skinned, displaced or alpha-tested objects, opt in per object, with their one frame of delay stated. |
| UI6 | **How UI controls get input** | (a) controls read engine actions directly; (b) controls consume abstract `UiCommand`s and pointers; `engine-ui` maps the `ui` action group to them | **(b)**: keeps `ui` free of the engine, and makes testing with synthetic commands trivial. |
| UI7 | **Action system before the UI** | (a) all of D55 to D87 first; (b) the subset the UI needs (actions, groups, bindings as settings, contexts with text capture, players, repeat, chords, tap-and-hold) as stage 0 | **(b)**. |
| UI8 | **Element storage** | (a) an object per element with its own fields; (b) `final` facades over structure-of-arrays slots with generations (D45 style); (c) a pure ECS | **(b)**. |
| UI9 | **Text shaping** | (a) HarfBuzz through `lwjgl-harfbuzz`; (b) our own shaper (months of work for complex scripts); (c) our own shaper for simple scripts first, HarfBuzz later | **(a)**: every script from the start, at no implementation cost, and allowed as an LWJGL binding. A small Java fallback stays for headless tests only. |
| UI10 | **Fonts and outlines** | (a) FreeType through `lwjgl-freetype`; (b) stb_truetype; (c) our own OpenType reader | **(a)**: CFF2, variable fonts, colour bitmaps and hinting for small raster text. (b) has no hinting or CFF2. (c) is about 2.5 thousand lines for no gain. |
| UI11 | **MSDF generation** | (a) `lwjgl-msdfgen` at run time on job threads; (b) build-time atlases only; (c) both, with one code path and one cache; (d) our own Java generator | **(c)**: dynamic glyphs (CJK, user text) at run time, and pre-baked shipped sets through a `tools` task, so the first frame does no work. (d) is not needed. |
| UI12 | **World-space panels** | (a) render to texture; (b) direct in 3D; (c) both, chosen per panel | **(c)**: a real trade-off between sharpness and VR legibility on one side and cost, lighting and sorting on the other. |
| UI13 | **HDR composition** | (a) the UI in its own image, composited after the tone curve at paper white; (b) the UI drawn into scene colour before the output transform | **(a)**: (b) tone-maps the UI. Needs the presenter's composition hook (frame-graph-v2 §4.8). |
| UI14 | **Layout engine** | (a) our own (stack, flex, grid, border, anchors, overlay, virtualizing); (b) Yoga (`lwjgl-yoga`) for flex, ours for the rest | **(a)**, with Yoga as the conformance oracle in tests. **Benchmark both** (full and incremental relayout, 2,000-element HUD, 500-row form). Offer Yoga flex only if it clearly wins: the native tree has to be mirrored, and text measuring needs upcalls from C. |
| UI15 | **Batching** | (a) one uber-pipeline: vertex pulling, bindless textures, clipping in the shader (about one draw per layer); (b) a pipeline per primitive kind | **Benchmark both in stage 1**. Keep one unless they trade off on the target GPUs. Expected: (a). |
| UI16 | **Small text** | (a) MTSDF only; (b) MTSDF plus FreeType hinted raster glyphs below a size threshold | Decide after the stage 1 visual check and benchmark. If (b), the threshold is a setting. |
| UI17 | **Property builder customisation** | (a) a fixed generator; (b) a pipeline of replaceable steps, where styles choose everything presentational and code only adds new editor kinds | **(b)**: the user's rule. Priority, highest first: instance code, styles, annotations, type defaults. |
| UI18 | **Collection editors** | (a) one list editor; (b) registered kinds (table, foldouts, master-detail, chips, compact, map) with operations (add with a factory or type chooser, remove, reorder, duplicate, clear, context menu), validation, undo hooks and virtualization, all selected and configured by style, with custom editors by code | **(b)**. |
| UI19 | **Property metadata generation** | (a) an annotation processor at build time; (b) cached reflection on first use; (c) both | **(c)**: (a) by default, like the Slang material records; (b) as the fallback for mods and scripts. Keep only (b) if the benchmark shows no meaningful difference. |
| UI20 | **Record write-back** | (a) generated lenses (`with` copies) into a versioned holder; (b) mutable mirror objects | **(a)**. |
| UI21 | **Focus per player** | (a) one global focus; (b) a focus scope per local player | **(b)**. |
| UI22 | **Pointer sources** | (a) pointers from actions only; (b) buttons and XR poses through `ui.pointer` actions; positions and touch fingers read from devices directly (D56) | **(b)**: actions do not model one instance per finger. |
| UI23 | **Chart decimation** | (a) min-max envelope; (b) LTTB; (c) both, chosen per series | **(c)**, with a min-max pyramid for zoom and streaming. |
| UI24 | **Where chart decimation runs** | (a) CPU; (b) GPU compute; (c) both | **Benchmark at 1M to 10M points** in stage 11. Expect the CPU with a pyramid to be enough. |
| UI25 | **Repository name** | (a) `ui`; (b) `scene-ui`; (c) `common-ui` | **(a)**: `true-sim/ui` with `ui-*` modules. (b) suggests it is part of scene; (c) suggests a leaf library. |
| UI26 | **Gizmo depth** | (a) always on top; (b) depth tested; (c) on top, with the occluded parts faded | **(c)** by default. It is a style value. |
| UI27 | **Accessibility** | (a) a semantics tree now, the OS bridge later; (b) everything later | **(a)**. The OS bridge (Windows UI Automation first, written ourselves through FFM, or AccessKit's C library as an explicit dependency decision then) comes in stage 15. |
| UI28 | **Console log sink** | (a) an SLF4J sink in `engine-ui` feeding `ui-console`'s ring through an interface; (b) a logging facade of our own | **(a)**. |
| UI29 | **Gamepad accept and cancel** | (a) south always accepts; (b) a setting following the platform convention | **(b)**, as binding defaults per `GamepadType`. |
| UI30 | **Versioning base** | (a) `common-state` as is, plus `VersionedTree` and `VersionedRingBuffer`; (b) a UI copy of Lemur's classes | **(a)**: it already has versions per element, change logs, `Derived`, `Watch` and one clock per world. |
| UI31 | **Where the LWJGL text code lives** | (a) an optional `ui-text-native` module in the `ui` repository (the other `ui` modules stay JDK-only); (b) in `engine-ui` | **(a)**: all text behaviour and its goldens in one repository and CI; tools and servers can lay out text without the engine. (b) keeps `ui` JDK-only, but splits text tests across repositories. |

## 10. Input: the `ui` action group

Stage 0 builds the action system subset (UI7) by D55 to D87:
- typed action constants in groups (D57) forming a tree with switches (D67, D68);
- bindings as settings (D58), sampled at frame start (D59);
- edges per clock and player (D60);
- a context stack with priority and text capture (D61);
- glyphs by the last device (D62), players (D63), XR actions as the device layer (D64);
- action types (D75), tap-and-hold (D70), chords (D69), filters (D82 to D86) including **repeat** (initial delay and
  rate as settings).

**One file**, `engine-ui/.../UiActions.java`:

```java
public final class UiActions {
    public static final ActionGroup UI = ActionGroup.root("ui", "input.group.ui");
    public static final ActionGroup NAVIGATION = UI.child("navigation");
    public static final ActionGroup POINTER = UI.child("pointer");
    public static final ActionGroup SCROLL = UI.child("scroll");
    public static final ActionGroup TEXT = UI.child("text");
    public static final ActionGroup TABS = UI.child("tabs");
    public static final ActionGroup CHARTS = UI.child("charts");
    public static final ActionGroup GIZMO = UI.child("gizmo");
    public static final ActionGroup DOCKING = UI.child("docking");
    public static final ActionGroup CONSOLE = UI.child("console");

    public static final BinaryAction UP = NAVIGATION.binary("up").repeat();
    // ... (table below)

    /** Every group and action, with default bindings for each device kind. */
    public static final ActionSet ALL = ActionSet.of(UI);
}
```

**Registration**: the `Ui` plugin registers `UiActions.ALL` once (D34, a plugin brings its whole slice), so a game
does nothing. Customisation:
- `app.defaults(UiActions.ACCEPT, Bindings.of(Key.SPACE, GamepadButton.SOUTH))` replaces game defaults (D6's
  game-default layer);
- `input.group(UiActions.GIZMO).enabled(false)` switches off a sub-group;
- players rebind through the generated settings screen (bindings are settings).

The binding table below uses gamepad positions (south, east, west, north) so the layout follows the pad (UI29).

| Action (type) | Keyboard and mouse | Gamepad | Touch | XR controller |
|---|---|---|---|---|
| **ui.navigation** | | | | |
| up/down/left/right (Binary, repeat) | arrows | d-pad, left stick (threshold) | — | thumbstick (threshold) |
| next/previous (Binary, repeat) | Tab / Shift+Tab | — | — | — |
| pageUp/pageDown (Binary, repeat) | PgUp/PgDn | LT/RT (threshold) | — | — |
| home/end (Binary) | Home/End | — | — | — |
| accept (Binary) | Enter, Space | south | tap (pointer) | trigger (on focused) |
| cancel (Binary) | Escape | east | system back | B / secondary |
| contextMenu (Binary) | Menu key, Shift+F10 | west | long press | grip-tap |
| menu (Binary) | — | start | — | menu |
| **ui.pointer** | | | | |
| position (Axis2) | mouse | virtual cursor (right stick, optional) | finger (direct, UI22) | — |
| ray (Pose) | — | — | — | aim pose (each hand) |
| primary/secondary/middle (Binary) | LMB/RMB/MMB | south / west (virtual cursor) | finger down | trigger / grip / thumbstick click |
| dragCopy, dragLink (Binary modifiers) | Ctrl / Alt (held) | — | — | grip held |
| **ui.scroll** | | | | |
| scroll (Axis2) | wheel (x and y) | right stick | two-finger pan | thumbstick y |
| **ui.text** | | | | |
| selectAll, copy, cut, paste | Ctrl+A/C/X/V (Cmd on macOS) | — | long-press menu | — |
| undo, redo | Ctrl+Z, Ctrl+Y / Ctrl+Shift+Z | — | — | — |
| deleteWordBack/Forward | Ctrl+Backspace / Ctrl+Delete | — | — | — |
| wordLeft/Right, lineStart/End, docStart/End | Ctrl+←/→, Home/End, Ctrl+Home/End | — | — | — |
| extendSelection (modifier) | Shift (held, combined with the moves) | — | handles | — |
| submit, newline | Enter, Shift+Enter (multi-line) | south | keyboard's done key | virtual keyboard |
| **ui.tabs** | | | | |
| nextTab/previousTab | Ctrl+Tab / Ctrl+Shift+Tab, Ctrl+PgDn/PgUp | RB / LB | swipe | — |
| closeTab | Ctrl+W | — | — | — |
| **ui.charts** | | | | |
| zoom (Axis1) | wheel | right stick y | pinch | thumbstick y |
| pan (Binary, drag) | MMB drag, LMB drag | left stick | one-finger drag | trigger drag |
| boxSelect / boxZoom (modifiers) | Shift+LMB drag / RMB drag | — | — | — |
| resetView (Binary) | double click, Home | north | double tap | — |
| **ui.gizmo** | | | | |
| translate/rotate/scale/universal (Binary) | W / E / R / T | d-pad up / right / down / left (when the gizmo is focused) | toolbar | — |
| bounds (Binary) | B | — | toolbar | — |
| nextMode (Binary: translate, rotate, scale, bounds, universal) | — | — | toolbar | right A |
| toggleSpace / previousSpace (Binary) | X / Shift+X | — | toolbar | left X / — |
| nextPivot / previousPivot (Binary: median, individual, active, cursor, bounds centre) | . / Shift+. | — | toolbar | left Y / — |
| snap (Binary, held) | Ctrl (held) | LT held | toolbar toggle | thumbstick pressed (either hand) |
| cancelDrag (Binary) | Escape, RMB | east | — | B |
| grabLeft / grabRight (Binary, held; both: two-handed scale and turn) | — | — | — | left / right grip |
| handLeft / handRight (Pose: where a hand grabs from) | — | — | — | grip pose (each hand) |
| **ui.docking** | | | | |
| dockModifier (Binary, held: no docking while held) | Shift (held while dragging) | — | — | — |
| nextWindow (Binary) | Ctrl+F6 | — | — | — |
| **ui.console** | | | | |
| toggle (Binary) | ` (grave) | left and right stick clicked (chord, D69) | four-finger tap | menu held (tap-and-hold, D70) |
| complete, historyUp/Down | Tab, ↑/↓ | — | — | — |

- Text editing actions apply only while the text context is on top (D61). Keys typed as text never fire game actions.
- The gizmo's keys: B is free in every `ui` group (bounds); period is Blender's pivot key, Shift with X and period goes
  backwards. The d-pad switches gizmo modes only while the game says a gizmo is focused (`UiCommands.gizmo`); then it
  moves no focus (the left stick and arrows still do), otherwise it navigates and switches nothing.
- Shortcut labels in menus show the bound glyph of the last device used (D62).

---

## 11. Performance targets

All measured in `engine-bench` (JMH and GPU timestamps) on the reference machines, steady state, p99 unless noted.

| Target | Budget |
|---|---|
| Steady-state allocation (UI update, picking, drawing) | **0 bytes per frame** (allocation test as in pitfalls.md) |
| Full HUD (500 elements, 5,000 glyphs) with nothing changed: CPU | **≤ 0.05 ms** (version checks only, no upload) |
| Full HUD with 10% of elements changing per frame (counters, bars): CPU | **≤ 0.3 ms** |
| Full HUD: GPU at 4K on a mid-range GPU | **≤ 0.15 ms** |
| 100,000 glyphs on screen (a console or log flood): GPU | **≤ 0.4 ms** |
| `ListView` of 10,000 rows, scrolling continuously: CPU per frame | **≤ 0.1 ms**; memory O(visible rows) |
| `ListView` of 1,000,000 rows, sorted and filtered view, 100 changes per frame | **≤ 0.5 ms** |
| `TableView` of 100,000 rows × 20 columns, scrolling | **≤ 0.3 ms** CPU |
| Picking per pointer per frame, 100,000-node scene, 1% interactive, mixed static and dynamic | **≤ 20 µs** (bounds), **≤ 50 µs** (mesh narrow phase on the hit candidates) |
| Picking cost of a node that is not pickable | **0** beyond the index's box test (no narrow phase) |
| Screen UI hit test per pointer | **≤ 5 µs** |
| Line chart: 1,000,000 points streaming at 1,000 points per frame, 4K width: CPU (decimation) | **≤ 0.5 ms**; GPU **≤ 0.2 ms** |
| Line chart: 10,000,000 points, zoom and pan at frame rate | **≤ 1 ms** CPU (pyramid) |
| Style hot reload of a 2,000-rule sheet: re-resolve and redraw | **≤ 5 ms** once (not per frame) |
| Text reshape of one 200-character label (HarfBuzz, including itemisation and line breaking) | **≤ 50 µs** |
| Input-to-photon latency | **No added frame**: UI records in the same frame as the input sample; no throttled picking |

---

## 12. Testing

- **Unit tests in the `ui` repository, without a GPU**:
  - layout (fixtures for flex, grid and anchors, including Yoga (lwjgl-yoga) as a test oracle for flex);
  - style resolution (precedence, states, property selectors, reload);
  - versioned binding (both directions, lenses);
  - virtualization (row ↔ offset, recycling counts);
  - event routing (synthetic pointers: capture, bubble, pass-through, multi-pointer, drag and drop across UI and
    scene);
  - focus navigation (spatial and explicit, scopes per player);
  - text layout with the JDK fallback shaper (HarfBuzz and FreeType goldens run in `ui-text-native`) (line breaking, BiDi runs, caret positions);
  - chart decimation against brute force;
  - gizmo math (drag solving, snapping, pivots, in doubles far from the origin);
  - console parsing and completion;
  - the property builder pipeline (every step replaced in a test).
- **Draw-list goldens**: recorded primitive lists compared as data (exact, cheap).
- **Image goldens in the engine**: offscreen renders of a control gallery, text samples (Latin, Arabic, Devanagari,
  CJK, emoji fallback), SDF shapes at several scales, HDR composition at several paper-white values. Compared with a
  tolerance metric (as for the renderer's tests).
- **Allocation tests**: steady-state frames of the HUD, list scrolling, picking and chart streaming must allocate
  nothing.
- **Benchmarks**: section 11 targets as JMH and GPU-timer benchmarks. Competing techniques (UI5, UI11, UI14, UI15,
  UI16, UI19, UI24) are benchmarked side by side before choosing.
- **Input**: scripted action and pointer playback (no devices); XR with recorded poses.
- **Accessibility**: the semantics tree checked for roles and names on every control in the gallery.

---

## 13. Build plan

**Rules for every stage:**
- Stage groups follow "CI once per group": one branch and PR per repository per group, and CI and release once at the
  end of the group.
- PRs merge in dependency order (common-* → scene → ui → engine). Each release is tagged only after a verified merge,
  on that PR's own merge commit, and the next repository moves to the released version before its CI.
- Every stage's done criteria include these items, which are not repeated below:
  - **all presentation comes from styles, and a test theme (`test-theme.tss`, deliberately garish) restyles every new
    element**;
  - **steady-state frames allocate nothing**;
  - **tunables are settings**;
  - **no added latency**;
  - **its benchmarks are recorded in engine-bench**.

### Group A1: foundation (stages 0 to 3)

PRs:
- `common-state`: `VersionedTree`, `VersionedRingBuffer`;
- `ui`: the new repository with build, CI and publish workflows, then `ui-core`, `ui-style`, `ui-layout`, `ui-text`,
  `ui-text-native`, `ui-testkit`;
- `engine`: `engine-input`, `engine-ui`, the presenter's UI composition hook, the IME rectangle in `engine-platform`.

**Stage 0: input actions** (engine `engine-input`, `engine-ui/UiActions`) — **Built** (engine-input, PR 386; `UiActions` and the `UiCommand` mapping in engine-ui)
- Scope:
  - the subset of D55 to D87: actions, groups with switches, bindings as settings, contexts with text capture,
    players, repeat and threshold filters, tap-and-hold, chords;
  - `UiActions` with defaults for keyboard and mouse, gamepad, touch and XR (section 10);
  - the `UiCommand` mapping.
- Decisions: UI6, UI7, UI29.
- Tests: binding resolution (the most specific wins), edges per clock, context capture, repeat timing, saved bindings
  round trip, allocation.
- Done: a test application rebinds `ui.navigation.accept` through settings; text capture blocks game actions; a game
  switches `ui.gizmo` off in one call.

**Stage 1: rendering core and text** — **Built** (see Build status) (ui: draw-list format, `ui-text`, `ui-text-native`; engine: `engine-ui`
renderer)
- Scope:
  - primitives: SDF box (four radii, border, shadow, gradients), nine-slice, image, line, arc;
  - FreeType fonts, HarfBuzz shaping, msdfgen MTSDF atlases on job threads plus the build-time atlas task, SVG icons
    into the atlas;
  - itemisation, BiDi and line breaking;
  - the uber-pipeline with vertex pulling, bindless textures and the clip table, against the per-kind alternative;
  - the `UI` image and its composition after the tone curve at paper white;
  - dirty-range uploads.
- Decisions: UI9, UI10, UI11, UI13, UI15, UI16, UI31.
- Tests:
  - draw-list goldens (data);
  - image goldens: shapes at several scales, text in Latin, Arabic, Devanagari, CJK and emoji fallback, SDR and HDR at
    three paper-white values;
  - shaping goldens against HarfBuzz's own test strings;
  - GPU timings.
- Done:
  - a static HUD mock-up meets the GPU target;
  - an unchanged frame uploads 0 bytes;
  - HDR white measures at `display.paperWhite`;
  - the build-time and run-time atlases are byte-identical for the same glyphs.

**Stage 2: elements, layout, Java styles, binding, value renderers, animation** — **Partly built** (see Build status) (ui: `ui-core`, `ui-layout`,
`ui-style` Java side; common-state)
- Scope:
  - `UiStore` and `Element` facades;
  - layouts (stack, flex, grid, border, anchors, overlay), with the Yoga oracle in tests and the own-vs-Yoga
    benchmark;
  - `StyleKey`, `StyleSheet` Java builders, selectors with states and attribute facts, the resolution cache with
    versions;
  - component stacks as decorations;
  - binding, lenses, converters, validators;
  - the `ValueRenderer` and `ValueEditor` registry, with kinds and recycling;
  - tweens, effect channels with fast-forward, transitions;
  - the semantics tree.
- Decisions: UI8, UI14, UI27, UI30.
- Tests:
  - layout fixtures, and random trees checked against Yoga;
  - style precedence (Lemur's documented order plus states);
  - cache invalidation by version;
  - binding in both directions;
  - effect channel replacement and reversal.
- Done: the HUD rebuilt from elements meets the CPU targets (0.05 ms idle, 0.3 ms with 10% changing).

**Stage 3: the `.tss` language and the default theme** — **Built** (see Build status) (ui: `ui-style`)
- Scope:
  - lexer, parser and evaluator: variables, maps, nesting with `&`, mixins with arguments, `@use` partials, `@extend`
    and placeholders, `@if` and `@each`, functions, unit arithmetic, colour functions;
  - the compiler to `StyleSheet`, with typed checks through `StyleKey` types;
  - errors with file, line, column, rule and include trace;
  - live tokens and token sets (light, dark, high contrast);
  - hot reload through a development file watch, and D25 when it exists;
  - the default theme as `.tss`.
- Decisions: UI1.
- Tests:
  - parser and evaluator fixtures, with expected errors;
  - Java-built and `.tss`-built sheets compared for equality;
  - a token switch re-resolves only the dependent styles;
  - reload keeps the last good sheet;
  - reload benchmark (2,000 rules in ≤ 5 ms).
- Done: a designer restyles the HUD live from a file, and switching light and dark at run time costs one
  re-resolution.

### Group A2: interaction (stage 4)

PRs:
- `ui`: `ui-core` events and focus, `ui-scene`;
- `scene`: only if the component stores of D46 are not there yet (the `Interactive` component needs a store);
- `engine`: pointer sources, world panels, GPU picking benchmarks.

**Stage 4: pointers, picking, events, focus, world panels**
- Scope:
  - pointer sources (mouse, touch fingers, pen, XR rays, virtual cursor);
  - the screen hit grid; ray tests against world panels;
  - `SpatialIndex` on an interactive layer; the `Interactive` component with the full interaction set; the mesh
    triangle BVH;
  - routing (capture, bubble, pass-through, pointer capture), click counts, long press, context menus, tooltip
    triggers;
  - drag sessions across UI and scene;
  - focus scopes per player, spatial and explicit navigation, modal scopes;
  - world panels by render to texture and direct;
  - benchmarks for the GPU ID buffer and the TLAS ray query against the CPU path.
- Decisions: UI4, UI5, UI12, UI21, UI22.
- Tests:
  - multi-pointer routing scripts;
  - drag from a list onto a 3D node and back;
  - focus navigation goldens;
  - picking at 100,000 nodes;
  - zero cost for nodes without `Interactive` (counted visits);
  - XR recorded poses.
- Done: in one demo scene, 3D objects, a VR world panel and the HUD respond to hover, click, double click, drag and
  drop, context menus, tooltips and the wheel through one listener API, within the picking targets.

### Group B: controls (stages 5 to 8)

PRs:
- `ui`: `ui-controls`, theme additions;
- `engine`: clipboard and IME glue, dock windows if multi-viewport is included.

**Stage 5: basic controls**
- Scope: label with rich spans, image, icon, button, toggle, checkbox, radio, switch, slider, drag value, progress,
  spinner, separator, badge.
- Tests: behaviour per input kind (keyboard, gamepad, pointer, touch, XR), gallery goldens, semantics roles.
- Done: the control gallery works with every input kind.

**Stage 6: text input**
- Scope:
  - text field, password, text area, number field, search;
  - the editing engine: undo, clipboard, IME composition, BiDi caret, grapheme-aware deletion.
- Tests: editing scripts, IME event sequences, RTL editing, password never reaching the clipboard, shaping benchmarks.
- Done: Arabic, Hindi and Japanese input and display work.

**Stage 7: collections**
- Scope:
  - combo and drop-down;
  - `ListBox`, `ListView`, `TreeView`, `TableView`;
  - the virtualizing layout (fixed heights and Fenwick heights);
  - sorted, filtered and grouped views fed from change logs;
  - selection models with box select.
- Tests: virtualized against non-virtualized output, incremental sort and filter against full re-sorting, benchmarks
  at 10,000 and 1,000,000 rows.
- Done: the list and table targets are met with zero allocation while scrolling.

**Stage 8: containers and windows**
- Scope:
  - scroll pane (kinetic), split pane, tabs, expander, group box;
  - window, dialog, pop-up, tooltip, context menu, menu bar, toast;
  - colour picker (HDR), gradient and curve editors;
  - dock space with its layout saved as data;
  - drag and drop polish.
- Tests: modal focus trapping, menu navigation by gamepad, docking save and restore, colour conversions.
- Done: an editor-like docked layout works in 2D and as VR world panels.

### Group C: charts (stages 9 to 11)

PRs: `common-state` (only if the ring buffer needs changes), `ui` (`ui-charts`), `engine` (chart shaders).

**Stage 9: chart core**
- Scope:
  - axes: any number, linear, log, symlog, time, category; linked ranges;
  - grid, ticks, legend;
  - line and scatter series with GPU lines and markers;
  - crosshair, nearest point, tooltips, zoom, pan, box zoom, auto-fit.
- Tests: tick generation, axis transforms, nearest-point queries, line goldens (joins, anti-aliasing, dashes).
- Done: two linked charts, each with three Y axes, interact at frame rate.

**Stage 10: chart types**
- Scope:
  - area and stacked area, shaded ranges;
  - bars (grouped, stacked, horizontal);
  - step and digital;
  - histograms 1D and 2D, heatmaps;
  - pie and donut, candlestick/OHLC, error bars, stems;
  - infinite lines, annotations, images, subplots.
- Tests: goldens and hit tests per type.
- Done: an ImPlot-parity demo page.

**Stage 11: real time and interaction**
- Scope:
  - ring buffers, the min-max pyramid, LTTB, follow mode;
  - drag points, lines and rects; query ranges; legend toggles; selectable axes; box select;
  - the CPU-vs-GPU decimation benchmark.
- Decisions: UI23, UI24.
- Tests: decimation exactness against brute force, streaming benchmarks.
- Done: the 1M- and 10M-point targets are met.

### Group D: gizmos (stage 12)

PRs: `ui` (`ui-gizmo`), `engine` (gizmo drawing in 3D views).

**Stage 12: 3D gizmos**
- Scope:
  - translate, rotate, scale, bounds and universal handles;
  - spaces (local, world, parent, view) and pivots;
  - snapping and numeric entry;
  - undo hooks;
  - the view cube;
  - XR grab and two-handed manipulation;
  - occlusion fade.
- Decisions: UI26.
- Tests: drag solving in doubles at 10⁷ m from the origin, snapping, pivots for several objects, cancel restoring the
  start, XR recorded poses.
- Done: the editor demo moves nodes with the mouse and with XR controllers, with undo; gizmo colours, sizes and
  shapes come from the theme.

### Group E: tooling (stages 13 to 15)

PRs:
- `ui`: `ui-props`, `ui-props-processor`, `ui-console`, the immediate façade;
- `engine`: generated inspectors for materials, nodes and settings; the SLF4J sink; the accessibility bridge;
- `build-logic`: wiring for the annotation processor, if needed.

**Stage 13: property builder and inspectors, including collections** — **Built in the ui** (A3 props; the engine's
inspectors left)
- Scope:
  - the metadata model, merged from the Slang-generated material records, `Setting`s and annotations;
  - the annotation processor, with the reflection fallback;
  - lenses;
  - the builder pipeline with replaceable steps; editors chosen by style; layout strategies (form, grid, inspector
    with foldouts, compact);
  - **collection editors** (table, foldouts, master-detail, chips, compact, map), with operations (add with a factory
    or type chooser, remove, reorder, duplicate, clear, context menu), validation, undo events and virtualization, all
    chosen and configured by style, with custom editors by code;
  - inspectors for nodes, materials and settings (the settings screen).
- Decisions: UI17, UI18, UI19, UI20.
- Tests:
  - pipeline steps replaced one by one;
  - a theme alone switches a `List<Waypoint>` from table to master-detail and hides "duplicate";
  - sealed-type add chooser;
  - min, max and unique validation;
  - edits on nested records in lists produce the expected new root through lenses;
  - undo round trips;
  - processor vs reflection benchmark.
- Done: a material, a node with nested list components, and the settings are edited live with no hand-written UI.

**Stage 14: developer console**
- Scope:
  - commands with typed arguments, completion and hints, history;
  - settings as variables;
  - the SLF4J log ring with filters and search;
  - 2D and VR presentation.
- Decisions: UI28.
- Tests: parser and completion fixtures, a log flood benchmark (100,000 lines), settings round trips.
- Done: `render.bloom PHYSICAL` works with completion, and a log flood stays within budget.

**Stage 15: immediate façade, debug overlays, accessibility, localisation**
- Scope:
  - the `Imm` façade over retained elements;
  - a UI debugger (tree, resolved styles with their source rule and line, timings);
  - debug overlays;
  - the OS accessibility bridge;
  - pseudo-localisation;
  - text scale and contrast themes.
- Decisions: UI3, UI27.
- Tests: façade state kept by id, screen-reader smoke tests, pseudo-localised gallery goldens.
- Done: tools written with the façade render through the same pipeline, and the gallery is readable by Windows
  Narrator.

### Plan additions from the user (2026-11-03)

The order of the areas below does not matter to the user; independent areas may be built in parallel on their own
branches once the A2 groundwork (picking, events, focus, layers) is in, which they all share. Every area keeps the
rules: everything stylable from `.tss`, tunables as settings, nothing allocated per frame, tests, screenshots; still
one PR per repository per group, CI and release once per group.

- **Text editing** (A2 pulled forward: focus, keyboard routing through `UiCommand`s, the IME rectangle in
  `engine-platform`, the clipboard through the engine's `Clipboard`): single-line text fields (caret, selection by
  pointer and keys incl. word and line, copy/cut/paste, undo/redo, IME preedit inline and the candidate window placed
  by the IME rectangle, BiDi caret movement, scrolling within the field, placeholder, maximum length, filters and
  validation, read-only) and password fields (masking, reveal toggle, no copy, no IME); multi-line text areas (word
  wrap, virtualized scrolling for 1 MB of text, tabs, optional line numbers, soft and hard breaks); rich text (spans
  with colour, weight, italic, size, underline/strike, highlight, links that can run console commands, inline images
  and icons, all by `.tss` span classes; an inline markup to author it; editable in text areas); colour emoji
  (bundled Noto Color Emoji, OFL; ZWJ sequences, skin tones, flags, variation selectors; emoticon and shortcode
  replacement as an optional input filter behind a setting).
- **Overlays, hovers, windows, docking**: a layer stack per view (HUD, overlays, pop-ups, tooltips, modal, debug)
  with z-order, input blocking or passthrough and dimming backdrops; overlays anchored to elements, 3D points or scene
  nodes, kept on screen and flipped; hover, pressed, focus and disabled states everywhere; tooltips (delay, follow or
  anchored, rich content, long-press and focus equivalents), hover cards and popovers with hover intent; floating
  windows (move, resize by edges and corners, minimise, maximise, close, title bar, modal and modeless, z-order and
  focus on click, snapping, remembered positions, keyboard and gamepad move/resize, also in 3D/VR panels); docking
  (left/right/top/bottom/centre, tab groups with drag between and reorder, split panes with splitters, drop previews,
  undocking to floating windows, nesting, a serialisable stylable layout description, optionally separate OS
  windows).
- **Container controls** (emphasised): tabbed panes (strips on any side, scrolling or wrapping, closable and
  reorderable tabs, overflow menu), collapsibles (headers, accordions single or multi open, animated by style
  transitions), group boxes and fieldsets, expanders, split panes outside docking, cards, carousels and pagers,
  steppers and wizards, sidebars and drawers, scroll views with sticky headers.
- **Basic controls**: buttons (plain, icon, toggle), checkboxes, radio groups, switches, segmented controls, labels
  with icons, images, separators, progress bars, spinners, sliders (range, vertical), number fields with steppers and
  drag-to-change, colour picker, date and time pickers, search box, tag and chip input, key-binding capture (for the
  input system's rebinding), drop-downs with search, message and confirm dialogs, toasts, badges, menu bars, context
  and pop-up menus with submenus, toolbars, status bars, breadcrumbs, scroll panes with stylable scrollbars (touch
  momentum, gamepad).
- **Data controls**: virtualized list boxes (single and multi select), tree views (lazy, drag and drop), tables and
  data grids (sort, filter, resizable, reorderable and frozen columns, inline editing, virtualized).
- **Also added**: a file browser with open and save dialogs (an engine-agnostic file model; native OS dialogs maybe
  later); a minimap or picture-in-picture camera view control (a second view rendered into a UI image).
- **Left out for now**: the node-graph editor.

## 14. Risks and open questions

- The presenter's `UI` key and the composition in the output transform are planned (frame-graph-v2 §4.8) but not
  built. Stage 1 needs at least the composition hook.
- LWJGL 3.4.3's FreeType, HarfBuzz, msdfgen and stb modules are not yet in any true-sim build. `ui-text-native` adds them; check native packaging per platform in stage 1, and confirm the API of LWJGL's msdfgen binding for loading FreeType outlines.
- D25 (asset hot reload) is built late. `.tss` hot reload may need a development-only file watch first.
- The mesh triangle BVH for picking needs the mesh data on the CPU after upload. Where meshes are streamed to the GPU
  only, the CPU copy is a memory cost (keep it only for interactive meshes).
- Skinned meshes have no CPU-side current pose. Precise picking on them is the GPU options' case (UI11).
- The action system is a sizeable piece (stage 0). Its full scope (D55 to D87) beyond the UI's subset is not planned
  here.

## Decisions made with the user (2026-11-03)

- **UI1 Styling:** both, one model: our own Sass-like `.tss` and typed Java builders, compiled into the same typed,
  versioned style sheet; the default theme is a `.tss` file. Everything presentational is stylable (controls, generated
  property layouts, collection editors and their operations, charts, gizmos, console, states, transitions).
- **UI2 Dependencies:** `ui` depends only on scene and common-* (plus LWJGL bindings in `ui-text-native`); the engine
  depends on `ui` and implements its interfaces.
- **UI3:** retained, with an immediate-mode façade.
- **UI4:** one event model for UI elements and plain scene objects (full interaction set on any object).
- **UI5:** CPU picking by default; GPU ID buffer and ray query benchmarked, opt-in for skinned objects only.
- **UI6:** controls consume abstract `UiCommand`s produced by the engine's `ui` action group.
- **UI7 (changed):** the whole input action system (D55–D87) is built first, before the UI stages.
- **UI8:** elements are facades over packed arrays.
- **UI25:** repository `true-sim/ui`.
- **UI26:** gizmos on top, hidden parts faded.
- **UI27:** semantics tree from the start; OS accessibility bridge later.
- **UI28:** console log as an SLF4J sink.
- **UI29:** gamepad accept/cancel is a setting following the platform.
- **The technical decisions** (UI9–UI24 except UI16, UI30, UI31) follow the recommendations; where they name a
  benchmark, the standing rule applies (one clear winner stays; a trade-off keeps both behind a setting).
- **UI16 small text (2026-11-03):** both techniques behind a setting, hinted raster below 13 px the default
  (section 6.3).
- **Rules that apply throughout:** no external libraries beyond the JDK, true-sim's repositories and LWJGL's bindings;
  tunables are settings; only correct techniques are kept; never add latency; nothing allocated per frame.

## Build status

### OS accessibility bridge (2026-11-05): local branches `leftovers-a11y` (ui) and `ui-leftovers-a11y` (engine)

Windows UI Automation (UI27), so Narrator and other screen readers read and drive the controls. Two new modules:
`ui-a11y` (platform-independent) and `ui-a11y-windows` (COM through FFM; nothing outside the JDK).

| Part | Built | Where |
|---|---|---|
| Values | `AccessibleValue` (text, read only, `set` on the UI thread, password) kept per slot (`UiStore.accessibleValue`, `Element.accessibleValue`): text editors give their text (never a password's: empty, with `IsPassword`) and take a new one unless read only; sliders each thumb's number (set snapped and clamped as a drag is); progress bars their fraction | ui-core, `TextEditor`, `Slider`, `ProgressBar` |
| Snapshot | `AccessibleTree`: the `Semantics` tree of the shown layers (hidden elements and what is under them left out) under a window node, numbered depth first: role, name, element id, state, actions, value, box in UI pixels; elements found by slot and generation (their identity across snapshots), the node at a point, the focused node (the nearest node at or above player 0's focus) | ui-a11y |
| Bridge | `AccessibilityBridge.update()` on the UI thread each frame: runs what assistive technology asked from its threads (`request`: invoke and toggle as a click, focus, set value; refused when gone, disabled, read only or not a number), then, while a client listens or asked since the last frame, takes a new snapshot when the store's version or the focus moved and tells `Events` the focus, name, value, toggle state, enabled and structure changes. Nobody listening: two comparisons, no allocation | ui-a11y |
| Provider | `WindowsAccessibility.attach(hwnd, bridge)` subclasses the window's procedure (`SetWindowLongPtrW`, `CallWindowProcW` for the rest, restored on `close` or `WM_NCDESTROY`) and answers `WM_GETOBJECT` (`UiaRootObjectId`) with `UiaReturnRawElementProvider`. `UiaElement`: a COM object per element identity, its interfaces cells of a vtable of upcall stubs and the object's id: `IRawElementProviderSimple` (server side; control type, name, automation id, framework `true-sim`, focus, focusable, enabled, password, dialog, a localized type for roles without a control type; patterns), `IRawElementProviderFragment` (navigation, runtime id `[UiaAppendRuntimeId, slot, generation]`, screen boxes from `ClientToScreen` and `scale`, focus), `IRawElementProviderFragmentRoot` (hit test, focus; the root's name and box from the window's host provider), `IInvokeProvider` (activatable nodes that do not toggle), `IToggleProvider` (checkboxes, toggle buttons, switches), `IValueProvider` (nodes with a value). Reference counted; an element that went answers `UIA_E_ELEMENTNOTAVAILABLE`, and its object goes with its last reference. `update()` raises `UIA_AutomationFocusChangedEventId` and property-changed events (name, value, toggle state, enabled) while `UiaClientsAreListening` | ui-a11y-windows |
| Roles | `ControlTypes`: every `Role` to a control type (toggle buttons and switches are buttons with the Toggle pattern; dialogs windows; scroll views and tab panels panes; rows data items; cells and role-less interactive elements Custom with a localized type) | ui-a11y-windows |
| Setting | `ui.a11y.bridge` (`on`/`off`, `AccessibilityTunables`, a `TunableSource`: an engine setting with the rest); off withdraws the provider (`UiaDisconnectProvider`) | ui-a11y |
| Engine | `Window.nativeWindow()` (the `HWND` from `SDL_PROP_WINDOW_WIN32_HWND_POINTER`, 0 under other drivers); `UiPlugin` attaches on Windows with a window, updates it after each UI update (scale: the window's pixels over the view's), closes it on stop (`UiPlugin.accessibility()`) | engine-platform, engine-ui |

**Decisions**

- *Our own COM through FFM*, not AccessKit's C library: no dependency outside the JDK; about 1,100 lines.
- *Snapshots, not live reads*: UI Automation calls on its own threads; the UI is read only on its thread, into an
  immutable snapshot, and only while a client listens or asked (no cost for players without assistive technology).
  Requests are queued to the next frame's update: no lock, no wait on the UI thread, at most a frame for the reader.
- *Free-threaded provider* (no `ProviderOptions_UseComThreading`): every method reads the volatile snapshot.
- *When the window's thread is the UI's* (it is in the engine), the first `WM_GETOBJECT` is answered from a snapshot
  taken then; otherwise a reader may see an empty window for a frame.
- *Invoke and toggle are a click* (`Interaction.dispatch(CLICK)`), so a control behaves as for a pointer or ACCEPT.
- *A password's text is never exposed*, even while revealed.
- *Coverage*: `ui-a11y` is held to 0.8 everywhere; `ui-a11y-windows` on Windows only (CI's Linux cannot run COM, as
  engine-xr's headset code cannot run there), where its tests reach 0.87 of lines and 0.85 of branches.

**Tests**: `AccessibilityBridgeTest` (ui-a11y, every platform; a rig of a text, buttons, a disabled button, a
checkbox, a field, a password, a slider and a progress bar): the snapshot's roles, names, ids, values, read-only and
password flags, links, boxes, hit tests, hidden elements; requests run on the next frame, and refused ones; events
for focus, name, value, toggle, enabled and structure; a frame with nobody listening, and one listening with nothing
changed, allocation-free. `WindowsAccessibilityTest` (Windows): a hidden Win32 window pumped on a UI thread and an
`IUIAutomation` client on the test's thread, as a screen reader is one: names, control types, automation ids, the
framework, enabled, focusable, password, values, toggle states, patterns only where they belong, runtime ids, boxes;
invoke, toggle, set value (refused when read only), focus set by the client and read back, a name changing live, an
element that went; the setting hiding the UI; an idle frame allocation-free. `ProviderCallsTest` (Windows): the
vtables called directly, as UI Automation calls them: the root (host provider, navigation, focus), hit tests in
screen pixels, unknown interfaces, patterns and properties, read-only and disabled refusals, elements that went,
reference counts. Engine: `PlatformTest` (an `HWND` under the Windows driver only), `UiPluginTest` (the setting
registered).

**Manual check with Narrator** (not automatable here: Narrator's speech and its own navigation). Run an engine app
with a window (the UI gallery) on Windows, start Narrator (Ctrl+Win+Enter) and check:

- Tab through the controls: each is announced with its name and type ("OK, button", "Agree, check box, not
  checked", "Name, edit, abc", "Volume, slider, 0.4"); a password field is announced as one, without its text.
- Caps Lock+Enter (Narrator's primary action) on a button clicks it, on a checkbox toggles it, and the new state is
  announced; a value set in a field by typing is read back.
- Caps Lock+arrows (scan mode) walk the tree in a sensible order (layers bottom to top, controls in tree order).
- Narrator's focus rectangle lands on the controls at their places, at 100 % and 150 % display scaling, and on a
  second monitor.
- With `ui.a11y.bridge` off, Narrator sees only the window.

**Left**: no SelectionItem, ExpandCollapse, RangeValue, Scroll, Text or Grid patterns (radios, tabs and list items
are invoked; sliders give their value as text; fields have no caret or selection for the reader); no
structure-changed or live-region events (alerts, toasts); no `IsOffscreen` from scroll clipping (only hidden
elements are left out); one window (docking's OS windows are not exposed); player 0's focus only; no macOS
(NSAccessibility) or Linux (AT-SPI) bridge (`ui-a11y` is their shared half).

### Scene picking out of the UI (2026-11-04): local branch `picking` (scene, ui, engine)

Picking is scene data now: scene-core's `Pickable`, `Selected` and `Hovered` components and `ScenePicker` (the narrow
phase and `PickMesh` moved there from ui-scene; `PickMeshes` from engine-ui to scene-render), and the engine's scene
interaction service drives it each frame for every pointer. `ScenePicking` routes the picker's hits into its elements:
`new ScenePicking(ui, picker)` (or `(ui, graph, index)`), the interactive layer is gone (`pickLayers(mask)` filters
pick layers instead), `Interactive.mesh` takes any `PickGeometry`, and a pickable node routes to its nearest
interactive ancestor. Behaviour kept: pick order, routing, pass-through, capture, hover, clicks, long presses, context
menus, tooltips, drag and drop.
### Profiler (2026-11-04): local branches `profiler` (ui and engine), not yet in CI

The in-game profiler's views as the new module `ui-profiler` (directory `profiler`, package `com.truesim.ui.profiler`,
over `ui-charts`); the engine feeds them (engine `README.md`, "Profiler"). The engine's branch needs this release
(its `ui` dependencies ask for 0.5.0).

| Part | Built | Where |
|---|---|---|
| Data | `Recording`: lanes (`LaneKind.THREAD`, `GPU`, `MARKERS`) of zones in start order (`open`/`close`, `zone`, nesting by depth; `seek` finds what shows from a time), numbered frames (CPU start, submit, wait, the GPU's span added when it arrives, hitches), counters (time, value); rings that grow to a capacity and then keep the newest, `retain(nanos)`, a version per `commit()`; allocation-free once grown | `Recording`, `LaneKind` |
| Frame graph | `FrameTimes` (fixed rings: CPU, wait, interval, GPU under its own frame's number, hitches; min / mean / p99 by quickselect) and `FrameGraphView` (a `chart.frame-graph`: lines `cpu`, `gpu`, `wait`, `frame`, `budget` and `hitch` lines, a statistics label every `ui.profiler.statsInterval`, the Y range from the p99s and budgets) | `FrameTimes`, `FrameGraphView` |
| Detailed view | `ProfilerView`: toolbar (Freeze, Step, Freeze on hitch, Live, Export, status), `TimelineView` (lanes under a ruler, zoom to `ui.profiler.minSpan`, pan, span selection, frame selection on the ruler, zone selection, runs narrower than a pixel merged, at most `ui.profiler.maxZones` boxes, names shaped once per name), in tabs `FlameGraphView` (`FlameTree`: paths merged over the span, click to zoom) and `ZoneTableView` (`ZoneStats`: calls, total, self, mean, p99, max; heap sort, no allocation), `CounterView` (a chart per counter group) | `ProfilerView`, `TimelineView`, `FlameGraphView`, `ZoneTableView`, `CounterView`, `FlameTree`, `ZoneStats` |
| Files | `ProfileFile` (`.tsprof`, the recording as it is), `ChromeTrace` (trace event JSON: threads named and sorted, complete events, frame instants, counters; opens in Perfetto) | `ProfileFile`, `ChromeTrace` |
| Styles | `ProfilerStyles` in ui-controls (`row-height`, `lane-gap`, `gutter-width`, `label-min-width`), `_profiler.tss`, `_profiler-test.tss` | ui-controls, ui-style |
| Tunables | `ProfilerTunables` (`ui.profiler.statsInterval`, `liveSpan`, `zoomStep`, `minSpan`, `maxZones`, `maxNodes`, `graphHeadroom`) | `ProfilerTunables` |
| Gallery | `ProfilerGallery` (test fixture): 240 engine-like frames (main thread phases, three job threads, audio, streaming, GPU graphics and compute, GC and JIT markers, a hitch at frame 200, counters), the frame graph over the profiler; `pose` selects a zone and a frame | `profiler/src/testFixtures` |

**Decisions** (taste where marked)

- *Zones are kept in start order*, opened when they begin and closed when they end, so a time is found by a binary
  search plus a walk back to its top-level zone; children never need sorting.
- *The view owns no engine types*: `Recording` is the model the engine writes; the views poll its version.
- *GPU times are drawn under their own frame's number* when they arrive (a second X column), never waited for.
- *Interval* is the time since the previous frame's start (what a player feels); CPU is start to submit.
- *Taste*: palettes and colours, F-key bindings (engine), the 2 x mean + 4 ms hitch rule (engine settings), tabs for
  flame graph / zones / counters under the timeline.

**Tests**: `RecordingTest` (rings, seek, retain, frames, zone statistics against brute force, the flame tree, the
own format round trip, Chrome JSON parsed strictly, frame statistics against sorting, units),
`ProfilerViewTest` (the gallery in both themes: `LayoutSanity`, named, every part drawn, the test theme restyling;
zoom, pan, selection, ruler, reset; sorting, flame focus; streaming 3,000 frames into the views: at most 10 of 3,000
frames allocate, the frame graph alone allocation-free), `ProfilerInteractionTest` (toolbar actions, scrolling lanes,
the zoom limit, span selection and double click, zones past the cap merged, the table's columns and rows, hovering,
counters per group, an empty recording, edges). Branch coverage 0.8.

**Streaming charts** (ui-charts, for the frame graph, any following chart): lines on the first following linear X
axis that draw each sample (no fill, steps, markers or dashes; at most 32 gaps in view) and vertical infinite lines
there are drawn by a `chart-stream` element in the plot. Their vertices are written once, at the axis's mapping when
the stream was anchored, into a block of each line's own; new samples append, and as the axis follows, the element's
drawn offset moves (one transform table entry): the plot, the hidden or shown axes' layout and the overlay record
nothing again (a shown X axis's ticks still move, their labels reused by text). A new anchor is taken when the span,
a Y range, the plot's place or the styles change. The frame graph view's CPU per frame went from 25 to 45 us to
7.9 us (engine `docs/benchmarks-profiler.md`); the chart benchmarks stay as they were (1M samples streaming, drawn as
envelopes, do not stream: 0.37 ms frame median). Tested by `StreamTest` (only the stream records; every vertex draws
where the axes map its sample, after appends, a Y change, a pause and a resume; allocation-free).

**Left**: zone labels are names only (durations show in the status line).

### Group A3 (2026-11-04): merged, local branches `a3-remaining` (ui) and `ui-rest` (engine), not yet in CI

The seven areas below were built in parallel on their own branches from `a3-remaining` (b7aed4b: the new
`UiCommand`s and `UiInput.mnemonic()`), then merged, with the engine's parts built on the merge. Builds:
`-Ptruesim.common-state=../common-state-tree -Ptruesim.scene=../scene-a3-scene -Ptruesim.common-math=../common-math-au`
(ui-scene needs the scene branch `a3-scene-raycast`: `SpatialIndex.raycast` with a `RayFilter`; release scene first).

#### Merge decisions

- *Chart style keys in ui-controls* (`com.truesim.ui.controls.charts.ChartStyles` and its enums), as the props' keys
  are: the shipped themes style charts, so every module that compiles a theme must know the keys without ui-charts.
- *One service for modules above the controls*: `TunableSource` (charts, scene, gizmo); the scene branch's identical
  `TunableHolder` went.
- *Triangles on the transform table*: the charts' `TRIANGLE` primitive keeps its corners in words 9 to 14, recorded
  like its box and baked with it (`DrawList.baked`); the gizmos draw their filled shapes with it.
- The file dialog is opaque (the theme's 0.86 surface showed the browser's text through it on the GPU).
- `PropertyPanel` no longer rebuilds the root before an edit through a setter (it wrote the old value back into the
  edited object; found by the engine's settings screen).

#### Engine (`ui-rest`: `ui-rest-render`, `ui-rest-input`, `ui-rest-integ`)

| Part | Built |
|---|---|
| Renderer | The transform table and dirty ranges (`GpuUiRenderer`, `draw.slang`); `TRIANGLE` in the uber and per-kind paths (anti-aliased flagged edges, a half-open rule on the others: no seams); world panels drawn directly (`worldVertexMain`/`worldFragmentMain`) or into a texture (`WorldPanelRenderer`) |
| Input | Every new command bound (`ui.docking.windowMove` Alt+F7, `windowResize` Alt+F8, tabs, `CHART_RESET`, gizmos, console toggle without typing the grave; completion and history only while the console's line has the text, `UiCommands.console`), mnemonics and pointer modifiers (`UiKeyboard`, `Pointer.GIZMO_SNAP`), touch, pen, XR-ray and virtual-cursor pointers |
| Eyedropper | `GpuScreenSampler`: a block of scene colour before the display transform (HDR kept), read back without waiting; the game hands `UiPlugin.screenSampler()` to its `Controls` |
| Scene | `UiPlugin.scene(graph, index)`: picking through `ScenePicking` with the camera of the frame (no delay), `PickMeshes`, ~~`DepthOcclusion` for gizmos (a level of the depth pyramid, a frame or more old)~~ gizmo occlusion by the depth of the same frame (group A3, occl), `GpuCameraView` (the minimap: a view of the same graph into a bound texture, the same frame), `GpuPicking` (ID buffer: 0.007 ms GPU, a frame late; the CPU path stays the default) |
| Console | `UiPlugin.console(...)`: `ConsoleLogProvider` (SLF4J 2), `SettingVariables` over the engine's settings with the constraint overlay's reasons, `FileHistoryStore`, `FolderScripts`, `FolderAssets`, `NamedNodes`, `SettingPermissions` |
| Inspectors | `SettingsSource`/`SettingsScreen` (the generated settings screen), `MaterialParametersSource`; nodes wait for the component stores (D46) |
| Settings | Every A3 tunable a setting (`UiPlugin.tunables(ui)`, the console's included) |
| Benchmarks | Module `engine-bench` (`./gradlew :engine-bench:uiBench`) writes engine `docs/benchmarks-ui.md` with the section 11 targets |
| Screenshots | Every gallery in both themes (`GalleryScreenshots`), the settings screen, world panels, the minimap, gizmo occlusion |

#### Left after the merge

- Every row of engine `docs/benchmarks-ui.md` is within budget on a quiet machine (2026-11-04, after the follow-ups
  below: chart lines and areas on the GPU 0.13 and 0.17 ms, 100,000 glyphs over a backdrop 0.31 ms, the console flood
  0.13 ms p50, 0.21 ms p99; engine-bench `-Ponly=console` runs one part).
- Not built: ~~the OS accessibility bridge (UI27, assessed in the tools section)~~ (built: Windows UI Automation,
  section "OS accessibility bridge (2026-11-05)"), VR multiview for direct world panels
  and XR gizmos drawn in 3D, the TLAS ray-query pick, node inspectors (no D46 stores), pen pressure and tilt (no fields
  in `Pointer`), a full spelling dictionary (only a self-written sample ships: no word list may be downloaded), the
  loupe of touch selection handles, rotated chart text; world panels drawn directly do not draw paths and columns.
- `CameraView` asks for the size it recorded the frame before (one frame late on a resize).

#### Follow-ups decided by the lead (2026-11-04)

- Engine rebased onto origin/main (window options v0.175.0, technique registry v0.176.0; `SettingVariables` maps
  technique slots to choices). ui and scene had not moved.
- Gizmo occlusion is exact in the same frame (section "occl"); every gizmo operation is a rebindable action
  ("gizmoact"); chart legends never cover data and the plot's background lies under the grid ("chartfix"); the
  benchmark misses are fixed ("perf").
- Overlays (menus, drop-downs, pop-overs, hover cards, tooltips, dialogs) are opaque in both themes (`--overlay`,
  the raised surface made opaque); panels keep the 0.86 surface.
- A dialog's buttons follow the platform: the fact `platform` on `dialog-buttons` (`Controls.platform`, the system's
  by default); Windows and Linux [Open][Cancel] (`row-reverse` in the theme), macOS [Cancel][Open]. Focus order stays
  the tree's (cancel, then accept).
- Spelling offers corrections two edits away only for words of `ui.spell.twoEditsFrom` (6) letters or more.
- Kept: mnemonics underlined while Alt is held, the 11-pixel loupe, 8 atlas pages (`ui.text.atlas.maxPages`), the
  chart palette and colormap, the gizmo look and 4-deep scene picking, the props and console defaults, box select
  from a row; world panels default to the texture mode (`WorldPanelRenderer.add(panel)`); OS windows stay top-level.

### Group A3, gizmoact (2026-11-04): local branch a3-gizmoact

Every gizmo operation as a rebindable action of `ui.gizmo`. Engine branch `ui-rest-gizmoact` (from `ui-rest`).

**Built**

| Item | What | Where |
|---|---|---|
| Commands | `GIZMO_BOUNDS`, `GIZMO_NEXT_MODE`, `GIZMO_PREVIOUS_SPACE`, `GIZMO_NEXT_PIVOT`, `GIZMO_PREVIOUS_PIVOT` appended to the gizmo line; `Gizmo` handles them (ignored while dragging, as every switch); `GizmoMode.next`, `GizmoSpace.previous`, `GizmoPivot.next/previous` | ui-core `UiCommand`, ui-gizmo |
| Actions | `ui.gizmo.bounds` B, `nextMode` (no key), `previousSpace` Shift+X, `nextPivot` period, `previousPivot` Shift+period (not while typing); the mode actions on the d-pad, taken only while a gizmo is focused (`UiCommands.gizmo(BooleanSupplier)`: then the d-pad moves no focus; otherwise it navigates and the switches give nothing) | engine-ui `UiActions`, `UiCommands` |
| XR | `grabLeft`/`grabRight` (grips; both held: two-handed) and the poses `handLeft`/`handRight` (grip poses) drive `GizmoGrab` through `XrGizmoGrab` (`UiPlugin.gizmoGrab`: each frame after the UI's input; `cancelDrag` cancels, the grip must be let go before it grabs again); `snap` on either thumbstick (grabs and, through `XrPointers`' modifiers, ray drags); right A next mode, left X next space, left Y next pivot (`XrPointers.defaults`) | engine-ui `XrGizmoGrab`, `XrPointers`, `UiPlugin` |

**Decisions**

- *B and period*: B is unused by every `ui` group (bounds); period is the pivot key of Blender's pivot menu; Shift goes
  backwards (Shift+X, Shift+period), as Shift+Tab does.
- *The d-pad by focus, not by a context*: a pushed context covering `ui.gizmo` would also take Escape, east and LT
  from navigation; `UiCommands` instead drops the focus move while a focused gizmo's switch is held, and the switch
  pressed with a focus move while none is focused.
- *XR snap on the thumbsticks*, not the grip (now the grab) nor the trigger (the ray's click while grabbing).
- *No pad bindings for bounds, spaces, pivots*: the four d-pad directions keep the design's four modes; the face
  buttons are navigation's.

**Tests**: `GizmoCommandsTest` (bounds by command and a face drag, the next mode round, pivots both ways moving the
gizmo, the space backwards, switches ignored while dragging); engine `UiCommandsTest` (each new key, rebinding,
typing blocks them, the d-pad by focus without repeats) and `XrGizmoGrabTest` (recorded grip poses: one hand follows,
two scale with a thumbstick snap, cancel, rebinding the grab to the trigger, a lost hand lets go, allocation free).

**Left**: pad bindings for bounds, spaces and pivots (taste); the XR `cancelDrag` B (its game default is the
confirm setting's).

### Group A3, chartfix (2026-11-04): local branch a3-chartfix

Legends that never cover the data, and the plot's background under the grid where the renderer can clear it. Engine
branch `ui-rest-chartfix` (from `ui-rest`).

| Part | Built | Where |
|---|---|---|
| Legend positions | `LegendPosition` gains `BEST` (`legend-position: best`: in the plot, at the first of top right, top left, bottom left, bottom right, right, left, bottom, top with the least data under the box, as matplotlib's `'best'`; probe points `ui.chart.legendProbe` pixels apart over the box, each covered when a shown series `contains` it or has a sample within reach (`nearest`); picked again when data, ranges, size or entries change) and `OUTSIDE_TOP`, `OUTSIDE_BOTTOM`, `OUTSIDE_LEFT`, `OUTSIDE_RIGHT` (the plot gives up the box's room plus its margins: above the plot between the title and top axes, below the bottom axes, beside the outermost Y axes; rows wrap at the chart's width; beside the plot a column). The legend is measured before the chart is arranged and placed after it, in the same frame (`legendMeasure`, `legendPlace`); its box is public (`legendLeft/Top/Right/Bottom`) | `Chart`, `LegendPosition`, `ChartTunables` |
| Themes | default: `legend-position: outside-top`, entries in a row without a box, centred over the plot under the title; test: `outside-right` (its garish box) | `_charts.tss`, `_charts-test.tss` |
| Plot background | `plot-background` (a colour on `chart`, `ChartStyles.PLOT_BACKGROUND`): the chart draws it first, under its grid (background, grid, data, as charts look), its edges on whole pixels. The default theme moves its `rgba(#0d1117, 0.35)` from `chart-plot`'s background (drawn over the grid) to it, and its grid's alphas from 0.16 / 0.06 to 0.11 / 0.04 (what showed through the old background); the test theme `#200020`, with a square bordered `chart-plot` | `Chart`, `ChartStyles`, themes |
| Engine | `GpuUiRenderer`: the boxes drawn right after a folded backdrop (or first of all) that are square, borderless, unclipped, without a gradient and on whole pixels of the image, opaque or translucent over the clear alone (inside the backdrop's corner and border band, over no box cleared before) are cleared as rectangles (`vkCmdClearAttachments`, `PassContext.clear`, `Commands.clearAttachments`) to what drawing them leaves; `clearedBoxes()` names them; `foldBackdrop(false)` draws them as before | engine-ui, engine-graph, engine-vulkan |

#### Decisions

- *Outside the plot by default*: `best` still covers data in charts whose data fill every corner (oscillating lines,
  scatter, stacked areas: about half the gallery); a row above the plot never does, costs nothing per frame and never
  jumps. `best` stays for legends inside the plot.
- *The plot's background clears*: drawn under the grid and first after the folded panel, the 3790 x 950 translucent
  box is cleared to its colour over the panel's instead of blended (the streaming chart's GPU time 0.219 to about
  0.17 ms p50, its line chart 0.187 to about 0.135). An opaque colour in the theme, drawn, was measured too: no
  faster (0.231 ms); without a plot background at all 0.145.

#### Tests

`LegendTest` (best moves away from a rising, then a falling line, never over data; falls back to the least covered
place; each outside position takes room and never meets the plot, entries still toggle), `ChartsGalleryTest` (no
legend box over a sample or shape of its chart in either theme, plain and posed: with the old `top-right` it fails
on "Linked charts, three Y axes"), engine `UiRendererTest.boxesDrawnFirstOverAFoldedPanelAreClearedWithTheSamePixels`
(a chart's panel, plot background, an opaque box: cleared, the image equal to drawing them; not cleared over the
panel's corners, over a box cleared before, off whole pixels, rounded; cleared without a backdrop). GPU screenshots
`final/ui-charts/charts-*.png`.

#### Benchmarks

engine-bench `uiBench` (RX 9070 XT, quiet machine, three runs after): the streaming chart's filled area on the GPU
0.2192 / 0.2241 ms (p50 / p99) before, 0.166-0.174 / 0.169-0.177 after; its line 0.1866 / 0.1884 before,
0.132-0.139 / 0.133-0.140 after. CPU rows unchanged within their run-to-run noise.

#### Left

- `best` places by samples, not segments: a steep line between two far samples may pass under it unseen.

### Group A3, occl (2026-11-04): local branch a3-occl

Gizmo occlusion exact in the same frame: the UI's primitives can carry a depth, and the renderer fades them per
pixel against the scene depth it draws over. Engine branch `ui-rest-occl` (from `ui-rest`).

| Part | Built | Where |
|---|---|---|
| Depth tested primitives | `Primitive.FLAG_DEPTH` (flag 128, header bit 15) on a solid `BOX`, a `LINE` or a `TRIANGLE`; words 7-8 (colour B, unused by these kinds otherwise) hold its view depth (distance along the view's forward axis): word 7 the depth at the first corner (box: its one depth; line: its first end; triangle: a) as a float whose low 8 mantissa bits are the occluded opacity as unorm8 (`Primitive.depthWord`: 15 mantissa bits left, 3e-5 relative); word 8 a line's second end's depth (float), a triangle's `halves(zb / za - 1, zc / za - 1)`, a box's 0. Recorded by `DrawList.depthBox`, `depthLine`, `depthTriangle`; transforms and clip offsets move and scale them, never their depth (`baked()` no longer fades words 7-8 of these); `DrawList.depthAt` is the CPU reference of the shader's interpolation (reciprocals under perspective, depths under an orthographic view; a line clamped to its segment, a triangle by barycentric weights past its edges) | `Primitive`, `DrawList` |
| Gizmo | `Gizmo.depthTest(true)`: axes, rings (but their back halves, faded on the CPU as before), arrow tips, plane squares, scale cubes and bounds handles recorded depth tested with the theme's `gizmo-occluded` opacity; its `GizmoOcclusion` is then not asked. `GizmoOcclusion`/`RayOcclusion` stay for views the renderer has no depth for (camera views), headless and tests. Trackball and centre discs are not faded (as before) | `Gizmo` |
| Previews and dumps | `DrawListDump` writes `depth=` and `occluded=` for these; the controls' `CpuPreview` draws triangles and fades depth tested primitives by a `SceneDepth`; `ScenePreviewTest` adds `scene-gallery-depth*.png` (the gallery's gizmos depth tested, faded by the depth of its boxes ray cast per pixel) | `testkit`, `controls` tests |
| Engine | `draw.slang` (uber and box, line, triangle entry points): `depthTest` reads `Keys.SCENE_DEPTH` of the same frame (`GpuUiRenderer` reads it with `g.optional`, after transparency), the image pixel scaled to the depth's render region (dynamic resolution included), reverse depth to view depth by `GpuUiRenderer.depthCamera` (near / depth, or (1 - depth) x range orthographic), faded where farther than the scene by more than `ui.gizmo.depthBias` (1e-4 relative); the plugin sets the camera from its scene bridge's; `UiScene.update` follows the camera again (the game may move it in its update). `DepthOcclusion` and `ui.gizmo.depthLevel` removed | engine-ui |

#### Decisions

- *Depth in colour B's words* rather than a per-primitive plane or new words: lines, triangles and boxes of a gizmo
  never use colour B, a triangle has no other free word, and the format change stays two words and one flag. The
  occluded opacity rides in the depth's lowest mantissa bits (a triangle has no ninth word).
- *View depth, not the device's depth*: the UI records what its `SceneView` knows; the renderer turns its depth into
  the same quantity with the camera's projection, so the UI needs no knowledge of reverse or infinite depth.
- *Opt-in per gizmo*: the renderer can test only against the view it draws the UI over; a gizmo in a camera view
  keeps the CPU occlusion.

#### Tests

`DrawListTest` (packing, `depthAt` under both projections, `baked`, allocation), `GizmoMoreTest` (depth tested
handles: every kind, the theme's opacity, depths at their ends, no CPU occlusion asked), engine
`GizmoOcclusionGpuTest` (a box moved in front of the X arrow: faded to 0.28 exactly in the frame it moved in, to two
pixels of its silhouette, and whole again the frame it moved away), the shader constants test. Screenshot
`integ/gizmo-depth-occlusion.png`.

#### Left

- Depth tested primitives on world panels (no depth bound there) and in XR views; a gradient box cannot be depth
  tested (its colour B is the gradient's).

### Group A3, perf (2026-11-04): local branches `a3-perf` (ui) and `ui-rest-perf` (engine)

The benchmark rows that missed (engine `docs/benchmarks-ui.md`).

| Part | Built | Where |
|---|---|---|
| Lines and envelopes over vertices | `Primitive.PATH` and `COLUMNS` draw a range of the list's vertex table (`DrawList.VERTEX_FLOATS` 4: a path's x, y, length; a column's first, last, lowest, highest y), expanded on the GPU: joins (miter with a limit, bevel, round), caps, dashes by length (4 entries), each pixel drawn once at joins; columns stroked as before or filled a quad per 4 columns. Streaming rewrites only changed vertices (compare before write). The streaming chart went from 7,529 primitives (13,216 filled) to 2 or 3 | ui-core, `Canvas`, `LineSeries`, `line-join` miter/bevel, `line-miter-limit`; engine `draw.slang` |
| Folded backdrop | A full-image box at the bottom of a layer is the pass's clear (its corners and border drawn along the edges): 100,000 glyphs over a backdrop 0.46 to 0.31 ms | engine `GpuUiRenderer` |
| `ShapeCache` | Every `TextSystem` shapes through a cache of printable Latin runs (`ui.text.shapeCache`, 4,096 runs; 0 off), two generations (a full one ages, runs shown again move up: no frame where everything shapes again); a generation's turnover deletes the dying slots with a backward shift (see the note below) | ui-text |
| Log rows | A row's time is two labels, `log-time` (seconds) and `log-millis`, in `log-clock`: both repeat, so the shape cache finds them; a flooded frame no longer shapes a new time per row (0.17 to 0.13 ms p50) | ui-console `LogView`, `_console*.tss` |
| Timing | engine-bench times CPU frames back to back and GPU frames in a run of their own (a GPU wait between UI frames inflated CPU times; engine `docs/pitfalls.md`) | engine `bench` |

*Lesson (CI, 2026-11-04):* an open-addressing table whose entries die by generation must not leave dead slots in
probe chains when lookups stop at them: the first version of the two-generation `ShapeCache` treated a slot of a dead
generation as empty, so live runs placed after it in the chain were not found (shaped again and kept twice). It passed
on Windows and failed on Linux because the slot layout depends on `System.identityHashCode` of the face. Fixed by
deleting the dying generation's slots at the turnover with backward-shift deletion (amortized constant, nothing
allocated); `ShapeCacheTest` forces the collision (`homeBits(0)`: every run's home slot the same) and checks random
runs over few home slots against a model of the generations. The other open-addressing tables of the UI were checked:
`LongIntMap` (text, style) and the console's logger table never delete, `WordSet` rebuilds on removal, `Imm` keeps
tombstones and rebuilds when they and live ids fill half the table, and the glyph atlas marks evicted glyphs instead
of removing them.

### Group A3, render (2026-11-04): local branch a3-render

The draw list's transform table and dirty ranges, CSS's automatic minimum size of flex items, SVG icons and atlas
page eviction. Engine branch `ui-rest-render` (engine-ui's renderer and shader).

#### Built

| Part | Built | Where |
|---|---|---|
| Automatic minimum size | A flex item whose main-axis minimum is auto and which does not clip its content (`Layout.clipsContent`, set from the `clip` style) shrinks no further than its min-content size (measured under an at-most constraint of 0: text its longest word, a container its items' minimums), or its specified size if smaller, within its maximum, never below its padding; a basis below it (`flex: 1`) grows from it. An explicit minimum wins; clipping and scrolling items keep 0. Measured only when an item is to be smaller than its hypothetical size, and kept per item until its layout changes. A wrapping container is never narrower than its widest item | `ui-layout` `FlexLayout`, `Layout`, `Ui.apply` |
| Transform table | Each primitive's word 15 (`Primitive.TRANSFORM`, was reserved) names an entry of its list's transform table (`DrawList.TRANSFORM_FLOATS` 8 floats, 32 bytes: scale, offset x, y, opacity, four reserved; entry 0 the identity). A primitive draws at `scale * (p + clip offset) + offset`, its sizes times the scale, its colours' alpha times the opacity. An element that composes opacity, scale or a drawn offset over its subtree (`Visuals.composes`: a set override or a style value other than 1) owns an entry its subtree shares; an animation rewrites that entry (`DrawList.setTransform`, compared) and no primitive. The first change that makes an element an owner records its subtree once; it stays an owner until its layer is rebuilt | `DrawList`, `Primitive`, `Ui`, `Visuals.TransformListener` |
| Clips follow transforms | A clip names the entry of its owner (`CLIP_FLOATS` 8: the transform index at float 7, `setClipTransform`): its rectangle and radius are drawn by it, so a scaled or moved subtree is cut where it draws; an ancestor's cut drawn by another entry is mapped into the clip's space when clips move | `DrawList`, `Ui.clipBox` |
| Reading back | `DrawList.drawnX/Y`, `drawnScale`, `drawnOpacity`, `drawnClip`, and `baked()`: a copy with every transform and clip offset applied (CPU previews, `LayoutSanity`, `DrawListDump`, `SoftwareGlyphRenderer`). `LayoutSanity` transforms clipping ancestors' boxes too. Picking still reads the layout (drawn offsets and scales are not seen by it, as before) | `DrawList`, `ui-testkit`, `ui-text` |
| Dirty ranges | `DirtyRanges`: a few sorted, disjoint ranges per table (primitives and transform entries), merged within `ui.draw.dirtyMergeGap` (16 primitives) and at most `ui.draw.dirtyRanges` (16; past it the closest two merge); `dirtyFrom/To` stay the span. The engine uploads each range apart | `ui-core` `DirtyRanges`, `DrawList`, engine `GpuUiRenderer` |
| Engine | `ui/primitive.slang`: `TRANSFORM` (was `RESERVED`), `CLIP_FLOATS`, `TRANSFORM_FLOATS` (checked against `Primitive` and `DrawList`); `ui/draw.slang`: the push constants carry the layer's transform table (`transforms`, `clipFloats` gone), vertices placed by the entry, coverage worked out in the primitive's own coordinates with distances scaled to pixels (box, shadow, glyph range, weight and outline, line width), colour times opacity; entry 0 needs no memory read. `GpuUiRenderer` keeps a transform buffer per layer and uploads every dirty range of primitives and entries | engine `ui` |
| SVG icons | `SvgIcon` parses an icon's filled shapes (`path` with every command, arcs as cubics; `rect` with radii, `circle`, `ellipse`, `polygon`, `polyline`; `g`; every `transform`) into a `GlyphOutline` in ems; `IconFace` makes them glyphs of the text atlas (MTSDF, generated, packed and evicted like text, never cached on disk). `UiImages.addSvg`, `loadSvg`, or `icons/<name>.svg` found on the classpath when a style names `icons/<name>` (a registered image or built-in icon of the name stays a texture); the UI draws them as glyph primitives tinted by the style's image colour, fitted like images and measured at their size. Our own parser: lwjgl-nanovg is not in the local repositories and downloading is not allowed | `ui-text` `SvgIcon`, `IconFace`, `controls` `UiImages`, `Ui` |
| Atlas page eviction | At most `ui.text.atlas.maxPages` (8) MTSDF and raster pages: a new page past it clears the least recently used page (by the last frame a glyph of it was looked up or installed) that no draw list draws (`GlyphAtlas.pageGuard`, the UI's scan of its lists, run only then) and that has no glyph generating; with none, the atlas grows past the budget. Evicted glyphs keep their entries as `EVICTED`; `generation()` grows, text looks its glyphs up again, which queues them again. Colour pages are not evicted | `GlyphAtlas`, `AtlasSettings`, `TextBlock`, `Ui` |
| Workarounds removed | The test theme's fixed heights of date cells and badges (labels no longer shrink under their text). Kept, as real sizing: the gallery's minimum-size panels and the test theme's step numbers (fixed-size boxes whose text would overflow anyway) | `_basic-test.tss` |

#### The draw-list format change (for branches that record primitives)

- Word 15 of a primitive is its transform index (`Primitive.TRANSFORM`); `DrawList.begin` resets it to 0
  (`useTransform` sets it). Code that records with `DrawList`'s methods needs no change.
- `DrawList.CLIP_FLOATS` is 8 (was 7): float 7 is the clip's transform index. Code reading `clips()` by
  `CLIP_FLOATS` keeps working; code reading positions should use `drawnX/Y`/`drawnClip` or `baked()`.
- The UI no longer fades colours by opacity nor bakes scales and offsets: `Painter.paint`'s `opacity` is always 1
  (the entry fades what a painter records), `TextBlock.record` gets the unfaded colour.
- `DrawList.transform(scale, x, y)` still bakes a transform while recording (the UI uses it for the clip's content
  space only).

#### Decisions

- *An entry per composing element, shared by its subtree*, not one per element: a fading window rewrites one entry;
  elements that never animate stay on entry 0. Becoming an owner records the subtree once (its primitives name the
  new entry); the entry stays until the layer is rebuilt, so a hover that fades in and out records once.
- *Opacity in the entry*, multiplied in the shader after coverage (premultiplied output), identical to fading the
  colours (goldens, which print primitives as they draw, are unchanged by it).
- *Scroll before transform*: the clip offset is added to a primitive's own coordinates, the entry applied after, so a
  scaled panel's scroll pane scrolls by scaled pixels (it moved by unscaled ones before).
- *The automatic minimum only where it can matter*: measured lazily when an item is to shrink below its hypothetical
  size, cached until its layout changes; Yoga's semantics are `min-width: 0`, which the oracle now sets.
- *An SVG parser of our own* (filled shapes, no strokes or CSS) rather than NanoSVG, which would need a download.
- *Eviction never drops a drawn glyph*: the page guard asks the UI's draw lists; past the budget with every page
  drawn, the atlas grows rather than flicker.

#### Tests

ui: `DirtyRangesTest` (merging, the most ranges, random coverage, allocation-free); `DrawListTest`
(`primitivesDrawByTheirTransformEntryWhichAnAnimationRewritesAlone`, `theTransformTableGrows`,
`changesFarApartAreSeparateDirtyRanges`); `AnimatorTest.aScaledClippingPanelClipsItsContentWhereItDraws` (an
animation dirties no primitive and no clip, the clip scales and moves with its owner, `LayoutSanity` sane) and the
transform test reading drawn values; `FlexLayoutTest.itemsShrinkNoFurtherThanTheirMinContentUnlessTheyClipOrHaveAMinimum`,
`textShrinksToItsLongestWordAndARowToItsItemsMinimums`, `aWrappingContainerIsNeverNarrowerThanItsWidestItem`;
`SizingTest` and `YogaOracleTest` adapted (1,304 of 2,000 trees, 9,255 elements compared, as before);
`SvgIconTest` (view box, every path command, shapes and transforms, sizes, errors, a ring's distance field);
`GlyphAtlasTest.pastItsPageBudgetTheLeastRecentlyUsedPageNoTextDrawsIsEvicted`,
`iconsAreGlyphsOfTheirFaceAndNeverCached`; `SvgIconsTest` (three self-written SVG resources drawn as tinted glyphs,
fitted, measured; files, code, missing names). Engine: `UiRendererTest.aScaledAndMovedSubtreeDrawsAndClipsWhereItsTransformEntryPutsIt`
(GPU read-back and screenshot `transformed-clipped.png`), `changesFarApartUploadApart`, an animated entry in the
allocation test; the constants test covers `TRANSFORM_FLOATS`.

Goldens regenerated: the basic gallery (both themes): the spinner and progress bar animate by entries, so the
gallery settles (no element records) a few frames earlier and their phase differs; in the test theme, labels in
fixed-size cells keep their height and centre a pixel or two higher. Draw-list dumps print primitives as they draw
(`baked()`), so other goldens did not change.

#### Benchmarks (Ryzen 9 9950X3D, RX 9070 XT, without JaCoCo; other agents were building: noisy)

| Case | Before | After |
|---|---|---|
| HUD (506 elements, 4,896 glyphs), idle, CPU | 0.0001 ms p99 | 0.0001 ms p99 |
| HUD, 10% changing, CPU (`HudBenchmarkTest`) | 0.24 to 0.25 ms median, 0.28 to 0.36 p99 | 0.27 ms median, 0.33 to 0.39 p99 |
| HUD, four cards fading (animated opacity), CPU | 0.025 ms median, 323,892 bytes a frame | 0.0002 ms median, 128 bytes a frame |
| HUD, two counters far apart changing | 412,603 bytes a frame | 503 bytes a frame |
| Renderer submit, 50 of 500 elements changing (`UiBenchmark`) | 8.5 us, 408,256 bytes a frame | 5.3 us, 292,096 bytes a frame |
| HUD at 4K, GPU, adaptive | 0.103 ms | 0.104 ms |
| 100,000 glyphs, GPU, adaptive: alone / over a translucent backdrop | 0.303 / 0.451 ms | 0.307 / 0.461 ms |
| Layout, HUD 500 elements 10% changing (`LayoutBenchmark`) | 50.6 us p50 | 55.5 us p50 |
| Layout, form 500 rows, 10% changing | 60.9 us p50 | 64.2 us p50 |

#### Left

- The 100,000-glyph-over-a-backdrop case is still 0.45 to 0.46 ms against 0.4 ms (not worked on).
- The 10% changing HUD (CPU) and the layout's HUD may be 5 to 10% slower (dirty ranges, the automatic minimum's
  extra measure of changed items); to re-measure on a quiet machine.
- SVG: strokes, CSS, gradients and `use` are not read; `fill-rule="evenodd"` needs holes wound the other way. Icons
  draw only through a text system with an atlas (a UI without text shows nothing for them).
- Picking does not follow drawn transforms (as before): a scaled element is hit at its layout box.
- An element that once composed keeps its entry until its layer is rebuilt (a small table, never cleaned up sooner).

### Group A3, charts (2026-11-04): local branch a3-charts

Design section 7 and stages 9, 10 and 11 (Group C) as the new module `ui-charts` (directory `charts`, package
`com.truesim.ui.charts`, on top of `ui-controls`, published like the others). No engine work yet: what the engine must do
is listed under Left.

#### Built

| Part | Built | Where |
|---|---|---|
| Model | `Chart`: any number of `Axis` objects (X at the bottom or top, Y left or right, stacked outwards), scales `LINEAR`, `LOG`, `SYMLOG` (constant per axis), `TIME` (seconds since 1970 UTC plus a zone offset), `CATEGORY` (names); ranges are versioned `AxisRange`s, so linking axes is sharing one (`Axis.link`), across charts too; a `Crosshair` (versioned) shared by charts shows the hovered X in all of them; axes can be hidden, inverted, auto-fitted (`autoFit`), kept out of fitting (`fits(false)`), followed (`follow`); series name their axes (`Series.axes`); every series, legend item, annotation and drag tool can be shown and hidden | `Chart`, `Axis`, `AxisRange`, `Crosshair` |
| Ticks | nice numbers (1, 2, 5 times a power of ten, nearest the spacing asked for on a log scale) with minors; log decades (every n-th when narrow, 2 to 9 as minors, linear within a decade); symlog zero and signed powers thinned to the spacing; time by calendar (microseconds to seconds as nice numbers, then 1 s to 1 week, 1/2/3/6 months, nice years) with labels by the step (`mm:ss.fff`, `HH:mm:ss`, `HH:mm`, `MM-dd`, `yyyy-MM`, `yyyy`); categories within their names. Labels are text without allocation (`TickFormat`: decimals of the step, scientific beyond 1e7 or below 1e-6, Hinnant's civil dates); X labels wider than their room thin the ticks; label blocks are reused by their text, so a pan shapes only the labels coming into view | `Ticks`, `TickFormat` |
| Layout and drawing | the chart element draws grid, axes, ticks, labels, titles (a Y axis's title above it: no rotated text); a child `chart-plot` (the plot rectangle, `clip: true` in the themes) records the series; a child `chart-overlay` records drag tools, annotations, crosshair, highlights, zoom and selection boxes, legend (in the plot at `legend-position`, rows wrapping at the plot's width or a column) and tooltips. The chart lays its children out after layout (`Ui.FrameListener.afterLayout`, offsets in code), in the same frame. Drawing goes through `Canvas`: polylines with dashes carried across segments and round joins (discs), segments clipped to the plot (Liang-Barsky), markers (circle and square as boxes, diamond and triangles as triangles, cross, plus, asterisk as lines), quads and pie wedges as triangles, images | `Chart`, `Canvas` |
| New primitive | `Primitive.TRIANGLE` (kind 7): the bounding box in words 1-4, colour A, corners in words 9-14, per-edge anti-aliasing flags (`EDGE_AB`, `EDGE_BC`, `EDGE_CA`: a shape's outline anti-aliased, its shared inner edges hard); `DrawList.triangle`. Recorded through the public API only; `DrawList` changed by one method (`triangle`, using `start`/`emit` and the range transform), `Primitive` by the constants | `ui-core` |
| Data | `Column`s by absolute index (`first`..`end`): `Columns.of` over `VersionedDoubleArray`, `VersionedFloatArray`, `VersionedDoubleRing`, `VersionedFloatRing` (appended samples visited by `changesSince`), plain `double[]`/`float[]` (`changed()`), getters with a `Versioned` trigger, and `Columns.index(start, step)` (implicit evenly spaced X) | `Column`, `Columns` |
| Series | `line` (stairs `Step.POST/PRE/MID`, `area` to a baseline), `scatter`, `stems`, `digital` (lanes at the plot's bottom), `shaded` (band between two columns), `stackedArea`, `bars` (grouped, `stacked`, `horizontal`), `histogram` (bins, range, density, cumulative; binned on change), `histogram2d`, `heatmap` (`HeatTexture`: a `UiTextures` texture coloured by the `colormap`, cells several texels wide so linear filtering keeps them sharp), `pie` (a donut by `donut-hole`, share labels), `candlestick` (or `ohlc`), `errorBars` (vertical or horizontal), `infiniteLines`, `image`, `text`, `annotate` (callouts kept in the plot); `Subplots` (rows of charts, `linkColumns`, `linkRows`, `linkAllX`, one crosshair) | `*Series`, `InfiniteLines`, `Annotation`, `Subplots` |
| Decimation (UI23) | `MinMaxPyramid`: a segment tree over blocks of `ui.chart.pyramidBlock` samples (power of two; 8 by default), partial blocks read directly; for rings laid over the ring's slots, so an appended sample updates its block and ancestors in O(log n) (`Tracked`, `XYSeries.poll`: appended ranges only, a rebuild on any other change). `Decimator.minMax`: per pixel column the index range (arithmetic for evenly spaced X, else a search guided by the last column's width), first, last, min and max (M4); the line draws a stroke per column and a connector only where neighbouring columns' spans do not touch. `Decimator.lttb`: Largest-Triangle-Three-Buckets in the axes' transformed units. Per series `Decimation.NONE`, `MIN_MAX`, `LTTB`, `AUTO` (min-max for lines and areas above `ui.chart.decimateAbove` samples a pixel, LTTB for markers; LTTB falls back to min-max above `ui.chart.lttbMaxInput`) | `MinMaxPyramid`, `Decimator`, `XYSeries`, `Tracked` |
| Hit queries | nearest sample by binary search on sorted X (by X for the crosshair, in two dimensions for clicks); a `ScatterGrid` (uniform grid in transformed units, built on change, ring search) for unsorted data; bars, pie slices, heatmap cells and images by geometry; `Chart.hitTest`, `axisAt`, `legendEntryAt`; all without allocation | `XYSeries`, `ScatterGrid`, kinds' `contains` |
| Interaction | hover crosshair (shared), nearest-point highlights, tooltips merged (Grafana) or per series (stacked so they do not cover each other) by `chart-tooltips`; legend click hides an item, hover highlights its series (`:hover` look of its part); wheel zooms about the pointer (all axes, the selected ones when any is, Shift X only, Ctrl or Cmd Y only; over an axis that axis); drag pans (on an axis that axis); secondary-button drag zooms to a box (a thin box one direction); Shift-drag selects (`Chart.selection()`, a `DragRect` the game reads as a query range); clicking an axis selects it; `DragPoint`, `DragLine`, `DragRect` (move and resize) bound to `VersionedDouble`s; double click and `UiCommand.CHART_RESET` fit everything again and resume follow and auto-fit; zooming or panning an axis pauses its follow and auto-fit | `Chart`, `DragTool` |
| Styles | `ChartStyles` (registered as a `StyleKeyHolder` service): `stroke`, `line-width`, `line-dash`, `line-join`, `fill`, `fill-opacity`, `marker`, `marker-size`, `marker-fill`, `marker-outline`, `palette`, `colormap`, `bar-width`, `cap-width`, `donut-hole`, `digital-height`, `up-color`, `down-color`, `tick-length`, `minor-tick-length`, `tick-spacing`, `minor-ticks`, `fit-padding`, `legend-position`, `swatch-size`, `chart-tooltips`, `tooltip-offset`; standard keys for fonts, colours, boxes, padding, gaps, radii, shadows. Partials `_charts.tss` (seaborn deep palette, viridis) and `_charts-test.tss`, one `@use` in each theme; style names for marker shapes and donuts | `ChartStyles`, themes |
| Tunables | `ChartTunables` (`ui.chart.zoomStep`, `decimateAbove`, `pyramidBlock`, `lttbPerPixel`, `lttbMaxInput`, `maxPoints`, `gridPerCell`, `hoverDistance`, `maxTicks`, `followLead`, `dragPixels`, `grabPixels`, `arcPixels`, `heatmapTexels`), listed by `Ui.tunables()` through the new `TunableSource` service of `ui-core` | `ChartTunables`, `ui-core`, `Ui` |
| Gallery | `ChartsGallery` (test fixture of `ui-charts`): the ImPlot-parity demo page, 16 tiles (linked charts with three Y axes each, log and symlog, time with stairs and digital, scatter markers, grouped bars on a category axis, stacked horizontal bars, areas, histogram, 2D histogram, heatmap, pie and donut, candlesticks, error bars and stems, annotations and drag tools, 2 x 2 subplots), `pose` for the hovered look; in `GallerySanityTest` (both themes, posed) | `charts/src/testFixtures` |

#### Decisions

- *Charts are three elements, not one.* A painter cannot add a clip, so the plot is a child with `clip: true` whose
  painter records the series, and an overlay child takes what draws over the series and outside the plot (legend,
  tooltips). Axes, ticks and labels are the chart element's own drawing. The chart places its children after layout.
- *A triangle primitive rather than approximations.* Areas, bands, pie slices and triangle markers need triangles;
  boxes per pixel column fill dense envelopes (exact at the pixel), triangles the trapezoids of sparse data, with
  anti-aliasing on outlines only, so translucent fills show no seams.
- *Lines keep the segment primitive.* Joins are discs at vertices (`line-join: round`), dashes are split on the CPU
  with the phase carried along the polyline; decimated lines need neither.
- *Pyramid blocks of 8 by default* (benchmarked 8, 16, 32, 64): 4 bytes a sample (40 MB for 10 million) for the
  fastest envelope; a tunable for memory against time.
- *UI24: the CPU path stays.* It meets the targets (below), keeps the envelope exact in doubles, needs no readback for
  hit queries and adds no frame of latency. The GPU compute alternative for the engine phase: the columns uploaded as
  storage buffers (appended ranges only; values relative to the axis origin, or double-float pairs to stay exact), a
  compute pass with one workgroup per pixel column finding its index range (arithmetic or a binary search in the
  buffer) and reducing first, last, min and max in shared memory (or over a GPU min-max pyramid kept by a compute pass
  on appends), writing per-column segments for an indirect instanced line draw. At 10 million visible samples that
  reads about 80 MB a frame without a pyramid; it is worth it only when the CPU envelope is the bottleneck.
- *Fitting* happens with the first data, on a reset and for auto-fitting axes; an axis with `fits(false)` keeps the range
  code gave it (a streaming window's span). Follow mode keeps the span and puts the newest sample at the end, less
  `ui.chart.followLead`.
- *Colormaps interpolate in sRGB* (as they are made), and draw in linear light.
- *Style keys with plain names* (`stroke`, `line-width`, `fill`...) live in `ChartStyles`; a module declaring the same
  names (gizmos) must reuse them, not declare them again (`StyleKeys` refuses a second key of a name).
- *Text reshaping without allocation:* `FontRegistry`'s chain key compared family lists with `List.equals`, which walks
  an iterator: an allocation per reshape without escape analysis whenever two styles hold equal but distinct lists;
  now compared by index.

#### Tests

`TicksTest` (nice steps, linear, log, symlog, category and time ticks, number and time formats, civil dates against
`java.time`), `AxisTest` (transforms both ways for every scale, inverted, zoom keeps the point under the pointer, pan,
box, fitting, linking), `DecimationTest` (the pyramid against brute force over random ranges: arrays, block sizes,
NaN, rings appended, wrapped, partly filled and cleared; the envelope against brute force for sorted, duplicated,
evenly spaced, log and inverted X; LTTB against the reference implementation; the scatter grid against brute force),
`SeriesTest` (every kind: what it draws, its nearest sample and geometry hits, stacking, hiding items), `CanvasTest`
(dashes and their phase, clipping, every marker, wedges, quads, ring order checks), `InteractionTest` (scripts of
synthetic input: wheel zoom over the plot, axes and with modifiers, selected axes, pans, box zoom, box select and
moving it, legend toggles and highlights, drag tools, double click and `CHART_RESET`, streaming with follow and
auto-fit, a shared crosshair, per-series tooltips in the test theme, charts without text), `ChartAllocationTest`
(streaming 1,000 samples a frame with follow, hovering, wheel and pan; the whole demo page idle and hovered: no
allocation, without escape analysis), `ChartsGalleryTest` (LayoutSanity in both themes plain and posed; with
`TRUESIM_SCREENSHOTS=true` the CPU previews), `ChartBenchmarkTest` (`TRUESIM_BENCHMARK=true`); `GallerySanityTest`
and `UiTunablesTest` in `ui-controls`.

#### Benchmarks (Ryzen 9 9950X3D, without JaCoCo, while other agents built: noisy, to be re-measured)

| Target | Budget | Measured |
|---|---|---|
| 1,000,000 samples streaming 1,000 a frame, 3,930 pixel columns (4K), CPU | ≤ 0.5 ms | envelope 0.21 ms median, 0.30 ms p99; whole frame (poll, pyramid appends, envelope, recording axes, plot and overlay) 0.38 ms median, 0.66 ms p99 |
| 10,000,000 samples zooming and panning every frame, CPU | ≤ 1 ms | sorted X stored: envelope 0.38 ms median (1.2 p99), frame 0.95 ms median (p99 up to 6 ms under load); evenly spaced X: envelope 0.20 ms median (0.51 p99), frame 0.52 ms median (2.0 p99) |
| Pyramid memory | — | 4 bytes a sample at blocks of 8 (40 MB for 10 million), 2 at 16, 1 at 32, 0.5 at 64 |

#### Left

- **Engine**: the `TRIANGLE` kind in the UI shader (`ui/draw.slang`, `primitive.slang`): its quad is the bounding box
  (grown by a pixel for anti-aliasing), moved by its clip's offset with its corners; coverage is the product over its
  three edges of the signed distance's coverage for anti-aliased edges (flags 1, 2, 4 in header bits 8-10) and a hard
  half-open test for the others (a top-left rule, so triangles sharing an edge never cover a pixel twice); in the
  per-kind path a `triangleMain`. `engine-ui` depends on `ui-charts` (the shipped themes need `ChartStyles`' keys at
  run time) and registers `ChartTunables` like the others (they arrive through `Ui.tunables()`). GPU screenshots of
  `ChartsGallery` in both themes (`charts-{default,test}.png`, plain and posed). The chart benchmarks recorded in
  engine-bench; the GPU line targets (1M streaming ≤ 0.2 ms GPU) measured.
- Engine-side line quality (design 7 GPU): instanced segments with miter joins and a bevel fallback, round caps and
  dashes by cumulative length in the shader would replace the CPU's discs and dash splitting; SDF markers instanced.
- A nearest-sampled image flag would let heatmaps use one texel a cell.
- Not built: rotated text (Y axis titles sit above their axes), axis tags, heatmap value labels, histogram binning on
  the GPU, decimation of bars and candlesticks beyond `ui.chart.maxPoints`, a custom scale (ImPlot's transform
  callbacks), ~~legends outside the plot~~ (built: chartfix).
- `common-state` with `VersionedDoubleRing`/`VersionedFloatRing` must be released (`ui-core` asks for 0.4.0; the build
  uses `-Ptruesim.common-state=../common-state-tree`).

### Group A3, controls (2026-11-04): local branch a3-controls

Mnemonics, repeat buttons, logarithmic sliders, HDR colours and an eyedropper, grapheme-level password masking, spell
checking, and windows and tabs by commands. Engine-agnostic: `ui-core` (`ScreenSampler`, command fallbacks),
`ui-text` (`com.truesim.ui.text.spell`), `ui-controls` (`basic`, `text`, `windows`). No engine work in this branch.

#### Built

| Part | Built | Where |
|---|---|---|
| Mnemonics | `&` marks the letter in button, menu-title, menu-item, tab and dialog-button text and in labels for fields (`Controls.labelFor(text, target)`); `&&` is an ampersand, an `&` not before a letter or digit stays. Names and shown text have no marker. `Mnemonics` (one per `Controls`): each label gets a style part `span.mnemonic` with the fact `mnemonics=shown` or `hidden` following `UiInput.mnemonicsShown()`, and `Ui.underline(label, index, part)` draws the letter's underline while the part's `text-decoration` underlines (in its colour), so a theme shows them always, while Alt is held (the default theme) or never. `UiInput.mnemonic()` goes to the deepest open menu (its one item with the letter activates, a submenu opens; several: focus cycles; an open menu takes every letter), else to the buttons and labels-for in the focused scope (the nearest ancestor announced `WINDOW` or `DIALOG`, the trapping modal scope, with no focus what is in no window): one is clicked (a menu bar's title opens its menu, a checkbox toggles, a label focuses its target), several cycle the focus as Windows does | `basic.Mnemonics`, `Button`, `MenuItem`, `Menu`, `MenuBar`, `Controls`, `Ui.underline` |
| Repeat button | `Repeater` runs an action on the press, then after `ui.button.repeatDelay` (0.4 s) at `ui.button.repeatRate` (20/s), the action system's defaults, at most once a frame, pausing while the held pointer is off the element; `ACCEPT` runs it once; the press's click is swallowed. `RepeatButton` (a `Button`), `Controls.repeatButton`. Used by number-field steppers, time-picker arrows and date-picker month arrows | `basic.Repeater`, `RepeatButton`, `text.NumberField`, `TimePicker`, `DatePicker` |
| Slider scales | `SliderScale`: `LINEAR`, `logarithmic(base)`, `symlog(linear, base)` (ranges crossing 0); `Slider.scale(...)` maps thumbs, fill, presses and drags; keys step a hundredth of the track (a page `ui.slider.pageFraction`) in the scale's coordinate, then snap to the step, at least one step; `ticks(count)` (even in the scale), `ticksAt(values...)`, `scaleTicks()` (powers of the base; zero and the powers either side for symlog; the step's multiples for linear); `Slider.range(min, max)`. `NumberField.scale(...)`: keys and steppers a hundredth of the range's coordinate, drags a two-hundredth per `ui.input.numberDragPixels` | `basic.SliderScale`, `Slider`, `text.NumberField` |
| HDR colours | `ColorPicker.hdr(maxStops)`: the colour is a base colour (area, strip, hex) times `2^stops`; a `color-intensity` row (a `slider.intensity` over 0 to `maxStops` and a `color-intensity-value` label, `+1.3 EV`); components above 1 kept in the model (half floats); the preview splits into `color-preview-clipped` (clipped to 1, as SDR shows it) and `color-preview-hdr`; hex shows and takes the base only (the intensity stays); `hdr(0)` turns it off | `basic.ColorPicker` |
| Eyedropper | `ScreenSampler` (ui-core): `request(x, y, size)` a block of the presented frame (odd, up to 31 px), `poll(ScreenSample)` copies the newest answer (linear, HDR kept), `stop()`; asked every frame, the newest answer shown, so nothing waits. `PatternScreenSampler` answers from a pattern after a latency (default: the frame after next), for tests and galleries. `Eyedropper`: a full-screen `eyedropper` element on its own blocking layer (z 450) takes the pointer, a `color-loupe` (painted: `loupe-pixels` cells, the centre outlined by `color-loupe-center`'s border, at `loupe-gap` from the pointer, flipped at screen edges) follows it; a click or `ACCEPT` picks the pixel (at once when the newest answer is for that point, else when it arrives), `CANCEL` or the secondary button cancels, the focus goes back. `Controls.screenSampler(...)`; a picker shows its `button.eyedropper` (icon `eyedropper`, `:checked` while picking) when the controls have one. A built-in `icons/eyedropper` | `ui-core` `input.ScreenSampler`, `ScreenSample`, `PatternScreenSampler`; `basic.Eyedropper`, `ColorPicker`, `BuiltinIcons` |
| Password masking | One mask per extended grapheme cluster (an emoji ZWJ sequence, a flag, a skin-toned emoji, a letter with combining marks): the slice shapes the cluster's first character as the mask and the rest as zero-width spaces, so indices stay the text's; Left and Right move logically by clusters while masked, a click never lands inside one, Backspace deletes a whole cluster (`EditableText.deleteBackCluster`), a double click selects all | `text.Slice`, `TextEditor`, `ui-text` `EditableText` |
| Spell checking | ui-text `spell`: `AffixRules` (the `.aff` subset: `SET`, `FLAG` char/long/num, `TRY`, `REP`, `KEY`, `NOSUGGEST`, `FORBIDDENWORD`, `NEEDAFFIX`, `PFX`/`SFX` with conditions, cross products and continuation flags for a second suffix); `Dictionary` (`.dic` + `.aff` streams or resources, or a plain list) expanded at load into a `WordSet` (one char pool, open addressing, looked up by `CharSequence` ranges without allocating); `SpellChecker`: dictionaries per locale, a user dictionary (`addToDictionary`, `removeFromDictionary`, saved through `UserWords`), session `ignore`, `version()`; `check(text, from, to, Misspelt)` allocates nothing; words are letters, marks and digits with inner apostrophes and hyphens (a hyphenated word is right whole or by parts); case: a lower-case entry is right capitalised and in capitals, a capitalised one in capitals; settings `ui.spell.ignoreUrls`, `ignoreNumbers`, `ignoreMixedCase`, `ignoreCapitals`; `suggest(word)` (at most `ui.spell.suggestions`): the replacement table, a `BkTree` (optimal string alignment, distance 1 then 2; swaps first), the user's words, split words, in the word's case. `TextEditor.spelling(checker, controls)`: the part `span.misspelt` (`color`, `squiggle-wavelength`, `squiggle-amplitude`, `squiggle-thickness`; 0 wavelength: straight) draws squiggles as line primitives; per-paragraph results shifted with the paragraph arrays, only damaged paragraphs checked again: the visible ones as they are drawn (no frame waits), the rest within `ui.text.spellBudget` ms a frame; the word being typed (ending at the caret) is not marked; the context menu (right click or `CONTEXT_MENU`) on a misspelt word offers corrections, "Add to dictionary", "Ignore" (`spellMenuText` localises them); passwords are never checked | `ui-text` `spell`; `text.TextEditor`, `TextStyles` |
| Windows and tabs by commands | `FloatingWindow`: `WINDOW_MOVE` and `WINDOW_RESIZE` with the focus in it start `keyboardMove()`/`keyboardResize()`; `Interaction.addCommandFallback` (commands no element consumed): `WindowManager` takes `NEXT_WINDOW` (`focusNext()`) and, with nothing focused, `WINDOW_MOVE`/`WINDOW_RESIZE` for the active window; dock panes: `NEXT_TAB`/`PREVIOUS_TAB` (wrapping, focus on the tab), `CLOSE_TAB` (asks the shown panel; the next tab focused once its rebuilt pane is laid out), `NEXT_WINDOW` to the next pane across the space's windows (its remembered focus or its tab); `TabPane`: the same three anywhere in the pane (the new tab focused: the old page leaves the tree) | `windows.FloatingWindow`, `WindowManager`, `DockSpace`; `basic.TabPane`; `ui-core` `Interaction` |
| Styles | `_controls.tss` and `_controls-test.tss` (one `@use` each in both themes): `span.mnemonic`, the intensity row and preview halves, the eyedropper button, screen and loupe, `span.misspelt`; keys `loupe-pixels`, `loupe-gap` (`BasicStyles`), `squiggle-*` (`TextStyles`, a new `StyleKeyHolder` service) | `style`, `controls` |
| Galleries | `ControlsGallery`: mnemonics on the menu bar, its items, Save and a label for the key-binding field ("&Jump key"), the Next button repeats, a logarithmic frequency slider with decade ticks, the colour picker in HDR with an eyedropper over a `PatternScreenSampler` (`Gallery.openEyedropper(x, y)`); `TextGallery`: a password of four clusters, the notes area spell checked (squiggles under "mispelled wrod") | `testFixtures` |

#### Decisions

- *Mnemonic underlines through a style part, not a flag in code*: the theme decides when they show (`span.mnemonic`
  plain, or with the fact `mnemonics=shown`), so "always" needs no key of its own: `text-decoration: underline` on
  `span.mnemonic` (the test theme does it).
- *Mnemonic scope by semantics*: the nearest ancestor announced as a window or dialog is the scope, so windows,
  dialogs and docking's OS windows need no registration.
- *Repeat timing as UI tunables with the action system's defaults* (`ui.button.repeatDelay` 0.4 s,
  `ui.button.repeatRate` 20/s): the engine's `ui.repeatDelay`/`ui.repeatRate` cannot reach a pointer hold.
- *Scales act on the coordinate*: keys step a fixed share of the track on every scale, so a logarithmic slider steps
  multiplicatively; a step set still snaps.
- *HDR as base times stops*: the area, strip and hex stay in the displayable range people pick in; the intensity is
  one exposure control, as in DCC tools.
- *Sampling is asynchronous by contract* (request, then poll the newest answer); a click commits on the answer for its
  own point, never on a stale one.
- *Spell-check index: a BK-tree* over the expanded forms rather than symmetric deletes: the forms do not change, its
  memory is four int arrays (symmetric deletes at distance 2 would need millions of entries for en_US), and a query
  runs on a context-menu click, not per frame: 18 ms median on 150,000 synthetic forms, five times faster than a
  scan (benchmark below). If that is too slow with a real dictionary, symmetric deletes at distance 1 plus the tree
  at distance 2 only when nothing is found is the next step. Forms are expanded at load so checking is
  one hash lookup that allocates nothing.
- *No word list was found* in the repositories, the JDK or the Gradle and LWJGL caches: the mechanism ships with a
  small self-written sample (`en-sample.aff`/`.dic`, 1,340 stems, about 4,000 forms; project licence). Candidates the
  user could add: SCOWL-based Hunspell en_US/en_GB (wordlist.aspell.net; SCOWL's permissive MIT-like licence, with
  parts under other permissive terms; LibreOffice's dictionaries repository), and other languages' Hunspell
  dictionaries (licences vary: LGPL/MPL/GPL; check each).

#### Tests

`MnemonicsTest` (parsing, underline shown only while the modifier is held and always in the test theme, buttons,
duplicates cycling, disabled, labels for fields, the menu bar's titles and open menus, submenus, scopes of windows and
dialogs), `RepeatAndScaleTest` (delay, rate, pausing off the button, ACCEPT once; number-field steppers; scale
mappings and round trips for five scales, ticks, keys in the scale's space, snapped steps, number-field drags),
`ColorHdrTest` (stops, base hex, the preview halves; the eyedropper's latency, clicks before and after the answer,
HDR kept or clipped, cancel by CANCEL and the secondary button, ACCEPT), `PasswordClustersTest` (six clusters six
masks, caret stops, Backspace by clusters, clicks), `SpellCheckerTest` (affix rules with long flags, continuation
flags, NEEDAFFIX, FORBIDDENWORD, NOSUGGEST, numeric flags; case; settings; suggestions; user dictionary and its
store; locales; the BK-tree against a brute-force scan), `SpellEditorTest` (squiggles, only the edited paragraph
rechecked, the typed word unmarked, 2,000 paragraphs over several frames within the budget, the spelling menu by
keys and by a right click: replace, add, ignore; passwords not checked), `WindowCommandsTest`, `TabCommandsTest`,
`A3AllocationTest` (mnemonics toggling, a held repeat button, a logarithmic slider by keys, a spell-checked area
scrolling, the eyedropper following the pointer: no allocation), `A3PreviewTest` (CPU previews, with
`TRUESIM_SCREENSHOTS=true`). Goldens regenerated: `basic-gallery.txt`, `basic-gallery-test-theme.txt` (the gallery
changed).

#### Benchmarks (Ryzen 9 9950X3D, JaCoCo off; other agents were building: noisy)

| What | Measured |
|---|---|
| Checking prose with the sample dictionary (`SpellBenchmarkTest`) | 60 ns a word, allocation-free |
| 150,000 synthetic forms (en_US-sized, random words, denser than real ones): BK-tree build | 284 ms (on the first suggestion, or `Dictionary.prepareSuggestions()` at load) |
| A distance-2 suggestion query, BK-tree vs a full scan | 18 ms median, 30 ms p99 vs 93 ms median |

#### Engine (to do there)

- Bind the `ui` group's actions to `WINDOW_MOVE` and `WINDOW_RESIZE`: proposed Alt+F7 and Alt+F8 (Windows and KDE),
  no gamepad default; `NEXT_WINDOW` Ctrl+F6 (and Alt+F6 on KDE); `NEXT_TAB`/`PREVIOUS_TAB` Ctrl+Tab and Ctrl+Shift+Tab
  (and Ctrl+PageDown/PageUp), the shoulder buttons on a gamepad; `CLOSE_TAB` Ctrl+W (Ctrl+F4).
- Fill `UiInput.mnemonic()` (a letter or digit pressed with Alt, not when Alt is part of another binding) and
  `mnemonicsShown()` (Alt held alone; on Windows the system's "underline access keys" setting may keep it on).
- Implement `ScreenSampler` over the presenter: copy a `size x size` block around the pointer from the frame's
  scene-referred HDR image (before the display transform) into a small read-back buffer each frame a request stands,
  hand it back the frame it lands (no wait, no stall), and call `Controls.screenSampler(...)`; with several OS windows,
  the window under the pointer (`request` false outside every window).
- Register the new tunables as settings (they are in `Ui.tunables()`): `ui.button.repeatDelay`,
  `ui.button.repeatRate` (default them from `ui.repeatDelay`/`ui.repeatRate`), `ui.text.spellBudget`,
  `ui.spell.ignoreUrls`, `ui.spell.ignoreNumbers`, `ui.spell.ignoreMixedCase`, `ui.spell.ignoreCapitals`,
  `ui.spell.suggestions`.
- Spell checking: a `SpellChecker.UserWords` store in the player's profile, the locale from the game's language, and
  where to load full dictionaries from.
- GPU screenshots of the changed galleries (the HDR preview half and the loupe show HDR values above 1 only there).

#### Left

- The eyedropper's loupe shows the sampler's pixels as they come (no zoom control); docking's OS windows sample the
  window under the pointer only once the engine's sampler knows them.
- Spelling: no compounds, no morphological analysis, no per-span language; suggestions do not use keyboard
  adjacency (`KEY` is read, not used); a full dictionary was not tried here (none available).
- A pre-existing allocation (also on the base commit, not from this branch): frames alternating between hovering a
  gallery button, wheel-scrolling a text area on the overlay layer and hovering elsewhere allocate about 8 KB per
  10,000 frames; found while writing `A3AllocationTest`, not investigated.
- From the brief, not done: a loupe for touch selection handles (the magnified text needs a clip inside a painter's
  content, left for the draw-list transform work), a grace for overlays across windows. Built of the small items: a
  touch selection handle dragged past a field's or area's edge scrolls it that far, as a mouse selection does
  (`PasswordClustersTest.aTouchHandleDraggedPastAFieldsEdgeScrollsIt`).

### Group A3, data (2026-11-04): local branch a3-data

The data controls' leftovers of group A2 and the file browser of the plan additions: box selection, horizontal
lists and wide rows, hidden and grouped table columns with header groups, renderers chosen by styles, records by their
metadata, a loading look for lazy tree nodes, and open and save dialogs over an engine-agnostic file model.

#### Built

| Part | Built | Where |
|---|---|---|
| Box selection | Dear ImGui 1.91's box select in every virtual view (lists, trees, tables; multiple selection): a drag from empty space (or from a row where rows do not drag: lists not reorderable, trees not draggable, tables) draws a `selection-box` over the viewport and covers rows by index range; Shift adds, the shortcut modifier toggles; past an edge the view scrolls (`ui.list.boxScrollRate`, `ui.list.boxScrollEdge`), so rows never bound are selected too; rows show the result while dragging (`Selection.previewed`), the selection changes once, on release (`Selection.box`); CANCEL (a drag ended without a release) drops it, a click in empty space selects nothing; keys unchanged. `BoxSelect` does the same on a free 2D canvas of the caller's items (rectangles and selected flags) | `VirtualView`, `Selection`, `BoxSelect`, `DataTunables` |
| Horizontal lists, wide rows | `ListView.horizontal(true)`: items in a row, virtualized along x (a fixed `width` or measured), LEFT and RIGHT move, `[axis=horizontal]` for themes; the wheel scrolls sideways in any view that only scrolls that way. Rows of vertical lists and trees grow to their content: the widest row seen makes the content wide and every row that wide, so wide rows and deep trees scroll sideways (tables place their own cells) | `VirtualView`, `ListView`, `ScrollView` |
| Hidden columns | `TableView.hide(column, on)` (in its place in the display order; frozen columns follow), `allColumns()`; `ColumnMenu.attach(controls, table)`: the header's context menu with a check per column, both ways | `TableView`, `basic.ColumnMenu` |
| Layout description | `TableView.layoutDescription()`: `column "<key>" <width> [hidden]` per column in display order, `frozen <n>`, `sort "<key>" ascending/descending`, `group "<key>"`; restored by `layoutDescription(text)` (unknown keys skipped); `onLayoutChanged` for saving; `Column.key` (the header by default) | `TableView`, `Column` |
| Grouped rows | `TableView.groupBy(columns)` (outermost first, up to four levels): a `table-group-row` before each group (`[level=n]`, `:expanded`; expander, the column's header, its cell view of the group's value, the count, an optional `groupSum`), collapsible by a click, ACCEPT, LEFT and RIGHT; headers are not selectable (`RowSource.selectable`: ranges, select-all and boxes skip them). `GroupedRows` keeps the groups incrementally from the source list's change log: leaves in order with counts and sums in Fenwick trees (a row index to a header or view position in O(log G)); changed rows leave their group and find their new one by binary search over representatives; a new or emptied group regroups from scratch (O(n)). `TableView.baseOrder` sorts before everything (folders first) | `GroupedRows`, `TableView`, `DataView.builds` |
| Header groups | `TableView.headerGroup(title, columns)`: a `table-header-group-row` above the headers, one `table-header-group-cell` per run of adjacent shown columns of a group (split at the frozen edge, by moves and by hiding) | `TableView` |
| Renderers by style | The style key `renderer: <name>` on `list-view`, `tree-view` or a column's `table-header-cell` chooses the renderer registered under that name (`ValueRenderers.name`; the built-ins are named `text`, `number`, `check`, `enum`, `vector`, `color`, `record`); views are made again when it changes | `DataStyles.RENDERER`, `StyledRenderer`, `Column.styled` |
| Records by metadata | The registry's second step is now real: a record shows its components (`record-view#<Class>` of `record-field#<component>`: a `label.record-name` and the component's view through the registry, records nesting); accessors become direct calls once per class (`LambdaMetafactory`), so binding allocates nothing; built-in records (vectors, colours) keep their renderers | `RecordRenderer`, `ValueRenderers` |
| Loading look | While a node loads its children (`TreeRows.loading`), a `tree-loading` row follows it (an indent, three `tree-loading-dot`s lit in turn per `loading-period`, a `label#loading`: `TreeView.loadingText`); never selected; type-ahead skips it | `TreeRows.placeholder`, `TreeView` |
| Type-ahead in tables | `TableView.typeAhead(text)`; the search moved into `VirtualView.typeAhead` (lists use it too) | `VirtualView`, `TableView` |
| File model | `FileModel`: list, stat, roots, home, parent, child, resolve, steps, create folder, rename, places (`FilePlaces`: favourites, recent at most `ui.files.recentLimit`, describe and restore), an optional watch polled once a frame. `NioFileModel` (any `java.nio` file system, its watch service), `MemoryFileModel` (roots like `/` and `D:/`, for tests and galleries) | `controls.files` |
| File browser | `FileBrowser`: a bar (back, forward, up, `Breadcrumbs` turning into a path field, list or details, new folder), places (`ListView`), a details `TableView` (name with icon, size, modified, type; sort by header, folders first; type-ahead; rename in place) or a `ListView`, a name field, a filter `DropDown`; modes open (one or several), save, folders; Backspace goes up; the folder's watch lists it again; errors show in `label.file-error` | `FileBrowser`, `FileCells` |
| File dialogs | `FileDialog.open`, `save`, `folder`: the browser in a `Dialog` (`dialog.file-dialog`), accept and cancel, an overwrite confirmation (`dialog.file-confirm`, opaque) when saving over a file, a filter's extension added to new names, the chosen folder first in the recent places | `FileDialog` |
| Styles | `_data.tss`, `_data-test.tss` (appended) and new partials `_files.tss`, `_files-test.tss` (one `@import` each); icons by id: `back`, `forward`, `edit`, `new-folder`, `folder`, `file`, `home`, `drive`, `favourite`, `recent` | themes |
| Galleries | `DataGallery` adds a grouped table (a header group, a hidden column, a collapsed group, sums), a horizontal list, a record, a loading tree node and a box-select canvas; `FilesGallery` (new): a browser, and a save dialog asking to replace a file; both in `GallerySanityTest` | `testFixtures` |

#### Decisions

- *Box selection previews, then commits.* The selection is a set of items, and adding entries allocates. While
  dragging, rows show the result computed from the selection and the box (allocation-free); the set changes once on
  release. A box covers rows by index range along the axis (rows fill the cross axis), so virtualization needs
  nothing more.
- *A box from rows only where rows do not drag*: reorderable lists and draggable trees keep their row drags; there a
  box starts in empty space (Dear ImGui's choice for full-row items).
- *Groups by comparators, not keys.* A group is a run of rows comparing equal by the group columns' comparators (the
  order the view sorts by), found by binary search over representatives, so no key objects are made (ints are not
  boxed). Representatives of immutable rows stay valid when the row is replaced; a representative changed in place
  regroups.
- *A table layout description of its own* (none existed): text lines in the style of `DockLayout`, saved by the game.
- *The column menu lives in `basic`* (it is a `Menu`), so `data` still does not depend on `basic`.
- *The file browser reads listings when navigating* (a user action), never per frame; per frame it compares the
  watch, the view choice, the filter and the selection versions.

#### Tests

`BoxSelectionTest` (replace, add, toggle, CANCEL, a click in empty space, scrolling past the edge selecting rows never
bound, rows that drag, tables, trees, the canvas helper, no allocation while dragging), `GroupedTableTest` (headers,
counts, sums, collapsing by pointer and keys, two levels, incremental updates equal to regrouping from scratch over 60
frames of 40 random changes with groups appearing, hidden columns, the column menu, the layout description round trip,
header group runs, no allocation with rows changing), `ListsMoreTest` (horizontal lists, wide rows, loading rows and
their dots, no allocation while loading, renderers by style, records by components without allocation),
`FileModelTest` (memory, places, `java.nio` in a temporary folder only), `FileBrowserTest` (listing, filters, hidden
files, sorting, navigation, breadcrumbs, the path field, Backspace, places, open, save with the overwrite
confirmation, folders, new folder and rename, the watch, type-ahead, the real file system in a temporary folder),
`DataThemesTest` and `GallerySanityTest` over the new parts and galleries, `DataFilesPreviewTest` (CPU previews).
Changed: `RenderersTest` (records by metadata), `DataGalleryTest` (the tree's loading row).

#### Benchmarks (Ryzen 9 9950X3D, without JaCoCo; other agents were building: noisy)

| Target | Budget | Measured |
|---|---|---|
| Table of 100,000 rows grouped by two columns (64 groups, a sum), sorted, 100 changes a frame (most moving between groups) | ≤ 0.5 ms | 0.151 to 0.155 ms median, 0.35 to 0.39 ms p99 (`DataBenchmarkTest`) |
| The same, regrouped from scratch (a group appearing or emptying) | — | 5.4 ms median |
| 1,000,000-row sorted, filtered view, 100 changes a frame (unchanged code, measured again) | ≤ 0.5 ms | 0.023 to 0.026 ms median, 0.057 to 0.065 ms p99 |

Allocation-free (without escape analysis): a box dragged while scrolling, a grouped table with rows changing, a
loading row's dots, lists of records scrolling.

#### Left

- The CPU preview draws a layer's text after all its boxes, so a dialog over a dialog's table shows the table's text
  in the preview; the GPU draws in order (to check in the engine's screenshots).
- A group appearing or emptying regroups from scratch (O(n), 5 ms at 100,000 rows).
- Box selection across the cross axis (a grid of items) and in a table's cell selection; `BoxSelect` does not scroll
  a scrolled canvas.
- File dialogs: no rename in the list view, no drag and drop of files, no thumbnails; listings are synchronous (a slow
  file system should list in the background behind `FileModel`); native OS dialogs maybe later.

### Group A3, props (2026-11-04): local branch a3-props

Stage 2's binding and stage 13's property builder (design 4.2, 5.11; UI17 to UI20), without the engine's inspectors:
new modules `ui-props` and `ui-props-processor`.

#### Built

| Part | Built | Where |
|---|---|---|
| Binding | `Binding<M,C>`: two-way between a model end and a control end (`Bindable`: a value with a version; adapters for `VersionedValue`, `VersionedDouble`/`Int`/`Long`/`Boolean`, and `Bindable.through(whole, lens)` for a field of a record in another bindable). UI to model on edit (converted, validated, written), model to UI by version, both compared once a frame (two loads, no allocation when nothing changed); a refused value marks the bound element `:invalid` with a message and stays shown while other fields of the record change. `Converter<M,C>` (identity, `of`, `inverse`, `then`, `degrees`), `Validator<T>` (null or a message; `of`, `range`, `and`), `Lens<R,F>` (`of`, `then`, `identity`, `element(i)`, `entry(k)`). `Ui.bindings()` polls them at the start of each update, before the frame listeners, so neither direction waits a frame | `ui-core` `bind`, `Ui` |
| Metadata | `PropertyModel<T>` (ordered `Property<T,V>`s, the type's annotations, a factory, a sealed type's permitted subtypes, its source), `Property<T,V>` (a `Lens`: name, declared type, element and key types of collections, getter, wither or setter, annotations as typed facts, label), `Annotations`; 30 annotations (`@Label @Tooltip @Range @Step @Unit @Color(hdr) @Angle @Slider @Field @Multiline @Password @EnumStyle @Hidden @ReadOnly @Group @Order @Foldout @ShowIf @Validate @AssetType`, `@ListStyle @Addable @Removable @Reorderable @MinSize @MaxSize @Unique @ElementLabel @Factory`, `@Inspectable`); `Hints` makes any annotation in code (the engine's sources, the generated classes); `AnnotationHandler` gives custom annotations facts, an editor and a validator | `ui-props` |
| Sources | `PropertySource` (priority, `model(type)`), merged per type by `PropertyModels` (cached): a higher source's properties come first and win per annotation type, a lower one's extra properties follow, accessors from the one that can write. `annotated()` (priority 0): the generated class, else reflection | `PropertyModels`, `PropertySource` |
| Processor | `PropertiesProcessor` (JDK only, by annotation name): for each record or class with a property annotation, `@Inspectable`, or a sealed parent of one, writes `<Type>Properties` (nested names joined by `_`): a `Property` constant per property with direct getter and wither (canonical constructor) or setter calls, element and key types, its runtime annotations through `Hints` (custom ones too: every value kind, arrays, nested annotations), `MODEL` (factory: `DEFAULTS`, a no-argument constructor, else defaults; subtypes), and direct static accessors. Private and generic types are skipped with a note (reflection serves them). Wired with plain Gradle (`testFixturesAnnotationProcessor`, `testAnnotationProcessor`) | `ui-props-processor` |
| Reflection | The fallback: records by their components (accessor `MethodHandle`s, the canonical constructor spread), classes by field order with public getters (`getX`, `isX`) and setters or public fields, superclasses first; annotations of component, field and accessors; generic element types | `ReflectionModels`, `Defaults` |
| Pipeline (UI17) | `PropertyBuilder.forType(T).replace(Steps.X, step).remove(key)...build(controls, holder)`: FILTER (`@Hidden`, `@ShowIf` re-evaluated when the value changes), ORDER, GROUP, EDITOR (code > style `editor` > annotations and handlers > type > fallback), LAYOUT (code > style `props-layout` > default), BIND (lens into the root holder, validators from `@Range`, `@Validate`, sizes, uniqueness, handlers). Elements `props.panel[model][layout]`, `props.row[type][kind][annotation...][property][group][model][label]` with `label.prop-label` and the editor. Styles set the layout, editor, `label-placement`, `visible`, `order` per row; read after styles resolve (a theme switch rebuilds what changed in the same frame); a row's styles are re-read only when its resolved style changed, and nothing is rebuilt unless a chosen value did. Code overrides (`layout`, `editor(property, name)`, `visible`, `collection`) beat styles | `PropertyBuilder`, `Steps`, `PropertyPanel`, `PropertyRow` |
| Editors | `number` (NumberField; range, step, decimals, `@Unit`), `slider` (with the value beside it), `angle` (degrees over radians), `checkbox`, `text`/`multiline`/`password` (written on Enter or leaving; the field's own `:invalid` while typing), `dropdown`/`radio`/`segmented` (`@EnumStyle`, `@Label` on constants), `color` (ColorPicker over a draw-list colour `long` or an 8-bit sRGB `int`), `asset` (a field with fact `asset=<kind>`), `record` (a nested panel in a collapsible, through the lens chain), `collection`, `readonly`; registered by name, per type and per annotation | `Editors`, `EditorFactory`, `PropertyEditor` |
| Layouts | `form` (titled groups), `grid` (groups as tile grids), `inspector` (groups as collapsibles kept across rebuilds), `compact`, `row` (a table row) | `Layouts`, `LayoutStrategy` |
| Collections (UI18) | `CollectionEditor` for lists, sets, maps and arrays of records, sealed types and simple values: `collection[property][element][type][kind]` with a header (count, operations) over a kind's view; kinds by style `collection` (> `@ListStyle` > type: map, chips for sets of enums or strings, foldouts for sealed or interface elements, else table): `table` (rows of nested `row` panels under column labels; above `virtualize-above` a `TableView` editing cells in place through a mirror list), `foldouts` (collapsible nested panels; above the threshold a master-detail list), `master-detail` (`ListView`, rows dragged to reorder, the selection's panel), `chips` (tags, enum constants added from a menu, text typed), `compact` (a summary and a dialog with a master-detail editor), `map` (key and value editors, keys unique); kinds registered by code are selectable by style | `CollectionEditor`, `CollectionKind`, `CollectionViews` |
| Operations | add (`@Factory`, `DEFAULTS`, constructor, defaults; a type chooser dialog over a sealed type's subtypes), remove, reorder (up and down, drags in lists), duplicate (a deep copy through the metadata), clear, a context menu per item (with `contextAction`s); shown by style `ops` (> `@Addable/@Removable/@Reorderable`), `ops-placement` (each item's end, or on hover with the bar's own `:hover`; views that select keep item operations in the header on the selection), `confirm`; buttons `button.props-op#<op>` with icons `image#op-<op>` and text `op-label`. `@MinSize`, `@MaxSize`, `@Unique` and map keys disable what would break them (`reason`, the tooltip with `tooltips(...)`) and refuse writes (`:invalid`, `message`) | `CollectionEditor`, `CollectionOps` |
| Edits and undo | Every write reports an `Edit` (path `waypoints[1].speed`, operation, root before and after, `undo`/`redo` into the holder) to `onEdit` listeners; a pointer-held gesture (a drag, a slider) is one edit, reported when released. Immutable updates through lens chains (`CollectionOps`: new lists, sets, maps, arrays) give a new root sharing everything off the path | `Edit`, `PropertyPanel`, `CollectionOps` |
| Themes | `_props.tss` and `_props-test.tss` (one `@use` in each theme); the test theme picks inspector panels and asks before clear and remove. `PropsStyles` (the keys) lives in ui-controls so the themes compile without ui-props | `ui-style`, `controls.props` |
| Controls hooks | `Control.dispose()`, `ScrollView.dispose()`, `TextEditor.dispose()` (views and fields destroyed without it stop by themselves); `Ui.removeFrameListener` during the listeners' pass takes effect at once without skipping another | `ui-controls` |
| Gallery | `PropsGallery` (testFixtures of ui-props): a squad record with groups, every editor, a nested record, and a collection of each kind; in `GallerySanityTest` (both themes) and previewed by `PropsPreviewTest` | `ui-props`, `ui-controls` tests |

#### Decisions

- *UI19, both techniques kept* (measured below): generated models build faster cold (61 to 67 ms against 80 to 92 ms
  for the gallery's ten types in a fresh JVM) and warm (3.8 against about 60 µs a type) and write 7 to 9 times
  faster (6 to 8 against 55 to 58 ns a `with`); reads are close (1.5 to 3.8 against 4.1 to 4.3 ns). Reflection stays
  as the fallback for types compiled without the processor. Most of the cold cost is the JDK's first proxy and
  lambda machinery (the first type: about 38 ms against 55 ms), not the models.
- *Annotations as annotation instances* (`Hints` makes them with a proxy, equal to the JDK's): custom annotations and
  handlers work the same for annotated types and external sources.
- *Bindings poll before the frame listeners*, in `Ui`, not through `Controls`: no frame of delay in either direction.
- *Style choices re-read after layout* (`afterLayout`), so a theme switch shows the new editors and kinds in the frame
  it applies; only when the element's resolved style changed, and nothing is rebuilt unless a chosen value did (hover
  restyles allocate nothing).
- *Hidden rows stay in the tree* (`layout.visible(false)`): their style rules (`visible: true`) keep applying.
- *`virtualize-above` is a style key*, as design 5.11 has it (a presentational threshold per collection), not a
  tunable.

#### Tests

ui-core `BindingTest` (both directions, an edit and a model change in one frame, validators and `:invalid`,
converters and conversion failures, primitive holders, lenses of mutable objects, collection lenses, 40 bindings
polled without allocating). ui-props: `ModelsTest` (generated and reflected models agree on every property, value
and annotation; beans; merging by priority; hints; defaults; collection operations and deep copies), `PipelineTest`
(edits and model changes both ways for every editor kind, each step replaced and removed, styles choosing layout,
editors, visibility, order and label placement, code beating styles, a theme switch, every layout, handlers and
`@Validate`, gesture-coalesced edits and undo), `CollectionsTest` (a theme alone switches `List<Waypoint>` from
table to master-detail and hides duplicate; the sealed-type add chooser; min, max, unique and map keys; nested edits
in lists giving the expected new root and undo round trips; confirm; hover placement; chips, compact, context menus
with code actions; virtualized tables and foldouts, a cell edit writing back), `UnitsTest`, `GalleryTest`
(LayoutSanity, both themes), `PropsAllocationTest` (idle and hover frames over the gallery allocate nothing),
`ModelBenchmarkTest` (UI19, `TRUESIM_BENCHMARK=true`). ui-props-processor: `PropertiesProcessorTest` (sources
compiled in process with `-Werror`: every annotation value kind and escape, beans, sealed hierarchies, skipped types,
the unnamed package; the written models load and work). ui-controls: `GallerySanityTest.propsGallery` (both themes),
`PropsPreviewTest`.

#### Benchmarks

UI19 (`ModelBenchmarkTest`, Windows, other agents building: noisy): first builds of ten types in a fresh JVM, 5 runs
each: generated 61.4 / 63.9 / 65.0 / 63.0 / 67.4 ms, reflection 84.8 / 83.3 / 81.8 / 79.9 / 91.6 ms; built again
3.8 µs against 59 to 73 µs a type. Access on `Squad` (warm, median of 15): get 1.5 to 3.8 ns generated, 4.1 to 4.3
ns reflection; with 6.4 to 8.0 ns against 55 to 58 ns.

#### Left

- The engine's inspectors (design 5.11): `PropertySource`s for `Setting`s (the settings screen) and for the Slang
  material records, and node components through the D46 stores; their GPU screenshots.
- A foldout list above `virtualize-above` shows as a master-detail list (nested panels of any height are not
  recycled); a map is never virtualized.
- The generated classes build annotations through `Hints` proxies; writing an implementing class per annotation would
  cut the cold first build further (most of it is the JDK's first proxy).
- An unsaved typed value in a text editor shown while the collection around it is rebuilt (add, remove) is lost.

### Group A3, tools (2026-11-04): local branch a3-tools

Design 5.10 and stages 14 and 15 (decisions UI3, UI27, UI28): the developer console (new module `ui-console`), the
immediate-mode façade, the UI debugger, pseudo-localisation, the text scale and the palette. The OS accessibility
bridge was assessed and not built (below).

#### Built

| Part | Built | Where |
|---|---|---|
| Commands | `ConsoleCommand.named("spawn").describe(..).arg(Arg.integer("count").range(1, 100)).arg(Arg.choice("kind", ..).optional()).developer().cheat().action(call -> ..)`: typed arguments (`integer`, `decimal`, `bool`, `text` (`rest()`: the rest of the line), `choice` (strings or an enum), `asset(type)` and `node()` through the engine's `AssetProvider` and `NodeProvider` (completion and an existence check), `setting()`, `command()`, `script()`), checked before the action runs, read by name from the `Invocation`; tiers and the cheat flag through `ConsolePermissions` (the refusal says why; refused commands are not offered) | `ui-console` |
| Parser | `CommandLine`: words, `"double"` quotes with `\"` `\\` `\n` `\t` `\;`, `'single'` quotes, a backslash outside quotes, `;` between statements, `//` comments; tokens keep their positions; an unclosed quote runs to the end (the line is being typed); `quote()` round trips | `CommandLine` |
| Shell | `ConsoleShell`: commands, aliases (`alias low "render.bloom OFF; render.scale"`, the words after an alias appended), `exec file` through a `ScriptSource`, nesting capped by `ui.console.execDepth`, built-ins `help`, `alias`, `unalias`, `exec`, `echo`, `reset`, `toggle`, `find`; an unknown name suggests the nearest (two edits away); output to a `LogSink` with logger `console`, stamped by a settable clock | `ConsoleShell`, `Builtins` |
| Settings as variables | `ConsoleVariables` (list, find) and `ConsoleVariable` (kind, value, default, min, max, choices, availability, `set` returning why a value is refused) are what the engine implements over its settings and the constraint overlay; `render.bloom` prints `render.bloom = CHEAP (default CHEAP; one of OFF, CHEAP, PHYSICAL): Bloom technique [unavailable: ...]`, `render.bloom physical` sets it (choices ignore case; numbers are checked against their kind and range first) or prints the overlay's reason; `TunableVariables` exposes `Ui.tunables()` where no engine stands behind the console; `ConsoleVariables.of` joins several | `ConsoleVariables`, `ConsoleVariable`, `TunableVariables` |
| Completion | `ConsoleShell.complete(line, caret)`: the word under the caret in its statement; first words complete commands (allowed ones), aliases and settings (by prefix, then containing), each with a detail (description, `= value`); arguments complete by their `Arg` (choices, on/off, assets, nodes, settings, commands, scripts), setting values by kind (`current` marked); the hint is the signature with the argument at the caret marked (`spawn <count: 1..100> [kind: orc\|elf]`); `apply` quotes candidates that need it; `commonPrefix` | `Completer`, `Completion` |
| History | `ConsoleHistory(appId, HistoryStore)`: older and newer, keeping the line being typed; repeats kept once; `ui.console.historyLimit` lines; saved after each run through the engine's store (`HistoryStore.memory()` for tests) | `ConsoleHistory`, `HistoryStore` |
| Log ring (UI28) | `LogSink.log(level, logger, message, source, millis)` of preformatted records; `LogRing`: `ui.console.logCapacity` records made once, text copied into their own buffers (cut at `ui.console.maxLine` with an ellipsis, control characters flattened), a record equal to the last one coalesced (`x3`), loggers interned once; `LogInbox`: any thread, a short lock, the oldest dropped when full, drained once a frame (a burst larger than the ring hands over only what the ring keeps); both allocation-free | `LogRing`, `LogInbox`, `LogRecord`, `LogLevel` |
| Log view | `LogRows`: the records a view shows, filtered by a level mask, a logger (and the loggers under it) and an incremental search (a longer search narrows the matches, another scans the ring once), kept as a ring of sequence numbers; new records join and gone ones leave once a frame. `LogView` (a `VirtualView`): rows of time, level, logger (short name), message, repeat count and source, with the fact `level` on the row; it follows the newest record while scrolled to the end; a click on a logger filters by it, on a source copies it to the clipboard | `LogRows`, `LogView` |
| Console widget | `Console`: its own layer (`console`, z 600), dropping down from the top by the theme's `console` and `console:expanded` offsets and their transition; `CONSOLE_TOGGLE` from anywhere (the focus goes to the command line and back); the filter bar (level toggles `:checked`, the logger chip, a search field); the command line: Enter runs the line (echoed at level `command`, added to the history), `COMPLETE` takes the only candidate, else the common start, else picks the first (the next press takes it), `HISTORY_UP/DOWN` (and UP, DOWN) walk the pop-up while it lists candidates and the history otherwise, `CANCEL` closes the pop-up, then the console; the pop-up shows the hint and up to `ui.console.completions` candidates (a click takes one); commands `clear`, `log.level`, `log.logger`, `log.search`, `history`; `cmd:` links of rich text run commands (`linkListener()`, shown in the gallery) | `Console`, `_console.tss`, `_console-test.tss` |
| Debugger commands | `DebugCommands.register(shell, debugger)`: `ui.inspect [on\|off]`, `ui.overlay <bounds\|clips\|picking\|dirty> [on\|off]`, `ui.pick` (developer commands) | `DebugCommands` |
| Immediate-mode façade (UI3) | `Imm`: `begin`/`end` windows (moved by their title; on the `imm` layer or a host), `beginGroup`/`endGroup`, `beginChild`/`endChild` (a region the wheel scrolls), `pushId`/`popId`, `sameLine`, `text`, `labelText`, `separator`, `button`, `selectable`, `checkbox`, `slider`/`sliderInt`, `drag`, `inputText` (a `StringBuilder` both ways), `combo`, `treeNode`/`treePop`, `beginTable`/`tableNextRow`/`tableNextColumn`/`endTable`; ids hash the label (`label##id`) in their scope; elements are reused while called, collected in the frame they were not, moved only when the calls' order changes; a frame's input shows in its calls (no latency); the theme's own `button`, `checkbox`, `textfield` and `separator` types and new `imm-*` types; misuse (an id twice, unbalanced calls) throws | `controls.imm`, `_tools.tss`, `_tools-test.tss` |
| UI debugger | `UiDebugger`, written with `Imm` on the `inspector` layer (above the debug layer): the element tree (open along the selection), picking by pointer (the press goes to the debugger), the selected element's id path, style name, states, facts, rectangle and every resolved value with its rule and `file:line` (`StyleResolver.explain`; `Rule.source` from the compiler); a frame window from `FrameStats` (input, frame code, styles, layout, record, total, passes, recorded elements, upload bytes); overlays on the debug layer drawn by a painter: bounds, clips, picking grid cells (`Picking.grid`, `cellCount`), dirty (the elements the last frame recorded), hover and selection, their looks from the parts `debug-bounds` to `debug-selected` | `controls.debug`, `FrameStats`, `StyleResolver.explain`, `Rule`, `Picking` |
| Text and colour settings | `ui.textScale` (0.5 to 3) multiplies every resolved `font-size` in the resolver (labels, fields, `em` sizes; one re-resolve when it changes); `ui.palette` (`theme`, `dark`, `light`, `high-contrast`) switches the theme's token set (both themes have a `high-contrast` set, from A1); `ui.l10n.pseudo` (`off`, `on`, `mirrored`) and `ui.l10n.pseudoExpansion` (0.35) show every label accented, longer by its own letters and bracketed (mirrored: forced right to left), accessible names keeping the real text | `StyleResolver.TEXT_SCALE`, `Ui.PALETTE`, `PseudoLocale` |
| Labels on one line | `text-wrap: false` (`Styles.TEXT_WRAP`) shapes a label without wrapping (log rows; clip the box to cut it) | `Styles`, `Ui` |
| Galleries | `ConsoleGallery` (ui-console test fixtures: the console open over a HUD with `cmd:` links, every level, a coalesced repeat, `spawn 3 ` typed with the pop-up and the hint), `ImmGallery` (a spawner tool and a scene tool), `DebuggerGallery` (the debugger over the controls gallery, bounds and clips on, a button selected) | test fixtures |

#### Decisions

- *The console widget lives in `ui-console`* with its model (the design's module), above `ui-controls`; so
  `GallerySanityTest` cannot see its gallery: `ConsoleGalleryTest` runs `LayoutSanity` on it in both themes, and its
  tunables are listed by `ConsoleTunables.all()`, not `Ui.tunables()`.
- *The log keeps text, not argument objects*: records are preformatted by the sink (SLF4J formats before it hands
  them over), so the ring holds no references and appending is a copy.
- *The completion pop-up sits in the console's layer*, placed under the command line after layout, rather than an
  `Overlay`: it moves with the console's drop and needs no other layer.
- *Imm builds its widgets from elements*, not from `Controls`: the basic controls register for polling for good and
  have no destroy path, while the façade's widgets come and go with the calls. They use the theme's types where they
  exist.
- *The debugger draws its overlays with a painter on the debug layer and its windows on a layer above it*, so the
  overlays never cover the inspector; picking puts a blocker on that layer so the press selects instead of clicking.
- *The text scale is applied in the resolver*, at one place, so every control (editors and painters included) and
  every `em` size follows; a change re-resolves every style once.
- *Pseudo-localisation pads with the label's own letters* (about the width of real added letters), not dots.
- *A `FontRegistry` fix*: chain lookups compared family lists with `List.equals`, which made an iterator per reshape
  when two rules gave equal lists (the console's log rows found it); they compare by index now.

#### Tests

ui-console: `CommandLineTest` (14 parser fixtures, positions, unclosed quotes, the statement at the caret, quoting
round trips), `CompletionTest` (21 completion fixtures, replacement and caret, quoting, hints with the marked
argument, details, the common prefix), `ConsoleShellTest` (typed arguments and their errors, permissions, settings
round trips with `FakeVariables`: set, refused by the overlay, out of range, locked, reset, toggle, text; tunables as
variables, nearest names, aliases with appended words and their nesting, scripts, built-ins, declarations, assets and
nodes), `ConsoleHistoryTest`, `LogRingTest` (wrapping, coalescing, cutting, interning, filters, incremental search, a
wrapped ring, changes in place, four threads through the inbox, a full inbox, a burst larger than the ring; appending
and filtering allocation-free), `ConsoleTest` (toggle and focus, Enter, the three steps of Tab, a click on a
candidate, history, links, the filter bar, logger and source clicks, the inbox, following the end; frames idle,
flooded with 200 records a frame and scrolling allocation-free), `ConsoleGalleryTest` (both themes: `LayoutSanity`,
every interactive node named, previews), `DebugCommandsTest`. ui-controls: `ImmTest` (state by id, a click in its own
frame, same line, collection and return, order following the calls, every widget's values, tree nodes, tables,
moving windows, misuse, named widgets; steady frames idle, changing and hovered allocation-free), `UiDebuggerTest`
(inspector text, explain with `file:line`, picking, timings, overlays, the tree), `TextSettingsTest` (the transform,
labels, text scale, palette, the controls gallery pseudo-localised), `GallerySanityTest` (`immGallery`,
`debuggerGallery`, both themes), `ToolsPreviewTest`. ui-style: `StyleExplainTest` (origins of inline values and rules
with their lines, Java rules, the text scale, live font sizes).

#### Benchmarks (Ryzen 9 9950X3D, without JaCoCo; other agents were building at the same time: noisy)

| Target | Budget | Measured |
|---|---|---|
| Log flood: 1,000 lines a frame into the open console (drain, filter, the visible rows bound, record), 1920 x 1080 | 0.3 ms (section 11's changing HUD) | 0.35 to 0.53 ms median, 0.63 to 0.77 ms p99 (`ConsoleBenchmarkTest`); layout is 0.33 ms of it: the ~16 visible rows show new records every frame, ~96 labels shaped anew |
| Log flood: 100,000 lines from another thread shown in one frame | — | 1.4 ms median (the ring keeps 10,000; a burst hands over only those) |
| Appending 1,000 lines to the ring | — | 0.047 to 0.058 ms median (about 50 ns a line) |

#### OS accessibility bridge (UI27): assessed here, built since (section "OS accessibility bridge (2026-11-05)")

The UI side is ready: `Semantics.of(store, interaction)` gives every layer's tree with roles, names (live text),
states and actions, and every new control here is named (the galleries' `Semantics.unnamed` is empty). A Windows UI
Automation bridge through FFM needs, in the engine (`engine-platform`, Windows only):

- COM objects written in FFM: a vtable of upcall stubs and a reference count per object for `IUnknown`,
  `IRawElementProviderSimple` (provider options, `GetPropertyValue` returning `VARIANT`s: `BSTR` names through
  `SysAllocString`, control types, `VT_BOOL` states), `IRawElementProviderFragment` (`Navigate` to parent, siblings
  and children, `GetRuntimeId` as a `SAFEARRAY`, `BoundingRectangle` in screen pixels, `SetFocus`) and
  `IRawElementProviderFragmentRoot` (`ElementProviderFromPoint`, `GetFocus`); `IInvokeProvider`, `IToggleProvider`
  and `IValueProvider` for buttons, checkboxes and fields;
- answering `WM_GETOBJECT` with `UiaReturnRawElementProvider`: SDL's window procedure subclassed
  (`SetWindowLongPtrW(GWLP_WNDPROC)` with an upcall, `CallWindowProcW` for the rest; SDL's message hook cannot
  return a result), the `HWND` from `SDL_PROP_WINDOW_WIN32_HWND_POINTER`;
- a snapshot of the semantics tree taken on the UI thread when the store's version changed, read by UIA's calls on
  its own threads;
- `UiaRaiseAutomationEvent` for focus changes (what Narrator follows) and property-change events for names, values
  and toggles.

That is some 1,500 lines of COM by hand, checkable only by hand with Narrator or by another 1,000 lines of an
`IUIAutomation` client in FFM for a test, on Windows only; a minimal slice (roles, names, focus) still needs every
interface above except the patterns. Not built in this group; nothing outside the JDK is needed when it is.

#### Left

- The engine's parts: the SLF4J provider writing to a `LogInbox`, settings as `ConsoleVariables`, the
  `HistoryStore`, `ScriptSource`, `AssetProvider`, `NodeProvider` and `ConsolePermissions`, the `ui.console.toggle`
  action producing `CONSOLE_TOGGLE` (and `COMPLETE`, `HISTORY_UP/DOWN` while the command line has the text context),
  registering `ConsoleTunables.all()`, and the console as a world panel in VR (a world-space `Ui` holding the
  console and a virtual keyboard, anchored to the hand or in front).
- The log flood at 1,000 lines a frame is above 0.3 ms: shaping the visible rows' new text dominates (a cache of
  shaped runs for repeated messages would help); to measure again on a quiet machine.
- The controls gallery pseudo-localised shows truncation and overflow in other areas' controls (`TextSettingsTest`
  prints them); not fixed here.
- ~~The OS accessibility bridge (above).~~ Built (section "OS accessibility bridge (2026-11-05)").

### Group A3, scene (2026-11-04): local branch a3-scene

Scene picking, world panels and camera views (`ui-scene`), and 3D gizmos with the view cube and XR grabs
(`ui-gizmo`): stage 4's leftovers and stage 12. Both modules sit on `ui-controls` (they draw and style through `Ui`)
and `scene-core`; scene gains one method (below), on the local scene branch `a3-scene-raycast`.

#### Built

| Part | Built | Where |
|---|---|---|
| Picking beyond the screen | `WorldPicking` in `ui-core`: after the screen layers' hit test (unless a blocking layer stopped it) the scene bridge appends what each pointer's world ray hits, nearest first, as elements standing for scene nodes and panels (on a hidden `scene` layer that takes no screen input and draws nothing), so the one `Interaction` routes them: capture and bubble, pass-through, pointer capture, hover, clicks with counts, long press, context, wheel, tooltips, drag and drop with one `DragSession`. `Pointer.ray` (doubles) for XR pointers and screen pointers whose ray the engine knows | `ui-core` `event.WorldPicking`, `Interaction.worldPicking`, `input.Pointer` |
| `Interactive` | The component (a sparse set by node slot in `ScenePicking`; scene-core has no component stores yet, D46): listeners for any mask through `UiListener`, capture listeners, narrow phase `BOUNDS`, `MESH` (`PickMesh`) or `CUSTOM` (`NarrowPhase`), hooks for a tooltip (a scene tooltip overlay anchored to the node's projected box), a context menu (`Menu.openAt`), a drag payload and accepted drop formats. Adding puts the node on the interactive `SceneLayer`; a node's element sits under its nearest interactive scene-graph ancestor's, so events bubble up the scene graph; `node(event)`, `targetNode`, `hitPoint`, `hitDistance`, `ray(pointerId)`, `hovered`, `pressed` | `ui-scene` `Interactive`, `ScenePicking` |
| Scene picking | `SpatialIndex.raycast(..., interactive mask, RayFilter, hit)` with each node's narrow phase; up to `ui.pick.sceneHits` objects along the ray (each further one a raycast skipping those found) for pass-through; screen pointers take the ray of the camera view they are over or the main `SceneView`, XR pointers their own (`ui.pick.rayLength`) | `ScenePicking.pick` |
| Mesh narrow phase | `PickMesh`: positions and indices in the mesh's space, a triangle `Bvh` (common-math) built on the first ray that reaches the mesh, shared by every node showing it (the engine keeps it with the mesh asset); the world ray goes into the node's space (translation, rotation, per-axis scale), distances stay those of the world ray | `PickMesh`, `ScenePicking.mesh` |
| World panels | `WorldPanel`: a world-space `Ui` on a rectangle placed in the world or following a node; the ray against its plane, then the panel UI's own 2D hit test at the local pixel (a panel where nothing is hit lets the ray through unless it is opaque, `passThrough(false)`), merged by distance with the scene's hits; `ScenePicking.input` hands each panel the pointers whose front-most hit it is (or that its UI captured, at their point on its plane even off it) and `update` updates the panels at their sizes. `transform()` gives the engine the panel's world transform and pixel size for drawing | `WorldPanel`, `ScenePicking.input/update` |
| `SceneView`, anchors | A camera as the UI sees it (doubles, camera-relative maths): rays from UI points, projection, units per pixel, perspective or orthographic, a viewport in UI pixels. `SceneAnchor`: an `OverlayAnchor` at a world point or a node's projected box | `SceneView`, `SceneAnchor` |
| Camera views | `CameraView`: a `camera-view` element showing the newest finished image of a `CameraViewSource` (a `UiTexture` and the image's size in it, asked for at the control's pixel size every frame; frames in flight show the last finished image, stretched, so the main view waits for nothing), its frame from the theme, tinted by `color`; pointers over it pick through its camera; markers are overlays anchored through its view (`marker`, `anchor`). `FakeCameraSource` (test fixture) renders a map picture a frame after each request | `CameraView`, `CameraViewSource`, `scene/src/testFixtures` |
| Gizmos | `Gizmo`: translate (axes, planes, screen centre), rotate (axis rings, view ring, trackball), scale (axes, uniform), bounds (8 corners, 6 faces, the opposite side fixed), universal; spaces local, world, parent, view (`GIZMO_TOGGLE_SPACE` cycles, `GIZMO_PREVIOUS_SPACE` back); pivots (`GIZMO_NEXT_PIVOT`/`GIZMO_PREVIOUS_PIVOT` cycle) median, individual, active, cursor, bounds centre; a multi-node selection (children of selected nodes follow their parents, not moved twice); `GIZMO_TRANSLATE/ROTATE/SCALE/BOUNDS/UNIVERSAL`, `GIZMO_NEXT_MODE`; `GIZMO_CANCEL`, `CANCEL` or a right click cancel and restore, `ACCEPT` commits; snapping to `ui.gizmo.snapTranslate/snapRotate/snapScale` while `Pointer.GIZMO_SNAP` is held (or the reverse, `ui.gizmo.snapByDefault`); typed values while dragging (distance, degrees, factor; `DELETE_BACK` edits); `GizmoListener` begin, update, commit, cancel with `GizmoTransforms` (local transforms for undo) | `ui-gizmo` `Gizmo`, `GizmoSolver`, `GizmoApply`, `GizmoShapes` |
| Drag solving | In doubles relative to the gizmo's origin: the ray against the axis line (closest points), the handle's plane, the view plane; rings by the angle on their plane, or by the pointer's angle about the projected centre when seen edge-on (as Blender does); the trackball by screen motion; results applied to each node's world transform about the pivot, then into its parent's space (`setLocal`) | `GizmoSolver`, `GizmoApply` |
| Gizmo picking and drawing | Analytic shapes (segments, rings of 48 segments, squares, spheres) against the ray with `ui.gizmo.pickTolerance` pixels (XR rays: `ui.gizmo.xrPickAngle` degrees), the nearest miss winning, before the scene's objects (`HandlePicker`); axes seen end-on and planes edge-on are hidden. Drawn as projected primitives on the `overlay` layer at a screen-constant size (`gizmo-size` height; XR: `ui.gizmo.xrAngularSize` from the head), filled shapes (planes, arrow tips, the view cube's faces) as triangles (`DrawList.triangle`); every colour, width and size from style parts, `:hover` on the hovered handle's part, `:pressed` while dragged. Hidden parts faded (UI26) by `gizmo-occluded`'s opacity as a `GizmoOcclusion` says: the back halves of rings always; the scene by `RayOcclusion` (the `SpatialIndex` by boxes, the selection ignored) or the engine's depth test | `Gizmo`, `GizmoShapes`, `GizmoOcclusion`, `RayOcclusion` |
| View cube | `ViewCube`: a `view-cube` element with the cube turned as the camera sees it (faces, edges, a hovered marker from parts); click a face, edge or corner: `Listener.align` with the camera rotation looking from there (world up kept); drag: `Listener.orbit` (`ui.viewCube.orbitSpeed`) | `ViewCube` |
| XR | Ray picking and dragging of handles; `GizmoGrab`: a direct grab (a grip with the hand in a selected node's box, `ui.gizmo.xrGrabRadius`, or at the gizmo's centre) follows the hand rigidly; two hands move with their midpoint, turn with the line between them and scale with their distance; snapping by a held snap; the same undo hooks; fed from the `ui.gizmo` grab actions and hand poses by engine-ui's `XrGizmoGrab` (group A3, gizmoact) | `GizmoGrab` |
| Styles and settings | `_scene.tss` and `_scene-test.tss` (one `@import` each in both themes): `camera-view`, `gizmo` and its parts, `view-cube` and its parts, `panel.minimap-marker`; no new style keys (parts read `color`, `opacity`, `width`, `height`, `radius`). Tunables reach `Ui.tunables()` through a new `TunableHolder` service (`ui-core`), which `Ui.tunables()` loads: `SceneTunables`, `GizmoTunables` | themes, `Ui.tunables`, `TunableHolder` |
| Gallery | `SceneGallery` (`ui-gizmo` test fixture): a synthetic scene behind five gizmos (one per mode, one hovered, one partly occluded), a view cube, a minimap with a fake picture and a marker; in `GallerySanityTest` and `ScenePreviewTest` (both themes) | `gizmo/src/testFixtures` |
| scene | `SpatialIndex.raycast(..., layerMask, RayFilter, Hit)`: a narrow phase decides each reached node's distance (or a miss); boxes beyond the nearest hit so far are not tested | scene `a3-scene-raycast` (ddadbd5) |

#### Decisions

- *Scene nodes and panels as elements*, not a second event system: each interactive node and panel has an element of
  its own on a hidden layer, so routing, capture, click counts, long presses, drag sessions and states are the screen
  UI's code. Costs an element per interactive node (1,000 at 1%), allocated when the component is added.
- *A narrow phase inside the index's raycast* (the one scene change): without it the index answers only the nearest
  box, and a mesh missed inside its box would hide everything behind it.
- *World panels are UIs of their own* (`worldSpace`), fed pointers by the bridge; their elements route within their
  own UI. A drag session therefore does not cross from a world panel to the scene or the screen UI (screen UI and
  scene nodes share one session).
- *A camera view element is transparent to clicks* unless it listens itself: pointers over it pick through its camera
  by its rectangle, so a click on the minimap reaches the scene object under it.
- *Gizmos drawn by the UI* as projected lines and boxes on the overlay layer (the engine needs nothing to show them);
  ~~filled planes, tips and cube faces are abutting lines until the draw list has a polygon primitive~~: triangles since the merge.
- *Occlusion through an interface*: the engine's depth test is exact; `RayOcclusion` (boxes, this frame) works without
  it. Hidden parts are faded, never hidden (UI26).
- *Drags update in the frame phase* from the captured pointer's ray each frame, not from MOVE events, so XR rays (whose
  screen position does not move) drag as mice do.

#### Tests

- `ScenePickingTest`: clicks with counts, hover states, pass-through to nodes behind and from screen elements, capture
  off the node, bubbling to interactive ancestors (and back on removal), mesh triangles against boxes, custom narrow
  phases, XR recorded poses, drag and drop from a list slot to a 3D node and back in one session, world panels (local
  points, pass-through, opaque, following a node, capture off the rectangle), context menus, tooltips, wheel and long
  press on nodes, camera views picking through their camera, pruning.
- `ScenePickingBenchmarkTest`: counted narrow-phase visits at 100,000 nodes (tests only of interactive nodes whose
  boxes the ray enters) and the timing benchmark below. `SceneAllocationTest`: pointers over nodes, meshes, a panel
  and a minimap, presses, drags, an XR ray sweeping: allocation-free.
- `GizmoTest`: the X arrow dragged 10,000 km from the origin moves the node exactly along x; snapping; typed values and
  cancel restoring the start (undo hooks in order); every pivot for two nodes 10,000 km out; scale by axis, uniform
  and a bounds face; spaces with a turned parent; trackball, view ring, a right click cancelling; the pixel tolerance.
  `GizmoXrAndViewCubeTest`: an XR ray picking and dragging a handle; a recorded hand grab (rigid follow), two-handed
  scale, cancel; the view cube's alignments, hover, click and orbit. `WorldPickingTest` (`ui-core`): world hits after
  screen hits, pass-through, a blocking layer.
- `GallerySanityTest.sceneGallery` (both themes); CPU previews `scene-gallery.png`, `scene-gallery-test-theme.png`.

#### Benchmarks (Ryzen 9 9950X3D, without JaCoCo, other builds running: noisy)

| Target | Budget | Measured |
|---|---|---|
| Picking per pointer, 100,000 nodes (a fifth dynamic), 1% interactive, bounds | ≤ 20 µs | 3.6 µs median, 13.3 µs p99 (`ScenePickingBenchmarkTest`, half the pointers on interactive nodes) |
| The same, mesh narrow phase (320-triangle spheres) | ≤ 50 µs | 3.2 µs median, 11.5 µs p99 |
| Narrow-phase tests of nodes without `Interactive` | 0 | 0 (1.15 tests a pick, all of interactive nodes the ray reaches) |

#### Left

- GPU picking (ID buffer, TLAS ray query; UI5: skinned objects) and its benchmark against the CPU path: the engine's.
- Vertex and surface snapping for gizmos (through `SpatialIndex` and mesh BVHs), typed values for plane handles and
  bounds (two or three numbers), axis constraint keys (Blender's G then X).
- A polygon (or quad) primitive in the draw list would draw plane handles, arrow tips and cube faces exactly.
- Drag sessions across world panels and the scene; component stores in scene-core (D46) to hold `Interactive`.

### Window options (2026-11-04): local branches `a3-window-options` (ui and engine), not yet in CI

Two options of docking's windows: a paper white per window, and windows see-through while dragged.

#### Built

| Part | Built | Where |
|---|---|---|
| Paper white per window | `OsDockHost.paperWhite(id, nits)` gives a window (`OsWindows.MAIN`: the main one) a paper white of its own; NaN follows the one every window follows again (the engine's `display.paperWhite`, which stays the default). `OsWindows.paperWhite` hands it to the engine. The layout saves it beside the window's place: `window <id> "<monitor>" <x> <y> <w> <h> [maximized] [paper-white <nits>] <node>`, and `main paper-white <nits>` for the main window (`DockLayout.mainPaperWhite`, `DockHost.mainPaperWhite`); saved once settled, like a move | `OsDockHost`, `OsWindows`, `DockLayout`, `DockHost`, `DockSpace` |
| Engine | Each OS window's presenter target reads a view of the graph's settings of its own (`EngineSettings.inheriting("ui.window<id>")`: a scoped view that reads its parent's value of every setting it holds none of, and hears its parent's changes); `UiOsWindows.paperWhite` sets `display.paperWhite` in that view's game layer, clamped to the setting's range, so the player's saved settings never hold it (the layout does). The main window's target gets such a view on its first paper white (`Presenter.Target.displaySettings`). `DisplayTransform` reads the settings its target hands it (`OutputTransform.transform(..., settings)`), the graph's by default | engine `settings`, `graph`, `render`, `ui` |
| Translucent while dragged | `drag-opacity` (`WindowStyles.DRAG_OPACITY`, 0.05 to 1; 1 is off, the fallback): a floating `window` moved by its title bar draws its subtree at its style's opacity times it (a `Visuals` override set on drag start, cleared on drop or cancel, so no frame of delay); the OS window following a drag asks the system (`OsWindows.opacity`, SDL's window opacity), once when it starts following and again only if the style changes, opaque again on drop. Where the system refuses (Wayland without the protocol, SDL's offscreen driver) the window stays opaque. Default theme 0.6 on `window` and `dock-window` (dock targets show through clearly, the window's content stays readable); test theme 0.35 and 0.45 | `FloatingWindow`, `OsDockHost`, `WindowStyles`, `_windows*.tss`, `_oswindows*.tss` |

#### Decisions

- *A window's value in a settings view, not a constant beside the transform*: the transform keeps reading settings
  when frames record (no rebuild, no delay), and an unset window follows the global value through the view.
- *The game layer of the window's view*: per-window values belong to the layout, which knows the window across runs;
  the window's settings scope (`ui.window<id>`) does not outlive the window.
- *Every floating window, not only undocked panels*: a moved window also snaps to edges behind it; a theme that wants
  it only for docking sets `drag-opacity` on a narrower selector.

#### Tests

ui: `OsDockTest.eachWindowMayHaveAPaperWhiteOfItsOwnWhichTheLayoutSavesBesideItsPlace` (two windows, one with its
own value, the main one too; saved once settled, round trip, restored in a new space, cleared, the format and its
errors), `aWindowFollowingADragIsSeeThroughUntilDroppedWhereTheSystemCan` (default and test theme, asked once a drag,
opaque on drop and on cancel, a system that refuses); `WindowTest.whileMovedByItsTitleBarAWindowIsSeeThroughAndOpaqueAgainWhenLetGoOrCancelled`.
Engine: `EngineSettingsTest.anInheritingViewFollowsItsParentUntilItHoldsAValueOfItsOwn`;
`UiCompositionTest.aWindowWithItsOwnPaperWhiteShowsTheUiAtItWhileOthersFollowTheGlobalOne` (HDR10 white measured
at the window's value, unmoved by the global one, the global one again when cleared); `OsWindowsPartsTest` and
`OsWindowsTest` (each window's target reads its own view, the main window's on its first value, clamping, the
layout line; opacity checked only where SDL accepts it, as `SDL_SetWindowOpacity` reports).

#### Left

- The UI's brightness (`ui.brightness`) is still one for all windows (the same view would carry it).
- Wayland and the offscreen driver: dragged OS windows stay opaque.

### Group A2 fixes (2026-11-04): local branches `a2-fixes` (ui) and `ui-a2-fixes` (engine), not yet in CI

The defects the GPU screenshots of the A2 galleries showed, a check that finds their kinds in any UI, the frame in
two phases (no frame of latency for the game's changes), built-in icons and touch selection handles.

#### Built

| Item | Cause and fix | Where |
|---|---|---|
| Data gallery: a section's rows at one place | Style names are inherited by unnamed children; the gallery's root is `panel.hud`, so every unnamed panel in it (a section's body) matched `panel.hud { layout: anchors; anchors: full }` and anchored all its rows to its corner (a minimal pane without a hud root stacked them). The HUD root's rule now applies to the root only (`root > panel.hud, layer > panel.hud`) | `default-theme.tss` |
| Stuck section headers over their rows | The header moved by its holder's scroll offset, but the `scroll-sticky` bar's background stayed behind, so the stuck title drew over the rows. `ScrollPane` scrolls an unstyled `scroll-stick` holding the bar; the default bar is opaque (the surface is 86 % opaque) | `controls.data` `ScrollPane`, `_data.tss` |
| Tab under the overflow button | With `tab-overflow: menu` (now the default theme's) the strip shows only tabs it holds whole, the selected one always; the others are drawn transparent and not pickable (their places stay: hiding changes no layout, so no oscillation) and reached through the menu (`TabPane.overflowing(i)`). Scroll mode scrolls and clips before the button; the test theme wraps | `TabPane` |
| Carousel: slides floating, mostly empty | The viewport is a surface of its own (96 px), clips, shows one page; a page lays its content over itself, stretched (`layout: overlay`), so a slide fills it; arrows beside, dots right below | `_basic.tss`, gallery |
| Split pane | Both panes are surfaces (background, border, padding, clip), the splitter visible | `_basic.tss` |
| Window buttons without icons | `BuiltinIcons`: the toolkit's own icons (close, dismiss, minimise, maximise, restore, more, previous, next, up, down, the drop-down, collapsible, menu and breadcrumb arrows, chevrons, check, minus, plus, eye, eye-off, search, grip), distance fields of lines, triangles, circles and rings on a 16-unit grid, rendered once at 32 texels when a style first names `icons/<name>`, tinted by the style's image colour; `UiImages.get` falls back to them, a game's image under the same name wins. Each `window-button` holds an `image.icon` of its id (`restore` while maximised) | `controls` `BuiltinIcons`, `UiImages`, `FloatingWindow`, `_windows.tss` |
| Test theme: rich text over its neighbours | `richtext` clips in both themes (it scrolls when taller than its box) | `_text-edit*.tss` |
| A clipping label's text drew outside it | Text and image content were recorded under the parent's clip only; all content now switches to the element's own clip, as painted content did (found by the check below) | `Ui` |
| More | Gallery: the drawer's row lost its flex row to a second inline style; sized panels are minimum sizes (the test theme's text overflowed them); test theme: date cells, badges and step numbers sized for its text | gallery, `_basic-test.tss` |
| `LayoutSanity` | `LayoutSanity.check(ui)` (ui-testkit) lists a laid-out, recorded UI's defects over every layer, from what is drawn (each range's primitives where they draw, cut by their clips; glyphs by their inked atlas texels; transparent primitives, shadows and focus rings left out; boxes transformed as their primitives): `CLIP` (drawn outside the nearest clipping ancestor's clip), `OVERLAP` (siblings of a stack, flex or grid overlap; not overlay, anchors, border or absolute layouts or negative margins), `TEXT_OVERFLOW` (ink leaves the box; sideways up to 0.4 of the ink's height for italics), `TEXT_OVERLAP` (two elements' glyphs overlap with nothing opaque drawn between them: it found the data gallery's rows, which `OVERLAP` cannot see in an anchors layout), `TAB_UNDER_OVERFLOW`, `TAB_PARTIAL`. `assertSane(ui, what)` throws with the list. `GallerySanityTest` runs it on every gallery in both themes with the real text path (controls, with its overlays open, while the carousel turns; data; windows; text) | `ui-testkit`, `controls` tests |
| Two-phase frame | `Ui.input(input, nanos)` at the frame's start (picking, events, states, focus, commands, text: the game's update sees this frame's clicks); `Ui.update(width, height)` after the game's update (listeners, animations, styles, layout, recording: what the input and the game changed shows in this frame). `Ui.input()` keeps returning the frame's input in the second phase, so popovers and tooltips see the pointer; `update(w, h, input, nanos)` does both. Engine: `UiPlugin` runs `input` in `beforeUpdate` and `update`, the submit and the cursor in `afterUpdate` (after the game's update and late update); `UiPlugin.pointers(source)` adds pointer sources | `Ui`, engine `UiPlugin` |
| Touch selection handles | A touch long press selects a word with a `selection-handle` (size, background, radius) at each end, centred on the line's bottom; touching within a line height of one drags that end (kept where grabbed, swapping past the other end); typing, a tap, a mouse press end them | `TextEditor`, `_text-edit*.tss` |

#### Decisions

- *A tab that does not fit is hidden, not taken out of the row*: taking it out would shrink the row, which would
  then fit and bring it back; transparent and unpickable, it keeps its place and the layout does not change.
- *Icons as small rendered textures*, not primitives: an image element already draws a texture tinted and fitted by
  its style; 32 texels with a texel of distance antialiasing stay crisp at the 10 to 16 px the themes use. Shapes are
  code, so no asset pipeline is needed and a game can still register its own.
- *The checker reads the draw lists*, not the layout alone: the defects users see are where things draw (clips,
  offsets, scales, text ink); layout-only checks missed the anchored rows and the label clip.
- *Two phases, one per side of the game's update*, instead of a second input-free update: input is processed once,
  everything after it once.

#### Left

- ~~Flex items shrink to their padding (Yoga's choice) instead of CSS's automatic minimum (min-content): a squeezed
  label's text overflows its box rather than the row overflowing. The galleries use minimum sizes; a layout change is
  its own step (the Yoga oracle tests pin the current behaviour).~~ Built: see "Group A3, render".
- `LayoutSanity` tolerates italic overhang by a share of the ink's height; it does not check text against elision
  (no element elides yet) nor overlaps across layers (pop-ups are meant to cover).
- Touch handles are not magnified (no loupe) and do not scroll a field while dragged past its edge.

### Group A2 (2026-11-04): built, local branches `a2-group` (ui) and `ui-a2` (engine), not yet in CI

The interaction core (A2 groundwork) and the four areas of the plan additions built on it in parallel (text editing;
overlays, windows and docking; basic and container controls; data controls), merged into one group with their
cross-branch work: one set of frame phases, the text field in the other areas' hooks, scrolling by clip offsets, the
engine's `UiPlugin` and GPU screenshots of every gallery. Engine-agnostic code is in `ui-core`, `ui-layout`, `ui-style`
and `ui-controls` (`com.truesim.ui.controls.{text,overlay,windows,basic,anim,data}`); the engine side is `engine-ui`.

#### Built

| Area | Built | Where |
|---|---|---|
| Interaction core | Layers (HUD, overlay, modal, popup, tooltip, drag, debug; input pass-through, block or none), picking by a uniform grid per layer, events with capture, target and bubble, drag and drop with typed payloads, states, focus per player with spatial navigation and modal scopes, commands and text routed to the focused element, semantics actions | `ui-core` `event`, `Ui` |
| Frame phases | `Ui.FrameListener` (`addFrameListener`): `frame(nanos)` after input, before animations, styles and layout; `afterLayout()` after each layout pass, with another styles-and-layout pass while listeners change styles, states or layout inputs (at most `Ui.MAX_PASSES`, 4). Layout inputs set in code are owned (`Ui.codeLayout`/`ownLayout`: kind and direction, placement, width, height, flex), so restyles keep them. Content a control draws itself: `Ui.paint` (a `Painter`), `repaint`, `remeasure`; style parts: `Ui.part` (hidden children whose resolved style a painter reads). `Ui.input()` is the frame's input | `Ui` |
| Text editing | Fields (caret, selection by pointer, touch and keys, every `ui.text` command, clipboard, coalesced undo, IME composition inline with the candidate window at the caret, BiDi caret movement, scrolling, placeholder, maximum length, filters and validators with `:invalid`, `:read-only`); `PasswordField`, `SearchField`, `NumberField`, `TagInput`; areas (wrap, Fenwick-indexed virtual paragraphs, line numbers, Tab policy); rich text runs and spans styled by `.tss` (`span.<class>`, `link`, `img`), BBCode `Markup`; colour emoji (Noto Color Emoji CBDT in `ui-fonts-emoji`, ZWJ, flags, skin tones), emoticon replacement | `controls.text`, `ui-text` `edit`, `ui-text-native` |
| Overlays and windows | `Overlays`/`Overlay` anchored to elements, points or scene objects, placed by `Placer` (side, align, gap, shift, flip, viewport margin; the side as fact `side`), pass-through, blocking or modal with a backdrop; overlays fade in and out by their `opacity` transition (input off while fading out); `Tooltips` (delay, warm moves, follow or anchored, long press, visible focus); `Popovers` (hover cards with a safe triangle and grace, click popovers, nesting); `WindowManager`/`FloatingWindow` (move with snapping, eight resize edges, minimise, maximise, close, modal, focus cycling, keyboard move and resize, `WindowLayout`); `DockSpace` (splits, tab groups, drop zones and preview, floating into windows and back, `DockLayout` round trip) | `controls.overlay`, `controls.windows` |
| Basic and container controls | `Button`, `ToggleButton`, `Checkbox` (`:indeterminate`), `RadioGroup`, `Switch`, `SegmentedControl`, `IconLabel`, `ImageView`, `Separator`, `Badge`, `ProgressBar`, `Spinner`, `Slider` (range, vertical), `ColorPicker`, `DatePicker`, `TimePicker`, `KeyBindingField`, `DropDown`, `Menu`/`MenuBar` (submenus with hover intent, context menus), `Dialog`, `Toasts`, `Toolbar`, `StatusBar`, `Breadcrumbs`, `TabPane`, `Collapsible`, `Accordion`, `GroupBox`, `Card`, `SplitPane`, `Carousel`, `Stepper`, `Drawer`; models are `common-state` versioned values polled once a frame by `Controls` | `controls.basic` |
| Animation | `Animator`: element channels (opacity, scale, drawn offset, colours, sizes, offsets, anchors) and controls' own, fast-forward reversal, CSS easings, `ui.animation.speed` (0: reduced motion); style transitions on restyle; `Visuals` per-element overrides; opacity and scale apply to the subtree; `DrawList.transform` draws scales and offsets without a renderer change | `controls.anim`, `ui-core` |
| Data controls | `Scroller`, `ScrollView` (stylable bars, auto-hide, touch momentum, rubber band, keys), `ScrollPane` with sticky headers; virtualization (`VirtualView`, fixed or Fenwick-measured extents, a moving origin for a million rows); `ValueRenderer`/`ValueEditor`/`ValueRenderers`; `ListView`, `DataView` (sorted and filtered, O(log n + B) a change), `TreeView` over `common-state`'s `VersionedTree` (lazy, drag and drop), `TableView` (sort, filter row, resize, move, frozen columns, inline editing, row, multi-row or cell selection, column virtualization) | `controls.data` |
| Cross-branch hooks | `SearchField` is a `DropDown.FilterSource` (it takes the focus when the list opens; `FilterSource.focusTarget`); `HexField` puts a hex text field in a `ColorPicker`'s hex slot; `TextCellEditor` is the `ValueEditor` of strings (Enter commits through `TextEditor.passSubmit`, Escape cancels, validators); `TextColumnFilter` puts a field in a table's filter row (contains, ignoring case). Styled as `textfield.cell`, `.column-filter`, `.hex` in both themes; the galleries use all four | `controls.text` |
| Scrolling by clip offsets | Each clip record carries an offset added to its primitives' coordinates (`DrawList.CLIP_FLOATS` 7, `setClipOffset`, `drawnX`/`drawnY`); the UI records an element in its clip's content space (its rectangle plus the clip owner's scroll), and a scroll region (`Ui.scrollRegion`: a virtual view's body, a scroll pane's viewport; any clipping element) moves its clip's offset when it scrolls, so its rows are not recorded again. Layout scroll shifts are whole pixels when snapping (`Layout.scrollPixelsX/Y`), so picking, which reads the layout, hits exactly where the content draws. The engine's shader moves each primitive's quad by its clip's offset and tests the clip's rectangle unmoved | `ui-core`, `ui-layout`, `Ui`, engine `ui/draw.slang` |
| Engine | `UiPlugin` (key `ui`): the `ui` action group, every UI tunable a setting (`UiTunables`, with `UiCommands.ALL`, group `ui`), each frame at its start `UiCommands`, `MousePointer` and `UiTextInput` into one `UiInput`, `ui.update(width, height, input, nanos)` with the view's size and the frame's real time, `GpuUiRenderer.submit`; with a window `SdlClipboard`, SDL text input, the display's content scale as the layout's, and the mouse cursor following the UI's (`Platform.setCursor`, `SystemCursor`). `UiTextInput`, `SdlClipboard`, `Window.startTextInput(kind)`, the colour glyph path of the shader (from `ui-text-input`) | engine `ui`, `platform` |
| Galleries | `ControlsGallery`, `DataGallery`, `WindowsScene` and `TextGallery` are `ui-controls` test fixtures (`java-test-fixtures`), used by its own tests and by the engine's `GalleryScreenshots` | `controls/src/testFixtures` |
| Styles | every key of the control packages registered as `StyleKeyHolder` services (one services file: `BasicStyles`, `DataStyles`, `OverlayStyles`, `WindowStyles`), loaded once by `StyleKeys.loadServices` when a sheet compiles; the partials `_text-edit`, `_basic`, `_data`, `_windows` and their garish counterparts, one `@use`/`@import` each in both themes; new states `:invalid`, `:read-only`, `:indeterminate` (bits 11, 12, 13) | `ui-style`, themes, `UiState` |
| Settings | `Ui.tunables()` lists every area's tunables once (39 with a text system's atlas settings besides): the frame and interaction, text editing, the basic controls, scrolling and lists, tooltips, hover cards and windows; `UiTunablesTest` finds every static `Tunable` of the UI modules by reflection and checks it is listed | `Ui` |

#### Decisions

- *One frame extension point.* The branches had four (`UiFrameHook` with a before-layout re-set of positions,
  `Updater` with a boolean second pass, `FrameListener` with `laidOut`, `onFrame`). Kept: a listener with `frame` and
  `afterLayout`, extra passes found from the dirty queue (not a returned flag), and code-owned layout inputs instead
  of setting them again before every pass (no work and no extra pass when a restyle happens). Content drawing
  (`paint`, `part`) stays separate: it is not a phase.
- *One style-key discovery.* `StyleKeyHolder` services, loaded once; `StyleKeyProvider` and `loadInstalled` went.
- *One type-ahead setting* (`ui.list.typeAheadTimeout`) for lists, drop-downs and menus.
- *Docking keeps its own tab strip and splitter.* `TabPane` and `SplitPane` were not substituted: docking's tabs
  drag across panes, to drop zones and out into windows through its own drag sessions and `DockHost`, and its
  splits hold any number of panes with ratios as flex factors, where `TabPane` reorders within one strip (pages taken
  out of the tree) and `SplitPane` splits two panes by a share. Replacing them would need drag-out hooks in `TabPane`
  and an N-way `SplitPane`, and give no benefit to either; they share the theme's looks instead.
- *Fades through the animator.* Overlays read their own `opacity` transition: fade in from transparent on the first
  styled frame, fade out before leaving the layer; without a transition or with animations off, at once. The
  overlay is logically hidden at once (focus, modality, `showing()`).
- *Scrolling by offset, not by re-recording*, with whole-pixel scroll shifts so draw and pick positions agree
  exactly; a non-region scroll (a sticky header) still records its content again.
- ~~*Engine frame wiring at the frame's start.*~~ The frame is two phases since the fixes below: input at the
  frame's start, the update after the game's.
- From the branches: BBCode markup, CBDT colour emoji on straight-alpha sRGB pages, style parts instead of
  control-specific keys (text); positions in code as layout inputs and overlays placed after layout in the same frame
  (windows); animated values in `Visuals`, never inline styles, and pages taken out of the tree rather than hidden
  (basic); hidden subtrees collapse and draw nothing (data).

#### Goldens regenerated by the merge

- Windows scene (`goldens/windows/scene-*`, both themes): content of hidden dock tabs no longer records (data's
  collapsed hidden subtrees); nested opacity multiplies, `scale` from styles draws, labels shape with a pixel of
  tolerance (basic). The scene now settles running transitions before its goldens.
- Basic gallery (`basic-gallery*.txt`): the searchable country drop-down and the hex field added by the hooks.
- `ListViewTest.drawListGolden`: rows draw under the body's region clip (index 2); positions unchanged.

#### Benchmarks (Ryzen 9 9950X3D, RX 9070 XT, without JaCoCo)

| Target | Budget | Measured |
|---|---|---|
| `TableView` 100,000 x 20 (1880 x 1000, 38 rows x 19 columns), scrolling | ≤ 0.3 ms | 0.077 ms median, 0.15 to 0.19 ms p99 (before clip offsets: 0.235 ms median, 0.38 to 0.46 ms p99) (`DataBenchmarkTest`) |
| `ListView` of 10,000 rows, scrolling | ≤ 0.1 ms | 0.0036 ms median, 0.010 to 0.012 ms p99 (before: 0.022, 0.036 to 0.045) |
| 1,000,000 rows, sorted and filtered, 100 changes a frame | ≤ 0.5 ms | 0.012 to 0.014 ms median, 0.025 to 0.047 ms p99 |
| 50 windows and 30 docked panels (1,091 elements): idle, dragging a window | 0.05 ms, 0.3 ms | 0.0074 ms (0.0080 p99), 0.031 ms (0.052 p99) (`WindowsBenchmarkTest`, windows branch) |
| Controls gallery (523 elements): idle animating, pointer moving | — | 1.0 µs (1.6 p99), 3.7 µs (7.3 p99) (`GalleryBenchmarkTest`, basic branch) |
| 1 MB text area: typing, scrolling | well under 1 ms | 0.045 ms (0.085 p99), 0.004 ms (0.022 p99) (`TextAreaTest`, text branch) |
| Picking, 2,001-element HUD | 5 µs | 0.017 µs front-most, 0.023 µs every hit (`PickingBenchmarkTest`, groundwork) |

Steady-state frames allocate nothing (allocation tests without escape analysis) in every area: interaction with two
pointers, text editing idle, blinking, scrolling and hovering, windows idle and dragging, the controls gallery with
hover, drags, wheel, keys and model changes, animations, lists and tables scrolling and changing.

#### GPU screenshots

`TRUESIM_SCREENSHOTS=true TRUESIM_SCREENSHOTS_DIR=<dir> ./gradlew :engine-ui:test --tests '*GalleryScreenshots'`
writes `ui-windows/windows-{default,test}.png`, `ui-controls/controls{,-overlays}-{default,test}.png`,
`ui-data/data-{default,test}.png` and `ui-text/text-{default,test}.png`, over the dark backdrop the CPU previews use.
Looked at: the GPU images match the CPU previews; the test theme restyles every element.

#### Left

- ~~The scroll pane of the data gallery lays the rows of a section's body on top of one another~~,
  ~~the game's changes to UI state show the next frame~~, ~~windows' title bar buttons draw no icons~~, ~~the tab
  strip's overflow button overlaps a clipped tab~~: fixed, see "Group A2 fixes" above.
- From the branches: ~~incremental shaping of very long paragraphs~~ (built: below), ~~touch selection handles~~,
  spell checking,
  grapheme-level password masking (text); OS windows for docking, bindings of the `ui` group for `keyboardMove`,
  `keyboardResize` and `focusNext` (windows); ~~a transform table in the draw list instead of transforms baked into
  primitives, clips following transforms~~ (built: "Group A3, render"), mnemonics, a repeat button, logarithmic sliders, HDR and an eyedropper in
  the colour picker (basic); ~~`VersionedTree` in `common-state`~~ (built: below), renderers chosen by a style key,
  ~~spell checking~~,
  ~~grapheme-level password masking~~ (built: group A3 controls) (text); OS windows for docking, bindings of the
  `ui` group for `keyboardMove`, `keyboardResize` and `focusNext` (the commands are handled since group A3 controls;
  the engine's bindings are left) (windows); a transform table in the draw list instead of transforms baked into
  primitives, clips following transforms, ~~mnemonics, a repeat button, logarithmic sliders, HDR and an eyedropper in
  the colour picker~~ (built: group A3 controls) (basic); ~~`VersionedTree` in `common-state`~~ (built: below), renderers chosen by a style key,
  records by their
  metadata, box select, hiding and grouping columns, horizontal list scrolling, a loading look for lazy tree nodes
  (data); ~~world panels, scene picking (`Interactive`, the mesh BVH)~~ (built: Group A3, scene, above), GPU picking,
  touch, pen and XR pointer sources
  (groundwork).
  `keyboardResize` and `focusNext` (windows); a transform table in the draw list instead of transforms baked into
  primitives, clips following transforms, mnemonics, a repeat button, logarithmic sliders, HDR and an eyedropper in
  the colour picker (basic); ~~`VersionedTree` in `common-state`~~ (built: below), ~~renderers chosen by a style key,~~
  ~~records by their metadata, box select, hiding and grouping columns, horizontal list scrolling, a loading look
  for lazy tree nodes~~ (data: built, see "Group A3, data"); world panels, scene picking (`Interactive`, the mesh
  BVH, GPU picking), touch, pen and XR pointer sources (groundwork).

### Group A1 (2026-11-03): built, local branches, not yet in CI

Repository `ui` (modules `ui-core`, `ui-style`, `ui-layout`, `ui-text`, `ui-text-native`, `ui-fonts`,
`ui-fonts-cjk`, `ui-controls`, `ui-testkit`) and the engine branch `ui-a1` (`engine-ui`, the composition hook in
`engine-render`). `ui-controls` was started in A1 because the frame that resolves styles, lays out, shapes and records needs all four modules below it.

| Stage | Status | Where |
|---|---|---|
| 0 input actions | Built: the action system (engine PR 386); `UiActions` with keyboard/mouse and gamepad defaults (section 10), `UiCommands` (edges and repeats as `UiCommand`s, `ui.confirmButton` per UI29, text capture), `MousePointer` | engine `input`, `ui` |
| 1 rendering core and text | Built: draw list (`DrawList`, `Primitive`: box, shadow, glyph, image, nine-slice, line), dirty-word tracking, `GpuUiRenderer` (vertex pulling, bindless textures, clip table, dirty-range uploads), `Keys.UI` composited after the tone curve at paper white; FreeType, HarfBuzz, BiDi, itemisation, line breaking, msdfgen MTSDF atlases on job threads and the build-time baker (byte-identical) | `ui-core`, `ui-text`, `ui-text-native`, engine `ui`, `render` |
| 2 elements, layout, Java styles | Built: `UiStore`/`Element`, layouts (stack, flex, grid, border, anchors, overlay; Yoga as the oracle), `StyleKey`/`StyleSheet` Java builders, selectors with states and facts, the resolution cache, the semantics tree, the frame (`Ui`). Not built: binding, lenses, converters, validators, the `ValueRenderer` registry, tweens, effect channels and transitions (parsed and resolved, not animated) | `ui-core`, `ui-layout`, `ui-style`, `ui-controls` |
| UI16 small text | Built: `ui.text.smallText` (`HINTED` default, `MTSDF`) and `ui.text.hintedBelow` (13 px); light-hinted FreeType raster at four subpixel phases in the MTSDF atlas pages, `Primitive.FLAG_RASTER`, `Ui.worldSpace`/`TextBlock.worldSpace` for 3D and VR panels (always MTSDF); the engine's shader and `UiTunables` (a choice setting) | `ui-core`, `ui-text`, `ui-text-native`, `ui-controls`, engine `ui` |
| Bundled fonts | Built: Inter, Noto Sans Arabic, Devanagari, SC and JP (SIL OFL 1.1, `ui-fonts`, `ui-fonts-cjk`); every shaping golden, screenshot and text test (JDK and native backends, controls, the engine's demo) uses them, so the build is the same on Windows and Linux; `TRUESIM_SYSTEM_FONTS=false` (no installed fonts) passes | `ui-fonts`, `ui-fonts-cjk`, tests |
| 3 `.tss` and the default theme | Built: the language (variables, maps, nesting, mixins, `@use` partials, extend, `@if`/`@each`, functions, unit maths, colour functions), errors with file/line/column/rule/trace, live tokens and token sets, hot reload through a file watch, `default-theme.tss` and `test-theme.tss` | `ui-style` |

**Decisions settled by benchmark** (standing rule: one clear winner stays):
- UI14 layout: our own layout, 7 to 16 times faster than Yoga in every case (500-element HUD, 10% changing: 48 µs
  against 425 µs; full relayout 112 against 810 µs). Yoga stays a test oracle; no Yoga-backed flex.
- UI15 batching: an adaptive uber pipeline (one draw per layer; runs of 256+ primitives of one kind use that kind's
  shader) is the default; HUD 0.10 ms against 0.16 ms per kind. `UBER` and `PER_KIND` stay for benchmarks only.
- Atlas packing: our own skyline (0.20 µs per rectangle) against stb_rect_pack (0.22 µs), same page count; ours stays.
- Style resolution: indexed and cached against a linear scan (19 ms per reload of 2,000 rules); the cache stays.

**Section 11 targets, measured** (Ryzen 9 9950X3D, RX 9070 XT, without JaCoCo):

| Target | Budget | Measured |
|---|---|---|
| HUD (506 elements, 4,896 glyphs) unchanged, CPU | ≤ 0.05 ms | 0.0001 ms p99 (`HudBenchmarkTest`) |
| HUD with 10% changing (50 counters a frame), CPU | ≤ 0.3 ms | 0.11 ms median, 0.18 ms p99 |
| HUD (500 elements, 5,000 glyphs) at 4K, GPU | ≤ 0.15 ms | 0.10 ms (`UiBenchmark`) |
| 100,000 glyphs, GPU | ≤ 0.4 ms | 0.29 ms alone; 0.44 ms over a full-screen translucent backdrop (above) |
| Reload of a 2,000-rule sheet, re-resolved | ≤ 5 ms once | 3.0 to 4.8 ms median (`StyleReloadBenchmark`) |
| Reshape of a 200-character label | ≤ 50 µs | 9.6 µs Latin, 28 µs mixed Latin, Arabic and CJK with BiDi |
| Unchanged frame upload | 0 bytes | 0 bytes; a changed counter a few hundred bytes |
| Steady-state allocation | 0 bytes | 0 (allocation tests: idle and 10% changing frames, layout, recording, input mapping) |

**Left from A1:**
- ~~The `Ui` engine plugin~~: built in A2 (`UiPlugin`).
- Stage 2's ~~binding, lenses, converters~~ (built in A3, props: `ui-core` `bind`) and effect channels (A2 built
  value renderers and editors, validators of text, tweens, style transitions and `scale` drawn by per-range
  transforms).
- ~~`common-state`'s `VersionedTree` and `VersionedRingBuffer`~~: built after A2 (`VersionedTree`,
  `VersionedDoubleRing`/`VersionedFloatRing`, below).
- ~~SVG icons through NanoSVG; atlas page eviction~~ (built in "Group A3, render", SVG by a parser of our own;
  colour emoji: built in A2, CBDT).
- Touch, pen and XR pointer sources (the IME rectangle: built in A2).
- 100,000 glyphs over a full-screen translucent backdrop is 0.40 to 0.45 ms, at or just above its 0.4 ms target.
- ~~A multi-range dirty list: one range per list spans all changed elements of a frame.~~ Built: "Group A3, render".
- ~~Colour emoji without a bundled font~~: Noto Color Emoji is bundled in A2 (`ui-fonts-emoji`).
- Benchmarks are tests in each module (`TRUESIM_BENCHMARK=true`), not yet recorded in `engine-bench`.

### Incremental reshaping and `VersionedTree` (2026-11-04): local branches `a2-text-tree` (ui) and `versioned-tree` (common-state)

#### Built

- **Incremental reshaping** (`TextBlock`, used by every text field and area paragraph): a change of the text alone is
  applied to the shaped and laid out block instead of shaping it again, for blocks of at least
  `TextBlock.INCREMENTAL_MIN` (128) characters (`incremental(boolean)`, on by default; `incrementalEdits()` counts).
  - The edit is the text between the common prefix and suffix (whole code points); per-character data after it moves.
  - BiDi: paragraphs without right-to-left text keep level 0 unless the inserted text needs BiDi; otherwise the levels
    from the last strong character before the edit to the first after it are resolved again (a level depends only on
    its strong neighbours without explicit embeddings, isolates and brackets), or the whole paragraph's when it has
    those or no strong character precedes the edit; the changed range is found by comparison. A paragraph whose
    direction changes is shaped from scratch.
  - Itemisation restarts at a character with a script of its own (its run's level, face and script are its own, so the
    itemiser's state there does not depend on what came before) a margin before the edit, inside its run, and stops at
    the first such character past the edit and its level changes, where the old runs go on.
  - Shaping: a long run (a Latin paragraph is one run) is shaped again only in a segment `[w, z)` around the edit. Split
    points are clusters whose glyphs lack HarfBuzz's `UNSAFE_TO_BREAK` flag (shapers without flags: after white
    space); the segment must give the old glyphs again over a margin at both ends (`[w, x)` and `[y, z)`), else it
    widens (four attempts, then the whole run). The old glyphs before `x` and from `z` stay in place; the rest is
    spliced (`ShapedGlyphs.replace`). `UNSAFE_TO_CONCAT` would be the strict condition for reuse after an edit, but
    HarfBuzz sets it on nearly every glyph a lookup looked at (every letter of a kerned font), so the overlap check
    replaces it. HarfBuzz is given the run's script explicitly (the script of the run's first character of a script of
    its own, HarfBuzz's own guess for the whole run), so a segment of common characters shapes as its run.
  - Lines are broken again from a clean line start (not inside a split word) before the edit's line, until a line
    starts, past the changed range, where an old line started clean (greedy breaking from a clean start depends on
    nothing before it); the new lines replace the old ones in place and the following lines move. The break iterator
    sees a window from 64 characters before the restart, not the whole paragraph.
  - Exactness: glyph and caret positions are kept relative to their line (`glyphX`, `charX0/1` before alignment,
    `glyphDy` from the baseline) and the line's offset and baseline are added when read or recorded, giving the same
    floats as before; line tops and alignment are recomputed for the moved lines. A line's glyphs are found by binary
    search in its runs when their clusters are monotone (all of HarfBuzz's), which also takes the full layout of a
    long paragraph from O(lines x glyphs) to O(glyphs + lines log glyphs).
  - Tests: `IncrementalShapingTest` in `ui-text` (JDK shaper, and a shaper marking flags) and `ui-text-native`
    (HarfBuzz): random typing, deleting, replacing, pasting and line feeds in 600 to 3,000-character paragraphs mixing
    Latin (kerning, ligatures), Arabic, CJK, Devanagari, marks, digits, brackets and (native) emoji, at several widths,
    alignments and directions; after every edit the block equals one shaped from scratch glyph by glyph (id, face,
    x, y, cluster, size), line by line (range, glyphs, left, width, baseline, top, direction) and caret by caret
    (levels, character edges, caret x), compared exactly (many more seeds were run while building it).
- Fixes found by the comparison: the JDK shaper read the whole character array as context (also stale characters past
  the text's end); it now passes 8 characters of context on each side, as the HarfBuzz shaper does. Paragraph
  separators get their paragraph's level instead of whatever the array held.
- **`VersionedTree<T>`** in `common-state` (with `VersionedDoubleRing`/`VersionedFloatRing` for chart streaming:
  appended ranges logged, `changesSince` visits the range appended since, clipped to the held samples). `TreeView` and
  `TreeRows` take a `VersionedTree`; the data controls' own `TreeModel`/`TreeNode` went. View state moved into
  `TreeRows`: expansion, lazy loaders (`TreeRows.ChildLoader`, `TreeView.lazy`) and loading, in arrays by node id
  (ids are dense, never reused), stamped on the tree's clock, so two views of one tree open nodes independently.
  Value changes come from `VersionedTree.changesSince` (its log survives structure changes, node identity being
  stable).

#### Benchmarks (Ryzen 9 9950X3D, without JaCoCo; `BenchmarkTest.typingInLongParagraphs`, HarfBuzz, wrapped at 600 px)

Per keystroke, typing into the middle of one paragraph (the text area shapes one block per paragraph):

| Paragraph | Before (A2) | Full reshape now | Incremental |
|---|---|---|---|
| 5,000 characters, Latin | 892 µs | 540 µs | 23 µs |
| 5,000 characters, Latin, Arabic, CJK, Devanagari | 1,183 µs | 1,206 µs | 31 µs |
| 20,000 characters, Latin | 8,978 µs | 2,254 µs | 31 µs |
| 20,000 characters, Latin, Arabic, CJK, Devanagari | 5,699 µs | 4,542 µs | 52 µs |

Target well under 0.2 ms: met. Incremental stays; the full reshape is the fallback (short blocks, style or box
changes, rich text, line feeds typed or deleted, a paragraph's direction changing, a segment that does not converge).
What remains per keystroke is linear but cheap: comparing the text, moving per-character and per-glyph arrays, the
following lines' cluster indices.

#### Left

- Rich text (style runs) still reshapes whole: its runs are rebuilt by the editor on every change.
- Brackets or explicit embeddings in a paragraph with right-to-left text resolve the whole paragraph's BiDi on each
  edit (0.25 ms at 20,000 characters); pairing brackets incrementally would remove that.
- `common-state` 0.4.0 must be released with `VersionedTree` before `ui` can use the published artifact: the
  dependency in `core/build.gradle.kts` stays 0.3.0 and builds use `-Ptruesim.common-state=../common-state-tree`
  until then.

### OS windows for docking (2026-11-04): built, local branches `a2-oswindows` (ui) and `ui-a2-oswindows` (engine)

Built on group A2: tabs tear out of the main window into OS windows of their own, and dock back.

#### Built

| Part | Built | Where |
|---|---|---|
| Dock areas | A `DockSpace` has areas: its main one (`root()`) and one per OS window (`newArea`, `closeArea`, `areaOf`, `findArea`, `dock(panel, area, position)`); each a tree of splits and panes; tabs drag between areas like between panes; an area left empty closes its window; a closed window's panels dock again where they last were in the main area (beside the panels they were beside, at their side and share, else into the main area) | `controls.windows` `DockArea`, `DockSpace` |
| Area hosts | `DockHost` gains default methods for hosts with areas (`areas`, `openArea`, `closeArea`, `titleArea`, `raiseArea`, `outside`, `follow`, `following`, `describe`, `restoreArea`); `WindowDockHost` (floating windows in the UI) is unchanged | `DockHost` |
| `OsDockHost` | Over `OsWindows` (open, close, move, title, raise, maximise, bounds, pixel ratio, content scale, monitors, the global cursor: an engine's, or a fake): a tab dragged beyond every window tears out into a window at the cursor that follows it until release (the drag session continues, so dropping on any area's panes or edges docks it); the only tab of a window drags the window; a window's close button re-docks its panels; a window gone by itself too. Every window shows a region of the one `Ui` (a layer `dock-window-<n>` at z 50 + n, its region at x 65,536 + n x 16,384 UI pixels), so sheet, tokens, text, atlas, focus, commands and drag and drop are shared; `toUi` maps screen points into the region of the front-most window under them (the following window left out; the OS focus orders them), `toScreen` back; a window's UI pixels per screen coordinate are its pixel density times the main window's scale over its own, so content keeps its size in points on monitors of other scales | `OsDockHost`, `OsWindows` |
| Overlays | Anchored overlays are kept inside the viewport of their anchor's window (`Overlays.viewports(Viewports)`) | `controls.overlay` |
| Layouts | `DockLayout` window lines: `window <id> "<monitor key>" <x> <y> <width> <height> [maximized] <node>`, the place from the monitor's top left in screen coordinates; round trip exact (`#` in quoted monitor keys is not a comment). Restored on a missing monitor, a window goes to the primary one, its size and place clamped into the usable area; persisted through the `LayoutStore`, saved once a moved or resized window kept still for 20 frames (never per frame) | `DockLayout`, `OsDockHost` |
| Engine | `UiOsWindows` (engine-ui) implements `OsWindows` over the platform: a window per area, presented by the presenter from a view of its own (`ui.window<id>`: `UiBackdrop`, the scene cleared black, and a `GpuUiRenderer.share()` drawing the main renderer's uploads at its region's `origin`): one device, one upload, one atlas, a presenter target per window with its own encoding (SDR, HDR10, scRGB by its display); the mouse of every window maps through the host (`MousePointer.mapper`; the global cursor where the system tells it), text input starts on the window holding the field (`UiTextInput.locator`), close buttons and focus reach the host. `UiPlugin.osWindows()` makes it with a window and a presenter and drives it each frame. Platform: `Platform.createWindow(options, x, y)`, `Platform.globalMouse(out)` (false on Wayland); monitors are `Platform.displays()` with `displayKey` | engine `ui`, `platform`, the UI shader's `originX`, `originY` |
| Styles | `dock-window` in `_oswindows.tss` and `_oswindows-test.tss` (one `@import` each) | themes |

#### Decisions

- *One UI, regions per window* (Dear ImGui's multi-viewport way) rather than a second `Ui` per window: elements
  belong to one store, so a panel's content could not move to another UI; one UI shares everything and a tab drags
  between windows as between panes. The cost: every window draws every layer, clipped by the rasteriser (the main
  window's primitives fall outside an OS window's image).
- *The window follows the drag; the drag session stays.* Docking on drop is the ordinary drop on a dock space's
  root, wherever the cursor is; the following window is left out of the mouse mapping and of the drop targets.
- *DPI by scaling the region*, not a second layout: an OS window's region is laid out at the main window's scale and
  drawn scaled to its monitor's; MSDF text stays sharp, raster small text drawn at another scale is resampled.

#### Tests

ui: `OsDockTest` (fake windows: tear out and follow, dock back, close and re-dock, between windows and out of them,
a drop where nothing docks, layouts with monitors round trip and restore on a missing monitor and clamped, the
store, scales and the front-most window, no global cursor, overlays kept in a window, the test theme),
`OsDockAllocationTest` (idle, the mouse over windows, a window following a drag: nothing allocated without escape
analysis). Engine: `OsWindowsTest` (real windows and the presenter: a tab dragged out opens a second window that
follows the cursor, both render, the close button docks it back, screenshots `ui-windows/os-window-main.png`,
`os-window-undocked.png`, `os-window-redocked.png`), `PlatformTest` (windows at a position, the global mouse).

#### Left

- Wayland: no global cursor and no window positions; windows open where the compositor puts them, the following
  window tracks the pointer's place in the UI (when the cursor leaves every window it is not known), and saved
  positions are not restored.
- Paper white and the UI's brightness are the graph's settings, one for all windows; each window's encoding is its
  own. (Paper white per window: see "Window options".)
- Windows stack by the OS focus as the host saw it; overlapping windows of other applications are not known.
- Pop-ups of content in a window flip within its region; a centred dialog shows in the main window.
- An OS window is not made a child of the main one (stays on top, no task bar entry): SDL's parent windows behave
  differently per system; to decide.

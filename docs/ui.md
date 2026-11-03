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
| `ui-controls` | ui-core, ui-style, ui-layout, ui-text | Every control of section 5.2 to 5.7, including collections and virtualization, data views (sorted, filtered), selection models, menus, pop-ups, dialogs, dock space. |
| `ui-charts` | ui-controls | Chart model, axes, series, ring buffers, decimation (min-max pyramid, LTTB), hit queries, interaction, chart drawing as primitives. |
| `ui-gizmo` | ui-core, scene-core, common-math | Gizmo shapes, ray tests, drag solving in doubles, snapping, pivots, undo events, view cube. |
| `ui-props` | ui-controls | Property metadata model and annotations, the builder pipeline, collection editors, lenses for records. |
| `ui-props-processor` | (build time) | The annotation processor that generates metadata and lenses. |
| `ui-console` | ui-controls | Console model and widget: commands, typed arguments, completion, history, log ring (fed through an interface). |
| `ui-scene` | ui-core, scene-core | The bridge to scene graphs: the `Interactive` component (a scene-core component store, D46), scene picking through `SpatialIndex`, the mesh triangle BVH for the narrow phase, world-panel nodes. |
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
3. **Scene objects**: `SpatialIndex.raycast(..., INTERACTIVE.mask(), hit)` with the bounds BVH. Only nodes with an
   `Interactive` component are on the interactive layer, so **objects without listeners cost nothing**.
4. **Narrow phase**, chosen per `Interactive` component (UI5):
   - `BOUNDS` (box or sphere): free;
   - `MESH`: the ray against a per-mesh triangle BVH on the CPU, built lazily from mesh data and kept with the mesh
     asset;
   - `GPU`: pixel-precise through an ID buffer or a ray query against the TLAS, with one frame of delay, offered only
     where CPU data does not exist (skinned, displaced, alpha-tested), and flagged.
5. Results are merged by distance. Screen layers win by layer order, as in Lemur where GUI goes before the scene.

The pointer's target is the nearest hit with a listener, found by walking up the element tree or the scene-graph
parents (Lemur's `findHitTarget`). Only nodes with an `Interactive` component take part, a sparse set in scene-core
(D46).

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
- **Depth**: drawn on top with the occluded parts faded, a style choice.
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
| translate/rotate/scale/universal (Binary) | W / E / R / T | d-pad (when the gizmo is focused) | toolbar | — |
| toggleSpace (Binary) | X | — | toolbar | — |
| snap (Binary, held) | Ctrl (held) | LT held | toolbar toggle | grip held |
| cancelDrag (Binary) | Escape, RMB | east | — | B |
| **ui.docking** | | | | |
| dockModifier (Binary, held: no docking while held) | Shift (held while dragging) | — | — | — |
| nextWindow (Binary) | Ctrl+F6 | — | — | — |
| **ui.console** | | | | |
| toggle (Binary) | ` (grave) | left and right stick clicked (chord, D69) | four-finger tap | menu held (tap-and-hold, D70) |
| complete, historyUp/Down | Tab, ↑/↓ | — | — | — |

- Text editing actions apply only while the text context is on top (D61). Keys typed as text never fire game actions.
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
| Picking cost of a node without an `Interactive` component | **0** (not in the interactive layer) |
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

**Stage 13: property builder and inspectors, including collections**
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

### Group A2 groundwork (2026-11-03): built, local branch `a2-groundwork`, not yet in CI

The shared interaction core the parallel feature branches (text editing; overlays, windows and docking; basic and
container controls; data controls) build on. Engine-agnostic, in `ui-core` (`com.truesim.ui.core.event`), wired into
the frame by `ui-controls`' `Ui`. Not built here: world panels, scene picking (`Interactive`, the mesh BVH, GPU
picking), engine pointer sources (touch, pen, XR), the IME rectangle in `engine-platform`.

| Piece | Built | Where |
|---|---|---|
| Layers | `Layers`/`Layer`: HUD (the store's root), overlay 100, modal 200 (modal), popup 300, tooltip 400, drag 500 and debug 1000 (no input), custom by z; each a root of the store (`UiStore.createRoot`, laid out over the viewport) with its own `DrawList`; input `PASS_THROUGH`, `BLOCK` or `NONE`; a modal or blocking layer showing anything blocks those below; `Ui.layers()`/`layerCount()` hand all lists to the renderer back to front, each layer rebuilt on its own | `ui-core`, `ui-layout`, `ui-controls` |
| Picking | per layer a uniform grid (64 px cells, coarsened to 4,096 cells) over pickable elements, rebuilt only when one of them or a clipping ancestor moved or was restyled, or the layer's structure changed; precise rounded-rect test, clips (style `clip`) respected; front to back over layers; `pointer-events: auto/none/all` in styles or code | `Picking` |
| Events and routing | `EventType` (enter, exit, move, press, release, click with count, long press, context, wheel, drag start/drag/drag end, drop enter/over/leave/drop, focus in/out, command, text, composition, tooltip, press outside); DOM-like capture, target, bubble with `consume()`; Lemur's pass-through of unconsumed pointer events to the next hit; capture on press; hover per pointer; listeners in arrays, events reused (16 nesting levels) | `Interaction`, `UiEvent` |
| Drag and drop | `DragSession` per pointer with a typed payload (`DataFormat<T>`), allowed and preferred operations (`DragOperation`, Ctrl/Alt modifiers), a preview element moved on the drag layer, drop targets found through picking (`acceptDrop`) | `DragSession` |
| States | hover, pressed, focused, `focus-visible` (navigation only), `drop-target`, `dragging` set from events; disabled elements get no events | `UiState` |
| Focus | a focus per player (8), explicit neighbours, container `FocusTraversal`s bubbling, default weighted spatial search within 45 degrees, tree order for next/previous, focus memory per container, modal scopes (`pushModal`, modal layers) trapping focus and restoring it, focus ring from `focus-ring`/`focus-ring-offset` | `Interaction`, `Ui` |
| Commands and text | `UiCommand`s to the player's focused element (capture/bubble), unconsumed navigation moves focus, ACCEPT clicks, CANCEL closes the top modal scope (or cancels a drag), CONTEXT_MENU asks for a menu; text and IME composition to the focused text-taking element; `TextInputHost` started and stopped with its IME area (the element or a caret rectangle, following layout); `Clipboard` (the engine's or one in memory) | `Interaction` |
| Frame | `Ui.update(w, h, UiInput, nanos)` processes input first (picking against the layout last drawn), then styles, layout and recording: one frame; tunables `ui.input.longPressDelay`, `doubleClickTime`, `doubleClickDistance`, `dragThreshold`, `touchDragThreshold`, `tooltipDelay`, `ui.focus.sidewaysWeight` | `Ui` |
| Semantics | actions per node (activate, focus, show-menu, scroll, drag, drop, edit-text) and states; `Semantics.unnamed` lists interactive nodes a screen reader could not announce; roles for the planned controls | `Semantics`, `Role` |
| Styles | `pointer-events`, `clip`, `cursor`, `focus-ring`, `focus-ring-offset`; both themes style `layer` and `:focus-visible`, `:drop-target`, `:dragging` | `Styles`, themes |

**Benchmark** (Ryzen 9 9950X3D, without JaCoCo, `PickingBenchmarkTest`): on a 2,001-element HUD (1,680 pickable), the
grid answers the front-most hit in 0.017 µs and every hit in 0.023 µs per pointer; a plain tree walk (front to back,
stopping at the first hit) takes 16.6 µs. The grid stays (target 5 µs); the walk is kept only as a test oracle. A
frame of interaction with one mouse moving over that HUD costs 0.2 µs. Steady-state frames with two pointers moving,
pressing and scrolling and commands arriving allocate nothing (`InteractionUiTest`, without escape analysis).

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
- The `Ui` engine plugin (key, settings registration, frame-start wiring); the demo wires the pieces in a test.
- Stage 2's binding, lenses, converters, validators, value renderers, tweens, effect channels and style transitions;
  `scale` from styles is resolved but not drawn (no per-element transforms yet).
- `common-state`'s `VersionedTree` and `VersionedRingBuffer` (needed by tree views and charts, groups B and C).
- SVG icons through NanoSVG; colour emoji (COLR draws as a silhouette); atlas page eviction.
- The IME rectangle in `engine-platform`; touch, pen and XR pointer sources.
- 100,000 glyphs over a full-screen translucent backdrop is 0.40 to 0.45 ms, at or just above its 0.4 ms target.
- A multi-range dirty list: one range per list spans all changed elements of a frame.
- Colour emoji: no emoji font is bundled; with bundled fonts only, emoji draws as `.notdef` (a test pins that), and an
  installed emoji font is used when the registry includes installed fonts.
- Benchmarks are tests in each module (`TRUESIM_BENCHMARK=true`), not yet recorded in `engine-bench`.

### Overlays, hovers, windows and docking (2026-11-03): built, local branch `windows`, not yet in CI

Built on the A2 groundwork in `ui-controls`: `com.truesim.ui.controls.overlay` (anchoring and placement, shared by
pop-ups of every kind) and `com.truesim.ui.controls.windows` (tooltips, popovers, floating windows, docking).

| Piece | Built | Where |
|---|---|---|
| Frame hooks | `UiFrameHook` (`Ui.addFrameHook`): `frame` once a frame (timers, the frame's `UiInput`), `beforeLayout` before every layout pass (positions and sizes set in code, re-set because a restyle applies the style's), `afterLayout` (what depends on layout); a change made after layout is styled and laid out again in the same frame (up to four passes), so an overlay sits at its anchor in the frame the anchor moved | `Ui`, `UiFrameHook` |
| Overlays | `Overlays` (one per UI) and `Overlay`: an element on a layer (or any host element), anchored (`OverlayAnchor`: `ElementAnchor`, `PointAnchor`, or a scene object the engine projects each frame; hidden anchors hide the overlay), centred or at a position; placement from styles (`placement` top/bottom/left/right/start/end, `placement-align`, `placement-gap`, `placement-shift`, `viewport-margin`, `placement-flip`) by `Placer`: preferred side, opposite, the two across, else the roomier; shifted back inside the viewport; the side ends up as the fact `side` (`tooltip[side=bottom]`); input `PASS_THROUGH`, `BLOCK_BOUNDS` or `BLOCK_SCREEN` (a stylable `backdrop` under it, light dismiss optional); modal overlays trap focus and close on CANCEL | `overlay` |
| Tooltips | `Tooltips`: text or rich content (any subtree) per target, one `tooltip` element on the tooltip layer; pointer rest (`ui.input.tooltipDelay`), hidden on leave, press, wheel; warm moves to the next target within `ui.tooltip.warmTime`; touch and pen by long press (no click), hidden on lift; keys and gamepad by visible focus after the delay; `tooltip-mode: anchored` or `follow` (the pointer) | `Tooltips` |
| Popovers | `Popovers.hover` (hover cards: open after `ui.hover.openDelay`, safe triangle from the leave point to the card's near edge for up to `ui.hover.intentTime`, else closed after the grace `ui.hover.closeDelay`; by visible focus and by a tap too) and `Popovers.click` (toggle on click or ACCEPT, close on a press outside it and its children and on CANCEL, focus in from the keyboard and back to the trigger); popovers opened from popovers are children: they keep their parent open and close with it | `Popover`, `Popovers` |
| Floating windows | `WindowManager` (a `window-host` on the overlay layer, or any host element: world panels, panes) and `FloatingWindow`: title bar (icon, title, minimise, maximise, close), move by the title (snapping to the host's and other windows' edges within `ui.window.snapDistance`, kept on screen by `ui.window.keepVisible`), resize by eight `window-resize` edges and corners (their `cursor`s, `resize-handle` thickness, `min-width`/`min-height`), minimise to the title bar, maximise (double click), restore (dragging a maximised window restores it under the pointer), close (vetoable), modal on the modal layer over a backdrop, raise and activate on press (`window:selected`), focus cycling (`focusNext`, Ctrl+F6), keyboard and d-pad move and resize (`keyboardMove`, `keyboardResize`: arrows by `ui.window.keyStep`, ACCEPT ends, CANCEL puts it back); `WindowLayout` text (one line a window) through `LayoutStore`, applied to windows made later | `WindowManager`, `FloatingWindow`, `WindowLayout`, `LayoutStore` |
| Docking | `DockSpace` and `DockPanel`: panes (`dock-tabs`) in nested `dock-split`s with `dock-splitter`s (drag, or arrows when focused; `dock-min-pane`), docking left/right/top/bottom/centre beside a pane or at the outer edges, tab activation (press, arrows), drag to reorder, into another pane, to a pane's side (zones `dock-zone` deep) or the outer edge; `dock-drop-preview` and the `dock-zones` guides while dragging; Shift docks nothing; dropped elsewhere a tab floats in a window (`WindowDockHost`), whose title dragged over the space docks it again; close (vetoable), collapsing emptied panes and merging same-direction splits; `DockLayout` text (`dock split.style(row; ratios; nodes)`, `tabs.style(active; ids)`, `floating id x y w h`), round trip exact, through `LayoutStore` | `DockSpace`, `DockPanel`, `DockLayout`, `DockHost`, `WindowDockHost` |
| Styles | keys in `OverlayStyles` and `WindowStyles`, registered for `.tss` as `StyleKeyHolder` services (`META-INF/services`), so the shipped themes compile wherever `ui-controls` is; `_windows.tss` and `_windows-test.tss` imported by both themes | `ui-style`, themes |
| Semantics | windows `WINDOW` (modal `DIALOG`) named by their title, title bar buttons `BUTTON`, edges and splitters `SEPARATOR`, tooltips `TOOLTIP` named by their text, click popovers `DIALOG`, tab strips `TAB_LIST`, tabs `TAB`, pane bodies `TAB_PANEL`; `Semantics.unnamed` is empty for the scene | all |

**Decisions.** Positions and sizes set in code are layout inputs re-set every pass rather than inline styles (no
allocation, no style cache entries per position). Overlays are placed after layout in the same frame (no frame of
lag). Hover intent combines a safe triangle and a grace period. Docking rebuilds its container elements on a
structural change (user action), never per frame; ratios are flex grow factors. The dock space's tab strip and
splitter are docking's own parts (inside `DockSpace`): when the generic `TabbedPane` and `SplitPane` of the basic and
container controls exist, they can replace them without API changes. Undocked panels float through a `DockHost` per
dock space: `WindowDockHost` (floating windows of the same UI) is built; OS windows need an engine-side host (an OS
window, presenter and input per viewport) implementing `DockHost` and reporting window drags through
`DockSpace.dragFloating`/`dropFloating`.

**Benchmark** (Ryzen 9 9950X3D, without JaCoCo, `WindowsBenchmarkTest`, `TRUESIM_BENCHMARK=true`): 50 open windows
and a dock space of 30 panels in 6 panes (1,091 elements, 1920 x 1080): idle 0.0074 ms median (0.0080 ms p99; target
0.05 ms); dragging a window 0.031 ms median (0.052 ms p99; target 0.3 ms). Steady-state frames allocate nothing
without escape analysis: idle with windows, a tooltip, a popover and the dock space; the pointer moving over chrome,
tabs, splitters and tooltip targets; dragging a window, a splitter, and a tab across panes (`WindowsAllocationTest`).

**Left:** style transitions are declared (`backdrop`, `tooltip`, popovers) but the frame does not animate them yet;
OS windows for docking (engine side); an action of the `ui` group bound to `keyboardMove`/`keyboardResize` and
`focusNext` (the engine's bindings); `TabbedPane`/`SplitPane` unification with the container controls; CPU previews
were not made (the integrator renders GPU screenshots of `WindowsScene`).

### Data controls (2026-11-03): built, local branch `data-controls`, not yet in CI

Scroll views, value renderers, virtualized lists, sorted and filtered views, trees and tables, in `ui-controls`'
package `com.truesim.ui.controls.data`; the looks in `_data.tss` (default) and `_data-test.tss` (garish).

| Piece | Built | Where |
|---|---|---|
| Scrolling | `Scroller` (one axis, doubles: wheel steps, touch drag with a rubber band, flings with `ui.scroll.friction`, spring back, `ui.scroll.overscroll` none/bounce); `ScrollView`: clipping viewport, stylable bars (track, start/end buttons that repeat while held, thumb; thickness, `scrollbar-visibility`, `scrollbar-overlay`, `thumb-min` from styles; `[active=true]` while in use for auto-hiding themes, `ui.scroll.autoHideDelay`), wheel and trackpad (Shift sideways, passed on at the ends), touch momentum, PAGE_UP/DOWN, HOME/END, arrows on the focused view, scroll-into-view of what navigation focuses; `ScrollPane` with sticky section headers | `Scroller`, `ScrollView`, `ScrollPane` |
| Virtualization | only rows in the viewport plus `ui.list.overscan` have elements, recycled by kind; fixed heights (the row style's `height`) O(1) (`FixedExtents`), else measured into a Fenwick tree of measured and estimated heights (`MeasuredExtents`), the top row kept in place; rows placed from a moving origin (a million rows exact); the body scrolls by a layout scroll offset | `VirtualView`, `Extents` |
| Renderers and editors | design 4.3: `ValueRenderer` (kind, create, bind, unbind, `viewType` to share views), `CellState`, `ValueEditor` (also the hook for the text-editing controls), `ValueRenderers` (exact class, records, superclasses, interfaces, built-ins, `String.valueOf`; names; a dynamic renderer for mixed values); built-ins for text, numbers and row fields without boxing, booleans, enums, vectors, colours; `Editors` for numbers (typed or stepped), booleans, enums | `ValueRenderer`, `ValueRenderers`, `Renderers`, `Editors` |
| Lists | `ListView` over a `RowSource` (a `VersionedList`, a `DataView`): idle frames compare one version, value changes rebind only changed rows shown (`changesSince`), structural changes reconcile the visible window by key; single or multiple selection (`Selection`, a `VersionedSet` of items: Shift ranges, shortcut toggles), keys, type-ahead, drag and drop reordering | `ListView`, `Selection`, `RowSource` |
| Data views | `DataView`: sorted and filtered, sorted once, then each changed row out and into its place in an `OrderIndex` (blocks of at most 1,024 indices, a Fenwick tree over block sizes): O(log n + B) a change; in-place changes logged for rebinding, moves change the structure | `DataView`, `OrderIndex` |
| Trees | `TreeModel`/`TreeNode` (per-node versions, a log of value changes, lazy children loaded on first expand, also asynchronously), `TreeRows` (visible rows), `TreeView`: indent, expander, RIGHT/LEFT, clicks, drag and drop into nodes and between rows | `TreeModel`, `TreeView` |
| Tables | `TableView`/`Column`: columns in code (renderer, order, editor, filter, width), header sort (Shift adds), resize by the header's grip (`column-min-width`), move by dragging headers, frozen columns (drawn over the rest), a filter row hook (`Column.filterView`) and per-column filters, inline editing (double click, or ACCEPT in cell selection; ACCEPT/SUBMIT commit, CANCEL cancels, focus leaving commits), row, multi-row or cell selection, columns virtualized (cells recycled per view type and kind) | `TableView`, `Column` |
| Semantics | LIST/LIST_ITEM, TREE/TREE_ITEM (`:expanded`), TABLE/ROW/CELL/COLUMN_HEADER, SCROLL_VIEW, SCROLL_BAR, SEPARATOR (column grips), CHECKBOX, SPIN_BUTTON, COMBO_BOX; rows and cells named by their renderers with live text | all |

**Shared changes** (separate commits): `Layout.scroll` (children shifted without relayout) and
`Layout.independent` (an element sized by its parent: measures as empty, stops the dirty bubble); hidden subtrees
collapse (they no longer draw or pick at their last place); `Ui.codeLayout` (code owns kind and direction,
placement, width, height) and `Ui.addUpdater` (control code before layout and after it, with one more styles and
layout pass when asked); labels name themselves with their live text (`UiStore.semantics` takes a `CharSequence`);
`Interaction` read per-slot arrays before growing them; `StyleKeyProvider` (ServiceLoader) so the shipped themes
compile with controls' keys, ui-style's tests run with ui-controls on the class path.

**Benchmarks** (`DataBenchmarkTest`, `TRUESIM_BENCHMARK=true`, default theme, HarfBuzz and msdfgen, 1920 x 1080; the
machine was shared with three other builds, so p99 is noisy):

| Target | Budget | Measured |
|---|---|---|
| `ListView` of 10,000 rows (480 x 900, 34 visible), scrolling continuously | ≤ 0.1 ms; memory O(visible rows) | 0.018 to 0.028 ms median, 0.042 to 0.048 ms p99; 40 row elements, 93 elements in all |
| 1,000,000 rows, sorted and filtered `DataView` (750,000 shown) in a list, 100 changes a frame | ≤ 0.5 ms | 0.018 to 0.021 ms median, 0.045 to 0.058 ms p99 (built once in 0.3 s) |
| `TableView` 100,000 x 20 (1880 x 1000: 38 rows x 19 columns visible, one frozen), scrolling | ≤ 0.3 ms | 0.17 to 0.29 ms median, 0.36 to 0.43 ms p99 |

The table's frame is 80% recording: every visible cell moved, so about 1,800 elements and 6,000 glyphs are recorded
again each scrolling frame (`TextBlock.record` alone is 38%); the data controls' own work is 3%. Meeting the p99
budget at this size needs scrolling without re-recording: a translation per clip in the draw list and
`GpuUiRenderer` (a scrolled viewport's primitives in content space, the clip entry moving them), which is engine
work. Smaller tables (or fewer columns) are within budget.

Steady-state frames allocate nothing (allocation tests without escape analysis): idle, scrolling and 100 changes a
frame for lists; scrolling for tables.

**Left:**
- The text editor for cells and filter fields comes from the text-editing branch (through `ValueEditor` and
  `Column.filterView`); `Editors` has numbers, booleans and enums only.
- `VersionedTree` should move to `common-state` (design 4.2): `TreeModel` depends on `ChangeClock` only.
- Translation per clip for scrolling without re-recording (above).
- Renderers chosen by styles (by name exists, a style key does not); records by their metadata (section 5.11) through
  `ValueRenderers.records`; colours as swatches (now hex text).
- Box select, hiding columns, grouping headers, horizontal scrolling of lists, a loading look for lazy tree nodes.
- An element hidden and shown again rebuilds its layer's draw list once (its draw range was empty).
- GPU screenshots of `DataGallery` (a test helper) by the integrator.

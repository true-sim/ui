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

### Group A3, render (2026-11-04): local branch a3-render

The draw list's transform table and dirty ranges, CSS's automatic minimum size of flex items, SVG icons and atlas
page eviction. Engine branch `ui-rest-render` (engine-ui's renderer and shader).

### Group A3, charts (2026-11-04): local branch a3-charts

Design section 7 and stages 9, 10 and 11 (Group C) as the new module `ui-charts` (directory `charts`, package
`com.truesim.ui.charts`, on top of `ui-controls`, published like the others). No engine work yet: what the engine must do
is listed under Left.

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
  callbacks), legends outside the plot.
- `common-state` with `VersionedDoubleRing`/`VersionedFloatRing` must be released (`ui-core` asks for 0.4.0; the build
  uses `-Ptruesim.common-state=../common-state-tree`).

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
  records by their
  metadata, box select, hiding and grouping columns, horizontal list scrolling, a loading look for lazy tree nodes
  (data); world panels, scene picking (`Interactive`, the mesh BVH, GPU picking), touch, pen and XR pointer sources
  (groundwork).

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
- Stage 2's binding, lenses, converters and effect channels (A2 built value renderers and editors, validators of
  text, tweens, style transitions and `scale` drawn by per-range transforms).
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

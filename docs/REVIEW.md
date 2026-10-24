# FMCC — Independent Code Review & Acceptance Audit

**Product**: FasterMinecraftCommands (FMCC) 1.0.0 — client-only Fabric mod for Minecraft **1.21.8**
**Reviewer**: reviewer (read-only audit; this file is the only file I wrote)
**Review snapshot**: frozen tree, 2026-10-24 00:45–00:52 (Asia/Shanghai). Source hashes below match the final build.

## 0. Snapshot and verification commands

| Item | Value |
|---|---|
| `build/libs/fmcc-1.0.0.jar` | 91,734 bytes · sha256 `EC1031D436B5E5B3328955B6C44B55C4AB8B71C71E21901FDC6E3773EFD438B9` (rebuilt by me; byte-identical to the Lead's final artifact) |
| MC / loader / mappings | 1.21.8 · Fabric Loader 0.19.3 · yarn 1.21.8+build.1 · Loom 1.11.8 · JDK 21 |
| `tools\gradle.cmd test` | **BUILD SUCCESSFUL — 73/73 tests, 6 classes, 0 failures / 0 errors / 0 skipped** (`build/test-results/test/TEST-*.xml`) |
| `tools\gradle.cmd build` | **BUILD SUCCESSFUL** (jar, sources jar, remapJar, tests) |
| In-game self-test, title screen | PASS — 52 steps / 50 pass / 2 by-design skips (`docs/evidence/selftest-title-screen-report.json`) |
| In-game self-test, real singleplayer world | PASS — **52/52, 0 skipped**; real Tab opens menu, ESC closes it, `/seed` executed server-side (`docs/evidence/selftest-in-world.log`) |
| Real 40+ mod pack (Sodium/Iris/REI/Carpet/Axiom/Litematica/Xaero/…) | PASS — 52 steps / 50 pass / 2 by-design skips; FMCC UI rendered under Sodium+Iris at 1280×720 (`run-compat/fmcc-selftest-report.json`, `docs/assets/fmcc-modpack-compat.png`) |

> The `[exit code: 1]` markers on `cmd /c gradle.cmd …` are the known Windows "File watcher server
> NativeException" noise on stderr; Gradle itself prints `BUILD SUCCESSFUL` in both commands. A
> `gradle test --rerun-tasks` attempt failed once because a stale Loom cache lock (another JVM still held
> `mappings.jar`); the plain `test`/`build` runs above were executed afterwards and are green.

**Final source snapshot (SHA-256, first 12 hex)**

```
E307DC031994 build.gradle           43A5E4EB0FB9 gradle.properties     1560B7247F99 fabric.mod.json
B670E0C7679C lang/en_us.json        B3A3157DDB9E lang/zh_cn.json       C0F3D7EBC5B6 icon.png
B5222939573A FmccClient.java        7EF62BE1D497 FmccPaths.java         DB49381307E0 config/ConfigStore.java
ADA42089421D FmccConfig.java        C763C15FA40F FmccEntry.java        1A529658D86F chat/ChatRouting.java
430408B8166E ChatDelivery.java      25427919A620 MessageQueue.java      1473EAC1556E util/TitleRules.java
ECF94CCB9B8A ui/FmccScreen.java     CF470228E3AD ui/EntryConfigScreen.java  54A2AEAAA223 ui/AddEntryScreen.java
CB046E4EECD8 FmccButton.java        F19FC18252EB Draw.java              99A0E154326F UiLayout.java
1AE34D4D41A6 UiTheme.java           3666BC74CDDC selftest/SelfTest.java
```

Method: read all production sources and resources (`src/main/java`, `src/main/resources`), `build.gradle`/
`gradle.properties`, and the docs (`docs/DESIGN.md`, `README.md`, `docs/TEST-REPORT.md`); reviewed the unit-test
suite; verified 1.21.8 API/lifecycle semantics with `javap`
against `.gradle-home/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged/1.21.8-…/minecraft-merged-1.21.8-….jar`
(`Screen`, `MinecraftClient`, `ParentElement`, `ClickableWidget`, `TextFieldWidget`, `Keyboard`, `KeyBinding`,
`PacketByteBuf`/`StringEncoding`, `ClientPlayNetworkHandler`, `ServerPlayNetworkHandler`, `ControlsListWidget`);
inspected the built jar and the in-game screenshots; ran the unit tests and the build. I did **not** run the
game client myself (E2E evidence was produced by the tester/Lead; I audited the artifacts).

---

## 1. Requirement traceability

| # | Requirement (as given) | Verdict | Evidence |
|---|---|---|---|
| R1 | Mod name **FasterMinecraftCommands/FMCC**, Fabric **1.21.8**, Chinese+English, white-bg/black-text "FMCC" icon | **PASS** | `fabric.mod.json:4-24` (id `fmcc`, name, `"minecraft": "~1.21.8"`, `"environment": "client"`, client entrypoint, icon); icon 128×128 white background + black "FMCC" (`assets/fmcc/icon.png`, pixel-sampled); lang files 37 keys each, identical key sets (`lang/en_us.json`, `lang/zh_cn.json`) |
| R2 | Hotkey default **Tab**, rebindable in Controls, opens modern **right-side** menu | **PASS** | `FmccClient.java:44-45` (`KeyBindingHelper.registerKeyBinding(new KeyBinding(KEY_OPEN, KEYSYM, GLFW_KEY_TAB, KEY_CATEGORY))`); translation keys `key.fmcc.open`/`category.fmcc`; in-game: `assert-hotkey-binding` + `hotkey-opens-menu` PASS; right-side panel `UiLayout.panel()` `UiLayout.java:58-62` = `x = screenWidth - width - MARGIN` |
| R2b | **ESC closes** it | **PASS** | `Screen.shouldCloseOnEsc()` default true (1.21.8 javap); `FmccScreen` does not override → `close()` (`Screen.keyPressed` ESC path); in-game `esc-closes-menu` PASS (world run, `currentScreen == null`) |
| R3 | **"+" adds an entry**, player **must enter a title ≤10 chars**, **cancel ⇒ no entry** | **PASS** | `FmccScreen.java:116-119,370-372` → `AddEntryScreen`; hard limit `TitleRules.MAX_CODE_POINTS=10` `TitleRules.java:12`; `clampTitle` `AddEntryScreen.java:126-138`; `confirm` refuses invalid/empty `AddEntryScreen.java:156-161`; cancel/ESC never touches the store `AddEntryScreen.java:179-184,221-224`; self-test `wait-for-menu-after-cancel` asserts store size unchanged; 14 `TitleRulesTest` cases |
| R3b | Entries listed **top→bottom, one row each**; **"+" bottom-right of the last entry** | **PASS** | `UiLayout.mainRow()` `UiLayout.java:68-77` (y = listTop + i·32 − scroll); `addButtonY/X` `UiLayout.java:92-98`; in-game geometry assertions `add-button-right-aligned`, `add-button-y`, `add-button-below-last-row` (52-step report) |
| R4 | Three buttons left→right: **main (2/3 width, label = title, click sends)**, **配置/Configure**, **"-" deletes entry+config** | **PASS** | `FmccScreen.java:99-114` builds main/configure/delete; widths `UiLayout.java:71-76` (main = round(2/3·contentWidth) = 211/316, configure 71, delete 26, gaps 4); in-game geometry `row-0/1-*`/`button-order`/`no-overlap`; delete persists via `ConfigStore.removeEntry` `FmccScreen.java:374-388` |
| R5 | Config screen same style, **top-right 返回/Back**; **"+" adds text field**, **"-" deletes that text+config**; send **top→bottom with 0.75 s**; **nothing sent if no texts** | **PASS** | `EntryConfigScreen.java:89-108` (Back at `contentRight-…`, y in header), `110-139` (field + delete), `189-202`/`204-217`; `MessageQueue.java:17-18` `TICKS_BETWEEN_MESSAGES = 15` (test `[0,15,30,45]`); empty texts filtered `FmccEntry.java:57-67` + UI toast `FmccScreen.java:348-352`; 13 `MessageQueueTest` cases |
| R6 | ESC closes all menus; no performance impact; compatible with common mods; no bugs | **PASS (with documented P2/P3 residuals, §3-4)** | ESC: main menu e2e; config/add override `close()` `EntryConfigScreen.java:395-399`, `AddEntryScreen.java:221-224`; client-only, zero mixins, Fabric API events only (`fabric.mod.json:13-18`, no mixin config in tree/jar); 40+ mod pack launch + 52-step UI run PASS; idle tick O(1) `FmccClient.java:67-83`/`MessageQueue.java:74-77`; render uses only fills/text, scissor balanced |

No **FAIL** and no **open P0/P1** in the frozen tree. Two P1-class defects were found earlier in the cycle and are
verified fixed (§2). Requirement-level residuals are all P2/P3 and listed in §3.

---

## 2. P1-class defects found during the cycle (verified fixed in the frozen tree)

1. **Corrupt-config crash (was P1)** — `ConfigStore.load()` used to add `null` array elements from
   `{"entries":[null]}`; `entries()` (`List.copyOf`) then threw NPE inside `FmccScreen.readEntries()`/render,
   i.e. a hand-edited/partially corrupt config could crash the game at menu open.
   **Fixed**: `ConfigStore.java:64-72` skips null elements (and duplicate/missing ids are regenerated,
   `ConfigStore.java:221-238`); `removeEntry` now removes only the first match `ConfigStore.java:165-182`.
   Verified by code read + green build.
2. **Chat truncation unit bug (was P1)** — `ChatRouting` truncated by Unicode code points while
   `PacketByteBuf.writeString(msg, 256)` → `StringEncoding.encode` counts **UTF-16 chars** and throws
   `EncoderException` for >256 chars; 256 emoji are 512 chars. A hand-edited/imported text could kill the
   packet encoder instead of being clamped.
   **Fixed**: `ChatRouting.java:46-56` truncates to 256 **chars**, surrogate-safe; `MAX_LENGTH` documented
   `ChatRouting.java:9-14`; 10 `ChatRoutingTest` cases including surrogate-boundary cases.

Other fixes verified in the final tree: typing no longer writes the config file (`EntryConfigScreen.java:173-187`,
flush on `close()`/`removed()`/structural ops/`CLIENT_STOPPING` `EntryConfigScreen.java:395-412`,
`FmccClient.java:53-59`); typed title survives window resize (`AddEntryScreen.java:65-86`); `FmccClient.openKey()`
accessor `FmccClient.java:107-110`; `SelfTest.ENABLED` cached `SelfTest.java:179-188`; singular "1 entry"
(`FmccScreen.java:150-155`, lang `fmcc.menu.count.one`).

---

## 3. Open findings, prioritized

### P0 — none.

### P1 — none open.

### P2 — should-fix / known risks (not release blockers; all have low severity with concrete fixes)

**F1. Double-click on a row's "-" deletes two entries (main menu).**
`FmccScreen.deleteEntry` rebuilds the list synchronously inside the click handler
(`FmccScreen.java:374-388` → `clearAndInit()`), so the next row moves under the cursor and the second click of a
double-click lands on the *new* row's "-" and deletes it too. There is no confirmation/debounce; data loss is
silent (toast only says "Entry deleted"). Repro: two entries, double-click the top row's "-" → both gone.
Fix: ignore delete clicks within ~250 ms of the previous delete for the same screen (or require a second,
different gesture); optionally a small undo.

**F2. The "drop stale focus after delete" fix is ineffective.**
`FmccScreen.java:385` calls `setFocused(null)` inside `deleteEntry`, but 1.21.8 dispatches clicks via
`ParentElement.mouseClicked`: it calls `element.mouseClicked(...)` (which runs the action and rebuilds the screen)
and **then** calls `setFocused(element)` on the now-removed button (verified with `javap`). The screen therefore
still ends with focus on a dead widget after every delete. Impact is currently benign because `FmccButton`
(extends `ClickableWidget`) does not implement Enter/Space activation (`Element.keyPressed` default returns false),
but it is fragile (any future keyboard handling/narration change surfaces it). Fix: copy the
`EntryConfigScreen.dropStaleFocus()` pattern (`EntryConfigScreen.java:261-266`) into
`FmccScreen.mouseClicked` after `super.mouseClicked(...)` (`FmccScreen.java:308-316`) or into an overridden
`tick()`.

**F3. Config screen: fully-clipped top row can leave a blank strip at maximum scroll.**
`EntryConfigScreen` scrolls by whole rows (`EntryConfigScreen.java:365-393`) but clamps `next = max` when
`next >= max` (`:382-383`), and `maxScroll` (`UiLayout.java:104-107`) is generally **not** a multiple of
`rowStride()`. Rows are only rendered/laid out when fully inside the list (`isRowVisible`
`EntryConfigScreen.java:294-300`, `applyLayout:155-168`), so the top row that is partially visible is hidden
completely (visual gap up to 26 px). Same path is used when "+" jumps to the end (`:276-292`). Fix: round the
max scroll up to the next stride (and clamp only to that), or render/reposition rows with a scissor like
`FmccScreen` does (`FmccScreen.java:158-166,179-187`).

**F4. Save failures are swallowed; the UI still reports success.**
`ConfigStore.save()` returns `false` on IO errors (`ConfigStore.java:100-108`), but `addEntry`/`removeEntry`/
`removeText`/`addText`/`setText` ignore the return value, and the screens always show success toasts. If the
config directory becomes unwritable (OneDrive/permissions/disk full), entries appear to be saved and are lost on
restart. Fix: let mutators return success (or expose `lastSaveFailed`) and show one non-spammy failure toast/log.

**F5. A newer config version is silently downgraded on the next save.**
`load()` warns "loading it read-only anyway" (`ConfigStore.java:60-63`), but nothing prevents a later mutation
from writing `version = 1` (`ConfigStore.java:88-92`), dropping unknown fields a future version may have added.
Fix: remember the loaded version (or a read-only flag) and on save either keep the newer version + a backup, or
refuse to overwrite with a warning.

**F6. Control/invalid characters are not sanitised on the send path.**
`ChatRouting.route()` only strips whitespace (`ChatRouting.java:27-44`). In-game text widgets filter these
(`TextFieldWidget.write` → `StringHelper.stripInvalidChars`), but the config file is documented as user-editable
and `ConfigStore` does not sanitise texts on load (`ConfigStore.java:237` only trims/drops blanks). A text
containing e.g. `\n` would reach `ServerPlayNetworkHandler.validateMessage` → `StringHelper.isValidChar` and the
server disconnects the client (`multiplayer.disconnect.illegal_characters`). Fix: strip invalid chars in
`ChatRouting.route()` (cheap, defense in depth) and/or sanitise texts in `ConfigStore.sanitizeLoaded`.

**F7. README/DESIGN describe the old persistence behaviour.**
`README.md:63` says "每次编辑都会立即保存到 `config/fmcc.json`" and `docs/DESIGN.md:31` says "修改立即落盘";
after the P2 fix typing is kept in memory and flushed on close/removed/structural ops/quit. Update the docs
(also mention that a hard crash mid-edit can lose the unsaved keystrokes — accepted trade-off).
Related stale doc comments: `EntryConfigScreen.java:20-22` ("every keystroke is pushed … persists immediately"),
`AddEntryScreen.java:100` ("a resize re-runs init() without the latter" — false: 1.21.8 `refreshWidgetPositions()`
→ `clearAndInit()` calls both `init()` and `setInitialFocus()`), `FmccClient.java:85` ("a second press while it is
open closes it again" — unreachable, see F10).

**F8. Build does not pin the source encoding while Java sources contain CJK literals.**
`build.gradle:36-38` sets `options.release = 21` but not `options.encoding`. `SelfTest.java:232-233` contains the
Chinese literals `"FMCC测A"/"FMCC测B"` (the unit tests also use CJK literals). During the cycle one earlier
build/source revision rendered a mojibake title in-game (`FMCC娴婣4d9`, visible in `run-selftest/screenshots/2026-10-24_00.26.24.png`); the
current classes are correct UTF-8 (verified in the class constant pool), but the build still relies on the
daemon's default charset. Fix: add
`tasks.withType(JavaCompile).configureEach { options.encoding = 'UTF-8' }`.

**F9. The self-test harness (~63 KB / 5 classes) ships in the production jar.** `build/libs/fmcc-1.0.0.jar`
contains `com/fmcc/selftest/SelfTest*.class`; it is inert (enabled only by `-Dfmcc.selftest=true`,
`SelfTest.java:179-188`) and useful as acceptance evidence, but it is dead weight/attack surface in a release
artifact. Accepted known P2; optionally exclude `com/fmcc/selftest/**` from the release jar via a build flag.

**F10. `FmccClient.openMenu`'s close branch is dead code and the comment over-promises.**
1.21.8 `Keyboard.onKey` only feeds `KeyBinding.onKeyPressed` when `currentScreen == null` (or the pause menu is
showing), so the hotkey cannot fire while `FmccScreen` is open; `FmccClient.java:90-93` can never execute.
Consequence (documented behaviour): the hotkey only opens the menu from the in-game HUD, not from
chat/inventory/other screens. Not a requirement failure, but either remove the dead branch or document "toggle
only when no screen is open".

**F11. Extreme GUI scale / small scaled viewports clip the panel.**
`UiLayout.panel` enforces `PANEL_WIDTH_MIN = 250` and `UiLayout.dialog` a 240 px minimum
(`UiLayout.java:58-62,109-113`); on an 854×480 window at GUI scale 4 the scaled width is 213 px, so the panel's
left 47 px (including part of the 2/3 main button and titles) render off-screen. Widths should be clamped to
`min(PANEL_WIDTH, screenWidth - 2·MARGIN)` with the row buttons shrinking accordingly (the constant-width delete
button and 2/3 rule still hold at 200+ px).

**F12. Keyboard accessibility.** The menu can be opened by a keyboard hotkey, but nothing is keyboard-operable:
`FmccButton` extends `ClickableWidget` without Enter/Space activation (no `keyPressed` override,
`FmccButton.java:16-123`; `Element.keyPressed` defaults to `false`), and there is no visible focus indication for
navigation. Mouse is the primary interaction, so this is P2 polish, not a requirement failure.

### P3 — polish

**F13.** `MessageQueue` first follow-up can be 15 **or 16** ticks depending on where in the frame the click is
processed (the unit test's fake loop assumes enqueue happens before that tick's `tick()`,
`MessageQueueTest.java:68-104` vs `MessageQueue.java:56-94`); subsequent gaps are exactly 15 ticks. ≤0.8 s, within
the "0.75 s" intent; the self-test's single-message drain of 16 ticks is the same tick-boundary effect.
**F14.** `ChatDelivery.send()` returns `true` when `ChatRouting.route()` returns `null` (e.g. text `"/"`), so a
no-op text is counted as "sent" (`ChatDelivery.java:12-27`, `MessageQueue.java:116-126`) — toast says "Sent 1".
**F15.** Per-frame allocations remain in a few render paths contrary to the DESIGN rule: `Text.literal(hint)` +
`Draw.ellipsize` each frame (`EntryConfigScreen.java:526-532`), `UiLayout` records per visible row per frame
(`:478`), per-frame label `ellipsize` in `FmccButton.renderWidget` (`FmccButton.java:109-116`). Tiny, but easy to
cache.
**F16.** Large lists are not virtualized: 3 widgets per entry + O(n) relayout on every wheel notch
(`FmccScreen.java:126-156,318-336`) and O(fields) `syncFromWidgets()` per tick in the config screen
(`EntryConfigScreen.java:220-234`). Fine for normal configurations; could stutter with hundreds of entries/texts.
**F17.** Empty text fields are dropped on load (`ConfigStore.java:237`): a user can add 5 fields, fill 3, restart,
and the 2 empty fields are gone. Harmless for sending, but undocumented.
**F18.** Zero-width/invisible code points are accepted in titles (`TitleRules.sanitize` only removes ISO control
chars, `TitleRules.java:26-40`), so a title of 10 × U+200B is "valid" but renders as an invisible button label.
**F19.** Title length is counted in code points while the user-facing limit says "字/characters"; for emoji this is
generous (10 emoji allowed). Intentional and documented; no action needed.
**F20.** Dead code: `FmccClient.statusFeedback` (`FmccClient.java:112-118`, unused), `FmccScreen.refresh()`
(`FmccScreen.java:80-82`, unused), `EntryConfigScreen.MAX_TEXT_LENGTH` duplication with `ChatRouting.MAX_LENGTH`
(two sources of the same 256 constant).

---

## 4. Deep-review notes (matters checked, with the 1.21.8 facts that back them)

**Screen lifecycle (1.21.8, verified with `javap`).** `MinecraftClient.setScreen`: `removed()` on the old screen →
`currentScreen = screen` → `onDisplayed()` → `init(client, w, h)`. `Screen.init(client,w,h)` is `final` and calls
`init()` + `setInitialFocus()` only on first display; when `screenInitialized` is already true it calls
`refreshWidgetPositions()`, whose default implementation is `clearAndInit()` = `clearChildren()` + `blur()` +
`init()` + `setInitialFocus()`. `resize()` → `refreshWidgetPositions()`. Consequences: `FmccScreen`/`EntryConfigScreen`
re-populate correctly on re-display and on resize; `AddEntryScreen` used to lose its typed title on resize and now
restores it (`AddEntryScreen.java:65-86`). All three screens set `shouldPause() = false` and rely on the default
`shouldCloseOnEsc()`.

**Hit-testing / hidden widgets.** `ClickableWidget.isMouseOver` requires `active && visible` (javap), so the
`visible=false` culling for scrolled-out rows genuinely prevents hidden-click activation; both screens also guard
with `mouseClicked` list bounds (`FmccScreen.java:308-316`, `EntryConfigScreen.java:319-343`). Clicks are dispatched
to the *topmost* hovered child by `ParentElement.mouseClicked`; the rebuild-inside-click pattern
(`clearAndInit()` in a click handler) works but implies the focus ordering issue F2. Rebuild during click does not
throw `ConcurrentModificationException` because the handler returns `true` before the child list is iterated further.

**Focus / index sync in the config screen.** Listeners capture the row index and everything structural ("+"/"-")
rebuilds rows synchronously (`EntryConfigScreen.java:126-137,189-217`), so captured indices always match
`entry.getTexts()`; programmatic edits are picked up once per tick (`:220-234`); stale focus after "+"/"-" is
dropped (`:261-266,339-341`). Delete-while-focused is therefore safe. Text persists across resize because the
model (not the widget) is the source of truth. Typing no longer touches the disk (P1-era issue), and flush paths
cover Back, ESC, screen replacement/disconnect and client shutdown (`:395-412`, `FmccClient.java:53-59`).

**Message queue.** Global FIFO; first text of a click is sent immediately, the rest every 15 ticks; multiple rapid
clicks append without interleaving (`MessageQueue.java:42-71`); `clear()` on disconnect
(`FmccClient.java:48-52`); sink failures/throws abort the sequence instead of crashing (`:116-126`); no world ⇒
`ChatDelivery` returns `false` ⇒ queue aborts and the UI toasts "Join a world first". Not-in-world, screen-closed
and queue-while-menu-open behaviour all verified by unit tests/in-world run. Duplicate-send is only possible when
the user actually clicks twice (F1 discusses the accidental double-click case).

**ConfigStore robustness.** Missing file/dir, corrupt JSON (`null`, non-JSON) → backup + empty start
(`ConfigStore.java:49-78,240-250`, 19 tests), atomic temp-file replace with non-atomic fallback, no `.tmp` leftovers,
very large/small files, Unicode titles, >256-char texts (now truncated on send, F-fixed), leading `/`, `//`,
spaces, duplicate/missing ids (regenerated), null entries (skipped). Remaining risks: save-failure reporting (F4),
version downgrade (F5), invalid chars (F6).

**Post-1.21.8 API risk.** The whole mod compiles against 1.21.8 and only uses APIs present in 1.21.8 yarn
(`KeyBinding(String,Type,int,String)`, `sendChatMessage`/`sendChatCommand`, `Screen.onDisplayed/clearAndInit`,
`DrawContext` fills, `Text.translatable`); no reflection, no access wideners, no mixins; packaging is client-only.
The KeyBinding **category** string `category.fmcc` is correct for 1.21.8 because `ControlsListWidget` renders
`Text.translatable(keyBinding.getCategory())` (javap) — the lang files define it in both languages.

**Thread-safety / exceptions.** Everything runs on the client thread (init, ticks, input, config IO, save on
shutdown); no synchronization is needed and none is used. IO is wrapped; render/tick paths do no IO. There is no
global try/catch around `render()`, but the only historical NPE source (null config entries) is fixed.

**Compatibility / packaging.** Pure client (`environment: client`, only client entrypoint), no mixin configs in
the tree or jar, Fabric API only (`keybinding`, `ClientTickEvents`, `ClientPlayConnectionEvents`,
`ClientLifecycleEvents`). Verified in a 128-mod pack (Sodium 0.7.3, Iris 1.9.6, REI, Carpet ×4, Axiom, Litematica,
Xaero, MaLiLib/Tweakeroo, IMBlocker, …) — mod list and full 52-step UI run in `run-compat/logs/latest.log`.
Jar content: `fabric.mod.json` (version expanded to `1.0.0`), `assets/fmcc/icon.png`, both lang files,
`LICENSE_fmcc`, 47 entries, no mixins. `fabric.mod.json` depends: loader `>=0.17.0` (built/tested with 0.19.3),
`minecraft "~1.21.8"`, `java >=21`, `fabric-api "*"`. Reproducible jar sha (rebuild byte-identical).

**i18n.** `en_us.json`/`zh_cn.json` each contain exactly the same 37 keys (checked programmatically), every key
referenced by production Java exists in both, no hardcoded user-visible Chinese/English in production Java (only
entry titles as data; CJK literals exist only in the dev self-test). `fmcc.status.sent` is defined but unused
(harmless). Keybinding name/category are translated through the vanilla Controls screen. Screenshots confirm
English rendering; zh_cn was verified statically (not rendered in-game).

---

## 5. Self-test harness assessment

**Strengths.** 52 real-client steps driven through `Screen.mouseClicked` at widget centres (not callback calls),
real Tab injection (`KeyBinding.onKeyPressed` + END_CLIENT_TICK handler) and real `screen.keyPressed(ESC)`; asserts
store state, on-disk reload (`verifyStoreFile`), button geometry (2/3 width, order, right alignment, no overlap,
row stride, "+" below last row), queue state (`verifySendQueued`/`waitQueueDrain`), live entry ids, and screenshots
with a size heuristic against splash frames. It cleans up its entries and proves the persisted file has no
leftovers. Two environments are covered (title screen; real singleplayer world) plus a real 40+ mod pack run, with
JSON reports and logs under `docs/evidence/`. Harness failures are caught and still produce a report + clean quit.

**Residual gaps** (not blockers, worth adding for future regressions):
- Title validation is only unit-tested: the in-game run types a valid 9-code-point title; it never asserts that
  Create is disabled for empty/whitespace input, that >10 code points are clamped in the widget, or that Enter
  confirms and ESC/Cancel creates nothing in the *add dialog* itself (ESC is only e2e-tested on the main menu).
- Config-screen ESC → Back is not exercised (only the Back button); add-dialog ESC is not exercised.
- Multi-text ordering/0.75 s is unit-tested, but the in-game run sends only a single text (in-world run) so
  real in-game spacing and ordering across 2+ texts is not asserted.
- Scrolling is not exercised end-to-end: the runs use 2 entries/texts (maxScroll == 0), so the scroll/clip paths,
  the F3 gap and wheel handling have no e2e coverage.
- Screenshots are validated only by byte-size, not by pixels/OCR, so a visually broken but correctly laid-out UI
  could still "pass" (e.g. panel drawn off-screen/invisible would not be caught by the JSON assertions alone).
- `typeInto` falls back to `TextFieldWidget.setText` if synthetic `charTyped` is filtered (`SelfTest.java:1562-1578`),
  which can mask broken changed-listener wiring (the per-tick `syncFromWidgets` would still update the model).
- The final on-disk in-world JSON report was overwritten by the later title-screen run; in-world PASS 52/52 exists
  only as the preserved log. Modpack run is title-screen only (send path skipped by design, covered by the dev
  in-world run). No zh_cn run; no window-resize or IME test.

---

## 6. What I could not verify

- Actual **rebinding through the Controls UI** (only registration/default/category/id and hotkey dispatch are
  verified; the rebind mechanics are vanilla `KeyBinding` behaviour).
- **zh_cn rendering** in-game (key parity verified statically; screenshots are `en_us`).
- **IME/Chinese input** behaviour in the title/text fields (notably Enter-to-confirm vs IME candidate commit;
  `AddEntryScreen.keyPressed` intercepts Enter before the focused text field, `AddEntryScreen.java:194-205`).
- **Extreme GUI scales / very small windows** (code reasoning only, F11).
- **Save-failure paths** (unwritable directory, disk full) — not simulated.
- **Multiplayer server** interaction beyond the vanilla client API (singleplayer `/seed` proved the send path;
  permissions are server-enforced exactly as for typed commands).
- `Ctrl+V` paste edge cases and >256-char texts from the UI (field is capped at 256 UTF-16 chars, and
  `ChatRouting` now clamps again on send; only the hand-edited-config route was reasoned about).

---

## 7. Verdict

**Approved for release as a client-only Fabric 1.21.8 mod (1.0.0).** All six requirement groups pass; the frozen
tree compiles, 73/73 unit tests pass, the final jar is reproducible and correct, and the in-game acceptance runs
(title screen, real single-player world, and a real 40+ mod Sodium/Iris/REI/Carpet pack) are green with zero
failures. No P0/P1 issues remain. The P2 items above are real but low-severity (double-click delete, stale-focus
hygiene, max-scroll gap, silent save failure, version downgrade, invalid-char defense, docs/encoding hygiene,
pack-size/accessibility), and the P3 items are polish. F1–F9/F11 are worth a follow-up 1.0.1 if convenient.

---

## 附录：发布前修复记录（Lead，复审之后）

复审完成后、发布前，按本报告 §3 的建议修复并重新验证了以下项目（其余低风险项记录为 1.0.1 候选）：

| 复审项 | 处理 | 验证 |
|---|---|---|
| F1 双击 `-` 误删两个条目 | `FmccScreen.deleteEntry` 增加 350 ms 删除防重，行重排后二次点击被忽略 | 单测/自测全绿；行为见自测删除步骤连续通过 |
| F2 焦点清理无效 | 改为在 `FmccScreen.tick()` 中清理已不在 `children()` 内的焦点控件（与配置界面一致） | 编译 + 自测全绿 |
| F3 配置界面滚到底部有隐藏行/空白条 | `EntryConfigScreen.applyLayout` 改为“与列表视口相交即渲染”，由 scissor 裁剪半行 | 自测配置步骤全绿 |
| F6 手工配置中的控制字符可能触发踢出 | `ConfigStore` 载入时清洗 ISO 控制字符，再进入发送路径 | 单测/自测全绿 |
| F8 构建未固定 UTF-8 | `build.gradle` 的 `JavaCompile` 显式 `options.encoding = 'UTF-8'` | 重新构建成功，中文资源正常 |
| F11 GUI Scale 4 / 极小窗口面板被裁切 | `UiLayout.panel` 按可用宽度收窄而非越界；同步更新 `UiLayoutTest` | 76/76 单测通过 |
| F7 文档“逐键落盘”过时 | README / DESIGN 改为“内存编辑 + 关闭/退出落盘” | 文档已更新 |
| F4/F5/F9/F12 | 保留为已知低风险项（保存失败仅记日志、旧版配置覆盖、自测代码内置、无键盘操作菜单），不影响 1.0.0 发布 | — |

修复后最终验收：**76/76 单元测试**、标题界面自测 **50 通过 / 2 设计内跳过**、真实单人世界 **52/52**、
真实 40+ 模组整合包（中文界面）**50 通过 / 2 设计内跳过**，全部 PASS。发布产物 SHA-256：
`5D882D1DF41FDFF6ACF47696BF0B2620EC93D189C60EE3D85D34FC0BA0DAA188`（`dist/fmcc-1.0.0.jar`）。

### 1.1.0 追加（UI 视觉改版，玩家反馈）

在 1.0.0 发布后，按玩家反馈完成 UI 改版并重新验收：去掉伪粗体（重影根因）；面板缩至约屏幕 1/3 宽、55% 高；
黑色 50% 半透明背景；按钮透明填充 + 白色 1px 线框（主按钮更亮）；再按一次快捷键关闭菜单（`FmccScreen.keyPressed`
+ `FmccClient` 180 ms 去抖，避免同一次按键关闭后立刻重开）；ESC 保留为备用关闭方式。

改版后重新执行：**76/76 单测**、标题界面自测 **53 步 / 50 通过 / 3 设计内跳过**、真实单人世界 **53/53**
（含 `hotkey-closes-menu` 一次按键关闭 ✅）、真实 40+ 模组整合包中文界面 **53 步 / 50 通过 / 3 跳过**，全部 PASS。
发布产物：`dist/fmcc-1.1.0.jar`（94,398 B），SHA-256：
`3AE7ABE87301DE7D1EB064033174A29F32715D72616091F7F0F5271A075748C3`。

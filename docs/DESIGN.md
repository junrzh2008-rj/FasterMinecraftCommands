# FMCC — 设计与协作说明（Lead 维护）

模组：**FasterMinecraftCommands (FMCC)** · Minecraft **1.21.8** · **Fabric** · 纯客户端 · MIT

## 1. 需求 → 实现映射

| # | 需求 | 实现位置 | 验收方式 |
|---|---|---|---|
| 1 | 模组名 FMCC、1.21.8、Fabric、中英双语、白底黑字图标 | `fabric.mod.json`、`assets/fmcc/icon.png`、`assets/fmcc/lang/{en_us,zh_cn}.json` | 解包 jar 检查；游戏内切换语言截图 |
| 2 | 快捷键默认 Tab、可在设置改绑 | `FmccClient`（`KeyBindingHelper` + `key.fmcc.open`） | 游戏内控制设置可见、改绑生效 |
| 3 | 屏幕右侧现代化菜单 | `ui/FmccScreen`（右侧面板、圆角、悬停动画、滚动、自定义 tooltip） | 游戏内截图（自测脚本输出） |
| 4 | "+" 添加条目，强制 1–10 字标题；取消标题输入则中止创建 | `ui/FmccScreen`（+ 按钮）→ `ui/AddEntryScreen` → `ConfigStore.addEntry` + `TitleRules` | 单测（标题规则）+ 自测脚本（取消不落盘） |
| 5 | 条目自上而下一行一个；"+" 与最后条目右下方对齐 | `UiLayout.mainRow/addButtonX/addButtonY`；自测脚本断言坐标 | 几何断言 + 截图 |
| 6 | 条目三按钮：主按钮占 2/3（标题=条目标题，点击发送文本）、`配置`、`-` 删除条目及配置 | `FmccScreen` + `ChatRouting`/`MessageQueue` + `ConfigStore.removeEntry` | 自测点击链路 + 单测 |
| 7 | 配置界面同风格、右上角 `返回`；"+" 加文本框、"-" 删文本框及其配置；多条文本按序发送、间隔 0.75 秒；无文本不发送 | `ui/EntryConfigScreen` + `ConfigStore.addText/removeText/setText` + `MessageQueue`（15 ticks=0.75s） | 单测队列节奏 + 自测 UI + 实际游戏内发送日志 |
| 8 | 三个按钮从左到右排列；ESC 关闭菜单 | `UiLayout`；`Screen.shouldCloseOnEsc` 默认 true | 截图 + 游戏内按键 |
| 9 | 兼容性 / 无卡顿 / 无 bug | 纯客户端、零 Mixin、仅 Fabric API 事件；渲染只用 fill/文字；异常全部兜底 | 单元测试 + 自测 + 真实整合包（Sodium/Iris/REI/Carpet…）启动验证 |

## 2. 架构与数据流

```
按键(Tab) ──FmccClient.onEndClientTick──> FmccScreen(右侧面板)
   │                                        ├─ +  -> AddEntryScreen -> ConfigStore.addEntry(标题)
   │                                        ├─ 主按钮 -> MessageQueue.enqueue(entry.sendableTexts())
   │                                        ├─ 配置 -> EntryConfigScreen -> ConfigStore.addText/removeText/setText
   │                                        └─ -    -> ConfigStore.removeEntry
   └─ MessageQueue.tick()（每 tick，15 tick 一条） -> ChatDelivery -> 聊天/指令
```
- 持久化：`config/fmcc.json`（`-Dfmcc.config.path` 可覆盖，供测试用），原子写入。
- 发送队列：全局 FIFO，首发立即发送，之后每 15 tick 一条；断线清空。
- 所有 UI 状态都从 `ConfigStore` 读；打字时只更新内存（`markDirty`），在关闭/返回界面、
  结构变化（增删条目/文本）与客户端退出时落盘，避免输入路径上的磁盘 IO。

## 3. 文件所有权（写入范围，避免冲突）

| 归属 | 文件 |
|---|---|
| **Lead** | `build.gradle`、`settings.gradle`、`gradle.properties`、`tools/**`、`docs/**`、`fabric.mod.json`、`icon.png`、`lang/*.json`、`FmccClient.java`、`FmccPaths.java`、`config/**`、`chat/**`、`util/**`、`ui/UiTheme.java`、`ui/Draw.java`、`ui/FmccButton.java`、`ui/UiLayout.java` |
| **ui-main** | `ui/FmccScreen.java`（可新建 `ui/StatusToast.java` 等自己用的私有辅助类） |
| **ui-config** | `ui/EntryConfigScreen.java`、`ui/AddEntryScreen.java`（可新建私有辅助类） |
| **tester** | `src/test/java/**`、`selftest/**` |
| **reviewer** | 只读；把问题写到 `docs/REVIEW.md` 并报告 Lead |

不要修改别人拥有的文件；需要接口变更时先告诉 Lead。

## 4. 已提供的公共 API（直接用）

```java
// 数据
FmccClient.store()            // ConfigStore：entries()/size()/addEntry(title)/removeEntry(e)/addText(e)/removeText(e,i)/setText(e,i,v)/saveIfDirty()
FmccEntry                     // getId()/getTitle()/getTexts()（可编辑列表）/sendableTexts()（去空白快照）
TitleRules                    // MAX_CODE_POINTS=10, sanitize(), isValid(), count()

// 发送
FmccClient.queue()            // MessageQueue.enqueue(Collection<String>) / tick() / clear() / isBusy() / queuedCount() / sentCount()
ChatRouting                   // route(raw) -> Routed(command, payload) 或 null
ChatDelivery                  // MessageQueue.Sink 实现

// UI 基础设施（Lead 拥有，只调用不修改）
UiTheme                       // 颜色/尺寸常量
Draw.roundedRect/roundedBorder/shadow/lerpColor/text/centered/ellipsize/tooltip/boldText
FmccButton                    // extends ClickableWidget；new FmccButton(x,y,w,h,Text,Style,Runnable)
                              //   .tooltip(Text...)；Style: PRIMARY/SECONDARY/DANGER/GHOST/ACCENT
UiLayout.panel(w,h)           // Panel: x/y/width/height/contentX()/contentWidth()/contentRight()/listTop()/listBottom()/listHeight()
UiLayout.mainRow(panel,i,scroll)   // MainRow: y/mainX/mainWidth/configureX/configureWidth/deleteX/deleteWidth
UiLayout.fieldRow(panel,i,scroll)  // FieldRow: y/fieldX/fieldWidth/deleteX/deleteWidth
UiLayout.addButtonX/addButtonY     // "+" 右对齐、位于最后一行下方
UiLayout.dialog(w,h)               // 居中标题对话框

// 平台
FmccClient.statusFeedback(key,args)  // actionbar 提示（有 player 才发）
FmccClient.openMenu(client)          // 打开/关闭主菜单
```

## 5. 屏幕约定

- 面板：`UiLayout.panel(width,height)`；`renderBackground(...)` 后再画半透明遮罩 + 阴影 + 面板。
- 行列表要在 `listTop()..listBottom()` 内滚动绘制：`context.enableScissor(listLeft,listTop,listRight,listBottom)` … `disableScissor()`；`mouseScrolled` 调整 `scrollOffset`（clamp 到 `UiLayout.maxScroll`）。
- 悬停 tooltip：`render` 末尾用 `Draw.tooltip(context, this.textRenderer, button.tooltipLines(), mouseX, mouseY)`。
- 点击路径必须走真实控件（自测脚本会用 `screen.mouseClicked(x,y,0)` 驱动），每个按钮都要是 `addDrawableChild` 的控件。
- 组件重建：数据变化后调用 `this.clearAndInit()`（Screen 的 protected 方法）或 `clearChildren(); init();` 重建。
- 不要 pause 游戏：`shouldPause() -> false`。
- ESC：主菜单默认由 `Screen` 关闭（`shouldCloseOnEsc` 默认 true）；配置界面/标题框 ESC = 返回上一级（Override `close()`）。
- 文本全部走 `Text.translatable("fmcc.*")`，不要硬编码中英文。
- 渲染循环里不要做 IO / 不要每帧 new 大量对象；`Text` 常量静态缓存。

## 6. 构建 / 测试命令

```powershell
# 构建（必须用这个包装脚本：JDK21 + 本地 GRADLE_USER_HOME + 补丁过的 jdk.zipfs）
D:\DeepSeek-Harness\FMCC\tools\gradle.cmd build

# 只编译（更快）
D:\DeepSeek-Harness\FMCC\tools\gradle.cmd compileJava

# 单测
D:\DeepSeek-Harness\FMCC\tools\gradle.cmd test
```
- 产物：`build/libs/fmcc-1.0.0.jar`。
- 已下载的 1.21.8 named jar（javap 查 API 用）：
  `.gradle-home\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged\1.21.8-net.fabricmc.yarn.1_21_8.1.21.8+build.1-v2\minecraft-merged-....jar`
- 常用 API 签名已 dump 到 `docs/api/*.txt`（Screen/DrawContext/TextFieldWidget/KeyBinding/…）。
- 反编译源码（如已生成）：`.gradle-home\caches\fabric-loom\...` 或 `build/` 下 `*-sources.jar`；用 `docs/api` 与 javap 为准。

## 7. 代码规范

- 只允许客户端代码；严禁服务端注册/包处理。
- 禁止 Mixin；只使用 Fabric API 稳定事件（key-binding / lifecycle / networking disconnect）。
- 所有 `client.player`、`client.getNetworkHandler()`、`entry`、`texts` 访问前判空。
- 任何 IO/解析失败都 `LOGGER.error/warn` + 兜底，不向游戏抛异常。
- 不要新增第三方依赖；不要改 `gradle.properties`。
- 中文注释可以，但对外文案必须走翻译键。

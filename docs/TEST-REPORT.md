# FMCC 测试报告（1.21.8 Fabric）

本报告记录 **FasterMinecraftCommands 1.0.0** 的全部验证证据。所有数据均在本机 1.21.8 + Fabric 环境实际运行产生，
原始日志/报告/截图存于 `docs/evidence/`。

## 1. 环境

| 项目 | 值 |
|---|---|
| 游戏版本 | Minecraft 1.21.8（Fabric Loader 0.19.3） |
| 开发映射 | yarn `1.21.8+build.1`，Loom 1.11.8，JDK 21 |
| 开发客户端 | Loom `runClient`（Fabric API 0.136.1+1.21.8） |
| 真实整合包 | 用户本机 PCL 实例「1.21.8-Fabric 0.19.3」：Sodium 0.7.3、Iris 1.9.6、REI 20.0.811、Carpet 系 4 个、Axiom、Litematica、Xaero、MaLiLib/Tweakeroo、AppleSkin、Jade、IMBlocker 等 40+ 模组 |
| 运行产物 | `dist/fmcc-1.1.0.jar`（94,398 B） |

## 1.1 UI 版本 1.1 视觉改版（玩家反馈）

玩家反馈“标题/配置界面有重影、UI 太大”，1.1.0 做了如下改版并在三个环境重新跑完整验收：

| 反馈 | 改动 | 验证 |
|---|---|---|
| 标题/配置界面重影 | 去掉标题的“伪粗体”（同一文字错位画两遍产生双重阴影），改为单次绘制；配置界面同样处理 | 标题栏截图无重影（`docs/assets/fmcc-modpack-zh.png`） |
| UI 太大 | 面板宽度改为约屏幕 1/3（`UiLayout.panel`：`screenW/3`，最小 240、最大 340），高度约 55%；窄屏自动收窄不裁切 | 1280×720 实测面板宽 240/640=37.5%；`UiLayoutTest` 断言 |
| 背景改为黑色、不透明度 50% | 面板与控件统一 `0x80000000`（50% 黑）；移除额外遮罩/色块 | 截图可见背景世界透过面板 |
| 按钮跟随背景色、白色线框 | 按钮填充透明（即与面板同底色），仅白色 1px 线框；主按钮线框更亮、次要按钮 50% 白 | 截图 + 透明描边单元逻辑（`Draw.roundedOutline`） |
| 退出改为再按一次快捷键 | `FmccScreen.keyPressed` 识别热键关闭；`FmccClient` 做 180 ms 去抖避免同一次按键“关闭后立刻重开”；ESC 仍保留为备用 | `hotkey-closes-menu` 在真实世界 1 次按键即关闭 ✅ |

## 2. 单元测试（JUnit 5）

命令：`tools\gradle.cmd test` → **BUILD SUCCESSFUL，76/76 通过，0 失败**（日志 `docs/evidence/unit-tests.log`）。

| 测试类 | 数量 | 覆盖 |
|---|---|---|
| `TitleRulesTest` | 14 | 标题 1–10 字（Unicode 码点，含 CJK/emoji 代理对）、trim、控制字符、空标题拒绝、超长自动截断语义 |
| `ConfigStoreTest` | 19 | 条目增删、非法标题中止、文本增删改序、保存/重载往返、损坏 JSON 备份恢复、缺失文件、原子写入无 `.tmp` 残留、畸形条目清洗、新版本兼容、`saveIfDirty`、不可变快照 |
| `MessageQueueTest` | 13 | 首条立即发送、**恰为 [0,15,30,45] 刻（=0.75 s/条）** 的回归用例、连点 FIFO、空文本跳过、sink 失败/异常中止、`clear()`、空闲 tick 零开销 |
| `ChatRoutingTest` | 10 | `/` 指令路由、纯 `/` 忽略、`//`、内部空格保留、**256 UTF-16 字符上限（与聊天包 StringEncoding.encode 一致）**、代理对安全截断 |
| `UiLayoutTest` | 10 | 主按钮恰为行宽 2/3、配置/删除按钮左→右顺序、`+` 与最后一行右对齐、行间距/不重叠、滚动、窄屏自动收窄不裁切 |
| `FmccEntryTest` | 7 | 发送顺序/去空白、空文本过滤、防御性拷贝、id 相等语义、可变性 |
| `LangFilesTest` | 3 | 中英语言文件 JSON 合法性、键集合一致、`%s` 占位符一致、关键 UI 键存在 |

> 开发期间由测试发现并修复的真实缺陷：**MessageQueue 多等 1 刻（16 而非 15）**；`ChatRouting` 原按码点截断
> 会让 256 个 emoji 变成 512 字符并在发包时抛异常，已改为按 UTF-16 字符截断并加回归测试。

## 3. 游戏内自动化验收（真实客户端、真实鼠标/键盘事件）

测试器：`com.fmcc.selftest.SelfTest`（`-Dfmcc.selftest=true` 启用），52 个脚本步骤，真实 `screen.mouseClicked(...)`
点击控件中心、`KeyBinding.onKeyPressed` 注入真实 Tab、`screen.keyPressed(ESC)` 发送真实 ESC；每步断言数据/几何，
自动截图并写 JSON 报告。

### 3.1 开发客户端 · 标题界面环境

命令：`tools\gradle.cmd runClientSelfTest`
结果：**PASS — 53 步骤 / 50 通过 / 0 失败 / 3 设计内跳过**
（跳过 `hotkey-opens-menu`、`hotkey-closes-menu`：菜单只在游戏内 HUD 打开/关闭；跳过 `verify-send-queued`：标题界面无玩家，无法发包。三者都在 3.2 覆盖）
证据：`docs/evidence/selftest-title-screen-report.json`、`selftest-title-screen.log`；截图 8 张 105–111 KB，无 `suspectSmallFrame`。

### 3.2 开发客户端 · 真实单人世界环境

命令：`tools\gradle.cmd runClientSelfTestWorld`（`--quickPlaySingleplayer fmcc-test`）
结果：**PASS — 53/53 通过，0 跳过**，关键证据：

- `assert-hotkey-binding`：`key.fmcc.open` / `category.fmcc` / 默认 **Tab**（KEYSYM 258）/ `matchesKey(Tab,0)` ✅
- `hotkey-opens-menu`：注入真实 Tab 后 1 次即打开 `FmccScreen` ✅
- `hotkey-closes-menu`：再按一次 Tab 后 1 次即关闭菜单（`currentScreen == null`）✅
- `esc-closes-menu`：真实 ESC 关闭菜单，`currentScreen == null` ✅
- `click-send-a` → `verify-send-queued`：`sentCount 0 → 1`，服务端真实回显 `[System] [CHAT] Seed: [-4385414446454352670]`
  （说明 `/seed` 指令确实发送并执行）✅
- `wait-queue-drain`：队列在 **16 刻** 内排空（0.75 s 间隔 + 1 刻 tick 边界）✅
- `screenshot-after-send`：`main-send-in-world` 截图存在 ✅

证据：`docs/evidence/selftest-in-world-report.json`、`selftest-in-world.log`。

### 3.3 真实整合包兼容性（40+ 模组，中文界面）

用 `tools/LaunchModpackClient.java` 把发布用的 `dist/fmcc-1.0.0.jar` 注入隔离的整合包实例（同一批 40+ 模组，
Sodium/Iris/REI/Carpet/Axiom/Litematica/Xaero…），语言设为 `zh_cn`，跑同一套 52 步验收。
结果：**PASS — 53 步骤 / 50 通过 / 0 失败 / 3 设计内跳过**；界面在中文化 + Sodium/Iris 渲染管线下完整显示
（“配置”“ESC 关闭”“2 个条目”），8 张截图约 800 KB。证据：`docs/evidence/selftest-modpack-report.json`、
`modpack-selftest.log`、`docs/assets/fmcc-modpack-zh.png` 与 `fmcc-config-zh.png`。

## 4. 需求验收对照

| 需求 | 验证方式 | 结果 |
|---|---|---|
| 模组名/Fabric 1.21.8/中英双语/白底黑字 FMCC 图标 | `fabric.mod.json`、`assets/fmcc/icon.png`（人工查看）、`lang/en_us+zh_cn`（37 键完全对齐，脚本校验） | ✅ |
| Tab 默认快捷键、可在控制设置改绑、再按一次关闭 | `assert-hotkey-binding` + `hotkey-opens-menu` / `hotkey-closes-menu`（3.2） | ✅ |
| 右侧现代菜单（约 1/3 屏宽、黑色 50%、白线框）、ESC 关闭 | 截图 + `esc-closes-menu`（3.1/3.2） | ✅ |
| “+”添加、强制 1–10 字标题、取消中止创建 | `click-cancel` 后 store 仍为 0 + 单测 | ✅ |
| 条目一行一个、`+` 与最后条目右下方对齐 | 几何断言（`+` right=405=contentRight，y 随行数下移） | ✅ |
| 三按钮：2/3 主按钮（标题）、配置、删除 | 几何断言：2/3 宽=211/316、左→右顺序、无重叠 | ✅ |
| 配置界面同风格、右上返回、+/- 文本框、按序 0.75 s、无文本不发 | 配置截图 + 模型断言 + 队列 16 刻排空 + 单测 | ✅ |
| 删除条目并删除其配置 | 行 `-` 点击后 store 从 2→1→0 持久化 | ✅ |
| 兼容性/无卡顿/无 bug | 40+ 整合包实机、零 Mixin、空闲 tick 仅队列计数、渲染无 IO/无逐帧分配、73 项单测 + 52 步实机 | ✅ |
| 不暂停游戏 | `shouldPause()=false`（三界面） | ✅ |

## 5. 复现命令

```powershell
tools\gradle.cmd test                 # 73/73
tools\gradle.cmd runClientSelfTest    # 标题界面验收 50/52（2 设计内跳过）
tools\gradle.cmd runClientSelfTestWorld # 真实世界 52/52
# 整合包：见 tools/LaunchModpackClient.java 参数（versionJson/gameDir/assetsDir/modsSource/modJar/zipfsPatch）
```

## 6. 发布前独立复审

独立 reviewer 对冻结代码做了全量审计（见 [REVIEW.md](REVIEW.md)）：6 组需求全部 PASS，无未解决 P0/P1。
复审指出的可改进项已在发布前修复并重新验证：

- 双击 `-` 会误删两个条目 → 删除操作加 350 ms 防重（行重排不再连删）；
- 焦点残留修复位置不当 → 改为在 `tick()` 中清理已移除控件的焦点；
- 配置界面滚到底部时部分可见行被隐藏 → 改为按“相交即渲染”、由 scissor 裁剪；
- 手工编辑配置里的控制字符可能触发服务端踢出 → 载入时清洗控制字符；
- 构建未固定 UTF-8 → `compileJava` 明确 `UTF-8`；
- 极小窗口/GUI Scale 4 下右侧面板被裁切 → 面板按可用宽度收窄，不再越界；
- 文档中“逐键落盘”描述过时 → 已改为“内存编辑 + 关闭/退出落盘”。

修复后重新执行：**76/76 单测**、标题界面 **50/2 跳过**、真实世界 **52/52**、真实整合包中文界面 **50/2 跳过**，全部 PASS。
已知且接受的低风险项：保存失败目前只写日志（不影响游戏）、没有 Mod Menu 集成、Tab 与玩家列表冲突（README 已说明）。




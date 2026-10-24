# 构建环境说明

## 标准构建（推荐）

需要 **JDK 21**（Gradle 8.14.2 通过仓库自带的 wrapper 下载）：

```bash
./gradlew build        # Windows: gradlew.bat build
./gradlew test         # 76 个 JUnit 5 测试
./gradlew runClient    # 开发客户端
./gradlew runClientSelfTest      # 游戏内 53 步 UI 自动化验收（标题界面环境）
./gradlew runClientSelfTestWorld # 同上，直接进入单人测试世界（需先准备 saves/fmcc-test）
```

产物：`build/libs/fmcc-1.1.0.jar`。

## 本机（DSH sandbox）的特殊处理

开发该模组的环境是一个受限沙箱：所有路径的 `access(W_OK)` 检查被拒绝，导致 JDK 的
zip 文件系统（Fabric Loom 合并/重映射 jar 时使用）变为只读，Loom 会以
`ReadOnlyFileSystemException` 失败。本机因此额外使用了：

1. 一份打过补丁的 `jdk.zipfs` 模块（把 `readOnly = !writeable` 改为 `readOnly = false`）；
2. `org.gradle.jvmargs` 里加入 `--patch-module jdk.zipfs=<补丁目录>`；
3. `runClient*` 的 JVM 参数同样加 `--patch-module`，Fabric 的 tiny-remapper 才能写出 jar。

这些**仅是本机构建环境 workaround，正常电脑不需要**，所以上面的 `build.gradle` /
`gradle.properties` 是干净的通用版本。真实电脑上直接 `./gradlew build` 即可。

## 自动化验收说明

- `src/main/java/com/fmcc/selftest/SelfTest.java`：仅在启动参数
  `-Dfmcc.selftest=true` 时激活，正常游戏加载后不会产生任何行为，只用于真实客户端里的
  脚本化 UI 验收（真实鼠标点击、真实 Tab/ESC 按键、JSON 报告 + 截图）。
- 单测：`./gradlew test`；验收报告见 [TEST-REPORT.md](TEST-REPORT.md)。

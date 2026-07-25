<p align="center">
  <img src="android-app/app/src/main/res/drawable-nodpi/momoding_head.png" width="144" alt="Momoding" />
</p>

# Momoding

Momoding 是一个本地优先的 Android AI 编程工作台。Agent 循环运行在手机上，通过用户自己的
OpenRouter API Key 调用模型，并且只能访问用户通过 Android Storage Access Framework
明确授权的文件夹。

[English](README.md)

> **开发者预览版：** 当前仓库适合代码审查与本地开发，但还不是正式发布版。公开构建明确
> 不包含实验性的 Linux/PRoot runtime 和远程 Host，也不会向模型暴露终端或测试执行工具。

## 当前能力

- Pi Agent 循环运行在手机 QuickJS 中；Node.js 只用于构建和测试。
- 用户自行配置 OpenRouter。API Key 使用 Android Keystore 支持的 AES-GCM 加密，并存放在
  App 的 no-backup 私有目录。
- 使用 Room 持久化任务、计划、目标、子 Agent、Skill、附件和恢复状态。
- 文件夹必须由用户通过 Android SAF 授权，任务只使用不暴露真实路径的文件身份。
- 读取文件内容需要单独批准；写入前必须展示 Diff 并再次确认。
- 支持 Photo Picker、已授权的照片元数据、拍照、系统分享入口和受大小限制的文本附件。

## 真实边界

| 范围 | 当前行为 |
| --- | --- |
| API Key | 仅在本地加密保存，不写入源码或 APK |
| 项目文件夹 | 只有用户通过 Android SAF 选择后才能访问 |
| 文件内容 | 需要任务级单独授权 |
| 文件修改 | 先在私有空间准备，展示 Diff，确认后才写入真实文件夹 |
| 照片 | Photo Picker 不需要全库权限；照片库元数据服从 Android 权限状态 |
| 终端 | 公开构建不可用 |
| 远程 Host | 不在本仓库中 |

这是一套安全边界设计，并不等同于形式化安全证明。安全问题请按照
[SECURITY.md](SECURITY.md) 私下报告。

## 本地构建

需要 JDK 17、Android SDK Platform 37 / Build Tools 37.0.0、Node.js 22.22.3 和 npm
10.9.8。设置 `JAVA_HOME` 与 `ANDROID_HOME`，不要提交 `local.properties`。

```bash
npm ci --prefix mobile-runtime-js
npm run check --prefix mobile-runtime-js

JAVA_HOME=/path/to/jdk-17 \
ANDROID_HOME=/path/to/android-sdk \
./android-app/gradlew -p android-app \
  testDebugUnitTest lintDebug assembleDebug assembleRelease
```

完整验证：

```bash
./scripts/verify.sh
```

架构见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)，安全边界见
[docs/SECURITY_MODEL.md](docs/SECURITY_MODEL.md)，发布流程见
[RELEASING.md](RELEASING.md)。仓库公开范围见
[OPEN_SOURCE_SCOPE.md](OPEN_SOURCE_SCOPE.md)，参与贡献见
[CONTRIBUTING.md](CONTRIBUTING.md)，第三方许可证见
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

Momoding 自有源码使用 [MIT License](LICENSE)。

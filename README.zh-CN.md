<p align="center">
  <img src="android-app/app/src/main/res/drawable-nodpi/momoding_head.png" width="144" alt="Momoding" />
</p>

# Momoding

Momoding 是一个 Android 本地优先的 AI 编程工作台。Pi Agent 循环运行在手机上，使用用户自己
配置的 OpenRouter API Key；所有设备侧动作都要经过 Android 端持有的权限、策略和确认流程。

[English](README.md)

Momoding 是一个独立项目，与 OpenAI、OpenRouter、Shizuku 及 Pi 上游维护者不存在隶属、官方
合作或背书关系。

> **开发者预览版：** 当前仓库适合源码审阅和本地开发，但还不是正式发行版或 Play 商店版本，
> 也尚未经过独立安全审计。

## 适合谁

Momoding 面向希望审阅和尝试 Android 端 AI 编程工作台的开发者：Agent 循环运行在 Android
设备上，模型凭据由 Android 端管理，设备能力及其授权状态对用户可见、可审查。

## 当前能力

- Pi Agent 循环在 QuickJS 中运行；Node.js 仅用于构建和测试运行时包。
- 用户自带 OpenRouter API Key，并由 Android Keystore 支持的加密方案保存。
- 持久化任务、计划、Goal、子 Agent、Skill、附件、审批与恢复状态。
- 用户授权项目目录、受控内容读取、变更预览以及写入前确认。
- 在支持的 Debug 构建中提供手机本地 Linux 项目环境，以及命令和测试工具。
- 图片元数据、拍照、系统分享导入、文本附件和共享存储工具。
- 用户主动开启的屏幕捕获，以及基于无障碍服务的界面检查和受控操作。
- 通过用户另行安装并授权的 Shizuku，执行只读的应用列表与单应用信息查询。

## 重要权限边界

| 能力 | 当前边界 |
| --- | --- |
| API Key | 本地加密；不会有意写入 JavaScript、日志或 APK |
| 项目目录 | 由用户通过 Android Storage Access Framework 选择 |
| 文件内容 | 通过任务级工具和当前有效的 Android 授权读取 |
| 文件修改 | 先准备和展示差异，再经过 Android 策略检查后提交 |
| 共享存储 | 需要用户在系统设置中授予“所有文件访问”权限 |
| 屏幕捕获 | 需要用户主动启动 MediaProjection；图像只在当前工具轮次使用 |
| 界面控制 | 需要开启无障碍服务；操作只接受最新快照产生的不透明节点句柄 |
| 应用信息 | 需要 Shizuku；只提供有界、只读的列表和详情查询 |
| 项目命令 | 运行在 PRoot/Alpine 环境；PRoot 不是面向恶意代码的安全沙箱 |
| 模型服务 | 用户授权的提示词与工具内容会发送到所选择的 OpenRouter 模型 |

更完整的说明见[安全模型](docs/SECURITY_MODEL.md)。这些设计用于减少越权和误操作，不构成
形式化安全证明。

## 仓库结构

```text
android-app/        Android 应用、Room 存储、设备策略、界面与测试
mobile-runtime-js/  为 QuickJS 构建的固定版本 Pi 运行时
wire/               共享协议与 Kotlin 合约
scripts/            运行时构建、验证与公开发布检查
third_party/        复现可选原生组件所需的已审阅补丁
```

公开仓库由私有主仓库中的明确白名单生成。内部调研、真机记录、凭据、远程 Host 服务、内部
编排代码和历史验证材料不会进入公开仓库；Android 应用所需的公开客户端与协议代码仍然保留。
详见[开源范围](OPEN_SOURCE_SCOPE.md)。

## 环境要求

- JDK 17
- Android SDK Platform 37、Build Tools 37.0.0、NDK 28.2.13676358
- Node.js 22.22.3、npm 10.9.8
- Git、curl、patch、ripgrep

请配置 `JAVA_HOME` 和 `ANDROID_HOME`，不要提交 `local.properties`。

## 构建与测试

```bash
npm ci --prefix mobile-runtime-js
npm run check --prefix mobile-runtime-js

JAVA_HOME=/path/to/jdk-17 \
ANDROID_HOME=/path/to/android-sdk \
./android-app/gradlew -p android-app \
  testDebugUnitTest lintDebug assembleDebug assembleRelease
```

Debug 构建会下载固定版本的 PRoot、talloc 和 Alpine 源码/资源，校验 SHA-256 后生成手机本地
项目运行环境。在完整审查对应源码和第三方声明义务之前，不应对外分发生成的 APK。

完整验证：

```bash
./scripts/verify.sh
```

## 参与贡献与安全问题

提交 Pull Request 前请阅读 [CONTRIBUTING.md](CONTRIBUTING.md)。安全问题请按
[SECURITY.md](SECURITY.md) 私下报告；不要在公开 Issue 中提交真实凭据、私有项目文件或诊断包。

## 许可证

Momoding 自有源码使用 [MIT License](LICENSE)。打包或构建时获取的第三方组件继续适用其原始
许可证，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

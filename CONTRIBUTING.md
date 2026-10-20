# 参与 Momoding / Contributing

谢谢你愿意帮 Momoding 变得更好。English below.

## 最有用的：反馈

Momoding 是在和用户的来回里长出来的。一份清楚的反馈，往往比一段代码更有用：

- **[提 Issue](https://github.com/1zhangyy1/momoding/issues/new/choose)**：哪里坏了、哪里别扭、你希望它能办什么事。
- **[Discussions](https://github.com/1zhangyy1/momoding/discussions)**：分享你的用法、聊想法、问问题。

好的反馈包括：你交代了什么、它做了什么、你期望它做什么；手机型号和 Android 版本；用的哪家模型；设置 → 关于 里的版本号。截图或录屏最好。

## 提交代码

欢迎 PR。几个约定：

1. **改动大的先开 Issue 聊一下**，免得白做。
2. **Momoding 自己的代码放在 `src/android/app/src/main/java/app/momoding/`**。改上游代码（`com.openminis.app`）时尽量小，并加一行 `// momoding:` 注释说明为什么，方便以后合并上游。
3. **上游本身的 bug** 也可以顺手报给 [OpenMinis](https://github.com/OpenMinis/OpenMinis/issues)。
4. **界面文字**：中文优先，用 Momoding 的口吻：说人话，不出现 Agent、Shell、token 这类词。新文字要同时加中文和英文。
5. 提交前跑一下单元测试：`cd src/android && ./gradlew :app:testDebugUnitTest`。

提交的代码以 GPLv3 授权，和项目一致。

## 安全问题

请不要公开提 Issue，见 [SECURITY.md](SECURITY.md)。

---

## English

**Feedback first.** A clear [issue](https://github.com/1zhangyy1/momoding/issues/new/choose) is often worth more than a patch: say what you asked for, what it did and what you expected, plus your device, Android version, model provider and app version. Use [Discussions](https://github.com/1zhangyy1/momoding/discussions) for ideas and use cases.

**Pull requests are welcome.** For anything large, open an issue first. Momoding's own code lives in `src/android/app/src/main/java/app/momoding/`. Keep edits to upstream code (`com.openminis.app`) small and mark them with a `// momoding:` comment so that upstream merges stay easy. Add new UI text in both Chinese and English. Run `./gradlew :app:testDebugUnitTest` in `src/android` before you submit. Contributions are licensed under GPLv3.

**Security issues:** please don't file a public issue. See [SECURITY.md](SECURITY.md).

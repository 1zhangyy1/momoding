# Momoding 隐私说明 / Privacy

更新于 2026-10-20 · 适用于 Momoding 1.0 及以后版本。English below.

## 一句话

Momoding 没有账号、没有服务器。你的东西存在你的手机上；只有在办事时，需要的内容才会发给**你自己选的模型服务**。

## 存在你手机上的

- 对话、它记住的你的偏好、允许规则、例行任务、办事过程的记录；
- 你填的模型 API Key（加密存储）；
- 崩溃日志（只存在手机上，除非你自己分享出去）。

这些都不会上传到 Momoding。Momoding 没有可以接收它们的服务器。

## 会离开你手机的

| 发给谁 | 发什么 | 什么时候 |
| - | - | - |
| 你选的模型服务（如 DeepSeek、Kimi、OpenRouter） | 你的话、对话上下文，以及它办事时读到的内容（比如一条日程、一个网页、一张截图） | 每次它思考或办事时 |
| 你让它打开的网站 | 和普通浏览器一样的访问请求 | 它替你上网查东西时 |
| 软件源镜像（Alpine、pip、npm 等）、models.dev | 下载软件包、获取模型列表的普通请求，不含你的对话 | 它在自己的 Linux 环境里装软件、更新可选模型列表时 |
| GitHub | 版本检查请求 | 你在"关于"里点"检查更新"时 |
| 你选择的收件人 | 你确认过的消息草稿 | 你点"发送"时 |

模型服务怎么处理你的数据，以它们各自的隐私政策为准。选哪家，由你决定。

## 手机权限

日历、通讯录、位置、通知、无障碍（动手操作屏幕）、悬浮窗，都是用到时才申请，并说明用途。不给权限，相应的功能不能用，其他功能照常。随时可以在 设置 → 手机权限 里关掉。

## 没有的东西

- 没有统计、广告、第三方追踪 SDK；
- 没有 Momoding 账号；
- 不会把你的数据卖给或提供给任何人。

## 删除

卸载 App 会删除它在手机上存的所有数据。已经发给模型服务的内容，请按该服务的政策处理。

## 联系

隐私相关的问题请在 [GitHub](https://github.com/1zhangyy1/momoding/issues) 提出；涉及安全漏洞请看 [SECURITY.md](SECURITY.md)。

---

## English

**In short:** Momoding has no accounts and no servers. Your data stays on your phone. While it works on a task, only the content that task needs is sent to **the model provider you chose**.

**Stored on your phone:** conversations, the preferences it remembers, allow rules, routines and records of past tasks; your model API key (encrypted); and crash logs (kept on the device unless you share them yourself). None of this is uploaded to Momoding, because there is no Momoding server to receive it.

**What leaves your phone:**

- your messages, the conversation context, and whatever it reads while working (a calendar entry, a web page, a screenshot) go to your model provider;
- websites it opens for you receive ordinary browser requests;
- software mirrors (Alpine, pip, npm and so on) and models.dev receive ordinary download requests, without your conversation, when it installs software in its own Linux environment or refreshes the model list;
- GitHub receives a version check when you tap "Check for updates";
- recipients you confirm receive the drafts you approve and send.

Each model provider handles data under its own privacy policy.

**Permissions:** calendar, contacts, location, notifications, Accessibility ("Hands") and the overlay permission are requested only when needed, each with an explanation. You can turn any of them off under Settings → Phone permissions.

**What it doesn't have:** analytics, ads, third-party tracking SDKs or accounts. Your data is never sold or shared.

**Deleting your data:** uninstalling the app deletes everything it stored on your phone. For content already sent to a model provider, follow that provider's policy.

**Contact:** open an issue on [GitHub](https://github.com/1zhangyy1/momoding/issues). For security issues, see [SECURITY.md](SECURITY.md).

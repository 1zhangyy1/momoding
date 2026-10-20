# 安全问题 / Security

Momoding 能读你的日历、通讯录，能在你同意后操作手机屏幕，安全问题我们会优先处理。

**请不要在公开 Issue 里报告安全漏洞。** 请用 GitHub 的私密报告：
仓库页 → Security → [Report a vulnerability](https://github.com/1zhangyy1/momoding/security/advisories/new)。

请尽量写清楚：影响哪个版本、怎么复现、可能造成什么后果。我们会尽快回复，修好后在更新说明里致谢（如果你愿意）。

我们特别关心的：绕过"先问你"审批的方法、模型 Key 泄露、网页或消息内容诱导它做你没让它做的事（提示词注入）、越过权限访问手机数据。

---

**Please don't report vulnerabilities in public issues.** Use GitHub's private reporting: Security → [Report a vulnerability](https://github.com/1zhangyy1/momoding/security/advisories/new). Include the affected version, steps to reproduce and the impact. We care most about bypasses of the approval ("Ask first") flow, model-key leaks, prompt injection from web pages or messages that makes it act without your consent, and access to phone data beyond the granted permissions.

Issues in upstream components (the sandbox, PRoot, iSH) may also need to go to [OpenMinis](https://github.com/OpenMinis/OpenMinis).

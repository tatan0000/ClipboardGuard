# 剪贴板护卫 (ClipboardGuard)

一个基于 LSPosed/Xposed 框架的 Android 模块，用于管理应用对剪贴板的写入与读取行为。防止应用在未经允许下在剪贴板里拉屎/读取剪贴板隐私信息。

## 前言
去年在浏览器打开123云盘的链接后，总是会复制莫名其妙的快手指令到我的剪贴板里，流体云显示了，我很不爽。😡😡但是也没什么办法，禁用写入剪贴板权限吧，那其他正常复制操作就受阻了，写了一个浏览器脚本但是没有效果，于是就放任不管了。  
今年，我在学校的外卖小程序点餐付完款后，总是有弹窗广告就算了，甚至直接在剪贴板里写入闲鱼的链接，流体云也显示了，我很不爽，😡😡这种感觉就跟被强奸了一样，无法反抗。实在忍受不了这种流氓行为，又因为最近AI很火，我又有root设备，我就想尝试写一下，即将毕业，也为自己积累一点项目经验。  
于是有了这个模块。希望能借此项目，推动国内应用市场及各厂商进一步收紧权限管控，后续也需要手机厂商、开发者和普通用户共同监督、一起整治。  
hook系统服务并不那么容易，需要深入了解 Binder 机制、系统广播、内存泄漏规避等底层知识。如果我没有及时更新，请原谅我时间和能力有限，但我会尽力维护，坚持把项目做下去、不停摆。同时也欢迎各位大佬提出建议想法、提交 Issue 和 PR，一起完善这个项目。

## 📌 关于模块
- **建议优先使用系统自带功能，避免产生不必要的冲突**
- 本模块由 腾讯WorkBuddy,Codex 辅助生成，参考许多开源项目
- 本模块基于 LSPosed/Xposed 实现，主要面向系统级剪贴板拦截场景
- 当前已包含写入拦截、读取拦截、规则管理、配置同步和日志输出
- 如果遇到问题，欢迎提交 Issue
- 如果模块对你有帮助，欢迎给我点个 star ⭐

## 📋 功能特点

- **写入拦截**：应用写入剪贴板时可弹窗询问，支持允许、拒绝、超时默认拒绝
- **读取拦截**：可按应用和正则规则控制读取行为，支持拒绝与拒绝并清空剪贴板
- **内容预览**：弹窗显示目标内容预览，便于快速判断
- **防抖机制**：同一应用在短时间内重复请求时复用最近决策
- **系统应用过滤**：系统核心应用自动放行，不影响系统稳定性

## 📱 使用方法

### 安装要求

- Android 11 (API 30) 及以上
- 已安装 LSPosed 或其他兼容的 Xposed 框架(基于Xposed API 82开发)

### 安装步骤

1. 安装 APK 到设备
2. 在 LSPosed 管理器中启用本模块，勾选推荐作用域（系统框架）
3. 首次安装需要授予应用 开机自启动 权限，
   Thanox里开启了后台启动的，也要打开本模块的后台启动
（开机自启动可能不生效，需要开机后自行打开APP进行配置同步）

4. 打开APP选择要监控的应用（推荐选择需要的应用）
5. 重启设备

## 📝 配置信息

| 配置项 | 值 |
|-------|-----|
| 包名 | com.android.clipboardguard |
| minSdk | 30 |
| targetSdk | 36 |
| Xposed API | 82 |

## 🐛 问题反馈
### 遇到 Bug ，按以下格式提交 Issue ：
### 1. 环境截图：模块主页面 + Xposed 版本 + Android 系统版本
### 2. 问题描述：清晰说明 Bug 表现、复现场景
### 3. 复现录屏：直观展示 Bug 触发过程
### 4. 日志信息：提供 logcat 或 LSPosed Manager 日志

### 温馨提示：由于开发时间有限，问题处理可能存在延迟，但我会尽力维护和更新模块，感谢理解和支持！🙏

## 🔧 技术原理
（此处待补充核心技术逻辑）

## 🏗️ 项目结构
（此处待补充项目模块划分）

## 🤝 参考与致谢（排名不分先后）
### 开源项目
- **Thanox**：[https://github.com/Tornaco/Thanox](https://github.com/Tornaco/Thanox)
- **HMA-OSS**：[https://github.com/frknkrc44/HMA-OSS](https://github.com/frknkrc44/HMA-OSS)
- **AdClose**：[https://github.com/zjyzip/AdClose](https://github.com/zjyzip/AdClose)
- **GKD**：[https://github.com/gkd-kit/gkd](https://github.com/gkd-kit/gkd)
- **Shizuku**：[https://github.com/RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku)
- **Magisk**：[https://github.com/topjohnwu/Magisk](https://github.com/topjohnwu/Magisk)
- **LSPosed**：[https://github.com/LSPosed/LSPosed](https://github.com/LSPosed/LSPosed)
- **XposedBridge**：[https://github.com/rovo89/XposedBridge/wiki/Development-tutorial](https://github.com/rovo89/XposedBridge/wiki/Development-tutorial)
- **libxposed API**：[https://github.com/libxposed/api](https://github.com/libxposed/api)
- **ClipboardManagerHook**：[https://github.com/superxlcr/ClipboardManagerHook](https://github.com/superxlcr/ClipboardManagerHook)

### 技术文章
- **Android 剪贴板基础与使用示例**：[https://www.w3schools.cn/android/android-clipboard.html](https://www.w3schools.cn/android/android-clipboard.html)
- **Android 官方复制粘贴开发指南**：[https://developer.android.google.cn/develop/ui/views/touch-and-input/copy-paste?hl=zh-cn](https://developer.android.google.cn/develop/ui/views/touch-and-input/copy-paste?hl=zh-cn)

本项目的开发离不开以上优秀开源项目与技术资料的启发与支撑，在此向所有作者与贡献者致以诚挚的感谢 🙏

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
- 当前已包含写入拦截、读取拦截、规则管理、规则单独适用域、配置同步和日志输出，形成完整的剪贴板访问控制与审计链路
- 如果遇到问题，欢迎提交 Issue
- 如果模块对你有帮助，欢迎给我点个 star ⭐

## 📋 功能特点

### 核心拦截功能
- **写入拦截**：Hook `ClipboardService.setPrimaryClip`，应用写入剪贴板时弹窗询问，支持允许、拒绝、超时默认拒绝（5秒超时）
- **读取拦截**：Hook `ClipboardService.getPrimaryClip`，支持拒绝、拒绝并清空剪贴板，特殊支持银行卡号 Luhn 算法验证
- **内容预览**：弹窗显示剪贴板内容预览（最多100字符），便于快速判断
- **规则管理**：基于正则表达式的内容匹配规则，支持自定义规则和默认规则，长文本分块扫描（每块5000字符，重叠100字符）
- **规则适用域**：每个规则可指定适用的包名列表，实现精细化控制，移除应用时自动联动清理关联规则
- **防抖机制**：写入拦截2秒防抖、读取拦截3秒防抖，同一应用短时间内重复请求时复用最近决策
- **配置同步**：通过广播机制实现 App 与 system_server 之间的实时配置同步，支持开机自动加载（含重试机制）
- **日志输出**：双进程日志系统，支持 LSPosed 模块日志页和 logcat 输出，剪贴板内容自动脱敏
- **系统应用过滤**：核心系统应用自动放行，不影响系统稳定性

## 📱 使用方法

### 安装要求

- Android 11 (API 30) 及以上
- 已安装 LSPosed 或其他兼容的 Xposed 框架(基于Xposed API 82开发)

### 安装步骤

1. 安装 APK 到设备
2. 在 LSPosed 管理器中启用本模块，勾选推荐作用域（系统框架）
3. 首次安装并激活模块重启后，需要打开APP进行配置同步，后续重启不需要，会自动读取配置并生效

## 📝 配置信息

| 配置项        | 值                          |
|------------|----------------------------|
| 包名         | com.android.clipboardguard |
| minSdk     | 30                         |
| targetSdk  | 37                         |
| Xposed API | 82                         |

## 🐛 问题反馈
### 遇到 Bug ，按以下格式提交 Issue ：
### 1. 环境截图：模块主页面 + Xposed 版本 + Android 系统版本
### 2. 问题描述：清晰说明 Bug 表现、复现场景
### 3. 复现录屏：直观展示 Bug 触发过程
### 4. 日志信息：提供 logcat 或 LSPosed Manager 日志

### 温馨提示：由于开发时间有限，问题处理可能存在延迟，但我会尽力维护和更新模块，感谢理解和支持！🙏

## 🔧 技术原理

本模块基于 LSPosed/Xposed 框架，在 `system_server` 进程中 Hook `ClipboardService` 的 `setPrimaryClip` 和 `getPrimaryClip` 方法，实现对剪贴板写入/读取行为的拦截与管控。

### 工作流程
1. **拦截触发**：应用调用剪贴板 API 时，Hook 点捕获调用，检查是否命中拦截名单或内容规则
2. **用户决策**：通过 `InlineDialogManager` 在前台弹窗询问用户，使用 `CountDownLatch` 阻塞 Binder 线程等待决策
3. **结果反馈**：根据用户决策放行、拒绝或拒绝并清空剪贴板，决策结果在防抖窗口期内自动复用
4. **配置同步**：App 修改配置后通过广播通知 system_server，更新内存缓存并持久化到 `/data/system/clipboardguard/`

### 架构特点
- **system_server 侧 Hook**：核心拦截逻辑运行在系统进程，App 仅作为配置控制台，确保拦截不依赖 App 前后台状态
- **双进程通信**：通过 `PermissionProvider`（ContentProvider）+ 广播机制实现跨进程配置同步
- **内存缓存 + 磁盘持久化**：`PermissionCache` 维护拦截名单内存缓存，`ConfigManager` 负责磁盘读写

> 完整的技术原理与架构设计详情请参阅 [DESIGN.md](./DESIGN.md)

## 🏗️ 项目结构

```
app/src/main/java/com/android/clipboardguard/
│
├───── Xposed Hook 入口 ──────────────────────────────────────
├── ClipboardHook.java              # Hook 入口，写入/读取拦截 + 状态查询通道
│
├───── Hook 侧核心组件 ─────────────────────────────────────
├── PermissionCache.java            # 拦截名单内存缓存 + 全局开关
├── ConfigManager.java              # 配置持久化中枢，广播处理 → 更新内存 + 落盘
├── ContentRulesManager.java        # 内容规则引擎，正则匹配 + Luhn 验证
├── InlineDialogManager.java        # 悬浮窗弹窗管理（全代码 UI）
├── XLog.java                       # 双进程日志工具
│
├───── 数据模型 ────────────────────────────────────────────
├── ContentRule.java                # 内容规则实体 + JSON 序列化
├── PermissionDecision.java         # 决策常量（BLOCK=0 / IGNORE=1 / CLEAR=2）
│
├───── 跨进程通信 ──────────────────────────────────────────
├── PermissionProvider.java         # ContentProvider，配置存储与同步入口
│
├───── App 侧 ─────────────────────────────────────────────
├── ClipboardGuardApp.java          # Application 入口
├── MainActivity.java               # 主界面（首页/写入/读取/设置四个 Tab）
├── WriteRulesDetailActivity.java   # 写入规则详情页
├── ReadRulesDetailActivity.java    # 读取规则详情页
├── WriteRuleAppsActivity.java      # 写入规则适用应用选择页
├── ReadRuleAppsActivity.java       # 读入规则适用应用选择页
├── AboutModuleActivity.java        # 关于模块页
```

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
- **ClipboardManagerHook**：[https://github.com/superxlcr/ClipboardManagerHook](https://github.com/superxlcr/ClipboardManagerHook)

### 技术文章
- **Android 剪贴板基础与使用示例**：[https://www.w3schools.cn/android/android-clipboard.html](https://www.w3schools.cn/android/android-clipboard.html)
- **Android 官方复制粘贴开发指南**：[https://developer.android.google.cn/develop/ui/views/touch-and-input/copy-paste?hl=zh-cn](https://developer.android.google.cn/develop/ui/views/touch-and-input/copy-paste?hl=zh-cn)

本项目的开发离不开以上优秀开源项目与技术资料的启发与支撑，在此向所有作者与贡献者致以诚挚的感谢 🙏

## 后记
去年在浏览器打开123云盘的链接后，总是会复制莫名其妙的快手指令到我的剪贴板里，流体云显示了，我很不爽。😡😡但是也没什么办法，禁用写入剪贴板权限吧，那其他正常复制操作就受阻了，写了一个浏览器脚本但是没有效果，于是就放任不管了。  
今年，我在学校的外卖小程序点餐付完款后，总是有弹窗广告就算了，甚至直接在剪贴板里写入闲鱼的链接，流体云也显示了，我很不爽，😡😡这种感觉就跟被强奸了一样，无法反抗。实在忍受不了这种流氓行为，又因为最近AI很火，我又有root设备，我就想尝试写一下，即将毕业，也为自己积累一点项目经验。  
于是有了这个模块。希望能借此项目，推动国内应用市场及各厂商进一步收紧权限管控，后续也需要手机厂商、开发者和普通用户共同监督、一起整治。  
hook系统服务并不那么容易，需要深入了解 Binder 机制、系统广播、内存泄漏规避等底层知识。如果我没有及时更新，请原谅我时间和能力有限，但我会尽力维护，坚持把项目做下去、不停摆。同时也欢迎各位大佬提出建议想法、提交 Issue 和 PR，一起完善这个项目。

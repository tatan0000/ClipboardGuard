# 剪贴板护卫 (ClipboardGuard) — 架构与原理

> 本文档详细描述 ClipboardGuard 的架构设计、技术实现和项目结构。README.md 中有精简版摘要。

---

## 目录

- [1. 整体架构](#1-整体架构)
- [2. 写入拦截流程](#2-写入拦截流程)
- [3. 读取拦截流程](#3-读取拦截流程)
- [4. 弹窗实现机制](#4-弹窗实现机制)
- [5. 同步等待模型](#5-同步等待模型)
- [6. 状态查询通道](#6-状态查询通道)
- [7. 配置同步机制](#7-配置同步机制)
- [8. 开机配置加载](#8-开机配置加载)
- [9. 规则引擎](#9-规则引擎)
- [10. 防递归保护](#10-防递归保护)
- [11. 日志系统](#11-日志系统)
- [12. 源码文件一览](#12-源码文件一览)
- [13. 资源文件](#13-资源文件)
- [14. 运行时配置文件](#14-运行时配置文件)
- [15. AndroidManifest 组件清单](#15-androidmanifest-组件清单)
- [16. 依赖项与构建配置](#16-依赖项与构建配置)

---

## 1. 整体架构

本模块基于 LSPosed/Xposed 框架，在 `system_server` 进程中 Hook `ClipboardService` 的 `setPrimaryClip` 和 `getPrimaryClip` 方法，实现对剪贴板写入/读取行为的拦截与管控。

架构分为两个进程、四个层次：

```
┌────────────────────────────────────────────────────────────┐
│ App 进程                                                   │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐  │
│  │   UI 层      │  │  Provider 层 │  │  配置文件存储      │  │
│  │ MainActivity │→ │Permission    │→ │ App 私有目录      │  │
│  │ *DetailAct.  │  │ Provider     │  │ write_rules.json │  │
│  │ *RuleAppsAct.│  │ (call/query) │  │ read_rules.json  │  │
│  └──────────────┘  └──────┬───────┘  │ *_blocklist.txt  │  │
│                           │          └──────────────────┘  │
│              广播 ACTION_CONFIG_CHANGED                    │
└───────────────────────────┴────────────────────────────────┘
                            │
                            ↓
┌───────────────────────────┬────────────────────────────────┐
│                    system_server 进程                      │
│                           │                                │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐  │
│  │  Hook 层     │  │  缓存层       │  │  持久化层         │ │
│  │ClipboardHook │→ │Permission    │→ │ ConfigManager    │  │
│  │ *SetPrimary  │  │ Cache        │  │ /data/system/    │  │
│  │ *GetPrimary  │  │ContentRules  │  │ clipboardguard/  │  │
│  │ *OnTransact  │  │ Manager      │  │                  │  │
│  └──────┬───────┘  └──────────────┘  └──────────────────┘  │
│         ↓                                                  │
│  ┌──────────────┐                                          │
│  │  弹窗层       │  全代码构建悬浮窗，无 XML 布局             │
│  │InlineDialog  │  TYPE_APPLICATION_OVERLAY                │
│  │ Manager      │                                          │
│  └──────────────┘                                          │
└────────────────────────────────────────────────────────────┘
```

---

## 2. 写入拦截流程

1. **Hook 捕获**：`SetPrimaryClipHook.beforeHookedMethod()` 拦截 `ClipboardService.setPrimaryClip` 调用
2. **白名单检查**：检查调用方是否为核心系统包（`isCorePackage()`），是则直接放行
3. **拦截名单检查**：查询 `PermissionCache.isWriteIgnored(pkg)`，不在拦截名单中则放行
4. **内容规则匹配**：调用 `ContentRulesManager.matchesWriteContent(text, pkg)` 进行正则匹配，命中规则则拦截
5. **防抖检查**：同一包名 2 秒内重复请求直接复用上次决策
6. **弹窗等待**：`InlineDialogManager.showWriteDialogAsync()` 在主线程创建悬浮窗，Hook 线程通过 `CountDownLatch.await(5s)` 阻塞等待
7. **决策执行**：用户点击允许/拒绝，或超时自动拒绝，决策结果写入防抖缓存

---

## 3. 读取拦截流程

1. **Hook 捕获**：`GetPrimaryClipHook.afterHookedMethod()` 拦截 `ClipboardService.getPrimaryClip` 返回值
2. **白名单与名单检查**：同写入拦截
3. **内容规则匹配**：支持银行卡号 Luhn 算法二次确认，减少快递单号/订单号误报
4. **防抖检查**：同一包名 3 秒内重复请求复用上次决策
5. **弹窗等待**：提供三种决策——允许、拒绝、拒绝并清空剪贴板（`PERMISSION_CLEAR`）
6. **清空执行**：选择"拒绝并清空"时，通过 `ThreadLocal<Boolean>` 标志防止递归触发 Hook

---

## 4. 弹窗实现机制

- **全代码 UI**：无 XML 布局文件，使用 `WindowManager.addView()` 添加 `TYPE_APPLICATION_OVERLAY` 悬浮窗
- **异步模型**：`showDialogAsync()` 通过 `mMainHandler.post()` 在主线程创建弹窗，立即返回不阻塞 Hook 线程
- **倒计时**：`CountDownTimer` 5 秒倒计时，超时自动拒绝
- **深色模式**：通过 `Configuration.UI_MODE_NIGHT_MASK` 检测，动态切换配色方案
- **按钮布局**：写入弹窗水平双按钮（拒绝/允许），读取弹窗垂直三按钮（允许/拒绝/拒绝并清空）

---

## 5. 同步等待模型
```
Binder 线程 (Hook)            主线程 (弹窗)              用户
│                            │                        │
├─ CountDownLatch.await()    │                        │
├─ 阻塞中...                  │                        │
│                            ├─ 创建悬浮窗             │
│                            ├─ 启动 5s 倒计时         │
│                            │                        │
│                            │   ← 点击按钮/超时  ─────┤
│                            ├─ latch.countDown()     │
├─ 被唤醒                     │                       │
├─ 读取决策结果               │                        │
├─ 写入防抖缓存               │                        │
└─ 返回拦截/放行              │                        │
```
多个同包名请求共享同一个 `CountDownLatch`（waiter 计数），避免并发弹窗冲突。

---

## 6. 状态查询通道

App 查询 Hook 状态不通过 ContentProvider，而是直接在 `ClipboardService$ClipboardImpl.onTransact` 上注入自定义 Binder 事务码 `0x43424744`，通过 `ServiceManager.getService("clipboard").transact()` 直连查询，避免注册新的 SELinux 服务。Hook 侧返回包含 `boot_id`、`pid`、`xposed_api` 版本等信息的 JSON。

---

## 7. 配置同步机制

### App → system_server（下行同步）

1. App 通过 `PermissionProvider.requestConfigSync()` 保存配置后发送 `ACTION_CONFIG_CHANGED` 广播
2. 广播携带拦截名单（ArrayList）、规则 JSON、开关状态等 Extra
3. `ConfigManager.applyConfigBroadcast()` 处理广播：更新 `PermissionCache` 和 `ContentRulesManager` 内存缓存 + 写入 `/data/system/clipboardguard/` 持久化

### system_server → App（上行查询）

1. App 通过 `PermissionProvider.call()` 方法发起 Binder IPC
2. 按 method 路由到不同操作（`getFullConfig` / `getBlockedPackages` 等）

### 安全模型

- ContentProvider 使用签名级自定义权限 `com.android.clipboardguard.permission.CONFIG_SYNC`（`protectionLevel=signature`）
- `isTrustedCaller()` 校验调用者 UID，仅允许本应用、system_server 或同 UID

---

## 8. 开机配置加载

`ClipboardHook.loadAllConfigDirect()` 在 Xposed 模块初始化时执行：

1. 首次尝试从 `/data/system/clipboardguard/` 同步读取全部配置
2. 失败后间隔 5 秒、7 秒重试，共 3 次
3. 最终失败则等待 App 启动后推送配置
4. 首次写入/读取拦截时各触发一次惰性兜底重试（`ensureWriteInitialized()` / `ensureReadInitialized()`）

---

## 9. 规则引擎

### 规则格式

```json
{
  "enabled": true,
  "content_rules": [
    {
      "name": "规则名称",
      "pattern": "正则表达式",
      "enabled": true,
      "applicable_packages": ["com.example.app"]
    }
  ]
}
```

### 规则匹配流程

1. 检查规则总开关是否启用（写入默认开、读取默认关）
2. 遍历已编译的规则模式列表
3. 检查当前包名是否在规则的 `applicablePackages` 中（空列表 = 适配所有已拦截应用）
4. 使用 `Pattern.matcher()` 匹配文本内容
5. 长文本分块扫描：每块 5000 字符，前后重叠 100 字符，防止边界漏匹配
6. 银行卡号规则命中后，额外执行 Luhn 算法二次确认

### 危险正则检测

`ContentRule.checkDangerousPattern()` 检测嵌套量词 `(X+)+` 和交替嵌套量词 `(a|b)+`，防止灾难性回溯导致 system_server 卡死。

### 规则合并策略

自定义规则文件的 `enabled` 字段是总开关；默认规则文件的 `enabled` 仅在无自定义规则时作为兜底。

---

## 10. 防递归保护

使用 `ThreadLocal<Boolean>` 标志（`sInAfterHook` / `sIsBlockingOperation` / `sIsReadBlockingOperation` / `sIsClearOperation`）防止以下递归场景：

- 清空剪贴板操作本身会触发 `setPrimaryClip`，导致写入拦截再次触发
- 读取剪贴板内容时可能触发其他剪贴板操作

---

## 11. 日志系统

- **Hook 侧（system_server）**：通过反射调用 `XposedBridge.log()` 输出到 LSPosed 模块日志页
- **App 侧**：回退到 `android.util.Log` 输出到 logcat
- **内容脱敏**：`maskClipboardContent()` 保留前半段非空白字符，后半段替换为星号
- **开关控制**：通过 `PermissionCache.isLsposedLogEnabled` 全局开关控制是否输出

---

## 12. 源码文件一览

项目为纯 Java 实现，共 16 个源文件，位于单一包 `com.android.clipboardguard` 下：

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
├── ReadRuleAppsActivity.java       # 读取规则适用应用选择页
├── AboutModuleActivity.java        # 关于模块页
```

---

## 13. 资源文件

```
app/src/main/res/
├── layout/                           # 布局文件（11 个）
│   ├── activity_main.xml             #   主界面（ViewPager + BottomNavigation）
│   ├── activity_write_rules_detail.xml
│   ├── activity_read_rules_detail.xml
│   ├── activity_rule_apps.xml
│   ├── activity_about_module.xml
│   ├── dialog_edit_rule.xml          #   规则编辑弹窗
│   ├── dialog_view_rule.xml          #   规则查看弹窗
│   ├── item_content_rule.xml         #   规则列表项
│   ├── item_app_permission.xml       #   应用权限列表项
│   ├── item_group_header.xml         #   分组头部
│   └── page_settings.xml             #   设置页面
│
├── drawable/                         # 矢量图标（22 个，均为 XML）
│   ├── ic_nav_home.xml / ic_nav_write.xml / ic_nav_read.xml / ic_nav_settings.xml
│   ├── ic_shield_on.xml / ic_shield_off.xml / ic_rules.xml
│   ├── ic_add.xml / ic_edit.xml / ic_delete.xml / ic_search.xml / ic_check.xml
│   └── ...
│
├── values/
│   ├── strings.xml                   #   字符串资源（中文，约 170 条）
│   ├── colors.xml                    #   颜色定义
│   ├── themes.xml                    #   Material 主题
│   └── arrays.xml                    #   xposed_scope + global_whitelist_packages
│
├── values-night/                     # 暗色模式资源
│   ├── colors.xml
│   └── themes.xml
│
├── mipmap-*/                         # 各密度启动器图标（webp 格式）
└── xml/                              # 备份与数据提取规则
```

资产文件：

```
app/src/main/assets/
└── xposed_init                       # 内容：com.android.clipboardguard.ClipboardHook
                                      # LSPosed 模块入口声明
```

---

## 14. 运行时配置文件

模块运行时在以下路径生成配置文件：

```
/data/system/clipboardguard/
├── write_blocklist.txt               # 写入拦截包名列表（每行一个）
├── read_blocklist.txt                # 读取拦截包名列表
├── write_rules.json                  # 自定义写入规则
├── write_default_rules.json          # 默认写入规则
├── read_rules.json                   # 自定义读取规则
├── read_default_rules.json           # 默认读取规则
├── global_flags.json                 # 全局开关状态
└── module_status.json                # 模块状态（boot_id/pid/api 版本）
```

---

## 15. AndroidManifest 组件清单

| 类型          | 组件                         | exported | 说明               |
|-------------|----------------------------|----------|------------------|
| Application | `ClipboardGuardApp`        | -        | 应用入口             |
| Activity    | `MainActivity`             | true     | 主界面（LAUNCHER）    |
| Activity    | `WriteRulesDetailActivity` | false    | 写入规则详情           |
| Activity    | `ReadRulesDetailActivity`  | false    | 读取规则详情           |
| Activity    | `WriteRuleAppsActivity`    | false    | 写入规则应用选择         |
| Activity    | `ReadRuleAppsActivity`     | false    | 读取规则应用选择         |
| Activity    | `AboutModuleActivity`      | false    | 关于模块             |
| Provider    | `PermissionProvider`       | true     | 配置存储与同步（签名级权限保护） |

---

## 16. 依赖项与构建配置

### 依赖项

| 类型                 | 依赖                                               | 版本     |
|--------------------|--------------------------------------------------|--------|
| Xposed API         | `XposedBridgeAPI-82.jar`（compileOnly）            | 82     |
| AndroidX Activity  | `androidx.activity:activity`                     | 1.9.3  |
| AndroidX AppCompat | `androidx.appcompat:appcompat`                   | 1.7.1  |
| Material           | `com.google.android.material:material`           | 1.10.0 |
| SwipeRefresh       | `androidx.swiperefreshlayout:swiperefreshlayout` | 1.1.0  |

### 构建配置

| 配置项         | 值                            |
|-------------|------------------------------|
| 语言          | Java 17                      |
| compileSdk  | 37 (Android 17)              |
| minSdk      | 30 (Android 11)              |
| targetSdk   | 37 (Android 17)              |
| AGP         | 9.1.1                        |
| 混淆          | 启用（保留 Hook 入口 + Manifest 组件） |
| 资源缩减        | 启用                           |

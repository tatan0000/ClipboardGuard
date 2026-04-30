# ClipboardGuard 项目记忆

## 项目简介
Xposed 模块，Hook system_server 进程的 `ClipboardManager.setPrimaryClip`，实现剪贴板写入权限管理。

## 技术架构
- **Hook点**：`ClipboardService$ClipboardImpl.setPrimaryClip(ClipData)`（在 system_server 进程）
- **弹窗方式**：InlineDialogManager（使用 WindowManager.addView() 直接在 system_server 内渲染浮窗）
- **数据存储**：纯文本文件 `/data/data/com.android.clipboardguard/files/blocklist.txt`
- **跨进程通道（关键）**：
  - App → system_server：广播 Intent（携带 blocklist 数据，Intent key="blocklist"）
  - system_server → App：ContentProvider.call()（作为初始加载兜底，会产生 FLAG_ONEWAY 警告但不影响功能）

## 权限模型
- `PERMISSION_BLOCK = 0`：拦截（勾选 = 黑名单）
- `PERMISSION_IGNORE = 1`：放行（未勾选 = 不在黑名单里）
- **弹窗决策 = 临时**，黑白名单只有 App 界面保存时才修改

## 弹窗按钮逻辑（重要）
- **允许**：这次放行，记录日志，不修改黑白名单
- **拒绝**：这次拦截，记录日志，不修改黑白名单
- **超时自动拒绝**：4 秒倒计时

## 系统核心包白名单
- 数据源：Thanox `global_white_list.xml`，约 110 个包
- 两处各持有一份：arrays.xml 资源 + Hook.java 静态 HashSet
- 匹配逻辑：精确匹配 + 子包前缀匹配

## 开机初始化策略（2026-04-30 最终版）
- **Hook 成功后延迟 10s 初始化**：Hook 加载时用 `postDelayed` 延迟 10s 再调用 `scheduleBootInit(0)`
- **失败重试一次**：首次失败后 10s 重试（`scheduleBootInit(1)`），再失败放弃
- **首次复制时按需初始化**：`ensureInitialized()` 在用户首次复制时触发，逻辑同开机初始化
- **广播正常注册**：不加延时，Hook 初始化完成后直接注册

### 开机时序
```
t=0:    Hook 加载
        └─ postDelayed 10s → scheduleBootInit(0)

t=10:   scheduleBootInit(0)
        ├─ 成功 → 完成 ✅
        └─ 失败 → postDelayed 10s → scheduleBootInit(1)

t=20:   scheduleBootInit(1) - 重试
        ├─ 成功 → 完成 ✅
        └─ 失败 → 放弃

用户复制时：
  └─ ensureInitialized() → 按需初始化 → 弹窗 ✅
```

## 防抖功能
- 防抖时间：1500ms
- 拒绝也防抖，1.5秒内复用上次决策

## 踩坑教训（经验沉淀）

### 1. 跨进程数据共享
- **MMKV 多进程模式**使用 ashmem 匿名共享内存，重启丢数据 → 改用文件 + 广播推送
- **SharedPreferences MODE_WORLD_READABLE** 在 Android 10+ 失效 → 改用文件 + ContentProvider
- **system_server 无法直接读 App 私有目录文件**：`/data/data/com.android.clipboardguard/files/blocklist.txt` 对 system_server 不可见，直接读导致 blockSet.size=0，所有 App 都放行 → **正确方案**：App 保存权限时把 blocklist 放入广播 Intent 推送，system_server 接收并更新内存缓存
- **ContentProvider 在 user unlock 前不可用**：开机后 5 秒第一次初始化时，App 进程未启动，ContentProvider 返回 "Failed to find provider info (user not unlocked)"，此时获取到 0 条数据但 sLoaded 仍为 true → **修复**：ContentProvider 查询失败时设置 `loadSuccess = false`，只有真正成功才标记 `sLoaded = true`，否则 isIgnored() 返回 false（保守拦截）直到后续初始化成功

### 2. ContentProvider 注意事项
- **ContentProvider.call()** 需用 `clearCallingIdentity()` 绕过 uid 校验
- **system_server 禁止同步出站 Binder 调用**：会收到 "Outgoing transactions must be FLAG_ONEWAY" 警告，但不影响功能 → 改用广播推送规避

### 3. 日志写入
- **writeLog 用 system_server context + getSharedPreferences 失败**："No data directory found for package android" → system_server context 对应 android 包，无数据目录。修复：去掉 getSharedPreferences，直接通过 PermissionProvider.writeLog() 走 ContentProvider
- **日志方案后续需更换**：当前 writeLog 静默忽略异常，不打印任何信息

### 4. Hook 机制
- **弹窗"允许"按钮不应永久放行**：只记录日志，不调用 savePermission()
- **嵌套 setPrimaryClip 会崩溃**：HostClipboardMonitor 回调再次调用 setPrimaryClipInternal 会触发 `RemoteCallbackList.beginBroadcast()` 嵌套 → 用 ThreadLocal `sInClipboardOp` 标记防递归

### 5. 广播 Intent 数据传递
- key = `"blocklist"`，类型 `ArrayList<String>`
- PermissionCache.updateFromBlockList() 解析

## 待开发功能
1. 日志功能完善（XposedBridge.log 输出）
2. 正则规则配置 UI
3. 读取剪贴板权限控制
4. Magisk 模块版（Zygisk + C++ Hook）

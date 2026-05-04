# ClipboardGuard 项目记忆

## 项目简介
Xposed 模块，Hook system_server 进程的 `ClipboardManager.setPrimaryClip`，实现剪贴板写入权限管理。

## 技术架构
- **Hook点**：`setPrimaryClip(ClipData)`（在 system_server 进程），兼容 `ClipboardService` 和 `ClipboardManagerService`（Android 14+ 重命名）
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

## 开机初始化策略（v4 - 2026-05-04）
- **开机立即注册必定失败**（AMS 未就绪），不再尝试
- **5s 后第一次延迟注册** + 注册成功后尝试 ContentProvider 加载
- **8s 后第二次延迟注册**（兜底）+ 重新加载配置
- **首次复制时按需初始化**：`ensureInitialized()` 兜底
- **BootReceiver 等 15s 再发广播**（预留充足缓冲应对开机拥堵）
- **BootReceiver 发两次广播**：第 15s 和第 18s 各发一次（兜底）
- **通知仅在开机自启动时发送**（BootReceiver 内），App 打开和保存配置只发广播不弹通知

### 推送职责分工
| 触发时机 | 调用方法 | 携带内容 |
|---------|---------|---------|
| 开机自启动 | BootReceiver → `sendFullConfigBroadcast` | blocklist + 规则 JSON |
| 打开 App | MainActivity.onCreate → `sendFullConfigBroadcast` | blocklist + 规则 JSON |
| 保存写入黑名单 | MainActivity → `sendBlocklistBroadcast` | 仅 write_blocklist |
| 保存读取黑名单 | MainActivity → `sendBlocklistBroadcast` | 仅 read_blocklist |
| 保存写入规则 | WriteRulesDetailActivity → `sendMergedWriteRulesBroadcast` | 仅 write_rules_json |
| 保存读取规则 | ReadRulesDetailActivity → `sendMergedReadRulesBroadcast` | 仅 read_rules_json |

### 开机时序（v5）
```
t=0:    Hook 加载（不注册，不加载，只 Hook）
        └─ postDelayed 5s, postDelayed 8s

t=5:    tryRegisterReceiver("延迟5s")
        └─ 成功 → loadAllConfig()（可能为0，等广播兜底）

t=8:    tryRegisterReceiver("延迟8s")（兜底）
        └─ 成功 → loadAllConfig()

BootReceiver 收到开机广播：
  └─ 立即发送广播 → Hook 侧收到配置 ✅
  └─ 3s 后第二次发广播（兜底）✅
  └─ 发通知「剪贴板保护服务已启动」（仅开机时）✅

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
- **RECEIVER_NOT_EXPORTED 跨进程广播完全失效**：在 system_server 中用 `Context.RECEIVER_NOT_EXPORTED` 注册广播接收器，App 进程 sendBroadcast 发的广播**完全收不到**（Android 14+ 静默丢弃）→ **必须用 `RECEIVER_EXPORTED`**（2026-05-05 最关键修复）

### 2. ContentProvider 注意事项
- **ContentProvider.call()** 需用 `clearCallingIdentity()` 绕过 uid 校验
- **system_server 禁止同步出站 Binder 调用**：会收到 "Outgoing transactions must be FLAG_ONEWAY" 警告，但不影响功能 → 改用广播推送规避

### 3. 日志方案
- **日志只输出到 XLog（XposedBridge.log）**，在 LSPosed Manager 日志页查看，不保存到数据库
- 2026-05-04 移除了 SQLite `clipboard_log` 表和所有 `writeLog/getLogs/clearLogs` 方法
- 数据库升级到 v3，自动 DROP 掉旧的 `clipboard_log` 表
- `WriteHook.writeLog()` 现在直接调用 `XLog.i()` 输出到 LSPosed

### 4. Hook 机制
- **弹窗"允许"按钮不应永久放行**：只记录日志，不调用 savePermission()
- **嵌套 setPrimaryClip 会崩溃**：HostClipboardMonitor 回调再次调用 setPrimaryClipInternal 会触发 `RemoteCallbackList.beginBroadcast()` 嵌套 → 用 ThreadLocal `sInClipboardOp` 标记防递归
- **`handleLoadPackage` 中 `hookMethod()` 之后的任何代码都必须 try-catch**：即使 Hook 已注册，如果后续初始化代码抛异常，LSPosed 会标记模块为异常并禁用
- **`ContentRulesManager.loadRules/loadReadRules` 必须有 try-catch**：文件解析/JSON 操作可能抛异常，缺少保护会导致整个 handleLoadPackage 失败

### 5. 广播 Intent 数据传递
- key = `"blocklist"`，类型 `ArrayList<String>`
- PermissionCache.updateFromBlockList() 解析

### 6. 开机广播时序竞争（2026-05-04 新增）
- **症状**：开机后 Hook 不生效，需手动打开 App 才生效
- **根因**：广播接收器 postDelayed(8s) 注册，但 BootReceiver 在 t=6s 就发广播 → 广播丢失
- **修复**：Hook 加载后立即注册广播接收器（不延迟），BootReceiver 延迟从 3s 改为 12s 再发广播，并发两次兜底

## UI 结构（四栏底部导航）
- **首页**：模块激活状态、版本信息、使用说明
- **写入**：应用权限管理（勾选 = 拦截写入），标题 "写入拦截"
- **读取**：应用权限管理（勾选 = 拦截读取），标题 "读取拦截"，独立黑名单
- **设置**：主题切换、关于

### 读取页（2026-04-30 新增）
- 读取页与写入页结构相同，独立维护 `isBlockedRead` 状态
- UI 独立：独立搜索框、独立全选/反选按钮、独立 ExpandableListView
- 数据独立：`mReadUserApps`、`mReadSystemApps`、`mReadCoreApps` 独立副本

### 写入页变量命名（*Write* 后缀）
| 变量 | 说明 |
|------|------|
| `mWriteAdapter` | 写入页适配器 |
| `mWriteUserApps` | 用户应用列表 |
| `mWriteSystemApps` | 系统应用列表 |
| `mWriteCoreApps` | 核心应用列表 |
| `mWriteFilteredUser` | 过滤后用户列表 |
| `mWriteFilteredSystem` | 过滤后系统列表 |
| `mWriteFilteredCore` | 过滤后核心列表 |
| `mWriteCurrentQuery` | 当前搜索关键词 |
| `mWritePendingChanges` | 未保存变更 |

### 写入页方法命名（*Write* 后缀）
| 方法 | 说明 |
|------|------|
| `refreshWritePermissions()` | 刷新权限 |
| `applyWritePermToItem()` | 应用权限到单项 |
| `applyWriteFilter()` | 应用过滤 |
| `matchesWrite()` | 匹配搜索 |
| `getWriteItem()` | 获取列表项 |
| `setAllWriteApps()` | 全选/反选 |
| `toggleWriteGroupSelection()` | 分组全选切换 |
| `sortWriteApps()` | 排序（基于 isBlocked）|

### 写入页布局ID（*_write 后缀）
- `et_search_write`
- `btn_select_all_write`
- `btn_deselect_all_write`
- `tv_tip_write`
- `expandable_list_write`

### 读取页 Bug 修复（2026-05-01）
1. **ClassCastException 崩溃修复**：`applyReadBlockFilter()` 移除 `runOnUiThread()` + `getAdapter()` 错误组合，改为直接持有 `mReadBlockAdapter` 引用调用 `notifyDataSetChanged()`
2. **排序修复**：新增 `sortAppsRead()` 方法基于 `isBlockedRead` 排序，与写入页 `sortApps()` 对称
3. **系统性重命名**：所有读取页变量/方法/布局ID 统一使用 `*ReadBlock*` 命名模式

### 读取页变量命名（*Read* 后缀）
| 变量 | 说明 |
|------|------|
| `mReadAdapter` | 读取页适配器 |
| `mReadUserApps` | 用户应用列表 |
| `mReadSystemApps` | 系统应用列表 |
| `mReadCoreApps` | 核心应用列表 |
| `mReadFilteredUser` | 过滤后用户列表 |
| `mReadFilteredSystem` | 过滤后系统列表 |
| `mReadFilteredCore` | 过滤后核心列表 |
| `mReadCurrentQuery` | 当前搜索关键词 |
| `mReadPendingChanges` | 未保存变更 |

### 读取页方法命名（*Read* 后缀）
| 方法 | 说明 |
|------|------|
| `refreshReadPermissions()` | 刷新权限 |
| `applyReadPermToItem()` | 应用权限到单项 |
| `applyReadFilter()` | 应用过滤 |
| `matchesRead()` | 匹配搜索 |
| `getReadItem()` | 获取列表项 |
| `setAllReadApps()` | 全选/反选 |
| `toggleReadGroupSelection()` | 分组全选切换 |
| `saveReadChanges()` | 保存变更 |
| `sortReadApps()` | 排序（基于 isBlockedRead）|

### 读取页布局ID（*_read 后缀）
- `et_search_read`
- `btn_select_all_read`
- `btn_deselect_all_read`
- `tv_tip_read`
- `expandable_list_read`

### 规则管理命名规范（2026-05-01 补充）

**适配器类**：
- `WriteRulesAdapter`：写入规则列表适配器
- `ReadRulesAdapter`：读取规则列表适配器
- 不再使用带 `isReadRules` 参数的单一 `RulesAdapter`

**批量选择方法**：
| 写入方法 | 读取方法 | 说明 |
|---------|---------|------|
| `enterWriteSelectionMode()` | `enterReadSelectionMode()` | 进入批量选择 |
| `exitWriteSelectionMode()` | `exitReadSelectionMode()` | 退出批量选择 |
| `deleteWriteSelectedRules()` | `deleteReadSelectedRules()` | 删除选中的规则 |
| `deleteWriteRule()` | `deleteReadRule()` | 删除单条规则 |
| `updateWriteSelectedCount()` | `updateReadSelectedCount()` | 更新选中计数 |

**数据模型**：
- `ContentRule`：规则数据模型（写入/读取共用，结构相同）
- `mWriteRules` / `mReadRules`：规则列表
- `mWriteRulesAdapter` / `mReadRulesAdapter`：适配器实例
- `mWriteSelectedRules` / `mReadSelectedRules`：选中的规则集合
- `mWriteRulesSelectionMode` / `mReadRulesSelectionMode`：选择模式标志

## 待开发功能
1. ~~日志功能完善~~ ✅ XLog + XposedBridge.log，LSPosed Manager 查看
2. 正则规则配置 UI
3. 读取剪贴板权限控制 - ✅ UI 和存储均已完成
4. Magisk 模块版（Zygisk + C++ Hook）

## 2026-05-01 代码审查修复记录

### 清理调试日志
- 删除了所有 `Log.d` 语句（WriteHook.java、ReadHook.java、MainActivity.java、PermissionStorage.java、ContentRulesManager.java）
- 最终验证：全项目 0 处 Log.d

### Bug 修复
1. **LogAdapter NPE**：修复 `log.packageName` 可能为 null 时 `NameNotFoundException` 无法捕获的问题，增加 null 检查
2. **AlertDialog 内存泄漏**：WriteRulesDetailActivity 和 ReadRulesDetailActivity 的 `mCurrentRuleDialog` 在 Activity destroy 时未 dismiss，增加 `onDestroy()` 处理

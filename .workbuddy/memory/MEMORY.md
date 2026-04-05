# ClipboardGuard - 项目记忆

## 项目简介
Xposed 模块，Hook system_server 进程的 `ClipboardManager.setPrimaryClip`，实现剪贴板写入权限管理。

## 技术架构（2026-04-06 最新）
- **Hook点**：`ClipboardService$ClipboardImpl.setPrimaryClip(ClipData)`（在 system_server 进程）
- **跨进程通信**：system_server → 广播 → DialogLaunchReceiver → PermissionDialogActivity，结果通过 ContentProvider (pending 表) 轮询回传
- **数据存储**：SQLite via PermissionProvider（permission 表 + pending 表）
- **权限读取**（关键）：Hook 在 system_server Binder 线程里，必须先 `Binder.clearCallingIdentity()` 再调 `ContentResolver.call()`
  - 原因：Binder 线程的 `getCallingUid()` 返回的是远端调用者 uid（如 Chrome=10131），直接发 ContentProvider 调用会 uid/包名不匹配报错
  - `clearCallingIdentity()` 后 uid 重置为 system_server 自身(1000)，与 callingPackage "android" 匹配，校验通过
  - fallback：直接 `openDatabase()` 读 SQLite 文件



## 用户偏好
- **只 Hook system_server**，不能 Hook App 进程（某些 App 检测到 Xposed 会闪退）
- 作用域只勾选系统框架（android），不勾选本模块

## 权限模型（2026-04-05 重构）
- `PERMISSION_BLOCK = 0`：拦截，每次写剪贴板弹窗询问（默认）
- `PERMISSION_IGNORE = 1`：放行，直接忽略不拦截
- 旧常量 `PERMISSION_ASK/ALLOW/DENY` 保留为别名
- **不再有永久允许/拒绝**，由主界面统一管理放行列表

## 超时机制
- Dialog 倒计时：5 秒
- Hook latch 等待：7 秒（留 2s 余量避免竞态）
- 超时/弹窗失败 → 默认 BLOCK（安全优先）

## 主界面设计（2026-04-05 第二次重构）
- **双页 + 底部导航栏**（BottomNavigationView）
  - 首页：模块激活状态卡片 + SDK版本/模块版本/Hook目标信息 + 使用说明
  - 应用页：搜索框 + ExpandableListView（用户应用/系统应用分组）+ FAB保存按钮
- **CheckBox 勾选 = 拦截(BLOCK)**，未勾选 = 放行(IGNORE)
- **FAB 右下角**：点击才批量保存到 ContentProvider，不即时写入
- 模块激活检测：`isModuleActive()` 被 Hook 替换返回 true（XposedSmsCode 方案）
- `mPendingChanges` Map 缓存未保存变更，切页时不丢失

## 用户偏好
- 不希望有永久允许/拒绝（这类功能交给 thanox/appops 等专业权限管理工具）
- 倒计时5秒改为3秒（避免弹窗ANR）
- 优化拦截日志：将"降级清空失败"改为warn级别，更清晰地说明拦截已生效
- 主界面参考"爱玩机工具箱"应用管理风格
- 作用域只勾选系统框架（android），不勾选本模块
- 主题选择需要实时切换

## 2026-04-05 下午更新
- 修复未勾选应用仍弹窗问题：Hook 中使用 moduleContext 查询 PermissionStorage.getPermission() 替代 queryPermission()
- 作用域推荐使用 arrays.xml 配置，推荐 android（系统框架）
- 主界面显示"系统框架"而非"系统框架 + 本模块"
- 主题实时切换：使用 recreate() 重建 Activity，applyTheme() 中动态设置状态栏颜色
- 应用界面美化：调整 item 高度/图标/字号，添加圆角背景 bg_item_app.xml
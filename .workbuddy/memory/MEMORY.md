# ClipboardGuard - 项目记忆

## 项目简介
Xposed 模块，Hook system_server 进程的 `ClipboardManager.setPrimaryClip`，实现剪贴板写入权限管理。

## 技术架构（2026-04-18 最新）
- **Hook点**：`ClipboardService$ClipboardImpl.setPrimaryClip(ClipData)`（在 system_server 进程）
- **跨进程通信**：system_server → 广播 → DialogLaunchReceiver → PermissionDialogActivity，结果通过 ContentProvider (pending 表) 轮询回传
- **数据存储**：SQLite via PermissionProvider（permission 表 + pending 表）
- **权限读取**（关键）：Hook 在 system_server Binder 线程里，必须先 `Binder.clearCallingIdentity()` 再调 `ContentResolver.call()`
  - 原因：Binder 线程的 `getCallingUid()` 返回的是远端调用者 uid（如 Chrome=10131），直接发 ContentProvider 调用会 uid/包名不匹配报错
  - `clearCallingIdentity()` 后 uid 重置为 system_server 自身(1000)，与 callingPackage "android" 匹配，校验通过
  - fallback：直接 `openDatabase()` 读 SQLite 文件

## UI 结构（2026-04-18 更新）
- **三组 ExpandableListView**
  - GROUP_USER (0)：用户应用，可勾选
  - GROUP_SYSTEM (1)：系统应用，可勾选
  - GROUP_CORE (2)：系统核心包，置灰不可改
- CheckBox 勾选 = 拦截(BLOCK)，未勾选 = 放行(IGNORE)
- 系统核心包（来自 Thanox 白名单）始终放行，UI 置灰且禁止点击
- `AppItem` 增加 `isCore` 字段区分
- `isChildSelectable(GROUP_CORE)` 返回 false，ClickListener 也跳过核心包

## 系统核心包白名单（2026-04-18）
- 数据源：Thanox `global_white_list.xml`，约 110 个包
- 两处各持有一份：arrays.xml 资源 + Hook.java 静态 HashSet
- 匹配逻辑：精确匹配 + 子包前缀匹配

## UI 美化（2026-04-06, 2026-04-18更新）
- 使用 Material Design 3 组件重写了主布局和设置页
- 使用 MaterialToolbar 替代旧 Toolbar
- 使用 MaterialCardView 替代旧卡片背景
- 使用 FloatingActionButton 替代 ExtendedFloatingActionButton（更简洁的圆形图标）
- 使用 NestedScrollView 替代 ScrollView
- 添加了箭头图标、搜索图标、勾选图标
- 底部导航使用 Material3 风格
- **首页增加 paddingBottom="80dp"** 防止被底部导航遮挡
- **FAB 改为圆形图标，位置靠上**（marginBottom=96dp），不再遮挡底部导航

### 底部导航栏（2026-04-18）
- 参考 LSPosed 风格：简洁纯图标导航，无文字标签
- 四图标：首页(房子)、应用(横线)、日志(文字行)、设置(齿轮)
- 线性描边风格图标，选中蓝色高亮，未选中灰色
- 高度从 56dp 精简到 48dp，移除顶部分割线
- 颜色选择器使用 `?attr/colorPrimary` / `?attr/colorOnSurfaceVariant`

## 权限模型（2026-04-05 重构）
- `PERMISSION_BLOCK = 0`：拦截，每次写剪贴板弹窗询问（默认）
- `PERMISSION_IGNORE = 1`：放行，直接忽略不拦截
- 旧常量 `PERMISSION_ASK/ALLOW/DENY` 保留为别名
- **不再有永久允许/拒绝**，由主界面统一管理放行列表

## 超时机制
- Dialog 倒计时：3 秒
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

## 用户偏好（更新）
- FAB 保存按钮改为简单圆形图标（ic_check.xml），位置靠上不挡底栏
- 首页底部增加 padding 避免被底部导航遮挡
- **只 Hook system_server**，不能 Hook App 进程（某些 App 检测到 Xposed 会闪退）
- 作用域只勾选系统框架（android），不勾选本模块
- 不希望有永久允许/拒绝（这类功能交给 thanox/appops 等专业权限管理工具）
- 倒计时5秒改为3秒（避免弹窗ANR）
- 优化拦截日志：将"降级清空失败"改为warn级别，更清晰地说明拦截已生效
- 主界面参考"爱玩机工具箱"应用管理风格
- 主题选择需要实时切换

## 防抖功能（2026-04-06）
- 防抖时间：1.5秒
- 逻辑：用户选择完成后1.5秒内对同一应用不弹窗，保持上次选择
- 变量：`sLastDecisionTime` Map 记录每个应用的用户决策完成时间

## 深色模式修复（2026-04-06）
- 修改 `values-night/themes.xml`，使用与浅色模式一致的 Material3 主题
- drawable 文件使用 `?android:attr/windowBackground` 动态颜色
- 布局文件中的硬编码白色 `#FFFFFF` 改为动态颜色
- 参考 ReVanced Manager 的主题实现方案
- **主题切换方案**：用户选择主题 → `switchTheme()` 保存设置 → `recreate()` 重建 Activity → `onCreate()` 中通过 `applyThemeNoView()` 调用 `AppCompatDelegate.setDefaultNightMode()` 应用主题
- **状态栏颜色**：使用 `colorPrimary` 资源（自动适配深浅色），深色模式/跟随系统深色时用黑色

## 待开发功能（2026-04-20 更新）
| # | 功能 | 说明 |
|---|------|------|
| 1 | 日志功能完善 | 日志页加开关 + Hook写库 |
| 2 | 正则匹配剪贴板内容弹窗优化 | 匹配到数字/邮箱/身份证等敏感信息时才弹窗，减少弹窗次数 |
| 3 | 读剪贴板权限控制 | 控制哪些应用可以读取剪贴板，支持正则匹配 |
| 4 | system_server内弹窗 | WindowManager.addView() 重构弹窗逻辑 |
| 5 | Magisk模块版 | Zygisk注入 + C++ Hook + nanohttpd |

## 已完成功能
| # | 功能 | 完成日期 |
|---|------|----------|
| 1 | 首次使用引导弹窗 | 2026-04-20 |
| 2 | 权限检查页面 | 2026-04-20 |
| 3 | 首次使用引导弹窗添加提示文字 | 2026-04-20 |

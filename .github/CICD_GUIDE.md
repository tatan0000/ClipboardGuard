# ClipboardGuard CI/CD 配置指南

> 本项目使用 **GitHub Actions** 实现自动构建、测试和发布。

---

## 📁 工作流文件总览

| 文件 | 触发条件 | 功能 |
|------|----------|------|
| `ci.yml` | push 到 main/dev，或 PR | 构建 Debug APK + 单元测试 + Lint |
| `release.yml` | 推送 `v*` 标签或手动触发 | 签名 Release APK + 自动发布 GitHub Release |
| `pr-check.yml` | PR 打开/更新 | APK 大小检查 + 安全扫描 + 编译验证 |

---

## 🔑 第一步：配置签名密钥（Secrets）

Release 工作流需要以下 4 个 **Repository Secrets**：

### 1. 生成 keystore（本地操作）

```bash
keytool -genkeypair \
  -v \
  -keystore clipboardguard.jks \
  -keyalg RSA \
  -keysize 2048 \
  -validity 36500 \
  -alias clipboardguard \
  -storepass YOUR_STORE_PASSWORD \
  -keypass YOUR_KEY_PASSWORD \
  -dname "CN=ClipboardGuard, OU=Dev, O=ClipboardGuard, L=SH, S=SH, C=CN"
```

### 2. 转为 Base64（用于 Secret 存储）

**macOS / Linux：**
```bash
base64 -i clipboardguard.jks | pbcopy   # 直接复制到剪贴板
```

**Windows PowerShell：**
```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("clipboardguard.jks")) | Set-Clipboard
```

### 3. 在 GitHub 仓库中添加 Secrets

进入仓库 → **Settings → Secrets and variables → Actions → New repository secret**，添加：

| Secret 名称 | 值 |
|-------------|-----|
| `KEYSTORE_BASE64` | keystore 文件的 Base64 内容 |
| `KEY_STORE_PASSWORD` | keystore 密码（`-storepass` 的值） |
| `KEY_ALIAS` | 密钥别名（如 `clipboardguard`） |
| `KEY_PASSWORD` | 密钥密码（`-keypass` 的值） |

> ⚠️ **不要把 keystore 文件或密码提交到 git 仓库！**
> 把 `*.jks`、`*.keystore` 添加到 `.gitignore`。

---

## 🚀 第二步：发布新版本

### 方式 A：推送版本标签（推荐）

```bash
# 修改 app/build.gradle 中的 versionCode 和 versionName
git add app/build.gradle
git commit -m "chore: bump version to 1.1.0"
git tag v1.1.0
git push origin main --tags
```

推送标签后，`release.yml` 自动触发，完成：
1. 构建签名 APK
2. 生成从上一个标签以来的 Changelog
3. 创建 GitHub Release 并上传 APK 和 SHA256 校验文件

### 方式 B：手动触发

在 GitHub 仓库 → **Actions → Release - Sign & Publish → Run workflow**，填写参数后点击运行。

---

## 🔍 第三步：查看构建结果

### CI 构建产物
- Debug APK：在对应 Workflow Run 的 **Artifacts** 区域下载
- 单元测试报告：`unit-test-results` artifact（HTML 格式）
- Lint 报告：`lint-results` artifact

### Release 产物
- 自动发布到 **Releases** 页面
- 包含签名 APK + SHA256 校验文件

---

## 🛠️ 常见问题

### Q: XposedBridgeAPI-82.jar 找不到？

工作流会自动尝试从 XposedBridge GitHub Releases 下载。
若下载失败（网络问题），建议**直接将 jar 提交到 `app/libs/`**：

```bash
git add app/libs/XposedBridgeAPI-82.jar
git commit -m "chore: add XposedBridgeAPI-82.jar"
```

### Q: 签名 Secret 未配置时如何处理？

Release 工作流会发出 warning 并构建**未签名**的 Release APK。
未签名 APK 可以手动用 `apksigner` 签名，或在本地用 Android Studio 签名。

### Q: 如何跳过 CI 运行？

在 commit message 中加入 `[skip ci]` 或 `[ci skip]`：

```bash
git commit -m "docs: update README [skip ci]"
```

### Q: 单元测试失败怎么办？

1. 下载 `unit-test-results` artifact，查看 HTML 测试报告
2. 本地复现：`./gradlew testDebugUnitTest`
3. 修复后重新 push

---

## 📦 构建变体说明

| 变体 | 签名 | 混淆 | 用途 |
|------|------|------|------|
| `debug` | 调试签名 | 否 | 开发调试 |
| `release` | 正式签名 | 否（当前关闭） | 分发 |

> 当前 `release` 构建关闭了 ProGuard（`minifyEnabled false`）。
> 对于 Xposed 模块，这是正常的——开启混淆可能导致 Hook 类名映射失败。

---

## 🔒 安全注意事项

1. **永远不要在代码中硬编码密码、API Key**
2. **Keystore 文件不提交 git**（已在工作流中 `rm -f keystore.jks`）
3. PR Check 工作流会自动扫描常见的硬编码敏感字符串
4. Release APK 附带 SHA256 供用户验证完整性

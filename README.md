# mpvDanmaku

mpvDanmaku 是一款基于 [mpvEx](https://github.com/marlboro-advance/mpvEx) 与 libmpv 的独立 Android 媒体播放器。相较于 mpvEx，本项目新增了对 [弹弹play开放弹幕网络](https://www.dandanplay.com/) 的可选支持：可为本地视频自动匹配剧集、手动搜索并绑定剧集、同步渲染弹幕，以及在本地缓存弹幕以支持离线播放。

项目保留 mpvEx 成熟的播放能力与可定制性，并将弹幕功能设计为独立、可关闭的扩展：未启用时不会进行弹幕匹配或访问弹弹play服务。

## 主要功能

### 相较于 mpvEx 的新增功能

- 接入弹弹play开放弹幕网络，支持本地视频的剧集匹配。
- 支持自动匹配、重新匹配和手动搜索/选择剧集，避免模糊匹配自动绑定错误剧集。
- 使用独立覆盖层同步渲染滚动、顶部和底部弹幕，不占用 mpv 的主字幕或副字幕轨道。
- 提供弹幕开关、显示区域、透明度、字号、速度、密度、同屏数量和时间偏移等播放内控制项。
- 支持关键词屏蔽、正则匹配、滚动/顶部/底部弹幕分类开关，以及简繁中文转换。
- 将剧集绑定与弹幕缓存保存在本机；网络不可用时可使用本地缓存继续观看。

### 继承自 mpvEx 的播放能力

- 基于 libmpv，支持硬件/软件解码及丰富的渲染设置。
- 支持本地媒体、网络串流和“打开 URL”。
- 支持主/副字幕、音轨切换、播放列表、播放历史及在线字幕搜索。
- 支持逐帧播放、睡眠定时器、播放速度预设、画中画和手势/键盘控制。
- 支持 mpv 配置与脚本，满足进阶自定义需求。
- 内置基础文件管理操作，包括复制、移动、重命名和删除。

## 弹幕使用与隐私

弹幕功能默认关闭。首次启用时，应用会说明并征得同意后，才向弹弹play开放弹幕网络发送用于匹配的视频文件名（不含路径）、文件大小、时长，以及本地文件前 16 MiB 的 MD5 值。

- 不会上传视频内容、完整文件路径、网络账号或完整网络 URL。
- 未启用弹幕功能时，不会访问弹弹play服务，也不会读取文件内容用于计算匹配指纹。
- 对无法安全读取指纹的网络媒体，应用会使用文件名匹配或提供手动搜索，不会额外下载媒体内容。
- 弹幕来自弹弹play开放弹幕网络；缓存、绑定和筛选设置均存储在本机，可随时在设置中清除。

请遵守弹弹play的服务规则及所在地区适用的版权法律。mpvDanmaku 不提供批量下载或导出弹幕的功能。

## 构建

### 环境要求

- JDK 17
- Android SDK 36
- Git

### 构建与测试

```powershell
.\gradlew.bat testStandardDebugUnitTest
.\gradlew.bat assembleStandardDebug
```

弹幕服务凭证未配置时，弹幕网络集成会自动禁用；播放器的其余功能不受影响。可通过 Gradle 属性、环境变量或 `local.properties` 配置开发凭证：

```text
DANDANPLAY_APP_ID=...
DANDANPLAY_APP_SECRET=...
```

请不要将凭证提交到仓库、写入日志或导出到设置文件。

## 版本与发布

- 应用 ID：`app.mpvdanmaku`
- 初始版本：`0.1.0`（`versionCode = 1`；按 ABI 输出时会生成派生版本号）
- 数据库：`mpvDanmaku.db`，当前 schema 版本为 `1`
- 更新偏好文件：`mpvDanmaku_update_prefs`

标准构建可检查本项目的 GitHub Release 更新。GitHub Actions 会自动使用 `GITHUB_REPOSITORY`；本地或可复现构建可设置：

```text
MPVDANMAKU_UPDATE_REPOSITORY=owner/repository
```

发布工作流需要配置本项目独立的签名密钥：

| Secret | 说明 |
| --- | --- |
| `MPVDANMAKU_SIGNING_KEYSTORE` | Base64 编码的 keystore |
| `MPVDANMAKU_SIGNING_KEY_ALIAS` | 密钥别名 |
| `MPVDANMAKU_SIGNING_STORE_PASSWORD` | keystore 密码 |
| `MPVDANMAKU_KEY_PASSWORD` | 密钥密码 |

请勿复用 mpvEx 的发布签名凭证。

## 与上游同步

mpvDanmaku 是长期维护的 fork，拥有独立的 Android 应用身份、数据库历史、签名密钥和发布渠道。为降低同步 mpvEx 播放器框架更新时的包级冲突，Kotlin 命名空间暂保留为 `app.marlboroadvance.mpvex`；这不影响实际应用 ID。

同步 mpvEx 更新时，请将以下内容视为本项目边界并保持独立：

- `applicationId`、产品名称、签名和发布配置
- Room 数据库版本与 schema 历史
- 数据库和偏好文件名称
- 弹弹play集成、隐私说明及其测试

上游数据库迁移需要适配 mpvDanmaku 自己的 schema 版本序列，不能直接照搬原始版本号。

## 网站

网站从以下环境变量读取仓库地址和正式站点 URL：

```text
NEXT_PUBLIC_GITHUB_REPOSITORY=owner/repository
NEXT_PUBLIC_SITE_URL=https://example.com
```

## 致谢与许可

- [mpvEx](https://github.com/marlboro-advance/mpvEx)：上游应用框架
- [mpv-android](https://github.com/mpv-android/mpv-android)
- [mpvKt](https://github.com/abdallahmehiz/mpvKt)
- [弹弹play开放弹幕网络](https://www.dandanplay.com/)

本项目采用 Apache-2.0 许可证，详见 [LICENSE](LICENSE)。

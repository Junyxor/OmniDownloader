# 米哈游百科 / 米游社原生解析（Android）

本分支从 `main` 的 `5ff8ff4` 建立，仅集成米哈游视频解析与设置页语言下拉菜单对齐修复，**不包括液态玻璃**。

## 解析路径

- **百科词条**：`baike.mihoyo.com/bh3/wiki/content/936/detail` → `api-static.mihoyo.com/common/blackboard/{app_sn}/v1/content/info?content_id={id}&app_sn={app_sn}`；读取 `data.content.contents[].text` 中的 `video[src]` / `source[src]`，对媒体链接执行 HEAD/Range 存活检查。
- **米游社文章**：`www.miyoushe.com/ys/article/79208044` → `bbs-api.miyoushe.com/post/wapi/getPostFull?post_id={id}`（静态镜像兜底）；读取 `data.post.vod_list[].resolutions[]`，提供多清晰度选项。
- **签名时效**：米游社 `auth_key` 不持久化到 `DownloadTask`。队列中只记录原帖 URL、选中视频索引（`#omni_vod=N`）、清晰度标签，实际下载才通过 API 获取签名直链。因时效失效而下载失败时再刷新一次。
- **下载执行**：复用 `DownloadEngine.downloadDirectMediaStream`，包括断点续传、媒体文件有效性检查、音频提取、GIF 转换、取消和后处理，无需 WebView、Python 或额外解析服务。

## 范围与限制

- App 标识映射：`bh3_wiki`、`sr_wiki`、`zzz_wiki`、`ys_obc`、`wd_obc`。`zzz_wiki` 的部分词条接口返回无内容，仍需线上样本复核。
- `bh2`、`dby` 的 Wiki API 尚未验证，故不声称支持。
- 仅对白名单来源域名及可信 HTTPS 媒体域名进行原生解析；无视频正文会显示明确错误。外部 B 站播放器未直接转换为可下载资源。
- 公开只读 API 无需登录；下载受视频著作权、服务条款与个人网络连通性约束。

## 验证

运行：

```shell
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Android APK 构建成功后在 Actions 的 artifact 下载 `app-debug.apk`。可使用以下链接手动试验：

```text
https://baike.mihoyo.com/bh3/wiki/content/936/detail?visit_device=mobile&bbs_presentation_style=no_header
https://www.miyoushe.com/ys/article/79208044
```

UI 手动验收：设置 → 外观与语言 → 展开语言下拉；四个选项左边缘应一致、勾号固定右侧、弹出菜单不越出屏幕。建议验证屏幕窄宽、系统字体放大、明暗主题。

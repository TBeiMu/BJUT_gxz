# 个人页 GitHub 入口验证

日期：2026 年 11 月 19 日。版本保持 v0.5.6／versionCode 37，本轮为源码维护，未替换公开 Release 附件。

## 实现行为

- “我的”首页在底部导航栏上方固定显示单色 GitHub 图标及小写 `github`，整组在内容区域水平居中。使用次级墨色和正文中号文字，无常驻底色、边框或说明；图标 20dp、间距 8dp，点击区域至少 48dp 高，底部留白 12dp。
- 个人信息与原有七个入口独立滚动，不被底部入口遮挡；GitHub 入口在滚动时保持位置，仅首页显示，二级页面不显示。
- 图标与文字共用一个点击事件，通过系统 `ACTION_VIEW` 打开 `https://github.com/TBeiMu/BJUT_gxz` 项目首页。无可处理应用或系统拒绝跳转时，复用 Snackbar 提示“暂时无法打开 GitHub 项目主页”。
- 图标使用本地矢量资源，许可随包保存在 `assets/licenses/octicons.txt`；不新增依赖、网络请求、内置网页或持久化数据。

## 已执行检查

```powershell
.gradlew.bat :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
```

测试断言调整后，再次执行 Debug Lint 与设备测试 APK 编译，通过后运行全部相关设备回归。

| 检查 | 结果 |
| --- | --- |
| Debug 单元测试 | 405 项通过，无失败、错误或跳过 |
| Release 单元测试 | 399 项通过，无失败、错误或跳过 |
| Debug／Release Lint | 通过，0 错误、0 警告，各 1 项原有提示 |
| Debug APK、设备测试 APK | 构建通过 |
| Android 12／API 31，小屏模拟器 | 16 项相关回归全部通过 |
| Android 16／API 36，模拟器 | 16 项相关回归全部通过 |
| Android 16 横屏补充检查 | 2 项通过，检查后恢复模拟器原旋转设置 |

设备端运行 `MyHomeUiTest`、`MyNavigationTest`、`MyUpdateArrowTest`，使用隔离模拟器和虚构身份资料：

- 滚动至七个入口，逐一核对入口可见、内容不被遮挡，GitHub 位置不变且居中；覆盖普通字号、420dp 小窗口两倍字体，以及 320dp 矮窗口两倍字体深色模式。
- 分别实际触摸图标和文字，确认每次触摸只调用一次回调；核对文字行的实际绘制边界、完整高度及无障碍描述。
- 在完整 App 中拦截外部 Intent，确认动作及地址正确；分别注入无浏览器与系统拒绝异常，核对短提示及页面仍可用。前后台往返后保留“我的”首页，进入二级页面隐藏入口，返回后恢复。
- 回归七个分组入口、二级页面返回、Activity 重建、问候语及更新箭头稳定性；另在系统横屏下检查两倍字体布局与完整 App 导航。
- 已查看完整 App 竖屏、横屏以及小屏浅色大字体截图。截图和日志仅保存在被忽略的构建目录，不进入产品流程。

本地日志：`app/build/github-entry-checks.txt`、`github-entry-ui-checks.txt`、`github-entry-api31.txt`、`github-entry-api36.txt`、`github-entry-landscape.txt`（后四项同属 `app/build`）。

## 验证范围

外链测试拦截系统 Intent，未在真实手机或真实浏览器中加载 GitHub 页面；前后台返回通过 Activity 生命周期模拟。未执行正式签名 APK 构建、安装覆盖或 Release 发布，公开下载仍为原 v0.5.6 附件。

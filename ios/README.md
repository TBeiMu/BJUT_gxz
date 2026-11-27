# 工小助 iOS 移植版

版本 **0.5.9／40**，最低 iOS 17，面向 iPhone 与 iPad，已发布未签名 IPA。原生 SwiftUI 页面，Network.framework 绑定实体接口，SwiftNIO HTTP，SwiftSoup 解析；凭据和资料由各模块独立 Keychain 密钥配合 CryptoKit AES-GCM 加密。

**未签名、未执行功能测试，签名与安装自行处理。** 不提供 macOS App，不使用 Android 检查结果作为 iOS 可用性证明。

## 工程与构建

Xcode 26.3；用 Swift 5 语言兼容模式编译。工程无测试 target，发布工作流只运行 iPhoneOS Release／arm64 编译、打包和文件完整性核对。

~~~sh
python3 ios/scripts/generate_project.py
xcodebuild -project ios/GongXiaoZhu.xcodeproj -scheme GongXiaoZhu \
  -configuration Release -sdk iphoneos -destination 'generic/platform=iOS' \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY='' build
~~~

资源已纳入版本管理。重新从 Android 原图准备资源时，需要 Pillow 12.0.0，执行 python3 ios/scripts/prepare_assets.py。工程生成器只使用 Python 标准库。直接依赖固定为 SwiftSoup 2.14.4、SwiftNIO 2.105.2、SwiftNIO Transport Services 1.28.0；全部六项解析版本纳入 Package.resolved，并保留每项许可证文本。

## 已接入的源码范围

| 模块 | 实现路径与行为 |
| --- | --- |
| 导航与个人页 | 原生三栏、二级返回、纸白卡片、小鸟与工字标志、时段文案、固定 GitHub 入口 |
| 课表 | 今日时间线、七列整周、重叠课程、原生分页、周次选择、本地学期、课程多星期／多周次／多节次、范围删除与内存撤销 |
| 学校课表 | CAS 登录及验证码桥接、身份核验、逐周导入、加密保存进度、完整后替换、暂停与手动继续 |
| 校园网 | 多账号、默认账号、公共／宿舍／自动模式、WiFi 约束、确认在线与清理分离、每版本姓名机会 |
| 资费 | 四指标、验证码与内置网页、在线设备和流量、单设备确认注销、失败保留与单条加密缓存 |
| 考试／成绩 | 独立教务绑定、RSA 与验证码、主动记住密码、完整分页、考试本机编辑／隐藏／恢复／撤销、成绩分类与学期筛选 |
| 个性化与校车 | 六渐变、深浅主题、四强调色、图片／图片主色、0–24 模糊、2%–100% 单层卡片、工作日／周末离线校车表 |
| iOS 更新 | 独立 update-ios.json、检查和忽略、用户启动下载、暂停续传、单次移动数据确认、实际 SHA-256 校验、IPA 分享导出 |

以上为源码实现范围，不代表经过功能测试。

2026 年 11 月 27 日校车源码同步更新工作日 17＋15 班及周末 5＋5 班、四条对应路线，按上海日期自动匹配。标题为“校车时刻表（工作）／（周末）”，右上角双向箭头切换；正文仅保留下一班预计，时刻格仅高亮，紧凑排列、逐行交替浅深底色并保留末车，不显示校内／校外运营说明。非当天类型不显示下一班高亮。iOS 按用户要求未验证，本次未替换公开 IPA，详见[校车调整记录](../docs/testing/校车工作日与周末调整验证.md)。

## 平台边界

2026 年 11 月 27 日考试管理源码同步移除考试来源分类和状态分组，统一列表中未结束考试在前、最近结束的考试随后；已结束卡片文字变灰，保留日期并将时间段替换为“已结束”，详情保留完整起止时间。时间未知或倒置时不推断结束，保留搜索、隐藏及恢复入口。前台分钟更新、返回前台及系统时间变化会重新计算，离开页面或后台后取消分钟任务。iOS 按用户要求未验证，版本保持 0.5.9／40，未替换公开 IPA，详见[考试列表优化验证](../docs/testing/考试管理列表优化验证.md)。

- iOS 不允许按 Android 前台服务规则长期驻留。iOS 26 的用户启动导入及下载申请持续后台处理；系统不提供或拒绝时使用有限后台时间，到期取消网络并保留加密周次／下载分段，返回后手动继续。尚未提交的教务登录表单切入后台时关闭，提交前离开需返回后重新登录。
- 不读取系统壁纸，改为用户选择图片后提取“图片主色”。照片选择使用系统 picker，不获取整库权限。
- 切入后台以遮罩覆盖敏感界面；不承诺阻止系统截图。
- Network.framework 按实体 WiFi／蜂窝接口发起 DNS 与连接，不请求 VPN 能力；iOS 不能提供 Android 同等的校园 SSID 与网络认证标记，必须以学校端点响应确认。
- 学校页使用非持久化 WebView、自定义资源 scheme 与当前页面标识；网页不能直接通过 HTTP(S) 联网，表单只由原生会话提交。学校 HTML、验证码、Cookie 和未主动记住的密码不落盘。
- 本机加密资料不直接跨 Android／iOS 迁移。不提供安装器、重签名、证书或描述文件。
- 校历、云同步、考试提醒、绩点计算和本科导入未加入。

## 发布

2026 年 11 月 27 日 00:15:36（上海时间）已向现有 v0.5.9 Release 追加三个独立附件：gongxiaozhu-ios-unsigned.ipa、SHA256SUMS-ios.txt、update-ios.json。IPA 为 6,928,647 字节，源码 `216d650f58ea01e50c111de8fcf507ae9f7f33cb`；Release／arm64 编译、包结构及公开文件完整性核对通过。原 Android 三个附件、发布说明和原标签未变。实际构建、摘要及未测试边界记录在 [iOS 交付记录](../docs/ios/交付记录.md)。

[下载未签名 IPA](https://github.com/TBeiMu/BJUT_gxz/releases/download/v0.5.9/gongxiaozhu-ios-unsigned.ipa) · [同版本 Release](https://github.com/TBeiMu/BJUT_gxz/releases/tag/v0.5.9)。

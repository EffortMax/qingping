# 清屏 v2.0.0

自动跳过其他应用的开屏广告，并拦截误触跳转（广告把页面带到浏览器或应用商店）。不联网，不申请网络权限。

## 工作方式
- 无障碍服务监听窗口变化，只在应用启动后 6 秒内查找按钮。
- 只匹配完整文本：跳过、跳过 3s、Skip 5 等。不匹配“关闭”“知道了”，以免误伤。
- 每次启动最多点击 1 次，两次点击间隔至少 1.5 秒。
- 候选按钮面积不得超过屏幕的 20%。
- 遇到输入框、登录/注册文字时不点击。系统界面和清屏自身也不处理。
- 误触跳转返回：默认关闭，在界面中手动开启。

## 权限
- 无障碍服务：必需，用于读取界面和点击。

## 说明
- 已删除后台保活（前台服务、开机广播）。无障碍服务由系统在开机后自行拉起。

## 限制
- 无法保证 100% 跳过。广告形态多变，以及 OEM 系统的后台限制，都可能导致失效。
- 部分广告使用自绘画面，没有可点击的文字节点，无法识别。
- 厂商系统可能需要额外设置电池白名单或自启动。

## 构建
- JDK 17，Android SDK 34，Gradle 8.7（wrapper 已包含），AGP 8.5.2，Kotlin 1.9.24。
- `gradlew.bat assembleDebug testDebugUnitTest`
- 单元测试覆盖规则引擎（SkipEngine、RedirectRules）。

## 统计
- 本地 SQLite（qingping.db），按应用统计“跳过次数”和“误触返回次数”。
- 界面中可清空统计。

## 目录
- `app/src/main/java/com/qingping/app/RuleEngine.kt`：纯 Kotlin 规则，无 Android 依赖。
- `SkipAccessibilityService.kt`：无障碍服务，扫描节点并执行点击。
- `StatsStore.kt`：SQLite 统计。
- `MainActivity.kt`：界面。

# 本地资产：定期账单导入版

一个离线使用的 Android 记账应用，基于 [DykiSensei/seamless-bookkeeping](https://github.com/DykiSensei/seamless-bookkeeping) 修改，沿用原项目的 MIT 许可。这个版本以每周或每两周手动导入账单为主，已关闭通知、短信和无障碍实时抓取。

## 目前能做什么

- 导入微信、支付宝的原始 CSV / XLSX 账单，以及建设银行文字版交易明细 PDF。扫描图片版 PDF 和其他银行格式暂不支持。
- 按订单号跳过重复导入，显示新增、跳过等结果。账单可查看收支方向、分类、转账路线，也可手动编辑。
- 记录银行卡、支付宝余额、微信零钱和每只基金的市值。基金市值由用户手动更新。
- 从最后一次手动设定余额的时间开始，根据明确账户的交易更新余额：银行卡只由建行 PDF 更新；支付宝、微信账单只有明确写出余额或零钱时才更新相应账户。
- 查看月度收支、分类统计和预算。支持手动记账与调用手机语音识别服务录入。

账本保存在手机本地，应用没有网络权限。导入的账单文件只在本机读取。语音识别是否联网取决于手机安装的识别服务。

## 使用前请看

[安装与使用说明](K80Pro-安装与使用.md)写明了余额起点、重复交易和导入格式。公开源码不包含个人账单、数据库或 APK。自行编译的调试包与其他签名的安装包不能直接互相覆盖；升级前不要卸载已装应用，否则本机账本会丢失。

同一笔银行卡付款若同时出现在银行 PDF 和支付平台账单中，可能在收支统计里重复出现，导入后需要核对。建设银行 PDF 只有日期时，交易会按当天 00:00 显示。当前没有账本导出备份功能。自动测试已通过，红米 K80 Pro 的完整真机导入流程尚未验证。

## 编译

需要 Android SDK、JDK 21 和 Gradle 8.9。项目使用 Kotlin、Jetpack Compose、Room 和 Hilt。

```powershell
./gradlew testDebugUnitTest assembleDebug
```

生成的 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。本仓库不提交调试 APK，也不把它作为已验证的正式发行版。

## 许可与来源

原项目：[DykiSensei/seamless-bookkeeping](https://github.com/DykiSensei/seamless-bookkeeping)，MIT 许可，版权声明见 [LICENSE](LICENSE)。账单导入参考了 [WhereIsMyMoney](https://github.com/FridayKoi/WhereIsMyMoney) 的表头思路；解析代码在本项目中编写。

# 本地资产：定期账单导入版

一个离线使用的 Android 记账应用，基于 [DykiSensei/seamless-bookkeeping](https://github.com/DykiSensei/seamless-bookkeeping) 修改，沿用原项目的 MIT 许可。这个版本以每周或每两周手动导入账单为主，已关闭通知、短信和无障碍实时抓取。

## 目前能做什么

- 导入微信、支付宝的原始 CSV / XLSX 账单，以及建设银行文字版交易明细 PDF。扫描图片版 PDF 和其他银行格式暂不支持。
- 按订单号跳过重复导入，显示新增、跳过等结果。账单可查看收支方向、分类、转账路线，也可手动编辑。
- 记录银行卡、支付宝余额、微信零钱和每只基金的市值。基金市值由用户手动更新。
- 从最后一次手动设定余额的时间开始，根据明确账户的交易更新余额：银行卡只由建行 PDF 更新；支付宝、微信账单只有明确写出余额或零钱时才更新相应账户。
- 查看月度收支、分类统计和预算；点开“其他”“餐饮”等分类可查看本月每笔明细。支持手动记账与调用手机语音识别服务录入。

账本保存在手机本地，应用没有网络权限。导入的账单文件只在本机读取。语音识别是否联网取决于手机安装的识别服务。

## 获取与安装

这个仓库只提供源码，不提供已验证的安装包。Windows 上准备 JDK 21 和 Android SDK 35 后，在纯英文路径下运行：

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

生成的 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。安装、升级和导入步骤见 [安装与使用说明](K80Pro-安装与使用.md)。自行编译的调试包如果与手机上已装版本签名不同，不能直接覆盖；当前也没有账本导出备份功能，请勿为了安装新版卸载旧版。

## 使用前请看

同一笔银行卡付款若同时出现在银行 PDF 和支付平台账单中，可能在收支统计里重复出现，导入后需要核对。建设银行 PDF 只有日期时，交易会按当天 00:00 显示。自动测试已通过，红米 K80 Pro 的完整真机导入流程尚未验证。

项目使用 Kotlin、Jetpack Compose、Room 和 Hilt。编译使用仓库内的 Gradle 8.9 Wrapper；Windows 中文路径可能导致单元测试无法加载测试类，建议用纯英文路径。

## 许可与来源

原项目：[DykiSensei/seamless-bookkeeping](https://github.com/DykiSensei/seamless-bookkeeping)，MIT 许可，版权声明见 [LICENSE](LICENSE)。账单导入参考了 [WhereIsMyMoney](https://github.com/FridayKoi/WhereIsMyMoney) 的表头思路；解析代码在本项目中编写。

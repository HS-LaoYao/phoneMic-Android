# phoneMic · Android 端

将手机用作电脑的麦克风与耳机，同一局域网内自动发现电脑，无需手动输入 IP。配套电脑端：[phoneMic-Windows](https://gitee.com/heishanyao_admin/phoneMic-Windows) ｜
[GitHub 镜像](https://github.com/HS-LaoYao/phoneMic-Windows)

## 下载

| 文件 | 说明 |
|---|---|
| [phoneMic.apk（码云）](https://gitee.com/heishanyao_admin/phoneMic-Android/releases/latest) | 推荐，国内下载快 |
| [phoneMic.apk（GitHub）](https://github.com/HS-LaoYao/phoneMic-Android/releases/latest) | 备用 |

安装后授予「麦克风」权限即可。APK 约 40KB，无广告、无第三方 SDK、不收集任何数据。

## 功能

- **两种录音模式**：长按说话（按住才录音，松手即停）/ 点按常开（点一下持续录音）
- **底部语音输入条**：仿聊天软件的交互——按住期间逐秒显示录音时长，松手后定格，右侧「发送」键可触发电脑上的回车 / Shift+回车
- **远程切换电脑模式**：手机上可直接切换三种音频路由模式（仅麦克风 / 麦克风+监听 / 手机当音响），两端界面实时同步，模式③下手机即电脑耳机
- **前台服务**：切后台或息屏不断连，通知栏可一键停止
- **检查更新**：右上角菜单，从码云发行版获取最新版本，下载后自动调起安装

## 使用

1. 电脑端安装 phoneMic-Windows 并点击「开始」
2. 手机与电脑连接同一 WiFi
3. 打开本应用，自动搜索并连接电脑，顶部横幅变绿即连接成功
4. 按住底部按钮或大圆按钮说话

## 技术说明

- 纯 Java 实现，零第三方依赖（不使用 AndroidX / Compose / Kotlin）
- 通信协议：UDP 47810；音频为裸 PCM 48kHz / 16bit / 单声道，10ms 一帧
- 最低系统要求：Android 8.0（API 26）
- APK 使用固定密钥签名，新版本可直接覆盖安装

## 许可

本仓库代码以 [MIT 许可](LICENSE)开源。

# DSport

在一加 / ColorOS 上读取计步传感器，并只把 `step_counter` 的累计值往上加。步数检测和厂商分钟计步只显示，不修改。另有一个默认关闭的微信防撤回。

模块跑在 [Vector](https://github.com/JingMatrix/Vector) 里，安装包是 APatch 模块。

## 行为

通用页显示这台手机自己的原始读数。偏移加在系统计步服务和微信进程里的 `step_counter` 上。

- 只增加，不减少。负数直接拒绝，不会从累计值里扣步。
- 目标低于已经计入的步数时，目标抬到已计入的数字，已加上的步数保留。
- 释放按每分钟、每 5 分钟两个上限滴加。微信会丢掉达到 5 分钟上限的那一截，所以实际停在该上限减 1。
- 送出的累计值到达步数上限后不再增加。默认上限是 18000。
- 开关打开时，输入的是累计目标。开关关闭再保存时，输入的是在已计入步数上再增加的数量。

微信页读取主用户的微信运动配置（`SportKv`）。可以把它的每分钟、每 5 分钟上限写回通用页。这需要 APatch 授予本应用 root。

防撤回默认关闭。打开后，别人撤回时，这台手机已经收到的消息保持原样，并在下面补一条红色「已撤回」。自己发出的撤回照常显示，公众号不处理。符号按微信 8.0.78 编写，其他版本对不上时会在 Vector 日志里留下 miss，功能不生效。

## 作用域

在 Vector 里启用本模块，作用域包含：

- `com.oplus.exsystemservice`
- `com.oplus.healthservice`
- `com.tencent.mm`

`exsystemservice` 和 `healthservice` 负责按上限释放步数。微信只使用已经释放的累计值。本应用自己的界面不挂钩，用来看原始传感器。

配置接口 `dev.dsport.module.config` 只接受本应用、root、shell、系统 uid，以及上面三个包。其他应用不能读写目标步数或状态。

## 构建

需要本机的 JDK 17 和 Android SDK 34，默认路径：

- `$HOME/.local/jdk/temurin-17`
- `$HOME/.local/android-sdk`，其中要有 `platforms/android-34` 和 `build-tools/34.0.0`

```bash
./build.sh
```

脚本会先跑 `StepMathTest`，再生成：

- `dist/dsport.apk`
- `dist/dsport-0.2.zip`（APatch 模块，内含 apk）

`dist/` 不入库。调试签名在本机 `$HOME/.local/android-sdk/dsport-debug.keystore`，第一次构建时生成。

## 安装

把 `dist/dsport-0.2.zip` 装进 APatch。模块脚本会执行 `pm install -r -g`，并授予活动识别权限。装好后在 Vector 里确认模块已启用、作用域如上。

已经装过旧版时，直接覆盖安装这份 zip。开机脚本会再用模块目录里的 apk 装一次。改完钩子后，重新打开微信；计步服务如果还在用旧代码，重启一次 `com.oplus.exsystemservice` 或重启手机。

## 目录

| 路径 | 内容 |
| --- | --- |
| `app/` | 界面、配置、计步钩子、防撤回 |
| `module/` | APatch 模块脚本和 `module.prop` |
| `stubs/` | 只用于编译的 Xposed API |
| `test/StepMathTest.java` | 计步公式测试 |
| `build.sh` | 构建入口 |

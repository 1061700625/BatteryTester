<div align="center">

# 充放电监测助手 Charge Discharge Monitor

**一款面向 Android 设备的充放电曲线记录与电池表现监测工具**

作者：**小锋学长生活大爆炸**

![Android](https://img.shields.io/badge/Android-8.0%2B-brightgreen)
![Kotlin](https://img.shields.io/badge/Kotlin-Android-blueviolet)
![minSdk](https://img.shields.io/badge/minSdk-26-blue)
![targetSdk](https://img.shields.io/badge/targetSdk-35-blue)
![Status](https://img.shields.io/badge/status-MVP-orange)

</div>

充放电监测助手是一款面向 Android 设备的电池性能辅助测试 App。它可以在用户主动启动后记录放电或充电期间的电量、电流、电压、温度、CPU 使用率和负载目标等数据，并生成可视化曲线与 CSV 数据文件。

本项目强调透明、安全和兼容。App 不会在后台自动放电，不会静默制造负载，所有测试均由用户主动点击开始。测试过程中会显示前台服务通知，用户可以随时停止。


## 界面风格

当前版本采用更轻量的扁平淡色系界面。首页以浅蓝信息头、低圆角卡片、轻阴影、浅色功能按钮和小标签组成，曲线页使用更淡的网格线与按指标区分的柔和曲线颜色。设计目标是接近电池监测工具常见的信息密度，同时保持测试辅助工具的简洁感。

## 功能亮点

- **快速放电测试**
  - 用户确认后启动测试。
  - 使用 CPU 计算负载与屏幕常亮加速耗电。
  - 根据电池温度和系统热状态自动调整 CPU 负载。
  - 温度较低时使用约 92% 高负载，避免 100% 满载导致界面刷新卡顿；温度升高后自动降到中/低/冷却负载。
  - 运行期间保持当前测试页不息屏，便于持续观察曲线。
  - 每秒采样一次电池状态、CPU 使用率和当前负载目标。
  - 通知栏持续显示测试状态。
  - 页面和通知栏均可停止测试。

- **充电曲线记录**
  - 记录充电过程中的电量、电流、电压、温度变化。
  - 运行期间保持当前测试页不息屏，不再因屏幕超时中断观察。
  - 不主动制造负载。
  - 可用于观察快充阶段、降流阶段和温度变化。

- **兼容性优先**
  - 所有电池指标均允许为空。
  - 不支持的字段在 UI 中显示“不可用”。
  - 曲线保留空值和断点，不使用 0 强行补齐。

- **安全保护**
  - 低电量自动停止。
  - 高温自动停止。
  - 系统热状态严重时自动停止。
  - 按温度分段调整负载，避免一直满载导致过热。
  - 最长测试时长到达后自动停止。

- **历史记录与导出**
  - 保存每次测试会话。
  - 记录设备型号、启动来源、停止原因和完整采样点。
  - 支持 CSV 导出与系统分享。

## 截图

> 当前仓库暂未附带截图。建议后续在 GitHub 仓库中添加以下图片，并放入 `docs/screenshots/` 目录。

| 首页 | 测试中 | 曲线详情 |
|---|---|---|
| `docs/screenshots/home.png` | `docs/screenshots/running.png` | `docs/screenshots/detail.png` |

## 适用场景

- 对比不同设备的放电速度。
- 观察手机充电过程中的电流变化。
- 检查温度升高是否影响充电或放电表现。
- 导出 CSV 后进行进一步分析。
- 观察 CPU 使用率、负载目标和温度之间的关系。
- 作为 Android `BatteryManager`、前台服务、SQLite 和自定义曲线视图的学习项目。

## 安全声明

充放电监测助手是辅助测试工具，不是后台省电工具，也不是自动压测工具。

本项目遵循以下设计原则：

- 放电测试只能由用户主动启动。
- App 不会开机自启放电。
- App 不会在后台静默启动 CPU 负载。
- 测试过程中始终显示前台通知。
- 用户可以随时停止测试。
- 温度过高、低电量或系统热状态严重时自动停止。

界面顶部保留作者标识：**小锋学长生活大爆炸**。测试运行期间，前台 Activity 会设置 `FLAG_KEEP_SCREEN_ON`，保持屏幕常亮；放电测试会额外提高屏幕亮度，并根据温度自动调整 CPU 负载。

## 技术栈

| 模块 | 技术 |
|---|---|
| 语言 | Kotlin |
| 构建 | Android Gradle Plugin |
| 后台任务 | Foreground Service |
| 电池采样 | BatteryManager + ACTION_BATTERY_CHANGED |
| 热状态 | PowerManager thermal status |
| 本地存储 | SQLiteOpenHelper |
| 曲线展示 | 自定义 ChartView |
| CPU 使用率 | `/proc/stat` 差分采样，设备不支持时显示为空 |
| 屏幕常亮 | Activity `FLAG_KEEP_SCREEN_ON` |
| 异步任务 | Kotlin Coroutines |
| 导出 | CSV + Android Sharesheet |

## 项目结构

```text
app/src/main/java/com/xfxuezhang/batterytester
├── battery
│   ├── BatteryModels.kt
│   └── BatterySampler.kt
├── data
│   ├── BatteryDbHelper.kt
│   ├── BatteryRepository.kt
│   └── DataModels.kt
├── export
│   └── CsvExporter.kt
├── load
│   ├── CpuBurner.kt
│   └── CpuUsageSampler.kt
├── service
│   └── BatteryTestService.kt
└── ui
    ├── ChartView.kt
    └── MainActivity.kt
```

## 数据字段

| 字段 | 单位 | 说明 |
|---|---:|---|
| `levelPercent` | % | 当前电量百分比 |
| `currentNowMa` | mA | 瞬时电流，正值通常表示充电，负值通常表示放电 |
| `currentAverageMa` | mA | 平均电流，是否可用取决于设备实现 |
| `chargeCounterMah` | mAh | 剩余电量计数，是否可用取决于设备实现 |
| `energyCounterNWh` | nWh | 剩余能量，是否可用取决于设备实现 |
| `voltageV` | V | 电池电压 |
| `temperatureC` | ℃ | 电池温度 |
| `status` | enum | 充电状态 |
| `plugged` | enum | 充电器类型 |
| `thermalStatus` | enum | 系统热状态 |
| `cpuUsagePercent` | % | CPU 使用率，来自 `/proc/stat` 差分采样 |
| `cpuLoadTargetPercent` | % | 放电测试当前目标负载比例 |

CPU 使用率曲线用于观察实际负载效果。负载目标曲线用于观察温控策略如何随温度变化自动调整。

默认放电负载策略：

| 条件 | 目标负载 | 说明 |
|---|---:|---|
| 电池温度 < 38℃ | 92% | 高负载，但不占满 CPU，给 UI 刷新和采样任务留出调度空间 |
| 38℃ - 42℃ | 75% | 中负载 |
| 42℃ - 45℃ | 45% | 低负载 |
| 45℃ - 48℃ | 20% | 冷却负载 |
| >= 48℃ | 0% | 自动停止测试 |

估算功率的计算方式：

```text
abs(currentNowMa / 1000) * voltageV
```

该值仅作为近似参考，不等同于外部功率计测量结果。

## 快速开始

### 使用 Android Studio

1. 下载或克隆本仓库。
2. 使用 Android Studio 打开项目根目录。
3. 等待 Gradle Sync 完成。
4. 连接 Android 手机，例如小米 15 Pro。
5. 运行 `app` 模块。
6. 首次启动时允许通知权限。

### 使用命令行构建，无需 Android Studio

本项目也可以只使用 Android SDK Command-line Tools 构建，适合只想生成 APK 的场景。

#### 1. 整理 Android SDK 目录

假设 SDK 根目录为：

```text
D:\Android\Sdk
```

Command-line Tools 解压后请整理成下面的结构：

```text
D:\Android\Sdk
└── cmdline-tools
    └── latest
        ├── bin
        │   └── sdkmanager.bat
        ├── lib
        ├── NOTICE.txt
        └── source.properties
```

如果解压后看到的是：

```text
D:\Android\Sdk\cmdline-tools\bin\sdkmanager.bat
```

请新建 `latest` 目录，并把 `bin`、`lib`、`NOTICE.txt`、`source.properties` 移进去。

#### 2. 配置 Windows 环境变量

```bat
setx ANDROID_HOME "D:\Android\Sdk"
setx ANDROID_SDK_ROOT "D:\Android\Sdk"
setx PATH "%PATH%;D:\Android\Sdk\cmdline-tools\latest\bin;D:\Android\Sdk\platform-tools"
```

执行后重新打开 CMD，验证：

```bat
sdkmanager --version
```

#### 3. 安装 SDK 编译组件

先接受许可证：

```bat
sdkmanager --licenses
```

然后安装项目需要的 SDK 组件：

```bat
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```

#### 4. 确认 JDK 17

```bat
java -version
```

需要看到 `17.x`。如果不是 JDK 17，请设置：

```bat
setx JAVA_HOME "C:\Program Files\Java\jdk-17"
```

重新打开 CMD 后再次检查：

```bat
java -version
```

#### 5. 配置 local.properties

如果构建时报 `SDK location not found`，在项目根目录新建 `local.properties`：

```properties
sdk.dir=D\:\\Android\\Sdk
```

也可以写成：

```properties
sdk.dir=D:/Android/Sdk
```

#### 6. 构建 debug APK

进入项目根目录，也就是包含 `settings.gradle.kts` 的目录：

```bat
cd D:\你的目录\BatteryTester
```

如果项目中存在 `gradlew.bat`，执行：

```bat
gradlew.bat assembleDebug
```

如果项目中没有 `gradlew.bat`，需要先安装本机 Gradle，然后执行：

```bat
gradle wrapper --gradle-version 9.4.1 --distribution-type bin
gradlew.bat assembleDebug
```

构建成功后，APK 路径通常是：

```text
app\build\outputs\apk\debug\app-debug.apk
```

#### 7. 安装到手机

手机开启开发者选项和 USB 调试，连接电脑后执行：

```bat
adb devices
```

确认设备在线后安装：

```bat
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## 使用说明

### 放电测试

1. 打开 App。
2. 阅读首页提示。
3. 点击“开始放电测试”。
4. 确认测试风险和停止条件。
5. 测试开始后保持通知可见。
6. 可在页面或通知栏停止测试。
7. 测试结束后进入历史记录查看曲线。

### 充电记录

1. 连接充电器。
2. 点击“开始充电记录”。
3. App 只记录数据，不制造额外负载。
4. 需要结束时手动停止。
5. 在历史记录中查看充电曲线。

### 导出 CSV

1. 打开历史详情页。
2. 点击导出。
3. 选择系统分享目标，或保存到本地。
4. CSV 中会保留空字段，方便后续分析。

## 常见问题

### 为什么某些曲线是空的？

不同设备的 fuel gauge 和系统实现不同，有些字段可能不支持。App 会保留空值，而不是用 0 伪造数据。

### 电流正负号是什么意思？

Android `BatteryManager` 中，瞬时电流通常以微安为单位。正值通常表示电流进入电池，负值通常表示电池正在放电。项目中已经转换为 mA。

### 为什么不用 0 补齐曲线？

0 可能会误导用户。例如电流不可用和真实电流接近 0 是两种完全不同的情况。本项目保留空值和断点。

### 放电测试会伤电池吗？

放电测试会增加功耗和发热。建议在电量 20% 以上开始测试，不要长时间重复高负载测试。App 内置低电量、高温和热状态保护，但用户仍应根据设备实际情况谨慎使用。

### 是否必须安装 Android Studio？

不必须。可以只安装 JDK 17 和 Android SDK Command-line Tools，然后通过 Gradle 构建 APK。

### 为什么需要通知权限？

测试期间 App 使用前台服务展示持续通知，便于用户确认测试正在运行，并能随时停止。

## 兼容性说明

| 项目 | 当前设置 |
|---|---|
| minSdk | 26 |
| targetSdk | 35 |
| compileSdk | 36 |
| 默认包名 | `com.xfxuezhang.batterytester` |
| 首个验证设备 | 小米 15 Pro |

项目不会为单一机型写死容量、功率或阈值。小米 15 Pro 可作为首个验证设备，其他 Android 设备会按系统实际返回值采样。

## 已知限制

- 电流精度取决于厂商 fuel gauge 实现。
- 某些设备可能无法提供平均电流、剩余容量或剩余能量。
- 估算功率不是外部功率计测量值。
- 当前版本使用 CPU 负载和屏幕负载，暂未实现 GPU 渲染负载。
- CPU 使用率来自 `/proc/stat` 差分采样，部分系统可能限制读取，限制时该字段会显示为空。
- 当前图表为内置自定义 View，功能以轻量和可控为主。

## 路线图

- [ ] 增加 GPU 渲染负载。
- [x] 根据温度自动调整 CPU 负载。
- [x] 增加 CPU 使用率曲线。
- [ ] 增加更细的手动放电档位。
- [ ] 增加测试前设备兼容性报告。
- [ ] 增加多会话曲线对比。
- [ ] 增加 JSON 导出。
- [ ] 增加横屏大图模式。
- [ ] 增加更多统计指标，例如平均功率、温升、单位时间耗电率。
- [ ] 增加 GitHub Actions 自动构建 APK。

## 贡献

欢迎提交 Issue 和 Pull Request。建议贡献前先说明目标，例如：

- 新增设备兼容性数据。
- 改进曲线显示。
- 增加导出格式。
- 修复特定机型上的采样问题。
- 优化放电负载策略。
- 调整温度分段阈值或负载比例。

## 免责声明

本项目仅用于学习、测试和辅助分析。电池数据来自 Android 系统接口，准确性取决于设备硬件、厂商实现和系统版本。放电测试会增加设备功耗和发热，请在理解风险后使用。

## 参考资料

- [Android BatteryManager](https://developer.android.com/reference/android/os/BatteryManager)
- [Android PowerManager thermal status](https://developer.android.com/reference/android/os/PowerManager)
- [Foreground service types](https://developer.android.com/about/versions/14/changes/fgs-types-required)
- [Build your app from the command line](https://developer.android.com/build/building-cmdline)
- [sdkmanager](https://developer.android.com/tools/sdkmanager)

## AGP 9 Kotlin 插件迁移说明

本项目使用 Android Gradle Plugin 9.x。AGP 9 已经内置 Kotlin 支持，因此不再需要在 `app/build.gradle.kts` 中声明 `org.jetbrains.kotlin.android` 插件。

如果你看到下面的错误：

```text
The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since AGP 9.0.
Solution: Remove the 'org.jetbrains.kotlin.android' plugin from this project's build file.
```

请确认：

```kotlin
// app/build.gradle.kts
plugins {
    id("com.android.application")
}
```

同时根目录 `build.gradle.kts` 中也不需要声明 Kotlin Android 插件：

```kotlin
plugins {
    id("com.android.application") version "9.2.0" apply false
}
```

本项目当前使用：

```kotlin
compileSdk = 36
targetSdk = 35
```

因此命令行环境至少需要安装：

```bash
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```


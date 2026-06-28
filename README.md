# Global PEQ

`Global PEQ` 是一个面向 Android 的全局音频调音实验项目，目标是在不 Root 的前提下，提供接近系统级的 PEQ、GEQ、虚拟低音、混响和设备级预设管理能力。

项目目前包含两条主要处理路径：

- `Default`：走系统 `Equalizer` / 系统音效链，兼容性更高，能力更保守。
- `Shizuku Mode`：走 `MediaProjection + DSP + Shizuku` 的高级链路，支持更完整的 DSP 处理。


## 功能总览

- 全局 PEQ / GEQ 调音。
- 设备预设保存与自动切换。
- 命名预设、草稿预设、导入导出。
- `Shizuku Mode` 下的系统音频捕获与 DSP 处理。
- 虚拟低音、额外低音增强、混响。
- 高级引擎参数调节。
- 开机恢复、前台服务常驻、蓝牙/有线/扬声器路由跟踪。

## 界面与按钮说明

下面按实际使用顺序梳理主要控件。

### 1. 顶部主控制区


- `Enable` 总开关
  - 控制当前运行中的音效链是否启用。
  - 这个状态现在是独立保存的，不会混进预设本身。

- `Auto Switch Output`
  - 是否根据当前输出设备自动切换到对应设备预设。
  - 打开后，切换蓝牙耳机 / 音箱 / 扬声器 / 有线耳机会自动切换设备配置。

- `Device`
  - 设备选择器。
  - 用于手动指定当前要关联和编辑的输出设备。

- `Preset`
  - 底部预设选择器。
  - 切换设备时会自动跳到该设备关联的预设。
  - 之后你也可以手动切到别的命名预设，此时主要是 `edit`，不一定立刻变成设备当前 `live` 预设。

- `Save`
  - 保存当前编辑预设。
  - 支持覆盖现有命名预设，或另存为新的预设。

### 2. EQ 页面

- `PEQ / GEQ` 模式切换
  - `PEQ`：按滤波器方式逐段调节。
  - `GEQ`：固定频点滑杆均衡。

- `+ Add EQ Band`
  - 新增一个 PEQ 滤波段。

- 每个 PEQ Band 的控件
  - `Type`：滤波器类型。
  - `Freq`：中心频率或截止频率。
  - `Gain`：增益。
  - `Q`：带宽/Q 值。
  - `Enable`：单段开关。

- 曲线相关控件
  - `Device Curve`：设备补偿曲线选择。
  - `Target Curve`：目标曲线选择。
  - `Smoothing`：曲线平滑。
  - `Gain Offset`：曲线整体增益偏移。

### 3. Reverb 模块

- `Reverb` 开关区
  - 只在支持的处理模式下可用。

- `Type`
  - 混响算法类型选择。

- `Main`
  - 主混响电平。
  - 已按非线性映射做过调整，常用范围更集中。

- `Decay`
  - 混响衰减时长，使用非线性映射。

- `Predelay`
  - 预延迟，0-50ms 占前半段，更方便细调。

- `Size`
  - 空间尺寸感。

- `Mix`
  - 发送量控制。
  - 当前设计是发送制，`Mix` 控制送入混响的量，不是简单干湿线性混合。

### 4. Virtual Bass 模块

- `Virtual Bass` 主开关区
  - 仅在支持的模式下可用。

- `Mode`
  - 虚拟低音模式选择。

- `Amount / Boost`
  - 虚拟低音总体强度。

- `Cutoff Hz`
  - 低频提取截止点。
  - 当前已修复：调整 cutoff 时不会再把上面的百分比重置为 0。

### 5. Extra Bass 模块

- `Extra Bass` 开关
  - 更偏“附加低频增强”的通道。

- `Amount`
  - 额外低音增强强度。

- `Cutoff`
  - 额外低音增强的工作频带上限。

### 6. Settings 页面

- `System Processing`
  - 切换 `Default` / `Shizuku Mode`。

- `Engine Status`
  - 当前引擎状态概览。

- `Advanced Monitor Settings`
  - 打开高级监听设置页。

- `Language`
  - 中英文切换。

- `Preset Import / Export`
  - 单个预设 JSON 的导入导出。

- `Global Config Import / Export`
  - 整套设备配置 JSON 的导入导出。
  - 现在只使用新格式。
  - 同一个文件内会分别保存：
    - `systemEqState`
    - `shizukuState`
    - `presets`

### 7. Advanced Monitor Settings 页面

- `Capture / Authorize`
  - 申请或启动音频捕获。

- `Shizuku Access`
  - 请求或检查 Shizuku 权限状态。

- `Monitored App`
  - 选择要监听的目标应用。
  - 在高级链路下可针对目标 App 做捕获和处理。

- `Latency`
  - 目标延迟参数。

- `Buffer Size`
  - DSP / 捕获缓冲区大小。

- `Lookahead`
  - 前视时间，主要给 limiter 等动态处理使用。

- `Limiter Ceiling`
  - 限幅器顶阈值。

- `Limiter Release`
  - 限幅器释放时间。

## 预设与配置逻辑

### 1. 设备预设

- 每个输出设备各自维护一套设备预设。
- 现在已经按处理模式拆开：
  - `Default` 一套
  - `Shizuku Mode` 一套

### 2. 命名预设

- 命名预设是独立库。
- 可被任意设备引用或拿来编辑。

### 3. 草稿预设

- 当前编辑中的临时状态会保存为草稿。

### 4. 主开关状态

- 总开关状态不再保存在预设里。
- 设备切换、模式切换、命名预设切换时，不会因为预设内容把总开关意外覆盖掉。

### 5. 全局配置文件

- 全局配置导出为一个 JSON 文件。
- 文件中同时包含两套模式配置：
  - `systemEqState`
  - `shizukuState`
- 不再兼容旧格式。

## 运行模式说明

### Default

- 更依赖系统自带音频效果接口。
- 适合基础均衡和更高的兼容性。
- 切换输出设备时，必要时会做系统 EQ 的完整重置。

### Shizuku Mode

- 通过 `MediaProjection` 捕获音频，再进入自定义 DSP。
- 支持更完整的混响、虚拟低音、限幅器等算法。
- 切换蓝牙或输出设备时，已经避免了不必要的 capture 重启。

## 导入导出说明

### 预设导入导出

- 用于分享单个 EQ / DSP 预设。
- 不包含完整设备映射关系。

### 全局配置导入导出

- 用于迁移整套设备与模式设置。
- 包含：
  - 当前设备信息
  - 当前处理模式
  - 自动切换开关
  - 两套模式的设备预设状态
  - 命名预设库

## 主要权限用途

- `FOREGROUND_SERVICE / MEDIA_PLAYBACK / MEDIA_PROJECTION`
  - 前台服务与音频捕获。

- `MODIFY_AUDIO_SETTINGS`
  - 调整系统音频效果相关参数。

- `RECORD_AUDIO`
  - 音频捕获链路需要。

- `BLUETOOTH_CONNECT`
  - 识别和管理蓝牙输出设备。

- `QUERY_ALL_PACKAGES`
  - 选择监听目标 App 时需要枚举应用。

- `RECEIVE_BOOT_COMPLETED`
  - 开机恢复服务状态。

## 技术栈

### 客户端

- `Java 17`
- `Android SDK / Android Framework API`
- 自定义 View UI，未使用 Jetpack Compose

### 音频与 DSP

- `android.media.audiofx.Equalizer`
- `MediaProjection`
- 自定义 PCM DSP 处理链
- `Limiter`
- `Virtual Bass`
- `Algorithmic Reverb`
- 输出设备监控与设备级路由策略

### 权限与系统能力

- `Shizuku`
- 前台服务
- Boot Receiver
- 蓝牙与音频设备枚举

### 数据与配置

- `SharedPreferences`
- JSON 预设 / 配置文件
- 设备维度与模式维度双层状态存储

### 构建

- `Gradle`
- `Android Gradle Plugin 8.5.2`
- `compileSdk 35 / targetSdk 35 / minSdk 28`

## 开发者备注

- 当前仓库内额外生成了一个 `offline-m2` 本地 Maven 镜像目录，用于尽量在离线环境下恢复构建。
- 这次真正拦住 `release` 的不是源码，而是当前环境对本机 Android SDK 平台目录的读取权限。
- 如果把 SDK 读权限放开，优先执行：

```powershell
.\gradlew.bat assembleRelease
```

- 成功后产物默认会在：

```text
app/build/outputs/apk/release/
```

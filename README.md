# LG_DrawPad

基于 Jetpack Compose 与 androidx.ink 的 Android 手写绘图工程：含可复用的绘图库 `:drawpad-compose` 与演示宿主 `:app`。

## 特性

- 压力感应自由手写
- 橡皮擦（实时预览）
- 撤销 / 重做 / 清空
- 背景图片导入
- 自动保存与恢复（JSON + AtomicFile）

## 模块

| 模块 | 类型 | Namespace |
|------|------|-----------|
| `:drawpad-compose` | Android Library | `com.louisgeek.drawpad` |
| `:app` | Android Application | `com.louisgeek.lgdrawpad` |

## 技术栈

- Kotlin 2.2.20
- AGP 8.7.3
- Gradle 8.9
- Compose BOM 2025.12.00
- androidx.ink 1.1.0-alpha10
- minSdk 24 · targetSdk 35
- Java 17

## 构建与测试

```bash
./gradlew :drawpad-compose:testDebugUnitTest           # JVM 单元测试
./gradlew :app:assembleDebug                           # 组装 debug APK
./gradlew :drawpad-compose:lintDebug :app:lintDebug    # Lint
./gradlew :drawpad-compose:connectedDebugAndroidTest   # 仪器测试（需设备）
```

## 目录结构

```
LG_DrawPad/
├─ app/                     # 演示宿主（DrawPadHostActivity）
├─ drawpad-compose/         # 绘图库
│  └─ src/main/java/com/louisgeek/drawpad/
│     ├─ DrawPad.kt          # 画布 + 指针处理
│     ├─ DrawPadState.kt     # 文档 / 历史 / 持久化状态机（@MainThread）
│     ├─ OperationHistory.kt # 撤销栈
│     ├─ model/              # DrawingModels / EraseGeometry / AabbGrid
│     └─ storage/            # DocumentStore（编解码 + 回放）
└─ docs/
```

## 使用

```kotlin
dependencies {
    implementation(project(":drawpad-compose"))
}
```

## 说明

- `androidx.ink` 为 `1.1.0-alpha`，API 可能变动。
- `DrawPadState` 为 `@MainThread`，勿跨线程调用。
- 擦除 / 恢复的端到端验证在 `androidTest`（需设备）。

## 许可

未声明开源许可证。
# 赛尔信息聚合页 Android 客户端

一个基于 Android WebView 封装的原生客户端，用于访问[赛尔号信息聚合页](https://seerinfo.yuyuqaq.cn/)。

## 项目简介

本项目将 `https://seerinfo.yuyuqaq.cn/` 网站包装为一个 Android 原生应用，提供比直接使用浏览器更流畅的用户体验，并额外支持图片保存、文件下载、缓存清除等原生功能。

## 功能特性

- **WebView 浏览**：在应用内直接访问赛尔号信息聚合页，支持 JavaScript、DOM 存储、多窗口等特性
- **下拉刷新**：支持下拉手势刷新页面
- **返回导航**：按返回键优先在 WebView 历史中后退，无历史时退出应用
- **长按保存图片**：长按页面中的图片，弹出确认框后保存至相册（`Pictures/Seerinfo` 目录）
- **文件下载**：通过系统 DownloadManager 下载文件，支持 APK 等各类文件类型，下载时自动携带 Cookie
- **操作菜单**：顶部可展开更多操作，包括刷新、后退、前进、清除缓存、关于
- **清除缓存**：一键清除 WebView 缓存、Cookie、表单数据、历史记录及应用缓存
- **自定义 User-Agent**：在默认 UA 后附加 `seerinfo-android/<版本号> (<包名>; vc=<版本码>)` 标识
- **Edge-to-Edge 支持**：兼容 Android 15 强制开启的边到边显示，正确处理状态栏与导航栏遮挡

## 网页 JavaScript 接口

网页端可通过注入的 `Android` 对象调用以下原生方法：

| 方法 | 说明 |
|---|---|
| `Android.clearAllCache()` | 清除全部缓存（WebView 缓存、Cookie、DOM Storage、应用缓存等） |
| `Android.showToast(message)` | 显示一条原生 Toast 提示 |
| `Android.getAppVersion()` | 获取当前应用的版本名称（如 `"1.26"`） |

## 环境要求

| 项目 | 要求 |
|---|---|
| 最低 Android 版本 | Android 7.0（API 24） |
| 目标 Android 版本 | Android 16（API 36） |
| 开发语言 | Kotlin |
| 构建工具 | Gradle + Android Gradle Plugin |

## 构建与运行

### 前置条件

- 安装 [Android Studio](https://developer.android.com/studio)（推荐 Ladybug 或更高版本）
- 安装 Android SDK，API Level 36

### 构建步骤

1. 克隆仓库：

   ```bash
   git clone https://github.com/WhY15w/seerinfo-android-webview.git
   cd seerinfo-android-webview
   ```

2. 使用 Android Studio 打开项目，或直接通过命令行构建：

   ```bash
   # Debug 版本
   ./gradlew assembleDebug

   # Release 版本
   ./gradlew assembleRelease
   ```

3. 生成的 APK 位于 `app/build/outputs/apk/` 目录下。

## 项目结构

```
app/src/main/
├── java/com/hurrywang/seerinfo/
│   ├── MainActivity.kt              # 主 Activity，负责整体页面组装与生命周期管理
│   ├── WebViewSetupExtensions.kt    # WebView 配置扩展函数（UA、设置、下载监听）
│   ├── WebAppInterface.kt           # 暴露给网页的 JavaScript 接口（Android 对象）
│   ├── RefreshAwareWebView.kt       # 自定义 WebView，感知滚动状态以联动下拉刷新
│   └── ImageDownloader.kt           # 图片下载器，支持网络图片与 Base64 图片保存
├── res/
│   ├── layout/                      # 布局文件
│   ├── menu/                        # 操作菜单定义
│   └── values/                      # 字符串、颜色、主题等资源
└── AndroidManifest.xml              # 应用权限与组件声明
```

## 权限说明

| 权限 | 用途 |
|---|---|
| `INTERNET` | 加载网页内容 |
| `WRITE_EXTERNAL_STORAGE` | 旧版 Android 写入下载文件（Android 10+ 通过 MediaStore 无需此权限） |
| `DOWNLOAD_WITHOUT_NOTIFICATION` | 后台下载文件 |

## 作者

**HurryWang**

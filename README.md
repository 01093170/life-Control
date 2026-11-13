# 人生RPG · 打卡换娱乐额度

原来的单文件网页（`www/index.html`）外面套了一层 Android 壳，加了两个原生能力：

1. **打卡发额度** —— 每次技能打卡往「娱乐额度」里加固定时长（默认 30 分钟，累加不过期）
2. **额度管应用** —— 额度归零时，受管的 App 会被盖一层拦截页，并用 root 强制退出

网页逻辑一行没重写，只是多了一个原生桥 `window.RewardNative`。

---

## 目录结构

```
www/index.html          页面本体（要改界面改这里）
backup/                 原始 HTML 快照，出问题时对照
android/                Android 工程（Capacitor 生成，已提交）
  app/src/main/java/com/liferpg/control/
    MainActivity.java     入口：注入 JS 桥、处理状态栏留白、接管返回键
    NativeBridge.java     window.RewardNative 的全部方法
    Wallet.java           额度钱包（files/reward-guard/wallet.json）
    GuardService.java     常驻前台服务：按秒扣额度、更新通知
    AppWatcher.java       无障碍服务：只负责报告"现在前台是谁"
    BlockOverlay.java     额度耗尽时盖的全屏拦截页
    RootShell.java        调 su 执行 am force-stop
    BootReceiver.java     开机自启
.github/workflows/build-apk.yml   云端编译
```

---

## 出 APK

推代码到 GitHub，Actions 会自动编译：

1. `git push` 到 `main`
2. 打开仓库的 **Actions** → 最新的 `Build APK` 运行
3. 跑完在页面底部 **Artifacts** 下载 `life-control-apk`
4. 解压得到 `.apk`，传到手机安装（小米会提示"未知来源"，允许即可）

首次编译大约 5～8 分钟，之后有缓存会快一些。

### 本地预览界面

直接双击 `www/index.html` 用浏览器打开即可。没有原生环境时，额度会降级存进 `localStorage`，界面上所有交互都能试。

---

## 装完必做的四件事

不做这些，管控会在几分钟内失效。

| 项 | 位置 |
|---|---|
| ① 无障碍服务 | 设置 → 无障碍 → 已下载的应用 → **人生RPG · 娱乐额度守护** → 开启 |
| ② root 授权 | 第一次额度耗尽时会弹 Magisk 授权框，选「允许」并记住 |
| ③ 自启动 | 设置 → 应用设置 → 人生RPG → 自启动 → 允许 |
| ④ 省电策略 | 同上 → 省电策略 → **无限制**；再到最近任务里下拉卡片加锁 |

App 里的「娱乐额度 → 管理」页面会检测①②，没开会有红字提示，点「去开启」直接跳转。

---

## 怎么用

**打卡**：技能页点「打卡」，额度自动 +30 分钟，toast 里会写「娱乐额度 +30 分钟」。

**加受管应用**：技能页顶部「娱乐额度 → 管理 → + 添加应用」。
- 从已安装列表里搜着挑，或者
- 底部手动粘贴包名，例如抖音 `com.ss.android.ugc.aweme`、抖音极速版 `com.ss.android.ugc.aweme.lite`、B站 `tv.danmaku.bili`

多个应用**共用同一份额度**，所以别把微信、钉钉这类加进去。

**改奖励时长**：「管理」页第一个输入框，1～600 分钟。

**临时调额度**：「管理」页底部 +30 / -30 分钟，方便测试。

---

## 额度耗尽时会发生什么

1. `AppWatcher` 发现受管应用到了前台，`GuardService` 累计时长
2. 余额减到 0 → `sudo am force-stop <包名>` 杀掉进程
3. 同时盖一张全屏页：「额度已用完 · 去打卡 / 回桌面」
4. 用户再点开该应用，会立刻被再杀一次

**能管住多少**：Android 不给第三方 App 永久禁用别的应用的权限，所以是「杀进程 + 拦截页」的组合。实际体感是打开了立刻被踢出去，但不是真的把它卸载或禁用。真有需要可以在 `RootShell` 里换成 `pm disable-user`（更狠，图标会消失）。

---

## 几点实现说明

**数据存哪**：额度在 `files/reward-guard/wallet.json`，不在 localStorage。好处是原生服务和 WebView 读同一份，将来想升级成 Magisk 模块常驻守护，脚本直接读这个文件就行。

**为什么无障碍权限要开**：`canRetrieveWindowContent=false`，只监听窗口切换事件，不读屏幕内容，也不上传任何东西。输入法弹窗会被过滤掉，不会误判成"切走了"。

**状态栏留白**：Android 15+ 强制 edge-to-edge，`MainActivity` 把状态栏/导航栏高度写进 CSS 变量 `--sat` / `--sab`，页面用它们做 padding。

**返回键**：`MainActivity` 先问页面 `window.__androidBack()`，页面处理了（关弹窗、退出详情页）就不退出 App。

---

## 常见问题

**编译失败**：看 Actions 日志里 `./gradlew assembleDebug` 那一步的报错，把红字贴给我。

**额度不扣**：先看「管理」页无障碍是否显示「已就绪」。再看通知栏有没有常驻的「娱乐额度」通知——没有就说明服务被系统杀了，回去检查自启动和省电策略。

**Magisk 没弹授权框**：Magisk App → 超级用户 → 看有没有 `人生RPG` 的记录，没有的话在「管理」页点一下 root 那行的「去开启」触发一次。

**想管更多应用**：直接加包名即可，代码里没有硬编码任何应用。

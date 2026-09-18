# 代码审查修复与 MuMu 调试验证

> v2.1.2 迭代记录：四个迭代共修复 17 项问题，MuMu 12 模拟器全链路验证通过。

## 一、修复清单

### 迭代 1：高危（泄漏与崩溃）

| 位置 | 问题 | 修复 |
|------|------|------|
| `PlayerFragment` | 待命播放器 `onPlayerError` 仅置空引用，解码器/加载线程/连接泄漏 | 置空前显式 `release()` |
| `PlayerFragment` | 播放器只在 `onDestroy` 释放，view 销毁但 Fragment 保留时持续占资源 | 释放移至 `onDestroyView`（与 `onCreateView` 配对），`onDestroy` 幂等兜底 |
| `PlayerFragment` | `(activity as MainActivity)` 强转、异步路径 `requireContext()` 可崩溃 | 统一 `as?`；`buildMediaSource` 改显式传 context |
| `MainActivity` | 时钟每秒自调度，`onDestroy` 未清理，Handler 拖住整棵 Fragment 树 | `handler.removeCallbacksAndMessages(null)` |

### 迭代 2：结构性

| 位置 | 问题 | 修复 |
|------|------|------|
| `MainActivity` | 重建时字段 Fragment 与 FragmentManager 恢复的实例脱节，`play()/reload()` 静默失效 | `add()` 带 tag，重建时 `findFragmentByTag` 找回实例覆盖字段 |
| `TVList` | 用户配置纯地方台源时，命中 1 条 CCTV 其余频道全被白名单丢弃 | 用户显式配置订阅源时跳过白名单（垃圾标题/黑名单域名在解析阶段独立清理） |
| `ChannelProbe` | 主线程每次裸起线程写 `probe.json`，并发交错损坏 | 落盘统一走单线程 executor 串行执行 |
| `MainFragment` | 拉流 IO 协程无异常保护，网络异常静默杀死协程 | `runCatching` 就地消化 |
| `MainFragment` | 协程内 `requireContext()` 在 detach 后抛异常 | 协程开始时安全取 context |

### 迭代 3：安全加固

| 位置 | 改动 | 理由 |
|------|------|------|
| `AndroidManifest` | `allowBackup="false"` | SP 中有订阅源与配置令牌，防 adb backup 提取 |
| `network.xml` | 移除 user 证书锚点 | 防抓包证书中间人替换播放地址/EPG |
| `ConfigServer` | 鉴权优先支持 `X-Config-Token` 请求头，页面 JS 全部改 header 传令牌 | 令牌不再进 URL（局域网日志/浏览器历史） |
| `SP` | 配置令牌 6 位 → 8 位 | 约 33^8 组合，局域网暴力枚举不可行 |

### 迭代 4：低危清理

- `MainFragment`：废弃的 `onActivityCreated` → `onViewCreated`；`prevSource/nextSource` 合并为 `switchSourceBy(delta)`
- `MainActivity`：`back()` 复用 handler 字段
- `ChannelProbe`：探测复用共享 `OkHttpClient`（连接池/TLS 会话不再每轮丢弃）
- `EpgStore` / `LineHealth` / `SourceHealth`：裸 `Thread` 落盘/抓取改单线程 executor

### 未做（需单独立项）

- 每频道 3 个 LiveData 观察者、主线程构建大列表：涉及列表绑定架构重构
- `TVList`（1100+ 行）拆分 Parser/Sorter/Filter：纯重构，建议配单元测试再做

## 二、MuMu 12 调试验证（Android 12，adb 127.0.0.1:16384）

### 启动链路

```
config server started on :34567
5 个 Fragment 依次 ready（Displayed +721ms）
epg cache loaded: 102 channels / cache loaded: 2 groups
```

### 播放与预加载（双播放器收益实测）

```
first frame in 5775ms: CCTV-2        ← 普通换台
standby: ready #2 (+378ms)           ← 后台为 CCTV-3 备好
standby: promote #2 (elapsed 0ms)    ← 换台命中接管
first frame in 46ms: CCTV-3          ← 首帧 5775ms → 46ms
```

### 修复项实测

- **确定性失败快速放弃**：`go.bkpcp.top` Connection reset 后 `give up retry x2` 立即换线，不再白等退避
- **终态兜底**：BRTV 全线路不可用 → 看门狗 15s 超时 → 终态提示 → 自动跳到可播台
- **待命错误路径**：`standby: failed` 后走新加的 release 路径，无泄漏堆积
- **探活**：首轮 `200/200 lines, ok=130, verified=116`；证书域名不匹配的线路被正确拒绝（移除 trust-all 后预期行为）
- **远程配置鉴权**（设备内自测）：header 正确令牌 `200`；无令牌/错误令牌 `401`；query 令牌兼容通过
- **全程零崩溃**：无 FATAL / AndroidRuntime

### 已知环境限制（非应用问题）

- MuMu NAT 网络下教育网（`ivi.bupt.edu.cn`）与部分运营商组播源不可达，真机盒子上表现更好
- 覆盖安装保留旧 6 位令牌（鉴权兼容），全新安装才生成 8 位令牌
- 宿主机 `adb reverse` 与 MuMu 自带 adb 混用时不监听，调试时用设备内 `nc` 自测代替

## 三、遗留建议

1. release 构建开启 R8（当前 `minifyEnabled false`）裁剪包体
2. `kotlinx-coroutines 1.8.0-RC` 转正式版；Glide 4.11.0 可升级
3. versionCode 依赖构建机 `git describe --tags`，CI 打包前需确认 tag 已拉取

# mcwifipnp P2P 打洞（NAT Hole-Punching）功能设计

> 状态：Phase 0（技术验证）完成，Phase 1 进行中。

## 目标

成员经 frp 加入房主 LAN 世界后，双方 mod 通过游戏内自定义数据包协调，用 Iroh
（QUIC over UDP）打洞建立 P2P 直连。打洞成功（直接路径）则客户端自动重连到
直连通道；失败或仅中继路径则继续走 frp（现状不变）。令牌可选：房主开启令牌后，
成员需在聊天中输入匹配令牌才启动打洞。

## 架构

```
1. 成员照常输入 frp 公网地址加入 → 游戏连接经 frp 建立（现状不变）
2. 双方 mod 在游戏内经自定义数据包通道 "mcwifipnp:p2p" 协商：
   host → member:  offer      { 是否要求令牌, p2p 版本 }
   member → host:  hello      { 令牌?, 成员端 iroh EndpointAddr }
   host → member:  accept     { 房主端 iroh EndpointAddr }
   （令牌不匹配 → 打洞不启动，完全走 frp）
3. 成员端 iroh endpoint 拨号房主端 endpoint（Iroh 中继协调 NAT 穿越）
   → 打通直接 UDP 路径（或退化为中继路径）
4. 建立双向流：房主侧 流 ⇄ 127.0.0.1:服务器端口
                 成员侧 客户端 ⇄ 127.0.0.1:本地代理 ⇄ 流
5. host → member: switch-ready（仅当连接为直接路径，PathSnapshot.isRelay()==false）
   → 成员客户端自动断开 frp、重连到本地代理（走直连）
6. 任何一步失败/超时 → 继续 frp，用户无感
```

## 代码组织与可移植性

- `p2p` 核心包（`io.github.satxm.mcwifipnp.p2p`）零 `net.minecraft.*` import，
  纯 Java 8 兼容语法（无 record/var/模式匹配），保证 1.7.10（Java 8）到 26.3
  （Java 25）全部可编译。
- 版本差异收敛到每分支一个适配文件：packet 注册+编解码、GUI 开关、
  重连入口、生命周期接线。
- 打洞协议字节流带魔数 + 协议版本头（见 `p2p/protocol` 的字节布局）。

## iroh 绑定集成（Phase 0 验证结论）

- 依赖：`computer.iroh:iroh:1.1.0`（官方 Kotlin/JVM 绑定，MIT/Apache-2.0，
  与 mod 的 GPL-3 兼容），传递依赖 kotlin-stdlib 2.2.21、kotlinx-coroutines 1.9.0、
  jna 5.15.0。原生库自包含于 jar（`win32-x86-64/iroh_ffi.dll`、
  `linux-x86-64/libiroh_ffi.so`、`linux-aarch64/libiroh_ffi.so`），JNA 自动按
  `{OS}-{ARCH}` 提取。**Windows x64 已验证加载成功**（Java 25.0.2）。
- **必须用 Kotlin shim 桥接**：iroh-ffi 的 suspend 函数（`connect`/`bind`/
  `acceptNext` 等）和 `ULong` 返回类型方法（`readToEnd`/`read`/`write`）带
  JVM 名字 mangle 后缀（如 `readToEnd-qim9Vi0`），**纯 Java 源码无法直接调用**。
  方案：仓库内置一个 `KtBridge.kt`（如 spike 中的 shim），用 `runBlocking` 包
  装所有需要的调用为 `@JvmStatic` 纯方法；Kotlin 编译产物随构建生成。
  核心包只依赖 KtBridge 的纯 Java 签名。
- **必须调用 `applyN0()`**：绑定默认无 TLS crypto provider，直接 `bind()` 报
  `Missing or incompatible rustls crypto provider`。`EndpointBuilder.applyN0()`
  应用 N0 preset（crypto provider + 默认 relay 列表）后即可绑定。
- **跨 NAT 场景必须 `online()`**：spike 中 `bind()` 后立即取 `addr()` 的
  `relayUrl()` 为 null（relay 尚未注册）。跨 NAT 打洞依赖中继协调，房主端
  需在发布后等待 `endpoint.online()` 完成再向成员发 accept。
- **直连/中继判断可行**：`Connection.paths()` 返回 `PathSnapshot` 列表，
  `isRelay()` 精确区分直连（false）与中继（true）路径，`isSelected()` 为当前
  选中路径。switch-ready 仅在存在 `isRelay()==false` 且 `isSelected()==true`
  的路径时发送。
- **Java 25 native access**：JNA 的 `System.load` 在 Java 25 打印 restricted
  方法警告，功能不受影响；spike 用 `--enable-native-access=ALL-UNNAMED` 消除。
  mod 环境（Minecraft 启动器）暂不影响，记录备查。

### spike 验证记录（2026-08-16，Windows 11 + JDK 25.0.2）

- 同 JVM 双 endpoint（host+client）经真实 iroh 连接建立 QUIC 连接。
- 双向流 `openBi`/`acceptBi`、`writeAll`/`readToEnd` 收发成功。
- `paths()` 输出 `{addr=..., relay=false, selected=true, ip=true}` 确认路径判断。
- 复现：`docs/iroh-spike/`（KtBridge.kt + Spike.java + 编译运行脚本）。

## 其他 Phase 0 结论

- `1.21.6_revprox` 分支：作者实验的"樱花frp SaaS 客户端"封装
  （`revprox/sakurafrp/` 调第三方 API 建隧道），与 P2P 打洞方案无关，不可复用；
  但证明作者考虑过 frp 场景。
- P2PTunnel（流 ⇄ TCP 双向中继）用 `java.net.Socket`/`ServerSocket`，纯 Java，
  无外部依赖，无需 spike。

## 实施阶段

- Phase 0 技术验证（本文件）✅
- Phase 1 依赖与基础设施：三平台 gradle 打包（Fabric jar-in-jar、
  NeoForge/Forge jarJar）、KtBridge 构建、P2PChannel 数据包 + 每加载器注册、
  Config 字段 + GUI 开关 + /p2p 命令 + 语言文件
- Phase 2 打洞与会话：IrohEndpointManager、P2PModule（房主侧生命周期）、
  P2PSession（成员侧）、令牌流程、内网地址跳过
- Phase 3 切换与隧道：P2PTunnel、switch-ready 自动重连、失败回退 frp
- Phase 4 健壮性：多成员并发、清理、超时重试、在线模式实测、文档
- Phase 5-6 移植：26.1/26.3、老版本线按家族

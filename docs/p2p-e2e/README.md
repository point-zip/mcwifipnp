# P2P 端到端验证（无 Minecraft 环境）

`P2PE2ETest.java` 在同 JVM 内跑两个 `P2PManager` 实例（房主 + 成员），通过
假数据包通道桥接（模拟 frp 上的游戏内通道），并走**真实 iroh 连接**验证完整链路：

```
host 开 P2P + 假游戏服务器(echo) → member "加入" → offer/hello/accept
→ member dial → iroh QUIC 经 relay 打洞建立 → openBi + 标记字节
→ host acceptBi 返回 → 桥接假服务器端口 → switch-ready
→ member 本地代理 → 测试客户端连代理 → 数据经隧道往返 echo 一致
```

2026-08-16 在 Windows 11 + JDK 25 实测输出（通过）：

```
[test] SWITCH: reconnect to 127.0.0.1:11549
[test] PASS: tunnel round-trip echoed "hello-through-p2p-tunnel"
[test] DONE (pass)
```

## 关键修复：QUIC 流标记字节

RFC 9000 中客户端打开双向流但**不发任何 STREAM 帧时，对端感知不到新流**
（服务端 `accept_bi` 永久阻塞）。因此成员侧 `openBi` 后立即 `writeAll` 一个
0x00 字节，房主侧 `acceptBi` 返回后先 `read(1)` 消费掉，再启动隧道——
游戏数据流保持干净。

## 运行方法

需要：fabric 工程的编译产物（`build/classes/java/main`、`build/classes/kotlin/main`）
+ gradle 缓存的 iroh/kotlin/jna 依赖 jar。见 `../iroh-spike/README.md` 的依赖清单。

```bash
javac -encoding UTF-8 -cp "<java-main>;<kotlin-main>;<iroh等依赖jar>" -d . P2PE2ETest.java
java --enable-native-access=ALL-UNNAMED -cp ".;<同上>" P2PE2ETest
```

Windows 下 classpath 用分号，jar 路径用 `C:/...` 形式（MSYS 的 `/c/...` JVM 不认）。

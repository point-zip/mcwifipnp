# iroh spike（Phase 0 复现）

验证 `computer.iroh:iroh:1.1.0` 官方绑定在 Windows + Java 25 下：
原生库加载、双端互连、双向流、直连/中继路径判断、纯 Java 可调用。

## 编译运行

```bash
# 下载依赖（Maven Central）
#   computer.iroh:iroh:1.1.0            https://repo1.maven.org/maven2/computer/iroh/iroh/1.1.0/iroh-1.1.0.jar
#   kotlin-stdlib:2.2.21                https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.2.21/kotlin-stdlib-2.2.21.jar
#   kotlinx-coroutines-core-jvm:1.9.0   https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.9.0/kotlinx-coroutines-core-jvm-1.9.0.jar
#   jna:5.15.0                          https://repo1.maven.org/maven2/net/java/dev/jna/jna/5.15.0/jna-5.15.0.jar
#   org.jetbrains:annotations:24.0.0    https://repo1.maven.org/maven2/org/jetbrains/annotations/24.0.0/annotations-24.0.0.jar
#   kotlinc 2.2.21（编译 shim）         https://github.com/JetBrains/kotlin/releases/download/v2.2.21/kotlin-compiler-2.2.21.zip

CP=".;iroh-1.1.0.jar;kotlin-stdlib-2.2.21.jar;kotlinx-coroutines-core-jvm-1.9.0.jar;jna-5.15.0.jar;annotations-24.0.0.jar"

./kotlinc/bin/kotlinc -cp "iroh-1.1.0.jar;kotlin-stdlib-2.2.21.jar;kotlinx-coroutines-core-jvm-1.9.0.jar;annotations-24.0.0.jar" -d . KtBridge.kt
javac -encoding UTF-8 -cp "$CP" Spike.java
java --enable-native-access=ALL-UNNAMED -cp "$CP" Spike
```

## 预期输出要点

```
[spike] host id=755c4c...（32 字节 hex）
[spike] host relay=null           ← 未 online() 时 relay 尚未注册（见设计文档）
[spike] host direct addrs=[...多网卡 IP:端口...]
[spike] CLIENT connected, remoteId=755c4c...
[spike] CLIENT sent: hello from client
[spike] HOST received: hello from client
[spike] CLIENT paths: {addr=198.18.0.1:50321, relay=false, selected=true, ip=true}
[spike] HOST paths: {addr=198.18.0.1:55910, relay=false, selected=true, ip=true}
```

## 关键结论

1. 原生库自包含于 jar（`win32-x86-64/iroh_ffi.dll` 等），JNA 自动提取。
2. suspend/mangled API（`readToEnd-qim9Vi0` 等）纯 Java 无法直接调用，
   必须经 Kotlin shim（KtBridge）桥接。
3. 必须 `EndpointBuilder.applyN0()`（配置 TLS crypto provider），否则
   bind 报 `Missing or incompatible rustls crypto provider`。
4. `PathSnapshot.isRelay()` 精确区分直连/中继路径。
5. Java 25 下 JNA `System.load` 打印 restricted-method 警告（功能不受影响）；
   `--enable-native-access=ALL-UNNAMED` 可消除。

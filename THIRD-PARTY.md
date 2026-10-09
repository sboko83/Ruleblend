# Third-party software and artwork

Third-party components retain their own licenses; Ruleblend's PolyForm license does not
replace them. Full terms and upstream notices are supplied in [licenses/](licenses/).
[NOTICE](NOTICE) contains the required product attributions.

## JVM runtime dependencies

The table records the resolved application runtime, including transitive dependencies.
Versions come from Gradle resolution, not only the version catalog. Each POM is a primary
metadata source; parent POMs supply inherited declarations. JGit's bundled `about.html`
also covers SHA-1 UbcCheck. The MCP SDK 0.15.0 POM says MIT, while its tagged `LICENSE`
describes a transition to Apache-2.0; that complete upstream file is reproduced here.
Skiko's native runtime variant is selected for Windows x64 or macOS arm64/x64.

| Component | Version | Terms | Metadata |
|---|---|---|---|
| `androidx.annotation:annotation-jvm` | `1.9.1` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://dl.google.com/dl/android/maven2/androidx/annotation/annotation-jvm/1.9.1/annotation-jvm-1.9.1.pom) |
| `androidx.arch.core:core-common` | `2.2.0` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://dl.google.com/dl/android/maven2/androidx/arch/core/core-common/2.2.0/core-common-2.2.0.pom) |
| `androidx.collection:collection-jvm` | `1.5.0` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://dl.google.com/dl/android/maven2/androidx/collection/collection-jvm/1.5.0/collection-jvm-1.5.0.pom) |
| `androidx.compose.runtime:runtime-annotation-jvm` | `1.11.2` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://dl.google.com/dl/android/maven2/androidx/compose/runtime/runtime-annotation-jvm/1.11.2/runtime-annotation-jvm-1.11.2.pom) |
| `androidx.compose.runtime:runtime-desktop` | `1.11.2` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://dl.google.com/dl/android/maven2/androidx/compose/runtime/runtime-desktop/1.11.2/runtime-desktop-1.11.2.pom) |
| `androidx.compose.runtime:runtime-retain-desktop` | `1.11.2` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://dl.google.com/dl/android/maven2/androidx/compose/runtime/runtime-retain-desktop/1.11.2/runtime-retain-desktop-1.11.2.pom) |
| `androidx.compose.runtime:runtime-saveable-desktop` | `1.11.2` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://dl.google.com/dl/android/maven2/androidx/compose/runtime/runtime-saveable-desktop/1.11.2/runtime-saveable-desktop-1.11.2.pom) |
| `androidx.lifecycle:lifecycle-common-jvm` | `2.9.4` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-common-jvm/2.9.4/lifecycle-common-jvm-2.9.4.pom) |
| `androidx.lifecycle:lifecycle-runtime-compose-desktop` | `2.9.4` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime-compose-desktop/2.9.4/lifecycle-runtime-compose-desktop-2.9.4.pom) |
| `androidx.lifecycle:lifecycle-runtime-desktop` | `2.9.4` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime-desktop/2.9.4/lifecycle-runtime-desktop-2.9.4.pom) |
| `androidx.lifecycle:lifecycle-viewmodel-desktop` | `2.9.4` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-viewmodel-desktop/2.9.4/lifecycle-viewmodel-desktop-2.9.4.pom) |
| `androidx.lifecycle:lifecycle-viewmodel-savedstate-desktop` | `2.9.4` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-viewmodel-savedstate-desktop/2.9.4/lifecycle-viewmodel-savedstate-desktop-2.9.4.pom) |
| `androidx.navigationevent:navigationevent-desktop` | `1.0.1` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://dl.google.com/dl/android/maven2/androidx/navigationevent/navigationevent-desktop/1.0.1/navigationevent-desktop-1.0.1.pom) |
| `androidx.savedstate:savedstate-compose-desktop` | `1.4.0` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://dl.google.com/dl/android/maven2/androidx/savedstate/savedstate-compose-desktop/1.4.0/savedstate-compose-desktop-1.4.0.pom) |
| `androidx.savedstate:savedstate-desktop` | `1.4.0` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://dl.google.com/dl/android/maven2/androidx/savedstate/savedstate-desktop/1.4.0/savedstate-desktop-1.4.0.pom) |
| `com.charleskorn.kaml:kaml-jvm` | `0.104.0` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/com/charleskorn/kaml/kaml-jvm/0.104.0/kaml-jvm-0.104.0.pom) |
| `com.googlecode.javaewah:JavaEWAH` | `1.2.3` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/com/googlecode/javaewah/JavaEWAH/1.2.3/JavaEWAH-1.2.3.pom) |
| `com.squareup.okio:okio-jvm` | `3.16.4` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/com/squareup/okio/okio-jvm/3.16.4/okio-jvm-3.16.4.pom) |
| `com.typesafe:config` | `1.4.9` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/com/typesafe/config/1.4.9/config-1.4.9.pom) |
| `commons-codec:commons-codec` | `1.22.0` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/commons-codec/commons-codec/1.22.0/commons-codec-1.22.0.pom) |
| `io.github.oshai:kotlin-logging-jvm` | `8.0.4` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/io/github/oshai/kotlin-logging-jvm/8.0.4/kotlin-logging-jvm-8.0.4.pom) |
| `io.ktor:ktor-events-jvm` | `3.5.1` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/io/ktor/ktor-events-jvm/3.5.1/ktor-events-jvm-3.5.1.pom) |
| `io.ktor:ktor-http-cio-jvm` | `3.5.1` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/io/ktor/ktor-http-cio-jvm/3.5.1/ktor-http-cio-jvm-3.5.1.pom) |
| `io.ktor:ktor-http-jvm` | `3.5.1` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/io/ktor/ktor-http-jvm/3.5.1/ktor-http-jvm-3.5.1.pom) |
| `io.ktor:ktor-io-jvm` | `3.5.1` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/io/ktor/ktor-io-jvm/3.5.1/ktor-io-jvm-3.5.1.pom) |
| `io.ktor:ktor-network-jvm` | `3.5.1` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/io/ktor/ktor-network-jvm/3.5.1/ktor-network-jvm-3.5.1.pom) |
| `io.ktor:ktor-serialization-jvm` | `3.5.1` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/io/ktor/ktor-serialization-jvm/3.5.1/ktor-serialization-jvm-3.5.1.pom) |
| `io.ktor:ktor-serialization-kotlinx-json-jvm` | `3.5.1` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/io/ktor/ktor-serialization-kotlinx-json-jvm/3.5.1/ktor-serialization-kotlinx-json-jvm-3.5.1.pom) |
| `io.ktor:ktor-serialization-kotlinx-jvm` | `3.5.1` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/io/ktor/ktor-serialization-kotlinx-jvm/3.5.1/ktor-serialization-kotlinx-jvm-3.5.1.pom) |
| `io.ktor:ktor-server-content-negotiation-jvm` | `3.5.1` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/io/ktor/ktor-server-content-negotiation-jvm/3.5.1/ktor-server-content-negotiation-jvm-3.5.1.pom) |
| `io.ktor:ktor-server-core-jvm` | `3.5.1` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/io/ktor/ktor-server-core-jvm/3.5.1/ktor-server-core-jvm-3.5.1.pom) |
| `io.ktor:ktor-server-sse-jvm` | `3.5.1` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/io/ktor/ktor-server-sse-jvm/3.5.1/ktor-server-sse-jvm-3.5.1.pom) |
| `io.ktor:ktor-server-websockets-jvm` | `3.5.1` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/io/ktor/ktor-server-websockets-jvm/3.5.1/ktor-server-websockets-jvm-3.5.1.pom) |
| `io.ktor:ktor-sse-jvm` | `3.5.1` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/io/ktor/ktor-sse-jvm/3.5.1/ktor-sse-jvm-3.5.1.pom) |
| `io.ktor:ktor-utils-jvm` | `3.5.1` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/io/ktor/ktor-utils-jvm/3.5.1/ktor-utils-jvm-3.5.1.pom) |
| `io.ktor:ktor-websocket-serialization-jvm` | `3.5.1` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/io/ktor/ktor-websocket-serialization-jvm/3.5.1/ktor-websocket-serialization-jvm-3.5.1.pom) |
| `io.ktor:ktor-websockets-jvm` | `3.5.1` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/io/ktor/ktor-websockets-jvm/3.5.1/ktor-websockets-jvm-3.5.1.pom) |
| `io.modelcontextprotocol:kotlin-sdk-core-jvm` | `0.15.0` | [Apache-2.0 / MIT transition](licenses/MCP-SDK.txt) | [POM](https://repo.maven.apache.org/maven2/io/modelcontextprotocol/kotlin-sdk-core-jvm/0.15.0/kotlin-sdk-core-jvm-0.15.0.pom) |
| `io.modelcontextprotocol:kotlin-sdk-server-jvm` | `0.15.0` | [Apache-2.0 / MIT transition](licenses/MCP-SDK.txt) | [POM](https://repo.maven.apache.org/maven2/io/modelcontextprotocol/kotlin-sdk-server-jvm/0.15.0/kotlin-sdk-server-jvm-0.15.0.pom) |
| `it.krzeminski:snakeyaml-engine-kmp-jvm` | `4.0.1` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/it/krzeminski/snakeyaml-engine-kmp-jvm/4.0.1/snakeyaml-engine-kmp-jvm-4.0.1.pom) |
| `net.thauvin.erik.urlencoder:urlencoder-lib-jvm` | `1.6.0` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/net/thauvin/erik/urlencoder/urlencoder-lib-jvm/1.6.0/urlencoder-lib-jvm-1.6.0.pom) |
| `org.eclipse.jgit:org.eclipse.jgit` | `7.7.1.202607240634-r` | [EDL-1.0 / MIT (SHA-1 UbcCheck)](licenses/JGit-about.html) | [POM](https://repo.maven.apache.org/maven2/org/eclipse/jgit/org.eclipse.jgit/7.7.1.202607240634-r/org.eclipse.jgit-7.7.1.202607240634-r.pom) |
| `org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose-desktop` | `2.9.6` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/androidx/lifecycle/lifecycle-runtime-compose-desktop/2.9.6/lifecycle-runtime-compose-desktop-2.9.6.pom) |
| `org.jetbrains.androidx.savedstate:savedstate-compose-desktop` | `1.3.6` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/androidx/savedstate/savedstate-compose-desktop/1.3.6/savedstate-compose-desktop-1.3.6.pom) |
| `org.jetbrains.compose.animation:animation-core-desktop` | `1.11.1` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/compose/animation/animation-core-desktop/1.11.1/animation-core-desktop-1.11.1.pom) |
| `org.jetbrains.compose.animation:animation-desktop` | `1.11.1` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/compose/animation/animation-desktop/1.11.1/animation-desktop-1.11.1.pom) |
| `org.jetbrains.compose.components:components-resources-desktop` | `1.11.1` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/compose/components/components-resources-desktop/1.11.1/components-resources-desktop-1.11.1.pom) |
| `org.jetbrains.compose.desktop:desktop-jvm` | `1.11.1` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/compose/desktop/desktop-jvm/1.11.1/desktop-jvm-1.11.1.pom) |
| `org.jetbrains.compose.foundation:foundation-desktop` | `1.11.1` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/compose/foundation/foundation-desktop/1.11.1/foundation-desktop-1.11.1.pom) |
| `org.jetbrains.compose.foundation:foundation-layout-desktop` | `1.11.1` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/compose/foundation/foundation-layout-desktop/1.11.1/foundation-layout-desktop-1.11.1.pom) |
| `org.jetbrains.compose.material3:material3-desktop` | `1.9.0` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/compose/material3/material3-desktop/1.9.0/material3-desktop-1.9.0.pom) |
| `org.jetbrains.compose.material:material-desktop` | `1.11.1` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/compose/material/material-desktop/1.11.1/material-desktop-1.11.1.pom) |
| `org.jetbrains.compose.material:material-ripple-desktop` | `1.11.1` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/compose/material/material-ripple-desktop/1.11.1/material-ripple-desktop-1.11.1.pom) |
| `org.jetbrains.compose.runtime:runtime-desktop` | `1.11.1` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/compose/runtime/runtime-desktop/1.11.1/runtime-desktop-1.11.1.pom) |
| `org.jetbrains.compose.runtime:runtime-saveable-desktop` | `1.11.1` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/compose/runtime/runtime-saveable-desktop/1.11.1/runtime-saveable-desktop-1.11.1.pom) |
| `org.jetbrains.compose.ui:ui-backhandler-desktop` | `1.11.1` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/compose/ui/ui-backhandler-desktop/1.11.1/ui-backhandler-desktop-1.11.1.pom) |
| `org.jetbrains.compose.ui:ui-desktop` | `1.11.1` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/compose/ui/ui-desktop/1.11.1/ui-desktop-1.11.1.pom) |
| `org.jetbrains.compose.ui:ui-geometry-desktop` | `1.11.1` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/compose/ui/ui-geometry-desktop/1.11.1/ui-geometry-desktop-1.11.1.pom) |
| `org.jetbrains.compose.ui:ui-graphics-desktop` | `1.11.1` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/compose/ui/ui-graphics-desktop/1.11.1/ui-graphics-desktop-1.11.1.pom) |
| `org.jetbrains.compose.ui:ui-text-desktop` | `1.11.1` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/compose/ui/ui-text-desktop/1.11.1/ui-text-desktop-1.11.1.pom) |
| `org.jetbrains.compose.ui:ui-tooling-preview-desktop` | `1.11.1` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/compose/ui/ui-tooling-preview-desktop/1.11.1/ui-tooling-preview-desktop-1.11.1.pom) |
| `org.jetbrains.compose.ui:ui-unit-desktop` | `1.11.1` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/compose/ui/ui-unit-desktop/1.11.1/ui-unit-desktop-1.11.1.pom) |
| `org.jetbrains.compose.ui:ui-util-desktop` | `1.11.1` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/compose/ui/ui-util-desktop/1.11.1/ui-util-desktop-1.11.1.pom) |
| `org.jetbrains.kotlin:kotlin-reflect` | `2.3.21` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-reflect/2.3.21/kotlin-reflect-2.3.21.pom) |
| `org.jetbrains.kotlin:kotlin-stdlib` | `2.4.10` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.4.10/kotlin-stdlib-2.4.10.pom) |
| `org.jetbrains.kotlinx:atomicfu-jvm` | `0.28.0` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/atomicfu-jvm/0.28.0/atomicfu-jvm-0.28.0.pom) |
| `org.jetbrains.kotlinx:kotlinx-collections-immutable-jvm` | `0.5.1` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-collections-immutable-jvm/0.5.1/kotlinx-collections-immutable-jvm-0.5.1.pom) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm` | `1.11.0` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.11.0/kotlinx-coroutines-core-jvm-1.11.0.pom) |
| `org.jetbrains.kotlinx:kotlinx-datetime-jvm` | `0.7.1` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-datetime-jvm/0.7.1/kotlinx-datetime-jvm-0.7.1.pom) |
| `org.jetbrains.kotlinx:kotlinx-io-bytestring-jvm` | `0.9.1` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-io-bytestring-jvm/0.9.1/kotlinx-io-bytestring-jvm-0.9.1.pom) |
| `org.jetbrains.kotlinx:kotlinx-io-core-jvm` | `0.9.1` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-io-core-jvm/0.9.1/kotlinx-io-core-jvm-0.9.1.pom) |
| `org.jetbrains.kotlinx:kotlinx-serialization-core-jvm` | `1.11.0` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-core-jvm/1.11.0/kotlinx-serialization-core-jvm-1.11.0.pom) |
| `org.jetbrains.kotlinx:kotlinx-serialization-json-io-jvm` | `1.11.0` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-json-io-jvm/1.11.0/kotlinx-serialization-json-io-jvm-1.11.0.pom) |
| `org.jetbrains.kotlinx:kotlinx-serialization-json-jvm` | `1.11.0` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-json-jvm/1.11.0/kotlinx-serialization-json-jvm-1.11.0.pom) |
| `org.jetbrains.runtime:jbr-api` | `1.9.0` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/runtime/jbr-api/1.9.0/jbr-api-1.9.0.pom) |
| `org.jetbrains.skiko:skiko-awt-runtime-macos-arm64` | `0.144.6` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/skiko/skiko-awt-runtime-macos-arm64/0.144.6/skiko-awt-runtime-macos-arm64-0.144.6.pom) |
| `org.jetbrains.skiko:skiko-awt-runtime-macos-x64` | `0.144.6` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/skiko/skiko-awt-runtime-macos-x64/0.144.6/skiko-awt-runtime-macos-x64-0.144.6.pom) |
| `org.jetbrains.skiko:skiko-awt-runtime-windows-x64` | `0.144.6` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/skiko/skiko-awt-runtime-windows-x64/0.144.6/skiko-awt-runtime-windows-x64-0.144.6.pom) |
| `org.jetbrains.skiko:skiko-awt` | `0.144.6` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/skiko/skiko-awt/0.144.6/skiko-awt-0.144.6.pom) |
| `org.jetbrains:annotations` | `23.0.0` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/annotations/23.0.0/annotations-23.0.0.pom) |
| `org.jspecify:jspecify` | `1.0.0` | [Apache-2.0](licenses/Apache-2.0.txt) | [POM](https://repo.maven.apache.org/maven2/org/jspecify/jspecify/1.0.0/jspecify-1.0.0.pom) |
| `org.slf4j:slf4j-api` | `2.0.18` | [MIT](licenses/SLF4J.txt) | [POM](https://repo.maven.apache.org/maven2/org/slf4j/slf4j-api/2.0.18/slf4j-api-2.0.18.pom) |
| `org.slf4j:slf4j-nop` | `2.0.18` | [MIT](licenses/SLF4J.txt) | [POM](https://repo.maven.apache.org/maven2/org/slf4j/slf4j-nop/2.0.18/slf4j-nop-2.0.18.pom) |

## Native graphics and codecs

Skiko 0.144.6 uses [JetBrains Skia m144-22f58c9fd4](https://github.com/JetBrains/skia/releases/tag/m144-22f58c9fd4).
The upstream binary archive supplies notices for FreeType, HarfBuzz, libpng, libwebp, zlib,
ANGLE, SwiftShader and Vulkan headers. These are retained, including notices for optional
backends; their presence does not mean every backend is active. Additional texts for ICU,
Brotli, Expat, libjpeg-turbo, Highway, Wuffs and D3D12 Memory Allocator come from the exact
revisions in that Skia tag's `DEPS`. Skia wrapper notices are retained as well.
See [Skia's license](licenses/Skia.txt) and [the native license tree](licenses/skia/).

This software is based in part on the work of the Independent JPEG Group.
FreeType is used under the FTL alternative; the upstream archive also includes its GPL alternative.

## Java and system frameworks

Native packages include a trimmed Java runtime. Its own `runtime/legal/` directory is
retained (inside `Contents/runtime/Contents/Home/` on macOS), with GPL v2, the Classpath
Exception and component notices. Runtime vendor/version and terms must be checked in each
release image; the Gradle daemon toolchain selects the packaging JDK. Its vendor `NOTICE`
and full `release` metadata are copied into `resources/legal/java-runtime/`.
The verified Windows runtime is Eclipse Temurin 21.0.7+6; its complete upstream source is
[OpenJDK21U-jdk-sources_21.0.7_6.tar.gz](https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.7%2B6/OpenJDK21U-jdk-sources_21.0.7_6.tar.gz).
Corresponding source distribution is a [release requirement](docs/RELEASING.md#alpha-artifacts).
The macOS translation helper has no Swift package dependencies and calls Apple's system
Translation framework; system frameworks are not redistributed by Ruleblend.

## Artwork and build tooling

The UI SVGs include adapted Lucide/Feather artwork under the ISC/MIT terms reproduced
in [Lucide.txt](licenses/Lucide.txt). Project modifications are described in [NOTICE](NOTICE).
The application logo was generated for Ruleblend with ImageGen, as confirmed by the
project owner. Its shipped PNG/ICNS/ICO variants and project-specific drawings use [LICENSE](LICENSE).
No third-party font files are bundled as application resources; platform fonts come from
the operating system and the Java runtime.

Gradle's wrapper scripts and wrapper JAR are distributed in the source tree under
[Apache-2.0](licenses/Apache-2.0.txt); they are not part of the app runtime.

## Maintenance

Upstream text origins and SHA-256 digests are recorded in [licenses/SOURCES.json](licenses/SOURCES.json).
To check dependency coverage and notice integrity before packaging:

```sh
./gradlew :app:writeRuntimeDependencyManifest
python -B tools/check_licenses.py app/build/reports/runtime-dependencies.txt
```

Update this inventory and the relevant upstream notices when a dependency or asset changes.
Keep all notices already embedded in dependency JARs and the Java runtime.

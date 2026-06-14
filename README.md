# exportReport

一个基于 Groovy + Gradle 的云房接口测试和批量处理工具。

当前程序入口是 `com.wf.YFTool`，启动后会打开 Swing 图形界面，支持接口测试、SM4 加密请求、CSV 批量调用等功能。

## 环境要求

- JDK 21
- Gradle Wrapper 已包含在项目中，优先使用 `./gradlew`

## 项目结构

```text
src/main/groovy/com/wf/
  YFTool.groovy     # GUI 主程序入口
  Sm4Util.groovy    # SM4 加解密工具
  Main.groovy       # CSV 估值/报告相关逻辑

apis.txt            # 接口列表配置
settings.txt        # 本地配置
build.gradle        # Gradle 构建配置
```

## 构建

生成可直接运行的 fat jar：

```bash
./gradlew clean build
```

构建完成后，jar 文件在：

```text
build/libs/exportReport-1.0-SNAPSHOT.jar
```

## 运行

```bash
java -jar build/libs/exportReport-1.0-SNAPSHOT.jar
```

如果已经把 jar 拷贝到项目根目录，也可以运行：

```bash
java -jar exportReport-1.0-SNAPSHOT.jar
```

## 常用说明

- `apis.txt` 用来维护接口地址和请求模板。
- `settings.txt` 用来保存工具配置。
- 批量调用会读取 CSV 文件，并把结果输出到配置的输出目录。
- fat jar 已包含 Groovy、groovy-json、BouncyCastle 等运行依赖。

## 清理构建产物

```bash
./gradlew clean
```

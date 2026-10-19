# jcompose

[![许可证](https://img.shields.io/github/license/GaTTGeng/jcompose.svg)](LICENSE)

[English](README.md)

**jcompose** 是面向 JDK 应用的库，用于加载 Docker Compose 项目，并通过 Docker Engine API 管理项目资源。项目上下文由调用方显式传入；jcompose 在进程内读取 Compose 配置，并使用熟悉的 `com.docker.compose.*` 标签标记所管理的资源。

jcompose 的兼容目标是 Docker Compose。[ComposeSharp](https://github.com/GaTTGeng/ComposeSharp) 是同一作者维护的姊妹项目，也是功能与文档同步来源：先在 ComposeSharp 开发，再把相应工作移植到这里。ComposeSharp 的 API 和实现不决定兼容行为；Docker Compose 文档及可观察到的行为才是兼容依据。jcompose 不是 `docker compose` 可执行文件的包装器，也不声称完整兼容 Docker Compose CLI 或 Compose Specification。

## 项目结构

```text
compose.yml + .env + env_file
              │
              ▼
       jcompose-loader
       Java 项目模型与校验
              │
              ▼
       jcompose-engine
       Docker Engine 资源与 Compose 标签
              │
              ▼
       应用中的 IComposeService
```

资源归属通过标签识别。项目上下文携带项目名、工作目录、Compose 文件、profiles、镜像仓库凭据和 Docker 连接配置。各项操作依靠这些边界查找项目资源，避免扫描或删除无关的 Docker 资源。

## 模块

| 模块 | 职责 |
| --- | --- |
| `jcompose-api` | Java 项目模型、操作选项和结果、回调及异步服务契约 |
| `jcompose-loader` | Compose YAML 加载、变量插值、dotenv 和 `env_file` 处理、校验及多文件合并 |
| `jcompose-engine` | Docker Engine 集成和项目范围内的操作 |
| `jcompose-spring-boot-starter` | 为 `IComposeService`、`JComposeContextFactory` 和 `jcompose.*` 默认值提供 Spring Boot 自动配置 |

公开 Java API 使用 `CompletableFuture` 表示有限操作，使用 `Flow.Publisher` 表示事件和 watch 流。项目目标为 JDK 21 或更新版本。

Maven 编译目标为 Java 21 字节码。当前本地核验使用 JDK 21.0.12 和 Maven 3.9.16；最低 Maven 版本和受支持的 Docker Engine 版本尚未通过兼容性矩阵确认。

## 开始使用

在仓库检出目录构建 Maven 多模块项目：

```powershell
mvn verify
```

应用可以直接创建 Engine 服务，并显式提供项目上下文：

```java
import io.github.gattgeng.jcompose.api.ComposeProjectContext;
import io.github.gattgeng.jcompose.engine.ComposeService;
import java.nio.file.Path;

var compose = new ComposeService();
var project = new ComposeProjectContext("inventory-dev", Path.of("C:/src/inventory"));

var config = compose.loadProject(project).join();
System.out.println(config.services().keySet());
```

API 和 Engine 模块仍在开发中。示例展示配置加载入口；使用任何运行时操作或字段前，请先查看对应操作契约和支持矩阵。

### Spring Boot

在 Spring Boot 3.5 应用中添加 `jcompose-spring-boot-starter`，starter 会注册 `IComposeService` 和 `JComposeContextFactory` Bean：

```java
@Service
class InventoryCompose {
    private final IComposeService compose;
    private final JComposeContextFactory contexts;

    InventoryCompose(IComposeService compose, JComposeContextFactory contexts) {
        this.compose = compose;
        this.contexts = contexts;
    }

    void load(Path workingDirectory) {
        var project = contexts.create("inventory-dev", workingDirectory);
        compose.loadProject(project).join();
    }
}
```

可通过 `jcompose.default-project-name` 和 `jcompose.default-socket-path` 配置默认值。Socket 路径使用 docker-java 支持的 Docker endpoint 格式；未设置时使用 docker-java 的自动发现。工厂优先读取 Docker Compose 的标准文件名 `compose.yaml`；该文件不存在时会回退到其他惯用 Compose 文件名。调用方也可指定 Compose 文件和 profiles。

## Loader 支持范围

Loader 会读取 Compose YAML、`.env` 和服务的 `env_file` 输入，执行变量插值并生成项目/服务模型。当前实现是增量支持子集，并非完整 Compose Specification 解析器。对于尚无完整强类型 Java 表示或 Docker Engine 行为的字段，会保留原始服务映射。

### 变量插值和环境文件

Loader 支持 `$NAME`、`${NAME}`、`$$` 以及 `:-`、`-`、`:?`、`?`、`:+`、`+` 插值操作符；插值只处理 YAML 标量值，不处理键、注释、单引号值和块标量。项目 `.env` 支持 `=`/`:` 分隔符、单双引号、单引号多行值、常用双引号转义和变量引用；进程环境变量优先于 `.env`。服务的 `env_file` 值会进入该服务的容器环境，但不会作为 Compose YAML 的插值来源。内联 `environment` 值优先于这些文件中的值。

`ComposeFileMerger.mergeWithEnv` 仅在本次合并中使用显式环境变量覆盖，优先级高于进程环境和 `.env`；它不会修改进程环境，因此并发合并互不影响。

详细优先级、操作符行为和当前限制见 [Loader 兼容性](docs/loader-compatibility.md)。多文件输入见[合并规则](docs/merge-semantics.md)。这里记录的是 jcompose 当前行为；每项语义都应对照 Docker Compose 验证，不能仅因 ComposeSharp 也有类似逻辑就视为兼容。

服务 `extends` 支持引用同一文件或外部文件中的服务、递归继承和 Compose 对应的合并行为，并校验 `healthcheck.disable` 的继承约束。外部继承服务的 build、env_file 和 bind 路径会按项目 Compose 文件重新定位；依赖服务和顶层资源仍需由当前项目声明。具体边界见 [Loader 兼容性](docs/loader-compatibility.md)。

本地 `include` 支持递归导入服务、网络、卷、secrets 和 configs；它在多文件覆盖合并之后处理，重名会报错而不会合并。被包含项目中的相对 build、env_file、bind mount、config 和 secret 文件路径会保留其原始项目位置。远程 OCI/Git 来源及完整 Compose include 规则尚未支持。具体行为和限制见 [Loader 兼容性](docs/loader-compatibility.md) 和[多文件合并规则](docs/merge-semantics.md)。

## Engine 当前实现

jcompose 以 ComposeSharp 的功能范围作为同步路线，并为每一项记录边界和状态；兼容目标始终是 Docker Compose。当前快照包含基础服务协调、一次性 `run`、主机与容器间 `copy`、副本伸缩、容器退出等待、镜像构建、容器文件系统导出和容器提交为镜像等部分能力；这些操作有明确限制，不能等同于完整 Docker Compose 行为。

| 范围 | 当前边界 |
| --- | --- |
| 项目生命周期 | 部分 start、stop、restart、pause、unpause、kill、remove、down、up、create 和 scale 操作可处理带项目标签的资源或协调服务。 |
| 检查与查询 | `ps` 和 `list` 查询带项目标签的容器；`top`、`images`、`port` 和 `volumes` 提供有限的 Docker 检查能力。`logsAsync` 会把容器输出流式传给 `ILogConsumer`。仅声明 API 方法或选项类型不代表功能已完整实现。 |
| 配置生成 | `generateAsync` 从 Loader 保留的配置渲染规范化 YAML 快照；不连接 Docker，也不写文件。 |
| 服务编排 | 已实现基础服务协调、一次性 `run`、主机与容器间 `copy`、副本伸缩、容器退出等待、部分依赖条件、健康检查、网络/卷资源定义、项目网络附件、DNS 设置、CPU/内存限制、镜像构建与推送、`exec`/`attach`、构建目录变更流、文件系统导出和容器提交等子集；完整 Compose 生命周期行为仍在开发中。 |

[Docker Compose 字段支持矩阵](docs/compose-field-matrix.md)是字段和操作状态的准确信息源。例如，`volumesAsync` 会列出带项目标签的 Docker 卷，并根据显式服务筛选关联到该服务的运行中或已停止容器所挂载的卷。[路线图](docs/roadmap.md)记录完整的 3.0.0 同步范围和发布条件。

## Docker Compose 兼容性

jcompose 的兼容目标是在本仓库明确记录的支持子集内，遵循 Docker Compose 文件加载和项目行为。ComposeSharp 3.0.0 基线及其后续开发决定同步哪些功能；是否符合兼容性，则由 Docker Compose 文档和可观察到的 Docker Compose 行为决定。

提交兼容性问题时，请提供最小 Compose 文件、Docker Compose 的预期行为、jcompose 的实际行为、jcompose commit，以及 Docker Engine/JDK 版本。行为变更应同步更新字段矩阵和相关操作契约。不能把相同的方法名、模型字段或 ComposeSharp 的实现当作兼容性证据。

## 文档

- [Docker Compose 字段支持矩阵](docs/compose-field-matrix.md)
- [Loader 兼容性](docs/loader-compatibility.md)
- [多文件合并规则](docs/merge-semantics.md)
- [Generate 和 Watch 契约](docs/generate-watch.md)
- [路线图和完成条件](docs/roadmap.md)
- [维护者发布指南](docs/maintainer-release.md)
- [贡献指南](CONTRIBUTING.md)
- [贡献者行为准则](CODE_OF_CONDUCT.md)
- [支持渠道](SUPPORT.md)
- [安全政策](SECURITY.md)
- [变更日志](CHANGELOG.md)

## 发布状态

当前仓库版本为 `3.0.0-SNAPSHOT`。这并不代表 3.0.0 移植工作已经完成，目前也没有发布 JDK 制品。首次 JDK 发布明确放在最后：功能范围、兼容性证据、仓库配置、CI 和发布内容全部完成并审阅后，才进入发布步骤。详见[维护者发布指南](docs/maintainer-release.md)。

## 许可证

MIT，详见 [LICENSE](LICENSE)。

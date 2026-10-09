

# DBSidekick

> 本地化、多数据源、自然语言 → SQL 的桌面查询助手

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17-orange.svg)]()
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-brightgreen.svg)]()
[![Vue](https://img.shields.io/badge/Vue-3.5-42b883.svg)]()

**DBSidekick** 让你用中文直接查询数据库。问"每个部门有多少人"，它自动检索表结构、生成 SQL、执行并返回结果。

所有数据留在本机——Schema 向量化走本地 ONNX 模型，对话走你自己的 DeepSeek Key，SQLite 存配置和会话。**不依赖任何云端服务，不需要部署。**

---



## 为什么做它

现有 Text2SQL 工具大多在服务端跑，有三个问题：

- **数据出内网**：Schema 甚至样本数据要上传到云端 LLM
- **部署复杂**：要跑 Web + DB + 向量库一堆服务
- **SQL 不安全**：LLM 生成的 SQL 可能误删数据

DBSidekick 的解法：

- **桌面客户端**：JavaFX + JCEF 打包成 exe，双击即用
- **多数据源热插拔**：MySQL / PostgreSQL 动态接入
- **本地向量检索**：Schema 用本地 ONNX（BGE-zh 768）向量化，存 Milvus
- **SQL 双层门禁**：LLM 生成后经 JSqlParser AST 级校验，只读 + 强制 LIMIT + 禁多语句

---



## 核心特性

### 检索准

- **Schema 卡片语义化**：表卡片带别名、用途、关联、关键字段
- **向量 + 稀疏混合检索**：RRF 融合
- **Query Rewrite**：LLM 改写问题，跨越"人 vs 用户"这类同义词
- **LLM Rerank**：精排 Top5，宁少勿多
- **Few-shot 示例库**：历史成功 SQL 自动复用
- **自学习关系**：从 JOIN 历史反推外键，越用越准

### 执行安全

- **JSqlParser AST 级校验**：只允许 SELECT
- **只读事务 + 强制 LIMIT + 超时**
- **脚本模式**：复杂查询用临时表分步执行，连接关闭自动清理
- **结果集截断**：最多 1000 行
- **查询审计日志**

### 体验好

- **SSE 流式推理**：实时看到「理解 → 检索 → 生成 SQL → 校验 → 执行」每一步
- **多标签页 + 会话持久化 + 历史回放**
- **查询编辑器**（Monaco Editor）+ AI 优化 / 解释 SQL
- **SQL 脚本库**（分类 / 收藏 / 版本）

### 多数据源

- MySQL / PostgreSQL 动态接入
- 一键初始化（同步 → 推断外键 → 生成别名 → 增强注释 → 向量化）
- 增量同步（基于 schema_hash，只处理变化的表）



## 快速开始

### 下载 Windows 版

预编译包不放进源码仓库。从 [Releases](https://github.com/Desktop-code/DBSidekick/releases) 下载 `DBSidekick-windows.zip`，解压后进入目录，双击 `DBSidekick.exe`。

[直接下载最新版](https://github.com/Desktop-code/DBSidekick/releases/latest/download/DBSidekick-windows.zip)

解压后应能看到这三个部分，缺一不可：

```text
DBSidekick/
  DBSidekick.exe
  app/
  runtime/
```

不要只拷贝 `DBSidekick.exe`。`app` 是程序，`runtime` 是自带的 JDK。

**依赖服务**：
- **Milvus**（`localhost:19530`）：Schema 向量检索
- **DeepSeek API Key**：环境变量 `DEEPSEEK_API_KEY` 或界面里配置
- **ONNX BGE 模型**（可选）：首次启动时选择模型目录

### 从源码构建

前置：JDK 17、Node 18+、Maven 3.8+。`JAVA_HOME` 指向 JDK 17；Maven 不在 PATH 时再设置 `MVN_CMD`。

```bat
git clone https://github.com/Desktop-code/DBSidekick.git
cd DBSidekick
set JAVA_HOME=C:\path\to\jdk-17
dbsidekick-desktop\build-exe.bat
```

脚本会用当前源码重新构建前端、打进后端，再生成 exe。产物在 `dbsidekick-desktop\dist\DBSidekick\DBSidekick.exe`。

发布给别人时，把整个 `dist\DBSidekick` 目录打成 zip，作为 Release 附件上传，文件名用 `DBSidekick-windows.zip`。上面的直接下载链接依赖这个文件名。



## 技术栈

| 层        | 技术                                                |
| :-------- | :-------------------------------------------------- |
| 桌面外壳  | JavaFX + JCEF + jpackage                            |
| 前端      | Vue 3 + Vite + Element Plus + Pinia + Monaco Editor |
| 后端      | Spring Boot 3.2 + HikariCP + JSqlParser + SQLite    |
| LLM       | DeepSeek（OpenAI 兼容接口）                         |
| Embedding | ONNX BGE-base-zh（768 维）/ Ollama 兜底             |
| 向量库    | Milvus                                              |



## 架构

详细架构文档：[系统架构说明.md](系统架构说明.md)

------



## 使用流程

1. 双击 exe，进入主界面
2. 左侧 [+ 新建连接] → 填 MySQL/PG 连接信息 → 测试 → 保存
3. 右键数据源 → [一键初始化]
4. 输入自然语言问题，如「每个部门有哪些人」
5. 观察流式推理 → SQL 生成 → 结果表格

------



## 项目状态

- ✅ 核心链路完整：提问 → 检索 → 生成 → 校验 → 执行 → 展示
- ✅ 桌面 exe 可运行
- 🚧 规划中：ER 图 / 智能评估框架 / 术语表
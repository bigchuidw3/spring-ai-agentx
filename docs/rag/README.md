# AgentX-RAG 模块

AgentX-RAG 是构建在 Spring AI 之上的 RAG Harness，提供「文档解析 → 分块 → 存储 → 查询增强 → 检索 → 编排」六大能力域。底层复用 Spring AI 的 Document / VectorStore / EmbeddingModel / ChatModel 等原生 SPI，框架只做编排与算法封装，不重复造底层脚手架。

调用方只需「选组件 + 传依赖」，按固定顺序驱动执行。

## 1. 功能总览

| 能力域 | 组件 | 一句话定位 |
|--------|------|-----------|
| 文档解析 | TikaDocumentParser / MineruDocumentParser | 把原始文件解析成结构化文本块 |
| 文档分块 | SlidingWindow / Recursive / Heading 切块器 | 把文本切成可检索的块 |
| 存储 | RagDocumentStore + VectorStore + DocumentStore | 按 chunk 角色路由写入向量库与父块库 |
| 查询增强 | compression / rewrite / hyde / expand | 检索前把问题改造成更适合检索的形态 |
| 检索 | EnhancedRetriever + Reranker | 向量/父子召回 + 重排精排 |
| 编排 | RagPipeline / RagRetrievalTool | 串行 RAG 与 Agentic RAG 两种接入方式 |

## 2. 什么场景用什么组件

### 解析器

| 场景 | 解析器 |
|------|--------|
| txt / md / html / csv / doc / docx / xls / xlsx 等简单文本 | TikaDocumentParser |
| 表格 + 图片 + 文本混排的复杂 PDF / 扫描件 | MineruDocumentParser |

MinerU 目前支持调用在线服务（每天有大量免费额度），后续可私有化部署 MinerU 服务。

### 切块器

| 场景 | 切块器 | 建议搭配 |
|------|--------|---------|
| 文档结构不清晰、通用文本 | SlidingWindowSplitter | 向量检索 |
| QA 文档、有明确段落/分隔符 | RecursiveSplitter | 向量检索 |
| 结构化文档（手册/说明书/制度） | HeadingSplitter（父子分块） | 父子检索 |

### 存储

| 场景 | 向量库 | 父块库 |
|------|--------|--------|
| 需要任意字段过滤、metadata 整包回读 | PgVectorStore | JdbcDocumentStore / RedisDocumentStore |
| 已有 Redis Stack、追求读写性能 | RedisVectorStore | RedisDocumentStore |

### 查询增强

| 场景 | 增强器 |
|------|--------|
| 多轮对话、问题含指代 | compression |
| 单轮、问题口语化/冗余 | rewrite |
| 问题短、语义稀疏 | hyde |
| 单一问题召回面窄 | expand（multiQuery） |

### 检索器

| 场景 | 检索器 | 开关 |
|------|--------|------|
| 父子分块数据 | EnhancedRetriever（enableParentChild=true） | 命中子块回查父块完整小节 |
| 普通分块数据 | EnhancedRetriever（enableParentChild=false） | 纯向量召回 |
| 召回质量要求高 | 叠加 Reranker | 精排 |

## 3. 框架接管 vs 调用方传递

| 职责 | 谁负责 |
|------|--------|
| 编排顺序、短路、去重、路由 | 框架自动 |
| 向量库/父块库/模型的选型与连接 | 调用方传递 |
| 契约 metadata 生成与注入 | 框架自动 |
| 自定义 metadata（如 docType） | 调用方传递，检索时 filter 过滤 |
| documentId 生成方式 | 框架提供（hash/uuid），调用方自选 |

## 4. 一期与二期

**一期（当前已实现）**：解析、分块、存储（PG/Redis 双向量库 + JDBC/Redis 父块）、查询增强（compression/rewrite/hyde/expand）、检索（向量/父子 + rerank）、编排（Pipeline + Agentic RAG 工具）。

**二期（规划）**：

- 混合检索：KeywordStore 生产实现（ES / Redis FT.SEARCH）+ HybridRetriever
- graph 检索：GraphRetriever / text2cypher
- 评测体系：RAGAS 召回/忠诚度/相关性评测

## 5. 文档导航

- [01 - 文档解析](01-文档解析.md)
- [02 - 文档分块](02-文档分块.md)
- [03 - 存储与索引](03-存储与索引.md)
- [04 - 查询增强](04-查询增强.md)
- [05 - 检索与重排](05-检索与重排.md)
- [06 - 编排与接入](06-编排与接入.md)

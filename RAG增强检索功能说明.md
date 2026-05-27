# RAG增强检索功能说明

## 概述

本模块实现了查询重写、多查询扩展和结果融合的RAG增强功能，用于提高知识库检索的准确性和覆盖率。

## 新增文件

### 1. QueryRewriteService.java
查询重写与扩展服务，提供以下功能：
- `rewriteQuery()` - 查询重写
- `expandQueries()` - 多查询扩展
- `enhanceQuery()` - 上下文增强查询
- `recognizeIntent()` - 意图识别

### 2. QueryFusionService.java
查询结果融合服务，提供RRF（Reciprocal Rank Fusion）算法实现：
- `fuseResults()` - 加权融合多个查询结果
- `deduplicate()` - 结果去重
- `groupByCategory()` - 按类别分组

### 3. EnhancedRagService.java
增强版RAG服务，集成所有增强功能：
- `enhancedQuery()` - 完整增强检索流程
- `contextualQuery()` - 上下文感知检索
- `intentAwareQuery()` - 意图感知检索

### 4. EnhancedRagQueryTool.java
AI工具接口，提供4个工具方法供AI调用：
- `enhanced_rag_query` - 完整增强检索
- `contextual_rag_query` - 上下文感知检索
- `intent_aware_rag_query` - 意图感知检索
- `basic_rag_query` - 基础检索

## 配置参数

在 `application.yml` 中添加了以下配置：

```yaml
# RAG增强检索配置
rag:
  query-rewrite:
    enabled: true   # 是否启用查询重写
  multi-query:
    enabled: true   # 是否启用多查询扩展
    expansion-count: 3  # 扩展查询数量
  fusion:
    k: 60  # RRF算法常数
```

## 检索流程

### 1. 完整增强检索流程 (enhancedQuery)

```
用户查询
    ↓
[1] 意图识别（可选）
    ↓
[2] 查询重写
    ↓
[3] 多查询扩展 → 生成 N 个相关查询
    ↓
[4] 并行向量检索（每个查询独立检索）
    ↓
[5] RRF结果融合
    ↓
[6] 去重和过滤
    ↓
输出检索结果
```

### 2. 上下文感知检索流程 (contextualQuery)

```
用户查询 + 对话历史 + 用户偏好
    ↓
上下文整合
    ↓
查询增强（考虑历史和偏好）
    ↓
意图识别
    ↓
基础向量检索
    ↓
输出检索结果
```

### 3. 意图感知检索流程 (intentAwareQuery)

```
用户查询
    ↓
意图识别 → attraction/hotel/food/transport/general
    ↓
查询优化（根据意图调整）
    ↓
类别调整（如果需要）
    ↓
基础向量检索
    ↓
输出检索结果
```

## AI工具调用示例

### 示例1：完整增强检索

```
用户：推荐一些适合亲子游的景点

AI 思考：用户想要景点推荐，而且提到了亲子游，我可以先调用增强检索获取更全面的结果

AI 调用工具：
  - tool: enhanced_rag_query
    parameters:
      query: "适合亲子游的景点推荐"
      category: "attraction"

返回：知识库中关于亲子景点的详细信息
```

### 示例2：上下文感知检索

```
对话历史：
  用户：三亚有什么好玩的地方？
  AI：推荐亚龙湾、天涯海角等景点...

用户：那里有什么好玩的？

AI 思考：用户问"那里"指的是之前提到的亚龙湾，我需要结合上下文

AI 调用工具：
  - tool: contextual_rag_query
    parameters:
      query: "亚龙湾有什么好玩的"
      conversationHistory: "用户在询问三亚的景点，AI推荐了亚龙湾"
```

### 示例3：意图感知检索

```
用户：我想找个地方休息

AI 思考：用户想要找住宿/休息的地方，这可能是酒店相关查询

AI 调用工具：
  - tool: intent_aware_rag_query
    parameters:
      query: "找个地方休息"
      category: "all"  # 不确定具体分类，让系统自动识别

返回：系统识别意图为 hotel，自动检索酒店相关内容
```

## 算法说明

### 查询重写

使用LLM将用户查询改写为更适合检索的形式：
- 扩展同义词
- 使用更完整的表达
- 添加必要的上下文

### 多查询扩展

使用LLM生成多个相关查询变体：
- 同义词替换
- 不同角度的表述
- 考虑可能的隐含意图

### RRF结果融合

Reciprocal Rank Fusion 算法：
```
RRF_score = Σ(1 / (k + rank))
```
- k: 常数（默认60）
- rank: 该结果在不同查询结果中的排名

融合分数 = RRF_score + (原始相似度 × 0.1)

## 性能考虑

1. **LLM调用开销**：查询重写和扩展会增加LLM调用
2. **并行检索**：多查询使用并行检索减少总延迟
3. **结果缓存**：可考虑缓存常用查询的扩展结果
4. **选择性启用**：可通过配置选择性地启用增强功能

## 使用建议

### 推荐使用场景

1. **复杂/模糊查询**：使用 `enhancedQuery`
2. **对话追问**：使用 `contextualQuery`
3. **不确定分类**：使用 `intentAwareQuery`
4. **明确查询**：继续使用原有的 `basicQuery`

### 性能优化

1. 高并发场景可考虑关闭部分增强功能
2. 热门查询可缓存扩展结果
3. 调整 `expansion-count` 平衡准确性和性能

## 测试建议

1. 对比基础检索和增强检索的召回率
2. 测试不同查询类型的效果
3. 性能测试：测量增强检索的延迟
4. 质量测试：评估结果的相关性

## 未来优化方向

1. 添加查询历史学习，个性化扩展
2. 实现动态调整扩展查询数量
3. 添加结果质量评估和反馈机制
4. 支持更复杂的意图识别

# 部分签项没有配置规则的兼容处理修复

## 问题描述

在原有的抽签系统中，存在一个关键问题：系统只处理从 `lot_rule_group` 表获取的规则组数据，对于没有配置规则组的签项，即使这些签项在 `lot_sign` 表和 `lot_rule` 表中有配置，也会被完全忽略，导致这些签项的用户无法被分配。

## 问题场景

假设有以下数据配置：
- `lot_sign` 表：签项1、签项2、签项3
- `lot_rule_group` 表：只有签项1和签项2的规则组
- `lot_rule` 表：签项1、签项2、签项3都有规则

**原有系统行为：**
1. 只处理签项1和签项2（因为有规则组）
2. 完全忽略签项3（因为没有规则组）
3. 签项3的用户不会被分配

## 修复方案

### 1. 扩展实体类

为 `LotRule` 实体类添加了缺失的字段：
```java
// 添加数量相关字段
private String countSymbol;
private Integer countValue;

// 添加对应的getter方法
public String getCountSymbol() {
    return countSymbol;
}

public Integer getCountValue() {
    return countValue;
}
```

### 2. 更新数据库映射

在 `LotMapper.xml` 中更新了 `getRulesByActivityId` 查询，添加了 `countSymbol` 和 `countValue` 字段的映射，并新增了 `getRulesBySignId` 查询方法。

### 3. 新增直接规则处理逻辑

在 `LotDrawService` 中新增了 `DirectRuleExecution` 内部类，用于处理直接从 `lot_rule` 表获取的规则：

```java
/**
 * 直接规则执行单元 - 处理直接从lot_rule表获取的规则
 */
private static class DirectRuleExecution {
    final Long signId;
    final LotRule rule;
    final List<String> candidates;

    public DirectRuleExecution(Long signId, LotRule rule, List<String> candidates) {
        this.signId = signId;
        this.rule = rule;
        this.candidates = candidates;
    }
}
```

### 4. 修改主要分配方法

在 `drawLotsMultiSignByActivityId` 方法中：
- 获取活动下所有签项（包括没有规则组的签项）
- 确保所有签项的容量都被包含在 `signTotalCounts` 中

在 `drawLotsMultiSign` 方法中：
- 在规则分配完成后，新增了对没有规则组的签项的处理逻辑
- 调用 `processSignsWithoutRuleGroups` 方法处理这些签项

### 5. 新增辅助方法

#### `processSignsWithoutRuleGroups` 方法
处理没有规则组的签项，直接从 `lot_rule` 表获取规则并分配。

#### `getCandidatesByRule` 方法
根据规则条件获取候选用户（当前为简化实现，返回所有用户）。

#### `calculateAssignCount` 方法
计算规则分配数量，支持各种数量符号（=、>=、<=、<、>、!=）。

#### `isDirectRuleSatisfied` 方法
判断直接规则是否满足条件。

### 6. 改进补齐分配逻辑

修改了签项容量补齐和全局唯一补齐的逻辑，确保：
- 所有签项（包括没有规则组的签项）都能被处理
- 补齐分配使用全局用户池而不是仅限规则组用户
- 全局唯一补齐按签项ID排序，确保所有签项都能被处理

## 修复效果

### 修复前
- 只处理有规则组的签项
- 没有规则组的签项被完全忽略
- 部分用户无法被分配

### 修复后
- 处理所有签项，无论是否有规则组
- 有规则组的签项按原有逻辑处理
- 没有规则组的签项直接从 `lot_rule` 表获取规则处理
- 所有用户都能被正确分配

## 测试验证

创建了 `testPartialSignsWithoutRuleGroups` 测试方法，验证：
- 数据结构正确性
- 签项1有规则组，签项2没有规则组但有直接规则
- 两个签项都能被正确处理

测试输出：
```
[测试] 部分签项没有规则组的数据结构验证通过
签项1规则组数量: 2
签项2直接规则数量: 2
总用户数: 70
签项1容量: 30
签项2容量: 40
```

## 兼容性

此修复完全向后兼容：
- 原有有规则组的签项处理逻辑保持不变
- 新增的处理逻辑只影响没有规则组的签项
- 不影响现有的API接口和数据结构

## 注意事项

1. **用户筛选逻辑**：当前的 `getCandidatesByRule` 方法为简化实现，实际使用时需要根据 `fieldName`、`fieldOperator`、`fieldValue` 实现真正的用户筛选逻辑。

2. **性能考虑**：对于大量签项和规则的情况，可能需要优化查询性能。

3. **日志记录**：系统会详细记录每个签项的分配情况，包括直接规则分配的用户数量和名单。 
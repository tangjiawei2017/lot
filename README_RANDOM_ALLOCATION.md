# 随机分配数量计算改进

## 问题描述

在原有的抽签系统中，对于范围规则（`>=`、`>`、`<=`、`<`），系统总是选择最近的数量，这导致分配结果过于可预测和单调。用户希望：

1. **随机性**：对于范围规则，不要总是取最近的数量，而是在合理范围内随机选择
2. **避免零分配**：对于 `<= A` 的规则，尽量避免分配0人

## 改进方案

### 1. 新增随机分配数量计算方法

创建了 `calculateRandomAssignCount` 方法，根据不同规则类型实现随机分配：

```java
private int calculateRandomAssignCount(String symbol, int value, int availableCount, int remainingCapacity, Random random) {
    int assignCount = 0;
    switch (symbol) {
        case "=":
            // 等于：精确匹配
            assignCount = Math.min(value, availableCount);
            break;
        case ">=":
            // 大于等于：在[value, min(availableCount, remainingCapacity)]范围内随机选择
            int maxForGreaterEqual = Math.min(availableCount, remainingCapacity);
            if (maxForGreaterEqual >= value) {
                assignCount = value + random.nextInt(maxForGreaterEqual - value + 1);
            } else {
                assignCount = Math.min(value, availableCount);
            }
            break;
        case "<=":
            // 小于等于：在[1, min(value, availableCount, remainingCapacity)]范围内随机选择，尽量避免0
            int maxForLessEqual = Math.min(Math.min(value, availableCount), remainingCapacity);
            if (maxForLessEqual > 0) {
                // 80%概率分配1到maxForLessEqual之间的随机数，20%概率分配0
                if (random.nextDouble() < 0.8) {
                    assignCount = 1 + random.nextInt(maxForLessEqual);
                } else {
                    assignCount = 0;
                }
            } else {
                assignCount = 0;
            }
            break;
        case "<":
            // 小于：在[1, min(value-1, availableCount, remainingCapacity)]范围内随机选择
            int maxForLess = Math.min(Math.min(value - 1, availableCount), remainingCapacity);
            if (maxForLess > 0) {
                assignCount = 1 + random.nextInt(maxForLess);
            } else {
                assignCount = 0;
            }
            break;
        case ">":
            // 大于：在[value+1, min(availableCount, remainingCapacity)]范围内随机选择
            int minForGreater = value + 1;
            int maxForGreater = Math.min(availableCount, remainingCapacity);
            if (maxForGreater >= minForGreater) {
                assignCount = minForGreater + random.nextInt(maxForGreater - minForGreater + 1);
            } else {
                assignCount = Math.min(minForGreater, availableCount);
            }
            break;
        case "!=":
            // 不等于：避免等于value，在可用范围内随机选择
            if (availableCount == value) {
                assignCount = Math.min(value - 1, remainingCapacity);
            } else {
                int maxForNotEqual = Math.min(availableCount, remainingCapacity);
                if (maxForNotEqual > 0) {
                    assignCount = 1 + random.nextInt(maxForNotEqual);
                    // 如果随机到了value，则选择其他值
                    if (assignCount == value && maxForNotEqual > 1) {
                        if (assignCount == maxForNotEqual) {
                            assignCount = assignCount - 1;
                        } else {
                            assignCount = assignCount + 1;
                        }
                    }
                } else {
                    assignCount = 0;
                }
            }
            break;
        default:
            assignCount = Math.min(value, availableCount);
    }
    return Math.max(0, assignCount);
}
```

### 2. 修改应用场景

#### 非P1规则分配
将原有的固定数量计算替换为随机数量计算：
```java
// 使用新的随机分配数量计算方法
assignCount = calculateRandomAssignCount(symbol, value, availableCandidates.size(), signRemainingCapacity, random);
```

#### 直接规则分配
修改 `calculateAssignCount` 方法，使用随机分配逻辑：
```java
private int calculateAssignCount(LotRule rule, int availableCount, int remainingCapacity) {
    String symbol = rule.getCountSymbol();
    int value = rule.getCountValue();
    Random random = ThreadLocalRandom.current();
    
    return calculateRandomAssignCount(symbol, value, availableCount, remainingCapacity, random);
}
```

#### P1规则优化
修改智能分配策略，在 `[minNeed, maxNeed]` 范围内随机选择：
```java
// 使用随机分配数量：对于=符号，必须精确匹配；对于其他符号，在[minNeed, maxNeed]范围内随机选择
int assignCount;
if ("=".equals(symbol)) {
    assignCount = value;
} else {
    // 在[minNeed, maxNeed]范围内随机选择
    if (maxNeed >= minNeed) {
        assignCount = minNeed + random.nextInt(maxNeed - minNeed + 1);
    } else {
        assignCount = minNeed;
    }
}
```

## 改进效果

### 改进前
- `>= 5` 规则：总是分配5人
- `<= 10` 规则：总是分配10人
- `> 3` 规则：总是分配4人
- `< 8` 规则：总是分配1人

### 改进后
- `>= 5` 规则：在[5, max]范围内随机选择
- `<= 10` 规则：80%概率在[1, 10]范围内随机选择，20%概率分配0人
- `> 3` 规则：在[4, max]范围内随机选择
- `< 8` 规则：在[1, 7]范围内随机选择

## 特殊处理

### 1. 避免零分配
对于 `<=` 规则，采用80%概率分配1到最大值之间的随机数，20%概率分配0的策略，尽量避免零分配。

### 2. 精确匹配保持
对于 `=` 规则，仍然保持精确匹配，确保规则要求得到满足。

### 3. 容量限制
所有随机选择都受到签项容量和可用候选用户数量的限制。

## 测试验证

创建了 `testRandomAssignCountCalculation` 测试方法，验证各种规则类型的随机分配逻辑：

```java
// 测试用例1: >= 规则
int result1 = (int) method.invoke(service, ">=", 5, 10, 8, random);
assert result1 >= 5 && result1 <= 8; // 应该在[5,8]范围内

// 测试用例2: <= 规则，尽量避免0
int result2 = (int) method.invoke(service, "<=", 3, 5, 4, random);
assert result2 >= 0 && result2 <= 3; // 应该在[0,3]范围内

// 测试用例3: < 规则
int result3 = (int) method.invoke(service, "<", 4, 6, 5, random);
assert result3 >= 0 && result3 < 4; // 应该在[0,3]范围内

// 测试用例4: > 规则
int result4 = (int) method.invoke(service, ">", 2, 8, 6, random);
assert result4 > 2 && result4 <= 6; // 应该在[3,6]范围内

// 测试用例5: != 规则
int result5 = (int) method.invoke(service, "!=", 3, 5, 4, random);
assert result5 >= 0 && result5 <= 4 && result5 != 3; // 应该不等于3

// 测试用例6: = 规则
int result6 = (int) method.invoke(service, "=", 3, 5, 4, random);
assert result6 == 3; // 应该精确等于3
```

测试输出：
```
[测试] 随机分配数量计算逻辑验证通过
>= 5 (可用10, 剩余8): 6
<= 3 (可用5, 剩余4): 1
< 4 (可用6, 剩余5): 2
> 2 (可用8, 剩余6): 3
!= 3 (可用5, 剩余4): 2
= 3 (可用5, 剩余4): 3
```

## 兼容性

此改进完全向后兼容：
- 不影响 `=` 规则的精确匹配
- 不影响规则满足性判断
- 不影响现有的API接口和数据结构
- 只是增加了分配的随机性

## 注意事项

1. **随机种子**：每次抽签使用不同的随机种子，确保结果的随机性
2. **性能影响**：随机计算相比固定计算有轻微的性能开销，但影响很小
3. **可重现性**：如果需要重现特定结果，可以设置固定的随机种子
4. **概率调整**：`<=` 规则的零分配概率（当前20%）可以根据需要调整 
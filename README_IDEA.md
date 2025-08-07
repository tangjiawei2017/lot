# 抽签系统 - IDEA启动指南

## 项目简介
这是一个基于Spring Boot的抽签系统，实现了贪心+多轮随机的抽签算法。

## 环境要求
- Java 24
- Maven 3.9+
- MySQL 8.0+
- IntelliJ IDEA

## 快速启动

### 1. 数据库准备
1. 启动本地MySQL服务
2. 创建数据库：
```sql
CREATE DATABASE lot DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```
3. 执行初始化脚本：`src/main/resources/schema.sql`

### 2. 在IDEA中启动项目
1. 打开项目：在IDEA中打开项目根目录
2. 配置运行环境：
   - 运行配置：`LotteryApplication`
   - VM选项：`-Dspring.profiles.active=dev`
   - 工作目录：项目根目录
3. 启动应用：点击运行按钮或使用快捷键 `Shift + F10`

### 3. 验证启动
访问健康检查接口：
```
GET http://localhost:8080/api/lottery/test/health
```

### 4. 测试抽签功能
使用POST请求测试抽签：
```
POST http://localhost:8080/api/lottery/test/draw?activityId=1&tryCount=10
```

## 项目结构
```
src/main/java/com/example/lottery/
├── LotteryApplication.java          # 启动类
├── controller/
│   └── LotteryTestController.java   # 测试控制器
├── service/
│   └── LotteryDrawService.java      # 抽签服务
├── mapper/
│   └── LotteryMapper.java           # 数据访问层
├── entity/                          # 实体类
├── utils/
│   └── RuleMatchUtils.java          # 规则匹配工具
└── config/
    └── ThreadPoolConfig.java        # 线程池配置
```

## 配置说明
- `application.yml`: 主配置文件
- `application-dev.yml`: 开发环境配置
- `src/main/resources/mapper/`: MyBatis映射文件

## 数据库表结构
- `lot_activity`: 活动表
- `lot_sign`: 签项表
- `lot_rule`: 规则表
- `lottery_user_info`: 用户信息表
- `lot_user_tags`: 用户标签表
- `lot_rule_group`: 规则分组表
- `lot_rule_group_detail`: 规则分组明细表
- `lot_record`: 抽签记录表

## 算法说明
抽签算法采用"贪心+多轮随机"策略：
1. 按优先级排序规则分组
2. 优先分配"最小可用用户数"的分组
3. 在用户选择时，优先分配"可被多个分组选中的用户"
4. 多轮随机尝试，选择最优结果

## 常见问题
1. **端口冲突**: 修改`application-dev.yml`中的端口配置
2. **数据库连接失败**: 检查MySQL服务是否启动，连接参数是否正确
3. **编译错误**: 确保使用Java 24版本

## 开发建议
1. 使用IDEA的Spring Boot支持功能
2. 开启热重载：`spring-boot-devtools`
3. 使用IDEA的数据库工具连接MySQL
4. 使用IDEA的HTTP客户端测试API 
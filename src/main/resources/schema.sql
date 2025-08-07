-- 创建数据库
CREATE DATABASE IF NOT EXISTS lot DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE lot;

-- 活动表
CREATE TABLE IF NOT EXISTS lot_activity (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    activity_name VARCHAR(100) NOT NULL COMMENT '活动名称',
    activity_desc TEXT COMMENT '活动描述',
    start_time DATETIME COMMENT '开始时间',
    end_time DATETIME COMMENT '结束时间',
    status TINYINT DEFAULT 1 COMMENT '状态：1-启用，0-禁用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 签项表
CREATE TABLE IF NOT EXISTS lot_sign (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    activity_id BIGINT NOT NULL COMMENT '活动ID',
    sign_name VARCHAR(50) NOT NULL COMMENT '签项名称',
    sign_desc TEXT COMMENT '签项描述',
    sort_order INT DEFAULT 0 COMMENT '排序',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_activity_id (activity_id)
);

-- 规则表
CREATE TABLE IF NOT EXISTS lot_rule (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    activity_id BIGINT NOT NULL COMMENT '活动ID',
    sign_id BIGINT NOT NULL COMMENT '签项ID',
    rule_name VARCHAR(100) NOT NULL COMMENT '规则名称',
    rule_type VARCHAR(20) NOT NULL COMMENT '规则类型',
    rule_value TEXT COMMENT '规则值',
    priority INT DEFAULT 0 COMMENT '优先级',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_activity_sign (activity_id, sign_id)
);

-- 用户信息表
CREATE TABLE IF NOT EXISTS lottery_user_info (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    activity_id BIGINT NOT NULL COMMENT '活动ID',
    user_code VARCHAR(50) NOT NULL COMMENT '用户编码',
    user_name VARCHAR(100) COMMENT '用户姓名',
    business_group VARCHAR(100) COMMENT '业务组',
    department VARCHAR(100) COMMENT '部门',
    office VARCHAR(100) COMMENT '办公室',
    birthday DATE COMMENT '生日',
    is_manager TINYINT DEFAULT 0 COMMENT '是否管理者',
    gender VARCHAR(10) COMMENT '性别',
    job_title VARCHAR(100) COMMENT '职位',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_activity_user (activity_id, user_code),
    INDEX idx_activity_id (activity_id)
);

-- 用户标签表
CREATE TABLE IF NOT EXISTS lot_user_tags (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    activity_id BIGINT NOT NULL COMMENT '活动ID',
    user_code VARCHAR(50) NOT NULL COMMENT '用户编码',
    tag_name VARCHAR(50) NOT NULL COMMENT '标签名',
    tag_value VARCHAR(200) COMMENT '标签值',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_activity_user (activity_id, user_code),
    INDEX idx_tag_name (tag_name)
);

-- 规则分组表
CREATE TABLE IF NOT EXISTS lot_rule_group (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    activity_id BIGINT NOT NULL COMMENT '活动ID',
    rule_id BIGINT NOT NULL COMMENT '规则ID',
    sign_id BIGINT NOT NULL COMMENT '签项ID',
    user_codes TEXT COMMENT '用户编码列表',
    user_count INT DEFAULT 0 COMMENT '用户数量',
    priority INT DEFAULT 0 COMMENT '优先级',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_activity_id (activity_id),
    INDEX idx_rule_id (rule_id),
    INDEX idx_sign_id (sign_id)
);

-- 规则分组明细表
CREATE TABLE IF NOT EXISTS lot_rule_group_detail (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    group_id BIGINT NOT NULL COMMENT '分组ID',
    user_code VARCHAR(50) NOT NULL COMMENT '用户编码',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_group_id (group_id),
    INDEX idx_user_code (user_code)
);

-- 抽签记录表
CREATE TABLE IF NOT EXISTS lot_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    activity_id BIGINT NOT NULL COMMENT '活动ID',
    sign_id BIGINT NOT NULL COMMENT '签项ID',
    user_code VARCHAR(50) NOT NULL COMMENT '用户编码',
    draw_time DATETIME COMMENT '抽签时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_activity_id (activity_id),
    INDEX idx_sign_id (sign_id),
    INDEX idx_user_code (user_code)
);

-- 插入测试数据
INSERT INTO lot_activity (activity_name, activity_desc, start_time, end_time, status) VALUES 
('测试抽签活动', '这是一个测试抽签活动', '2024-01-01 00:00:00', '2024-12-31 23:59:59', 1);

INSERT INTO lot_sign (activity_id, sign_name, sign_desc, sort_order) VALUES 
(1, '一等奖', '一等奖签项', 1),
(1, '二等奖', '二等奖签项', 2),
(1, '三等奖', '三等奖签项', 3);

INSERT INTO lot_rule (activity_id, sign_id, rule_name, rule_type, rule_value, priority) VALUES 
(1, 1, '部门规则', 'department', '技术部', 1),
(1, 2, '性别规则', 'gender', '男', 2),
(1, 3, '年龄规则', 'age', '25-35', 3); 
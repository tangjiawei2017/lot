-- 活动表
CREATE TABLE IF NOT EXISTS lot_activity (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    activity_name VARCHAR(100) NOT NULL,
    activity_desc TEXT,
    start_time DATETIME,
    end_time DATETIME,
    status INT DEFAULT 1,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 签项表
CREATE TABLE IF NOT EXISTS lot_sign (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    activity_id BIGINT NOT NULL,
    sign_name VARCHAR(50) NOT NULL,
    sign_desc TEXT,
    sort_order INT DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 用户信息表
CREATE TABLE IF NOT EXISTS lottery_user_info (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    activity_id BIGINT NOT NULL,
    user_code VARCHAR(50) NOT NULL,
    user_name VARCHAR(50),
    business_group VARCHAR(50),
    department VARCHAR(50),
    office VARCHAR(50),
    birthday DATE,
    is_manager BOOLEAN DEFAULT FALSE,
    gender VARCHAR(10),
    job_title VARCHAR(50),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 规则表
CREATE TABLE IF NOT EXISTS lot_rule (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    activity_id BIGINT NOT NULL,
    sign_id BIGINT NOT NULL,
    rule_name VARCHAR(100) NOT NULL,
    rule_type VARCHAR(20) NOT NULL,
    rule_value TEXT,
    priority INT DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 规则分组表
CREATE TABLE IF NOT EXISTS lot_rule_group (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    activity_id BIGINT NOT NULL,
    sign_id BIGINT NOT NULL,
    rule_id BIGINT NOT NULL,
    priority INT NOT NULL,
    user_count INT DEFAULT 0,
    count_symbol VARCHAR(8) NOT NULL DEFAULT '=',
    count_value INT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 规则分组详情表
CREATE TABLE IF NOT EXISTS lot_rule_group_detail (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    group_id BIGINT NOT NULL,
    user_code VARCHAR(50) NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 抽签记录表
CREATE TABLE IF NOT EXISTS lot_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    activity_id BIGINT NOT NULL,
    sign_id BIGINT NOT NULL,
    user_code VARCHAR(50) NOT NULL,
    draw_time DATETIME,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 插入测试活动
INSERT INTO lot_activity (id, activity_name, activity_desc, start_time, end_time, status) VALUES 
(1001, '测试抽签活动', '这是一个测试抽签活动', '2024-01-01 00:00:00', '2024-12-31 23:59:59', 1);

-- 插入测试签项
INSERT INTO lot_sign (id, activity_id, sign_name, sign_desc, sort_order) VALUES 
(1, 1001, '一等奖', '一等奖签项', 1),
(2, 1001, '二等奖', '二等奖签项', 2),
(3, 1001, '三等奖', '三等奖签项', 3); 
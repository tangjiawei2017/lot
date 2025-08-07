-- 1. 用户表
CREATE TABLE IF NOT EXISTS lottery_user_info(
    `id`   int   PRIMARY KEY   NOT NULL      AUTO_INCREMENT    comment '自增ID',
    `user_code`   varchar(64)      NOT NULL   DEFAULT ''       comment '用户英文名',
    `user_name`   varchar(64)      NOT NULL   DEFAULT ''       comment '用户中文名',
    `role`   int      NOT NULL   DEFAULT 0       comment '用户角色',
    `business_group`   varchar(64)      NOT NULL   DEFAULT ''       comment '事业群',
    `department`   varchar(64)      NOT NULL   DEFAULT ''       comment '部门',
    `office`   varchar(64)      NOT NULL   DEFAULT ''       comment '科室',
    `user_icon`   varchar(500)      NOT NULL   DEFAULT ''       comment '用户头像icon',
    `create_time`   datetime      NOT NULL   DEFAULT CURRENT_TIMESTAMP       comment '创建时间',
    `update_time`   datetime      NOT NULL   DEFAULT CURRENT_TIMESTAMP   ON update CURRENT_TIMESTAMP    comment '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin comment="用户表";

-- 2. 活动表
CREATE TABLE IF NOT EXISTS lottery_activity_info(
    `id`   BIGINT   PRIMARY KEY   NOT NULL      AUTO_INCREMENT    comment '自增ID',
    `activity_name`   varchar(64)      NOT NULL   DEFAULT ''       comment '活动名称',
    `activity_front_pic`   varchar(500)      NOT NULL   DEFAULT ''       comment '活动封面图',
    `activity_back_pic`   varchar(500)      NOT NULL   DEFAULT ''       comment '活动背景图',
    `live_link`   varchar(500)      NOT NULL   DEFAULT ''       comment '直播地址',
    `music`   varchar(500)      NOT NULL   DEFAULT ''       comment '音乐',
    `show_countdown`   int(2)      NOT NULL   DEFAULT 0       comment '是否展示倒计时',
    `activity_status`   int(2)      NOT NULL   DEFAULT 0       comment '活动状态 1-草稿 2-未开始 3-已开始 4-已结束 5-已删除',
    `activity_type`   int(2)      NOT NULL   DEFAULT 0       comment '活动类型',
    `join_num`   int      NOT NULL   DEFAULT 0       comment '参与人数',
    `extract_num`   int(2)      NOT NULL   DEFAULT 0       comment '已抽取次数',
    `can_repeat`   int(2)      NOT NULL   DEFAULT 0       comment '是否允许重复抽取',
    `start_time`   datetime      NOT NULL   DEFAULT 0       comment '活动开始时间',
    `end_time`   datetime      NOT NULL   DEFAULT 0       comment '活动结束时间',
    `create_notice_status`   int(2)      NOT NULL   DEFAULT 0       comment '创建推送状态 1-不推送 2-未推送 3已推送',
    `start_notice_status`   int(2)      NOT NULL   DEFAULT 0       comment '开始推送状态 1-不推送 2-未推送 3已推送',
    `end_notice_status`   int(2)      NOT NULL   DEFAULT 0       comment '结束推送状态 1-不推送 2-未推送 3已推送',
    `notice_status`   int(2)      NOT NULL   DEFAULT 0       comment '是否推送',
    `create_time`   datetime      NOT NULL   DEFAULT CURRENT_TIMESTAMP       comment '创建时间',
    `update_time`   datetime      NOT NULL   DEFAULT CURRENT_TIMESTAMP   ON update CURRENT_TIMESTAMP    comment '更新时间',
    `created_by`   varchar(64)      NOT NULL   DEFAULT ''       comment '创建人',
    `updated_by`   varchar(64)      NOT NULL   DEFAULT ''       comment '更新人',
    KEY `activity_type` (activity_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin comment="活动表";

ALTER TABLE lottery_activity_info ADD COLUMN IF NOT EXISTS `activity_tag` int NOT NULL DEFAULT '1' COMMENT '活动类型 1-抽奖 2-抽签';
ALTER TABLE lottery_activity_info ADD COLUMN IF NOT EXISTS `mode` int NOT NULL DEFAULT '0' COMMENT '抽奖模式 0-创建者抽 1-参与者抽';

-- 3. 抽签用户参与表
DROP TABLE IF EXISTS lot_user_join;
CREATE TABLE lot_user_join (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增ID',
    user_code VARCHAR(64) NOT NULL DEFAULT '' COMMENT '用户code',
    user_name VARCHAR(64) NOT NULL DEFAULT '' COMMENT '用户中文名',
    activity_id BIGINT NOT NULL DEFAULT 0 COMMENT '活动id',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    created_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '创建人',
    updated_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '更新人',
    UNIQUE KEY uk_activity_user (activity_id, user_code),
    KEY idx_activity_id (activity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='抽签用户参与表';

-- 4. 抽签用户标签表
DROP TABLE IF EXISTS lot_user_tags;
CREATE TABLE lot_user_tags (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增ID',
    activity_id BIGINT NOT NULL DEFAULT 0 COMMENT '活动ID',
    user_code VARCHAR(64) NOT NULL DEFAULT '' COMMENT '用户code',
    tag_name VARCHAR(64) NOT NULL DEFAULT '' COMMENT '标签名称',
    tag_value VARCHAR(255) NOT NULL DEFAULT '' COMMENT '标签值',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    created_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '创建人',
    updated_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '更新人',
    UNIQUE KEY uk_activity_user_tag (activity_id, user_code, tag_name),
    KEY idx_activity_id (activity_id),
    KEY idx_user_code (user_code),
    KEY idx_tag_name (tag_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='抽签用户标签表（统一管理所有标签）';

-- 5. 签表
DROP TABLE IF EXISTS lot_sign;
CREATE TABLE lot_sign (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    activity_id BIGINT NOT NULL DEFAULT 0 COMMENT '活动ID',
    sign_name VARCHAR(64) NOT NULL DEFAULT '' COMMENT '签名称',
    sign_type VARCHAR(32) NOT NULL DEFAULT '' COMMENT '签类型',
    total_count INT NOT NULL DEFAULT 0 COMMENT '该签总数量',
    sign_description VARCHAR(255) DEFAULT NULL COMMENT '签描述',
    is_active TINYINT(1) DEFAULT 1 COMMENT '是否有效',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 6. 抽签规则表
DROP TABLE IF EXISTS lot_rule;
CREATE TABLE lot_rule (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '规则ID',
    activity_id BIGINT NOT NULL DEFAULT 0 COMMENT '活动ID',
    sign_id BIGINT NOT NULL DEFAULT 0 COMMENT '签ID',
    rule_name VARCHAR(64) NOT NULL DEFAULT '' COMMENT '规则名称',
    field_name VARCHAR(64) NOT NULL DEFAULT '' COMMENT '用户属性字段名（如gender、department等）',
    field_operator VARCHAR(16) NOT NULL DEFAULT '=' COMMENT '字段操作符（=、!=、in、not_in、like、>、<、>=、<=）',
    field_value TEXT NOT NULL COMMENT '字段匹配值（JSON格式，支持多值）',
    count_symbol VARCHAR(8) NOT NULL DEFAULT '=' COMMENT '数量符号（=、>、<、>=、<=）',
    count_value INT NOT NULL DEFAULT 0 COMMENT '分配数量',
    priority INT NOT NULL DEFAULT 0 COMMENT '优先级，数字越小优先级越高',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    created_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '创建人',
    updated_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '更新人',
    KEY idx_activity_id (activity_id),
    KEY idx_sign_id (sign_id),
    KEY idx_priority (priority)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='抽签规则表';

-- 7. 抽签记录表
DROP TABLE IF EXISTS lot_record;
CREATE TABLE lot_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '抽签记录ID',
    activity_id BIGINT NOT NULL DEFAULT 0 COMMENT '活动ID',
    sign_id BIGINT NOT NULL DEFAULT 0 COMMENT '签ID',
    rule_id BIGINT NOT NULL DEFAULT 0 COMMENT '规则ID',
    description VARCHAR(1024) COMMENT '抽签描述',
    user_code VARCHAR(64) NOT NULL DEFAULT '' COMMENT '用户Code',
    user_name VARCHAR(64) NOT NULL DEFAULT '' COMMENT '用户姓名',
    show_status INT(2) NOT NULL DEFAULT 1 COMMENT '是否展示 1-展示 2-不展示，主持人抽签时默认为1',
    draw_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '抽签时间',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    description VARCHAR(1024) COMMENT '抽签描述',
    KEY idx_activity_id (activity_id),
    KEY idx_sign_id (sign_id),
    KEY idx_user_code (user_code),
    KEY idx_create_time (create_time),
    KEY idx_show_status (show_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='抽签记录表'; 
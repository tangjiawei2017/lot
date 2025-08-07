CREATE TABLE IF NOT EXISTS lot_rule_groups (
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

CREATE TABLE IF NOT EXISTS lot_rule_group_details (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  group_id BIGINT NOT NULL,
  user_code VARCHAR(64) NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS lot_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  activity_id BIGINT NOT NULL,
  sign_id BIGINT NOT NULL,
  rule_id BIGINT  NULL ,
  description varchar(255)  NULL ,
  user_code VARCHAR(64) NOT NULL,
  draw_time DATETIME,
  create_time DATETIME,
  update_time DATETIME
);

package com.example.lottery.entity;

import lombok.Data;
import java.util.Date;

@Data
public class LotRecord {
    private Long id;
    private Long activityId;
    private Long signId;
    private String userCode;
    private Date drawTime;
    private Date createTime;
    private String description; // 新增：分配描述
    private Long ruleId;
    public Long getRuleId() { return ruleId; }
    public void setRuleId(Long ruleId) { this.ruleId = ruleId; }
    // 只保留 drawLotsImproved 相关字段
} 
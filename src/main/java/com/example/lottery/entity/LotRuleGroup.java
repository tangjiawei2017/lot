package com.example.lottery.entity;

import lombok.Data;
import java.util.Date;

@Data
public class LotRuleGroup {
    private Long id;
    private Long activityId;
    private Long ruleId;
    private Long signId;
    private Integer priority;
    private Date createTime;
    private Integer countValue;
    private String countSymbol;
    // 只保留 drawLotsImproved 相关字段
} 
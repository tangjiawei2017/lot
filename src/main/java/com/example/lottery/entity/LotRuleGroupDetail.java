package com.example.lottery.entity;

import lombok.Data;
import java.util.Date;

@Data
public class LotRuleGroupDetail {
    private Long id;
    private Long groupId;
    private String userCode;
    private Date createTime;
    // 只保留 drawLotsImproved 相关字段
} 
package com.example.lottery.entity;

import lombok.Data;
import java.util.Date;

/**
 * 抽签规则
 */
@Data
public class LotRule {
    private Long id;
    private Long signId;
    private String ruleName;
    private String fieldName;
    private String fieldOperator;
    private String fieldValue;
    private Integer priority;
    private Boolean isActive;
    private Date createdAt;
    private Date updatedAt;
    
    // 添加数量相关字段
    private String countSymbol;
    private Integer countValue;
    
    // 添加缺失的getter方法
    public String getFieldName() {
        return fieldName;
    }
    
    public String getFieldOperator() {
        return fieldOperator;
    }
    
    public String getFieldValue() {
        return fieldValue;
    }
    
    public Long getId() {
        return id;
    }
    
    public Integer getPriority() {
        return priority;
    }
    
    public Long getSignId() {
        return signId;
    }
    
    public String getCountSymbol() {
        return countSymbol;
    }
    
    public Integer getCountValue() {
        return countValue;
    }
} 
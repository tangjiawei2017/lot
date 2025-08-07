package com.example.lottery.entity;

import lombok.Data;
import java.util.Date;

/**
 * 用户自定义标签
 */
@Data
public class LotUserTags {
    private Long id;
    private String userCode;
    private String tagName;
    private String tagValue;
    private Date createTime;
    private Date updateTime;
    
    // 添加缺失的getter方法
    public String getUserCode() {
        return userCode;
    }
    
    public String getTagName() {
        return tagName;
    }
    
    public String getTagValue() {
        return tagValue;
    }
} 
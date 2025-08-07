package com.example.lottery.entity;

import lombok.Data;
import java.util.Date;

/**
 * 抽签签项
 */
@Data
public class LotSign {
    private Long id;
    private Long activityId;
    private String signName;
    private String signType;
    /**
     * 签项总容量，对应total_count
     */
    private Integer totalCount;
    public Integer getTotalCount() { return totalCount; }
    public void setTotalCount(Integer totalCount) { this.totalCount = totalCount; }
    private String signDescription;
    private Boolean isActive;
    private Date createdAt;
    private Date updatedAt;
    
    // 添加缺失的getter方法
    public Long getId() {
        return id;
    }
    
    public Integer getPriority() {
        return 0; // 默认优先级
    }
} 
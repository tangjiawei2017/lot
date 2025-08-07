package com.example.lottery.entity;

import lombok.Data;
import java.util.Date;

/**
 * 预处理统计信息
 */
@Data
public class PreprocessStats {
    private Long activityId;
    private Integer userCount;
    private Integer ruleCount;
    private Integer groupCount;
    private Long processingTime;
    private Date processTime;
} 
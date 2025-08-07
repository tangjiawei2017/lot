package com.example.lottery.entity;

import lombok.Data;
import java.util.Date;

/**
 * 用户参与活动表
 */
@Data
public class LotUserJoin {
    private Long id;
    private Long activityId;
    private String userCode;
    private Date joinTime;
    private Date createTime;
    private Date updateTime;
} 
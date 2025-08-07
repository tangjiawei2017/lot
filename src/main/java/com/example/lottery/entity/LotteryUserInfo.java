package com.example.lottery.entity;

import lombok.Data;
import java.util.Date;

/**
 * 抽签用户信息
 */
@Data
public class LotteryUserInfo {
    private String userCode;        // 用户编码
    private String businessGroup;   // 事业群
    private String department;      // 部门
    private String office;          // 科室
    private Date birthday;          // 生日
    private Boolean isManager;      // 是否领导
    private String gender;          // 性别
    private String jobTitle;        // 职务名称
    
    // 添加缺失的getter方法
    public String getUserCode() {
        return userCode;
    }
    
    public String getBusinessGroup() {
        return businessGroup;
    }
    
    public String getDepartment() {
        return department;
    }
    
    public String getOffice() {
        return office;
    }
    
    public Date getBirthday() {
        return birthday;
    }
    
    public Boolean getIsManager() {
        return isManager;
    }
    
    public String getGender() {
        return gender;
    }
    
    public String getJobTitle() {
        return jobTitle;
    }
} 
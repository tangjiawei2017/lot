package com.example.lottery.entity;

import lombok.Data;
import java.util.Date;
import java.util.Map;

/**
 * 用户完整信息（包含自定义标签）
 */
@Data
public class UserCompleteInfo {
    private String userCode;
    private String businessGroup;
    private String department;
    private String office;
    private Date birthday;
    private Boolean isManager;
    private String gender;
    private String jobTitle;
    private Map<String, String> customTags;  // 自定义标签
    
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
    
    public Map<String, String> getCustomTags() {
        return customTags;
    }
} 
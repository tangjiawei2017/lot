package com.example.lottery.mapper;

import com.example.lottery.entity.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface LotMapper {
    List<LotRuleGroup> getRuleGroupsByActivityId(@Param("activityId") Long activityId);
    List<LotRuleGroupDetail> getRuleGroupDetailsByGroupId(@Param("groupId") Long groupId);
    void batchInsertLotRecords(@Param("records") List<LotRecord> records);
    LotRule getRuleById(@Param("ruleId") Long ruleId);
    LotSign getSignById(@Param("signId") Long signId);
    void deleteLotRecordsByActivityId(@Param("activityId") Long activityId);
    void deleteLotRuleGroupDetailsByActivityId(@Param("activityId") Long activityId);
    void deleteLotRuleGroupsByActivityId(@Param("activityId") Long activityId);
    void insertLotRuleGroup(LotRuleGroup group);
    void insertLotRuleGroupDetail(@Param("groupId") Long groupId, @Param("userCode") String userCode);
    List<LotRecord> selectLotRecordsByActivityId(@Param("activityId") Long activityId);
    /**
     * 查询某活动下所有参与用户ID
     */
    List<String> getAllJoinUserIdsByActivityId(Long activityId);
    
    /**
     * 查询某活动下所有签项
     */
    List<LotSign> getSignsByActivityId(@Param("activityId") Long activityId);
    
    /**
     * 查询某活动下所有规则
     */
    List<LotRule> getRulesByActivityId(@Param("activityId") Long activityId);
    
    /**
     * 查询某签项下所有规则
     */
    List<LotRule> getRulesBySignId(@Param("signId") Long signId);
} 
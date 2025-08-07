package com.example.lottery.service;

import com.example.lottery.entity.LotRuleGroup;
import com.example.lottery.mapper.LotMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;
import java.util.Arrays;

import com.example.lottery.entity.LotRule;
import com.example.lottery.entity.LotSign;
import com.example.lottery.entity.LotRecord;
import com.example.lottery.entity.LotRuleGroupDetail;

import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.lottery.utils.SpringContextHolder;

/**
 * 重复分配异常
 */
class DuplicateAssignmentException extends RuntimeException {
    public DuplicateAssignmentException(String message) {
        super(message);
    }
}

/**
 * 分配验证异常
 */
class AssignmentValidationException extends RuntimeException {
    public AssignmentValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}

@Slf4j
@Service
public class LotDrawService {
    @Autowired
    private LotMapper lotMapper;

    // 抽签结果专用日志
    private static final Logger lotResultLogger = LoggerFactory.getLogger("lotResultLogger");

    public static void logDrawResult(Long activityId, MultiSignAssignResult result, Map<Long, List<LotRuleGroup>> signGroups, Map<Long, Map<Long, List<String>>> signGroupDetails, Map<Long, Integer> signTotalCounts, List<String> allUsers, long startTimeMillis) {
        StringBuilder output = new StringBuilder();
        long endTimeMillis = System.currentTimeMillis();
        output.append("===============活动ID:").append(activityId != null ? activityId : "(无)").append("  分配结果==========\n");
        output.append("分配开始时间:").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new java.util.Date(startTimeMillis))).append("\n");
        output.append("分配耗时:").append(endTimeMillis - startTimeMillis).append("ms\n");
        output.append("全局分配用户: ").append(allUsers.size()).append("人 ").append(allUsers).append("\n\n");
        output.append("===============分配明细 ===================\n\n");
        for (Map.Entry<Long, AssignResult> entry : result.signResults.entrySet()) {
            Long signId = entry.getKey();
            AssignResult signResult = entry.getValue();
            String signName = null;
            try {
                LotSign sign = SpringContextHolder.getBean(LotMapper.class).getSignById(signId);
                if (sign != null && sign.getSignName() != null && !sign.getSignName().trim().isEmpty())
                    signName = sign.getSignName();
            } catch (Exception ignore) {
            }
            output.append("签项 ");
            if (signName != null) output.append(signName).append(" ");
            output.append("ID: ").append(signId).append("   总分配: ").append(signResult.usedUserCodes.size()).append("人\n");
            Map<Long, List<String>> groupToUsers = signResult.groupToUsers;
            List<LotRuleGroup> groups = signGroups.get(signId);
            if (groups != null) {
                groups.sort(Comparator.comparingInt(LotRuleGroup::getPriority));
                for (LotRuleGroup group : groups) {
                    String ruleName = null;
                    try {
                        LotRule rule = SpringContextHolder.getBean(LotMapper.class).getRuleById(group.getRuleId());
                        if (rule != null && rule.getRuleName() != null && !rule.getRuleName().trim().isEmpty())
                            ruleName = rule.getRuleName();
                    } catch (Exception ignore) {
                    }
                    List<String> assigned = groupToUsers.get(group.getId());
                    List<String> candidates = signGroupDetails.getOrDefault(signId, Collections.emptyMap()).getOrDefault(group.getId(), new ArrayList<>());
                    String priorityStr = "P" + group.getPriority();
                    output.append("  - 规则ID:").append(group.getRuleId()).append("(").append(priorityStr).append("):");
                    output.append(" 匹配候选").append(candidates.size()).append("人, ");
                    output.append("实际分配").append(assigned != null ? assigned.size() : 0).append("人, ");
                    output.append("分配名单=").append(assigned != null ? assigned : Collections.emptyList()).append(", ");
                    output.append("匹配候选名单=").append(candidates).append(", ");
                    // 校验分配名单是否都属于候选名单
                    List<String> notInCandidates = new ArrayList<>();
                    if (assigned != null) {
                        for (String user : assigned) {
                            if (!candidates.contains(user)) {
                                notInCandidates.add(user);
                            }
                        }
                    }
                    output.append("规则要求").append(group.getCountSymbol()).append(group.getCountValue()).append("人");
                    if (assigned != null && !assigned.isEmpty()) {
                        if (notInCandidates.isEmpty()) {
                            // 全部在候选名单，不输出校验提示
                        } else {
                            output.append(" ❌分配名单 ").append(notInCandidates).append(" not in 匹配候选名单");
                        }
                    }
                    output.append("\n");
                }
            }
            // 补齐分配名单 groupId=-1L
            List<String> supplyUsers = groupToUsers != null ? groupToUsers.get(-1L) : null;
            int supplyCount = supplyUsers != null ? supplyUsers.size() : 0;
            output.append("  - 补齐分配: 实际分配").append(supplyCount).append("人, 分配名单=").append(supplyUsers != null ? supplyUsers : Collections.emptyList()).append("\n");
            
            // 兜底分配名单
            List<String> backupUsers = signResult.backupAssignedUsers;
            int backupCount = backupUsers != null ? backupUsers.size() : 0;
            if (backupCount > 0) {
                output.append("  - 兜底分配: 实际分配").append(backupCount).append("人, 分配名单=").append(backupUsers).append("\n");
            }
            
            int total = signTotalCounts.get(signId);
            output.append("  总分配: ").append(signResult.usedUserCodes.size()).append("/").append(total).append("人\n\n");
        }
        lotResultLogger.info(String.valueOf(output));
    }

    public static class AssignResult {
        public Map<Long, List<String>> groupToUsers = new HashMap<>();
        public Set<String> usedUserCodes = new HashSet<>();
        public int satisfiedGroupCount = 0;
        public List<String> backupAssignedUsers = new ArrayList<>(); // 兜底分配的用户列表
    }

    /**
     * 全局用户状态管理器 - 负责跨签项的用户分配状态管理
     */
    public static class GlobalUserManager {
        private Set<String> assignedUsers = new HashSet<>();
        private Map<String, Long> userToSignMap = new HashMap<>(); // 用户 -> 签项ID映射
        private Map<Long, Set<String>> signToUsersMap = new HashMap<>(); // 签项ID -> 用户集合

        /**
         * 检查用户是否已被分配
         */
        public boolean isUserAssigned(String userCode) {
            return assignedUsers.contains(userCode);
        }

        /**
         * 分配用户到指定签项（带重试机制）
         */
        public boolean assignUser(String userCode, Long signId) {
            return assignUserWithRetry(userCode, signId, 3); // 最多重试3次
        }
        
        /**
         * 分配用户到指定签项（带重试机制）
         */
        private boolean assignUserWithRetry(String userCode, Long signId, int maxRetries) {
            int retryCount = 0;
            while (retryCount <= maxRetries) {
                try {
                    return doAssignUser(userCode, signId);
                } catch (DuplicateAssignmentException e) {
                    log.error("[验证失败-重试{}] 重复分配: {}", retryCount, e.getMessage());
                    if (retryCount == maxRetries) {
                        log.error("[验证失败-最终] 重复分配重试{}次后仍然失败，用户: {}, 目标签项: {}", 
                            maxRetries, userCode, signId);
                        throw e;
                    }
                    retryCount++;
                    // 短暂延迟后重试
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("重试被中断", ie);
                    }
                } catch (AssignmentValidationException e) {
                    log.error("[验证失败-重试{}] 分配验证失败: {}", retryCount, e.getMessage());
                    if (retryCount == maxRetries) {
                        log.error("[验证失败-最终] 分配验证重试{}次后仍然失败，用户: {}, 目标签项: {}", 
                            maxRetries, userCode, signId);
                        throw e;
                    }
                    retryCount++;
                    // 短暂延迟后重试
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("重试被中断", ie);
                    }
                }
            }
            return false;
        }
        
        /**
         * 执行实际的用户分配
         */
        private boolean doAssignUser(String userCode, Long signId) {
            if (isUserAssigned(userCode)) {
                Long existingSignId = userToSignMap.get(userCode);
                String errorMsg = String.format("❌ 重复分配检测：用户 %s 已被分配到签项 %s，拒绝分配到签项 %s", 
                    userCode, existingSignId, signId);
                throw new DuplicateAssignmentException(errorMsg);
            }
            
            assignedUsers.add(userCode);
            userToSignMap.put(userCode, signId);
            signToUsersMap.computeIfAbsent(signId, k -> new HashSet<>()).add(userCode);
            
            // 分配后立即验证
            try {
                validateAssignment(userCode, signId);
            } catch (Exception e) {
                // 验证失败，回滚分配
                assignedUsers.remove(userCode);
                userToSignMap.remove(userCode);
                signToUsersMap.get(signId).remove(userCode);
                if (signToUsersMap.get(signId).isEmpty()) {
                    signToUsersMap.remove(signId);
                }
                throw new AssignmentValidationException("分配验证失败，已回滚分配: " + e.getMessage(), e);
            }
            return true;
        }

        /**
         * 获取用户被分配的签项ID
         */
        public Long getAssignedSign(String userCode) {
            return userToSignMap.get(userCode);
        }

        /**
         * 获取所有已分配用户
         */
        public Set<String> getAssignedUsers() {
            return new HashSet<>(assignedUsers);
        }

        /**
         * 验证分配的唯一性
         */
        public void validateAssignment(String userCode, Long signId) {
            // 验证用户是否在已分配集合中
            if (!assignedUsers.contains(userCode)) {
                throw new RuntimeException("❌ 验证失败：用户 " + userCode + " 不在已分配用户集合中");
            }
            
            // 验证用户映射是否正确
            Long mappedSignId = userToSignMap.get(userCode);
            if (!signId.equals(mappedSignId)) {
                throw new RuntimeException("❌ 验证失败：用户 " + userCode + " 的签项映射不一致，期望 " + signId + "，实际 " + mappedSignId);
            }
            
            // 验证签项用户集合是否包含该用户
            Set<String> signUsers = signToUsersMap.get(signId);
            if (signUsers == null || !signUsers.contains(userCode)) {
                throw new RuntimeException("❌ 验证失败：签项 " + signId + " 的用户集合中不包含用户 " + userCode);
            }
            
            // 验证全局唯一性
            long userCount = assignedUsers.size();
            long mappedCount = userToSignMap.size();
            long totalSignUsers = signToUsersMap.values().stream().mapToInt(Set::size).sum();
            
            if (userCount != mappedCount || userCount != totalSignUsers) {
                throw new RuntimeException("❌ 验证失败：用户数量不一致 - 已分配集合:" + userCount + 
                    ", 映射表:" + mappedCount + ", 签项用户总数:" + totalSignUsers);
            }
            
    
        }

        /**
         * 全局验证所有分配的唯一性（带重试机制）
         */
        public void validateAllAssignments() {
            validateAllAssignmentsWithRetry(3); // 最多重试3次
        }
        
        /**
         * 全局验证所有分配的唯一性（带重试机制）
         */
        private void validateAllAssignmentsWithRetry(int maxRetries) {
            int retryCount = 0;
            while (retryCount <= maxRetries) {
                try {
                    doValidateAllAssignments();
                    return; // 验证成功，直接返回
                } catch (Exception e) {
                    log.error("[验证失败-重试{}] 全局验证失败: {}", retryCount, e.getMessage());
                    if (retryCount == maxRetries) {
                        log.error("[验证失败-最终] 全局验证重试{}次后仍然失败", maxRetries);
                        throw new RuntimeException("全局验证最终失败: " + e.getMessage(), e);
                    }
                    retryCount++;
                    // 短暂延迟后重试
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("验证重试被中断", ie);
                    }
                }
            }
        }
        
        /**
         * 执行实际的全局验证
         */
        private void doValidateAllAssignments() {
            log.info("[验证] 开始全局验证分配唯一性...");
            
            // 验证集合大小一致性
            long userCount = assignedUsers.size();
            long mappedCount = userToSignMap.size();
            long totalSignUsers = signToUsersMap.values().stream().mapToInt(Set::size).sum();
            
            if (userCount != mappedCount || userCount != totalSignUsers) {
                String errorMsg = String.format("用户数量不一致 - 已分配集合:%d, 映射表:%d, 签项用户总数:%d", 
                    userCount, mappedCount, totalSignUsers);
                log.error("[验证失败] ❌ 全局验证失败：{}", errorMsg);
                throw new RuntimeException("❌ 全局验证失败：" + errorMsg);
            }
            
            // 验证每个用户的映射一致性
            for (String user : assignedUsers) {
                Long signId = userToSignMap.get(user);
                if (signId == null) {
                    String errorMsg = String.format("用户 %s 在已分配集合中但映射表中不存在", user);
                    log.error("[验证失败] ❌ 全局验证失败：{}", errorMsg);
                    throw new RuntimeException("❌ 全局验证失败：" + errorMsg);
                }
                
                Set<String> signUsers = signToUsersMap.get(signId);
                if (signUsers == null || !signUsers.contains(user)) {
                    String errorMsg = String.format("用户 %s 的签项映射不一致", user);
                    log.error("[验证失败] ❌ 全局验证失败：{}", errorMsg);
                    throw new RuntimeException("❌ 全局验证失败：" + errorMsg);
                }
            }
            
            // 验证每个签项的用户都在已分配集合中
            for (Map.Entry<Long, Set<String>> entry : signToUsersMap.entrySet()) {
                Long signId = entry.getKey();
                Set<String> signUsers = entry.getValue();
                
                for (String user : signUsers) {
                    if (!assignedUsers.contains(user)) {
                        String errorMsg = String.format("签项 %d 中的用户 %s 不在已分配集合中", signId, user);
                        log.error("[验证失败] ❌ 全局验证失败：{}", errorMsg);
                        throw new RuntimeException("❌ 全局验证失败：" + errorMsg);
                    }
                    
                    Long mappedSignId = userToSignMap.get(user);
                    if (!signId.equals(mappedSignId)) {
                        String errorMsg = String.format("用户 %s 的签项映射不一致", user);
                        log.error("[验证失败] ❌ 全局验证失败：{}", errorMsg);
                        throw new RuntimeException("❌ 全局验证失败：" + errorMsg);
                    }
                }
            }
            
            log.info("[验证通过] 全局验证完成，共 {} 个用户分配正确", userCount);
        }

        /**
         * 获取分配统计信息
         */
        public String getAssignmentStats() {
            StringBuilder stats = new StringBuilder();
            stats.append("分配统计:\n");
            stats.append("- 总分配用户数: ").append(assignedUsers.size()).append("\n");
            stats.append("- 映射表大小: ").append(userToSignMap.size()).append("\n");
            stats.append("- 签项数量: ").append(signToUsersMap.size()).append("\n");
            
            for (Map.Entry<Long, Set<String>> entry : signToUsersMap.entrySet()) {
                stats.append("- 签项 ").append(entry.getKey()).append(": ").append(entry.getValue().size()).append(" 人\n");
            }
            
            return stats.toString();
        }
    }

    /**
     * 多签项协调分配结果
     */
    public static class MultiSignAssignResult {
        public Map<Long, AssignResult> signResults = new HashMap<>(); // 签项ID -> 单签项结果
        public Set<String> totalAssignedUsers = new HashSet<>();
        public GlobalUserManager globalUserManager;
    }

    /**
     * 多签项协调分配 - 全局规则优先级调度
     * 核心思想：所有priority=1规则先执行，然后priority=2规则，不分签项
     * P1规则必须100%满足，如果无法满足则报错
     * 分配流程：
     * 1. 收集所有规则，按优先级分组
     * 2. 先全局分配P1规则（全局最优）
     * 3. 再按优先级顺序分配非P1规则
     * 4. 每个签项补齐剩余容量
     * 5. 全局唯一补齐，确保所有用户分配唯一签项
     * 6. 输出分配日志
     *
     * @param activityId       活动ID
     * @param signGroups       签项ID -> 规则组列表
     * @param signGroupDetails 签项ID -> (规则组ID -> 候选用户列表)
     * @param signTotalCounts  签项ID -> 签项总容量
     * @param globalUserPool   全部可分配用户
     * @return 分配结果
     */
    public MultiSignAssignResult drawLotsMultiSign(Long activityId,
                                                   Map<Long, List<LotRuleGroup>> signGroups,
                                                   Map<Long, Map<Long, List<String>>> signGroupDetails,
                                                   Map<Long, Integer> signTotalCounts,
                                                   Collection<String> globalUserPool) {
        return drawLotsMultiSignWithRetry(activityId, signGroups, signGroupDetails, signTotalCounts, globalUserPool, 0);
    }
    
    /**
     * 带重试机制的多签项协调分配
     */
    private MultiSignAssignResult drawLotsMultiSignWithRetry(Long activityId,
                                                             Map<Long, List<LotRuleGroup>> signGroups,
                                                             Map<Long, Map<Long, List<String>>> signGroupDetails,
                                                             Map<Long, Integer> signTotalCounts,
                                                             Collection<String> globalUserPool,
                                                             int retryCount) {
        // === 1. 全局预判 ===
        int totalTarget = signTotalCounts.values().stream().mapToInt(Integer::intValue).sum();
        if (globalUserPool.size() < totalTarget) {
            throw new RuntimeException("全局用户池人数不足，无法满足所有签项目标人数！");
        }
        long start = System.currentTimeMillis();
        log.info("[抽签] 活动{} - 开始多签项协调分配 (第{}次尝试)", activityId, retryCount + 1);
        Random random = ThreadLocalRandom.current();
        MultiSignAssignResult result = new MultiSignAssignResult();
        GlobalUserManager globalUserManager = new GlobalUserManager();

        log.info("[抽签] 活动{} - 开始分组规则收集", activityId);
        // 1. 收集所有规则，按优先级分组
        List<RuleExecution> allRules = new ArrayList<>();
        List<RuleExecution> p1Rules = new ArrayList<>();
        for (Map.Entry<Long, List<LotRuleGroup>> entry : signGroups.entrySet()) {
            Long signId = entry.getKey();
            List<LotRuleGroup> groups = entry.getValue();
            Map<Long, List<String>> groupDetails = signGroupDetails.get(signId);
            for (LotRuleGroup group : groups) {
                List<String> candidates = groupDetails.get(group.getId());
                if (candidates != null && !candidates.isEmpty()) {
                    RuleExecution ruleExec = new RuleExecution(signId, group, candidates);
                    allRules.add(ruleExec);
                    if (group.getPriority() == 1) {
                        p1Rules.add(ruleExec);
                    }
                }
            }
        }
        log.info("[抽签] 活动{} - P1规则{}条, 非P1规则{}条", activityId, p1Rules.size(), allRules.size() - p1Rules.size());

        // 2. 初始化每个签项的分配结果和计数器
        for (Long signId : signGroups.keySet()) {
            AssignResult signResult = new AssignResult();
            signResult.groupToUsers = new HashMap<>();
            signResult.usedUserCodes = new HashSet<>();
            signResult.satisfiedGroupCount = 0;
            result.signResults.put(signId, signResult);
        }

        // 3. P1规则全局优化处理
        if (!p1Rules.isEmpty()) {
            log.info("[抽签] 活动{} - 开始P1规则全局分配", activityId);
            P1OptimizationResult p1Result = optimizeP1Rules(p1Rules, signTotalCounts, activityId);
            if (!p1Result.isSuccessful()) {
                log.info("[抽签] 活动{} - P1规则全局分配失败: {}", activityId, p1Result.getConflictMessages());
                throw new RuntimeException("❌ P1规则冲突无法解决：" + String.join(", ", p1Result.getConflictMessages()));
            }
            // 应用P1规则的分配结果
            for (Map.Entry<Long, List<String>> entry : p1Result.getRuleAssignments().entrySet()) {
                Long ruleId = entry.getKey();
                List<String> assignedUsers = entry.getValue();
                RuleExecution ruleExec = p1Rules.stream()
                        .filter(r -> r.group.getRuleId().equals(ruleId))
                        .findFirst().orElse(null);
                if (ruleExec != null) {
                    Long signId = ruleExec.signId;
                    AssignResult signResult = result.signResults.get(signId);
                    
                    // === 严格检查：P1规则分配前检查签项剩余容量 ===
                    Integer signTotalCount = signTotalCounts.get(signId);
                    int currentSignAssigned = signResult.usedUserCodes.size();
                    int signRemainingCapacity = signTotalCount != null ? (signTotalCount - currentSignAssigned) : Integer.MAX_VALUE;
                    
                    // 严格限制P1规则分配数量不超过签项剩余容量
                    int actualAssignCount = Math.min(assignedUsers.size(), signRemainingCapacity);
                    List<String> actualAssignedUsers = assignedUsers.subList(0, actualAssignCount);
                    
                    if (actualAssignCount < assignedUsers.size()) {
                        log.warn("[抽签] 活动{} - P1规则{}分配数量从{}调整为{}（签项{}剩余容量限制）", 
                            activityId, ruleExec.group.getId(), assignedUsers.size(), actualAssignCount, signId);
                    }
                    
                    for (String user : actualAssignedUsers) {
                        globalUserManager.assignUser(user, signId);
                        signResult.usedUserCodes.add(user);
                    }
                    signResult.groupToUsers.put(ruleExec.group.getId(), new ArrayList<>(actualAssignedUsers));
                    signResult.satisfiedGroupCount++; // P1规则一定满足
                    
                    log.info("[抽签] 活动{} - P1规则{}分配{}人（签项{}剩余容量：{}人）", 
                        activityId, ruleExec.group.getId(), actualAssignedUsers.size(), signId, signRemainingCapacity);
                }
            }
            log.info("[抽签] 活动{} - P1规则全局分配完成", activityId);
            
            // P1规则分配后验证
            log.info("[抽签] 活动{} - P1规则分配后验证", activityId);
            globalUserManager.validateAllAssignments();
            log.info("[抽签] 活动{} - P1规则分配验证通过", activityId);
        }

        // 4. 按全局优先级顺序执行非P1规则
        List<RuleExecution> nonP1Rules = allRules.stream()
                .filter(r -> r.group.getPriority() != 1)
                .sorted((r1, r2) -> {
                    int priorityCompare = Integer.compare(r1.group.getPriority(), r2.group.getPriority());
                    if (priorityCompare != 0) return priorityCompare;
                    int signCompare = Long.compare(r1.signId, r2.signId);
                    if (signCompare != 0) return signCompare;
                    return Long.compare(r1.group.getRuleId(), r2.group.getRuleId());
                })
                .collect(Collectors.toList());
        log.info("[抽签] 活动{} - 开始非P1规则分配，共{}条", activityId, nonP1Rules.size());

        // === 新增：同优先级、同规则符号、同候选名单时均匀分配 ===
        int idx = 0;
        while (idx < nonP1Rules.size()) {
            int curPriority = nonP1Rules.get(idx).group.getPriority();
            String curSymbol = nonP1Rules.get(idx).group.getCountSymbol();
            // 收集同一优先级、同规则符号、同候选名单的所有规则（规则值可以不同）
            List<RuleExecution> batch = new ArrayList<>();
            List<String> firstCandidates = nonP1Rules.get(idx).candidates;
            int j = idx;
            while (j < nonP1Rules.size()) {
                RuleExecution r = nonP1Rules.get(j);
                if (r.group.getPriority() == curPriority &&
                    r.group.getCountSymbol().equals(curSymbol) &&
                    r.candidates.size() == firstCandidates.size() &&
                    new HashSet<>(r.candidates).equals(new HashSet<>(firstCandidates))) {
                    batch.add(r);
                    j++;
                } else {
                    break;
                }
            }
            // 如果batch大于1且候选名单完全一样，进行智能分配（尽量满足所有规则）
            if (batch.size() > 1) {
                log.info("[抽签] 活动{} - 发现{}个同优先级、同候选名单的规则，进行智能分配", activityId, batch.size());
                
                // 过滤掉已经被分配的用户
                List<String> allCandidates = firstCandidates.stream()
                        .filter(user -> !globalUserManager.isUserAssigned(user))
                        .collect(Collectors.toList());
                Collections.shuffle(allCandidates, random);
                int M = allCandidates.size();
                
                if (M == 0) {
                    log.info("[抽签] 活动{} - 候选名单为空，跳过智能分配", activityId);
                    idx = j;
                    continue;
                }
                
                // 按规则类型分组：= > >= <=
                List<RuleExecution> exactRules = new ArrayList<>();    // = 规则
                List<RuleExecution> greaterRules = new ArrayList<>();  // > 规则  
                List<RuleExecution> greaterEqualRules = new ArrayList<>(); // >= 规则
                List<RuleExecution> lessEqualRules = new ArrayList<>();    // <= 规则
                
                for (RuleExecution r : batch) {
                    String symbol = r.group.getCountSymbol();
                    switch (symbol) {
                        case "=":
                            exactRules.add(r);
                            break;
                        case ">":
                            greaterRules.add(r);
                            break;
                        case ">=":
                            greaterEqualRules.add(r);
                            break;
                        case "<=":
                            lessEqualRules.add(r);
                            break;
                    }
                }
                
                log.info("[抽签] 活动{} - 候选人数: {}, 规则分布: ={}个, >{}个, >={}个, <={}个", 
                    activityId, M, exactRules.size(), greaterRules.size(), greaterEqualRules.size(), lessEqualRules.size());
                
                int candidateIndex = 0;
                
                // 1. 优先处理 = 规则（精确匹配，必须满足）
                for (RuleExecution r : exactRules) {
                    int value = r.group.getCountValue();
                    // 修复：检查签项剩余容量，而不是候选人数
                    AssignResult signResult = result.signResults.get(r.signId);
                    Integer signTotalCount = signTotalCounts.get(r.signId);
                    int currentSignAssigned = signResult.usedUserCodes.size();
                    int signRemainingCapacity = signTotalCount != null ? (signTotalCount - currentSignAssigned) : Integer.MAX_VALUE;
                    
                    // 以签项剩余容量为准
                    int assignCount = Math.min(value, signRemainingCapacity);
                    
                    if (assignCount > 0 && candidateIndex + assignCount <= M) {
                        List<String> assignedUsers = allCandidates.subList(candidateIndex, candidateIndex + assignCount);
                        for (String user : assignedUsers) {
                            globalUserManager.assignUser(user, r.signId);
                            signResult.usedUserCodes.add(user);
                        }
                        signResult.groupToUsers.put(r.group.getId(), new ArrayList<>(assignedUsers));
                        if (isRuleSatisfied(r.group, assignedUsers.size())) {
                            signResult.satisfiedGroupCount++;
                        }
                        candidateIndex += assignCount;
                        log.info("[抽签] 活动{} - 精确规则{}分配{}人（签项容量限制）", activityId, r.group.getId(), assignCount);
                    } else {
                        log.warn("[抽签] 活动{} - 精确规则{}无法满足，需要{}人，签项剩余容量{}人", activityId, r.group.getId(), value, signRemainingCapacity);
                    }
                }
                
                // 2. 处理 > 规则（大于，尽量满足）
                for (RuleExecution r : greaterRules) {
                    int value = r.group.getCountValue();
                    // 修复：检查签项剩余容量
                    AssignResult signResult = result.signResults.get(r.signId);
                    Integer signTotalCount = signTotalCounts.get(r.signId);
                    int currentSignAssigned = signResult.usedUserCodes.size();
                    int signRemainingCapacity = signTotalCount != null ? (signTotalCount - currentSignAssigned) : Integer.MAX_VALUE;
                    
                    // 大于value，所以至少value+1，但不能超过签项剩余容量
                    int assignCount = Math.min(Math.min(value + 1, M - candidateIndex), signRemainingCapacity);
                    if (assignCount > 0) {
                        List<String> assignedUsers = allCandidates.subList(candidateIndex, candidateIndex + assignCount);
                        for (String user : assignedUsers) {
                            globalUserManager.assignUser(user, r.signId);
                            signResult.usedUserCodes.add(user);
                        }
                        signResult.groupToUsers.put(r.group.getId(), new ArrayList<>(assignedUsers));
                        if (isRuleSatisfied(r.group, assignedUsers.size())) {
                            signResult.satisfiedGroupCount++;
                        }
                        candidateIndex += assignCount;
                        log.info("[抽签] 活动{} - 大于规则{}分配{}人（签项容量限制）", activityId, r.group.getId(), assignCount);
                    }
                }
                
                // 3. 处理 >= 规则（智能混合分配策略）
                if (!greaterEqualRules.isEmpty() && candidateIndex < M) {
                    int remainingCandidates = M - candidateIndex;
                    
                    // 统计各条件出现的次数
                    Map<Integer, List<RuleExecution>> valueToRules = new HashMap<>();
                    for (RuleExecution r : greaterEqualRules) {
                        int value = r.group.getCountValue();
                        valueToRules.computeIfAbsent(value, k -> new ArrayList<>()).add(r);
                    }
                    
                    // 找出主流条件（出现次数最多的条件）
                    int mainstreamValue = valueToRules.entrySet().stream()
                            .max(Map.Entry.<Integer, List<RuleExecution>>comparingByValue((a, b) -> Integer.compare(a.size(), b.size())))
                            .map(Map.Entry::getKey)
                            .orElse(0);
                    
                    List<RuleExecution> mainstreamRules = valueToRules.get(mainstreamValue);
                    List<RuleExecution> nonMainstreamRules = greaterEqualRules.stream()
                            .filter(r -> r.group.getCountValue() != mainstreamValue)
                            .collect(Collectors.toList());
                    
                    log.info("[抽签] 活动{} - >=规则分析: 主流条件{}出现{}次，非主流条件{}个", 
                        activityId, mainstreamValue, mainstreamRules.size(), nonMainstreamRules.size());
                    
                    // 如果主流条件占比超过50%，采用混合分配策略
                    if (mainstreamRules.size() > greaterEqualRules.size() / 2) {
                        log.info("[抽签] 活动{} - 采用混合分配策略", activityId);
                        
                        // 3.1 先处理主流条件（平均分配）
                        if (!mainstreamRules.isEmpty()) {
                            int N = mainstreamRules.size();
                            int perGroup = Math.min(mainstreamValue, remainingCandidates / N);
                            
                            log.info("[抽签] 活动{} - 主流条件{}平均分配，每组{}人", activityId, mainstreamValue, perGroup);
                            
                            for (int i = 0; i < mainstreamRules.size(); i++) {
                                RuleExecution r = mainstreamRules.get(i);
                                int assignCount = perGroup;
                                if (i < remainingCandidates % N && perGroup < mainstreamValue) {
                                    assignCount++; // 尽量均匀
                                }
                                
                                if (assignCount > 0 && candidateIndex + assignCount <= M) {
                                    List<String> assignedUsers = allCandidates.subList(candidateIndex, candidateIndex + assignCount);
                                    AssignResult signResult = result.signResults.get(r.signId);
                                    for (String user : assignedUsers) {
                                        globalUserManager.assignUser(user, r.signId);
                                        signResult.usedUserCodes.add(user);
                                    }
                                    signResult.groupToUsers.put(r.group.getId(), new ArrayList<>(assignedUsers));
                                    if (isRuleSatisfied(r.group, assignedUsers.size())) {
                                        signResult.satisfiedGroupCount++;
                                    }
                                    candidateIndex += assignCount;
                                    log.info("[抽签] 活动{} - 主流规则{}平均分配{}人", activityId, r.group.getId(), assignCount);
                                }
                            }
                        }
                        
                        // 3.2 再处理非主流条件（按需分配）
                        if (!nonMainstreamRules.isEmpty() && candidateIndex < M) {
                            int remainingAfterMainstream = M - candidateIndex;
                            int totalNonMainstreamDemand = nonMainstreamRules.stream()
                                    .mapToInt(r -> r.group.getCountValue())
                                    .sum();
                            
                            if (remainingAfterMainstream >= totalNonMainstreamDemand) {
                                // 剩余人数足够，按需求分配
                                for (RuleExecution r : nonMainstreamRules) {
                                    int value = r.group.getCountValue();
                                    if (candidateIndex + value <= M) {
                                        List<String> assignedUsers = allCandidates.subList(candidateIndex, candidateIndex + value);
                                        AssignResult signResult = result.signResults.get(r.signId);
                                        for (String user : assignedUsers) {
                                            globalUserManager.assignUser(user, r.signId);
                                            signResult.usedUserCodes.add(user);
                                        }
                                        signResult.groupToUsers.put(r.group.getId(), new ArrayList<>(assignedUsers));
                                        if (isRuleSatisfied(r.group, assignedUsers.size())) {
                                            signResult.satisfiedGroupCount++;
                                        }
                                        candidateIndex += value;
                                        log.info("[抽签] 活动{} - 非主流规则{}按需求分配{}人", activityId, r.group.getId(), value);
                                    }
                                }
                            } else {
                                // 剩余人数不足，按比例分配
                                int totalValue = nonMainstreamRules.stream().mapToInt(r -> r.group.getCountValue()).sum();
                                int assignedIndex = 0;
                                
                                for (int i = 0; i < nonMainstreamRules.size(); i++) {
                                    RuleExecution r = nonMainstreamRules.get(i);
                                    int value = r.group.getCountValue();
                                    int assignCount = (int) Math.round((double) remainingAfterMainstream * value / totalValue);
                                    
                                    if (i == nonMainstreamRules.size() - 1) {
                                        assignCount = remainingAfterMainstream - assignedIndex;
                                    }
                                    
                                    if (assignCount > 0 && assignedIndex + assignCount <= remainingAfterMainstream) {
                                        List<String> assignedUsers = allCandidates.subList(candidateIndex + assignedIndex, candidateIndex + assignedIndex + assignCount);
                                        AssignResult signResult = result.signResults.get(r.signId);
                                        
                                        // === 严格检查：P1规则分配前检查签项剩余容量 ===
                                        Integer signTotalCount = signTotalCounts.get(r.signId);
                                        int currentSignAssigned = signResult.usedUserCodes.size();
                                        int signRemainingCapacity = signTotalCount != null ? (signTotalCount - currentSignAssigned) : Integer.MAX_VALUE;
                                        
                                        // 如果签项已达到目标人数，跳过此规则
                                        if (signTotalCount != null && currentSignAssigned >= signTotalCount) {
                                            log.info("[抽签] 活动{} - P1规则{}分配前发现签项{}已达到目标人数{}人，跳过", 
                                                activityId, r.group.getId(), r.signId, signTotalCount);
                                            continue;
                                        }
                                        
                                        // 如果签项剩余容量不足，调整分配数量
                                        int actualAssignCount = Math.min(assignCount, signRemainingCapacity);
                                        if (actualAssignCount < assignCount) {
                                            log.warn("[抽签] 活动{} - P1规则{}分配数量从{}调整为{}（签项{}剩余容量限制）", 
                                                activityId, r.group.getId(), assignCount, actualAssignCount, r.signId);
                                            assignedUsers = allCandidates.subList(candidateIndex + assignedIndex, candidateIndex + assignedIndex + actualAssignCount);
                                        }
                                        
                                        for (String user : assignedUsers) {
                                            globalUserManager.assignUser(user, r.signId);
                                            signResult.usedUserCodes.add(user);
                                        }
                                        signResult.groupToUsers.put(r.group.getId(), new ArrayList<>(assignedUsers));
                                        if (isRuleSatisfied(r.group, assignedUsers.size())) {
                                            signResult.satisfiedGroupCount++;
                                        }
                                        assignedIndex += actualAssignCount;
                                        log.info("[抽签] 活动{} - 非主流规则{}按比例分配{}人（签项{}剩余容量：{}人）", 
                                            activityId, r.group.getId(), actualAssignCount, r.signId, signRemainingCapacity);
                                    }
                                }
                                candidateIndex = M; // 所有候选人都已分配
                            }
                        }
                        
                        // 3.3 剩余用户按整体比例分配
                        if (candidateIndex < M) {
                            List<String> remainingUsers = allCandidates.subList(candidateIndex, M);
                            int totalValue = greaterEqualRules.stream().mapToInt(r -> r.group.getCountValue()).sum();
                            int assignedIndex = 0;
                            
                            for (int i = 0; i < greaterEqualRules.size(); i++) {
                                RuleExecution r = greaterEqualRules.get(i);
                                int value = r.group.getCountValue();
                                int extraCount = (int) Math.round((double) remainingUsers.size() * value / totalValue);
                                
                                if (i == greaterEqualRules.size() - 1) {
                                    extraCount = remainingUsers.size() - assignedIndex;
                                }
                                
                                if (extraCount > 0 && assignedIndex + extraCount <= remainingUsers.size()) {
                                    List<String> extraUsers = remainingUsers.subList(assignedIndex, assignedIndex + extraCount);
                                    AssignResult signResult = result.signResults.get(r.signId);
                                    
                                    // === 严格检查：P1规则分配前检查签项剩余容量 ===
                                    Integer signTotalCount = signTotalCounts.get(r.signId);
                                    int currentSignAssigned = signResult.usedUserCodes.size();
                                    int signRemainingCapacity = signTotalCount != null ? (signTotalCount - currentSignAssigned) : Integer.MAX_VALUE;
                                    
                                    // 如果签项已达到目标人数，跳过此规则
                                    if (signTotalCount != null && currentSignAssigned >= signTotalCount) {
                                        log.info("[抽签] 活动{} - P1规则{}额外分配前发现签项{}已达到目标人数{}人，跳过", 
                                            activityId, r.group.getId(), r.signId, signTotalCount);
                                        continue;
                                    }
                                    
                                    // 如果签项剩余容量不足，调整分配数量
                                    int actualExtraCount = Math.min(extraCount, signRemainingCapacity);
                                    if (actualExtraCount < extraCount) {
                                        log.warn("[抽签] 活动{} - P1规则{}额外分配数量从{}调整为{}（签项{}剩余容量限制）", 
                                            activityId, r.group.getId(), extraCount, actualExtraCount, r.signId);
                                        extraUsers = remainingUsers.subList(assignedIndex, assignedIndex + actualExtraCount);
                                    }
                                    
                                    for (String user : extraUsers) {
                                        globalUserManager.assignUser(user, r.signId);
                                        signResult.usedUserCodes.add(user);
                                    }
                                    signResult.groupToUsers.get(r.group.getId()).addAll(extraUsers);
                                    assignedIndex += actualExtraCount;
                                    log.info("[抽签] 活动{} - 规则{}额外分配{}人（签项{}剩余容量：{}人）", 
                                        activityId, r.group.getId(), actualExtraCount, r.signId, signRemainingCapacity);
                                }
                            }
                            candidateIndex = M; // 所有候选人都已分配
                        }
                        
                    } else {
                        // 没有明显的主流条件，使用原有的按比例分配策略
                        log.info("[抽签] 活动{} - 无主流条件，使用按比例分配策略", activityId);
                        
                        int totalDemand = greaterEqualRules.stream().mapToInt(r -> r.group.getCountValue()).sum();
                        
                        if (remainingCandidates >= totalDemand) {
                            // 候选人数足够，按需求分配
                            for (RuleExecution r : greaterEqualRules) {
                                int value = r.group.getCountValue();
                                if (candidateIndex + value <= M) {
                                    List<String> assignedUsers = allCandidates.subList(candidateIndex, candidateIndex + value);
                                    AssignResult signResult = result.signResults.get(r.signId);
                                    
                                    // === 严格检查：P1规则分配前检查签项剩余容量 ===
                                    Integer signTotalCount = signTotalCounts.get(r.signId);
                                    int currentSignAssigned = signResult.usedUserCodes.size();
                                    int signRemainingCapacity = signTotalCount != null ? (signTotalCount - currentSignAssigned) : Integer.MAX_VALUE;
                                    
                                    // 如果签项已达到目标人数，跳过此规则
                                    if (signTotalCount != null && currentSignAssigned >= signTotalCount) {
                                        log.info("[抽签] 活动{} - P1规则{}分配前发现签项{}已达到目标人数{}人，跳过", 
                                            activityId, r.group.getId(), r.signId, signTotalCount);
                                        continue;
                                    }
                                    
                                    // 如果签项剩余容量不足，调整分配数量
                                    int actualAssignCount = Math.min(value, signRemainingCapacity);
                                    if (actualAssignCount < value) {
                                        log.warn("[抽签] 活动{} - P1规则{}分配数量从{}调整为{}（签项{}剩余容量限制）", 
                                            activityId, r.group.getId(), value, actualAssignCount, r.signId);
                                        assignedUsers = allCandidates.subList(candidateIndex, candidateIndex + actualAssignCount);
                                    }
                                    
                                    for (String user : assignedUsers) {
                                        globalUserManager.assignUser(user, r.signId);
                                        signResult.usedUserCodes.add(user);
                                    }
                                    signResult.groupToUsers.put(r.group.getId(), new ArrayList<>(assignedUsers));
                                    if (isRuleSatisfied(r.group, assignedUsers.size())) {
                                        signResult.satisfiedGroupCount++;
                                    }
                                    candidateIndex += actualAssignCount;
                                    log.info("[抽签] 活动{} - 大于等于规则{}按需求分配{}人（签项{}剩余容量：{}人）", 
                                        activityId, r.group.getId(), actualAssignCount, r.signId, signRemainingCapacity);
                                }
                            }
                            
                            // 剩余用户按比例分配
                            if (candidateIndex < M) {
                                List<String> remainingUsers = allCandidates.subList(candidateIndex, M);
                                int totalValue = greaterEqualRules.stream().mapToInt(r -> r.group.getCountValue()).sum();
                                int assignedIndex = 0;
                                
                                for (int i = 0; i < greaterEqualRules.size(); i++) {
                                    RuleExecution r = greaterEqualRules.get(i);
                                    int value = r.group.getCountValue();
                                    int extraCount = (int) Math.round((double) remainingUsers.size() * value / totalValue);
                                    
                                    if (i == greaterEqualRules.size() - 1) {
                                        extraCount = remainingUsers.size() - assignedIndex;
                                    }
                                    
                                    if (extraCount > 0 && assignedIndex + extraCount <= remainingUsers.size()) {
                                        List<String> extraUsers = remainingUsers.subList(assignedIndex, assignedIndex + extraCount);
                                        AssignResult signResult = result.signResults.get(r.signId);
                                        
                                        // === 严格检查：P1规则分配前检查签项剩余容量 ===
                                        Integer signTotalCount = signTotalCounts.get(r.signId);
                                        int currentSignAssigned = signResult.usedUserCodes.size();
                                        int signRemainingCapacity = signTotalCount != null ? (signTotalCount - currentSignAssigned) : Integer.MAX_VALUE;
                                        
                                        // 如果签项已达到目标人数，跳过此规则
                                        if (signTotalCount != null && currentSignAssigned >= signTotalCount) {
                                            log.info("[抽签] 活动{} - P1规则{}额外分配前发现签项{}已达到目标人数{}人，跳过", 
                                                activityId, r.group.getId(), r.signId, signTotalCount);
                                            continue;
                                        }
                                        
                                        // 如果签项剩余容量不足，调整分配数量
                                        int actualExtraCount = Math.min(extraCount, signRemainingCapacity);
                                        if (actualExtraCount < extraCount) {
                                            log.warn("[抽签] 活动{} - P1规则{}额外分配数量从{}调整为{}（签项{}剩余容量限制）", 
                                                activityId, r.group.getId(), extraCount, actualExtraCount, r.signId);
                                            extraUsers = remainingUsers.subList(assignedIndex, assignedIndex + actualExtraCount);
                                        }
                                        
                                        for (String user : extraUsers) {
                                            globalUserManager.assignUser(user, r.signId);
                                            signResult.usedUserCodes.add(user);
                                        }
                                        signResult.groupToUsers.get(r.group.getId()).addAll(extraUsers);
                                        assignedIndex += actualExtraCount;
                                        log.info("[抽签] 活动{} - 大于等于规则{}额外分配{}人（签项{}剩余容量：{}人）", 
                                            activityId, r.group.getId(), actualExtraCount, r.signId, signRemainingCapacity);
                                    }
                                }
                                candidateIndex = M; // 所有候选人都已分配
                            }
                        } else {
                            // 候选人数不足，按比例分配
                            int totalValue = greaterEqualRules.stream().mapToInt(r -> r.group.getCountValue()).sum();
                            int assignedIndex = 0;
                            
                            for (int i = 0; i < greaterEqualRules.size(); i++) {
                                RuleExecution r = greaterEqualRules.get(i);
                                int value = r.group.getCountValue();
                                int assignCount = (int) Math.round((double) remainingCandidates * value / totalValue);
                                
                                if (i == greaterEqualRules.size() - 1) {
                                    assignCount = remainingCandidates - assignedIndex;
                                }
                                
                                if (assignCount > 0 && assignedIndex + assignCount <= remainingCandidates) {
                                    List<String> assignedUsers = allCandidates.subList(candidateIndex + assignedIndex, candidateIndex + assignedIndex + assignCount);
                                    AssignResult signResult = result.signResults.get(r.signId);
                                    
                                    // === 严格检查：P1规则分配前检查签项剩余容量 ===
                                    Integer signTotalCount = signTotalCounts.get(r.signId);
                                    int currentSignAssigned = signResult.usedUserCodes.size();
                                    int signRemainingCapacity = signTotalCount != null ? (signTotalCount - currentSignAssigned) : Integer.MAX_VALUE;
                                    
                                    // 如果签项已达到目标人数，跳过此规则
                                    if (signTotalCount != null && currentSignAssigned >= signTotalCount) {
                                        log.info("[抽签] 活动{} - P1规则{}分配前发现签项{}已达到目标人数{}人，跳过", 
                                            activityId, r.group.getId(), r.signId, signTotalCount);
                                        continue;
                                    }
                                    
                                    // 如果签项剩余容量不足，调整分配数量
                                    int actualAssignCount = Math.min(assignCount, signRemainingCapacity);
                                    if (actualAssignCount < assignCount) {
                                        log.warn("[抽签] 活动{} - P1规则{}分配数量从{}调整为{}（签项{}剩余容量限制）", 
                                            activityId, r.group.getId(), assignCount, actualAssignCount, r.signId);
                                        assignedUsers = allCandidates.subList(candidateIndex + assignedIndex, candidateIndex + assignedIndex + actualAssignCount);
                                    }
                                    
                                    for (String user : assignedUsers) {
                                        globalUserManager.assignUser(user, r.signId);
                                        signResult.usedUserCodes.add(user);
                                    }
                                    signResult.groupToUsers.put(r.group.getId(), new ArrayList<>(assignedUsers));
                                    if (isRuleSatisfied(r.group, assignedUsers.size())) {
                                        signResult.satisfiedGroupCount++;
                                    }
                                    assignedIndex += actualAssignCount;
                                    log.info("[抽签] 活动{} - 大于等于规则{}按比例分配{}人（签项{}剩余容量：{}人）", 
                                        activityId, r.group.getId(), actualAssignCount, r.signId, signRemainingCapacity);
                                }
                            }
                            candidateIndex = M; // 所有候选人都已分配
                        }
                    }
                }
                
                // 4. 处理 <= 规则（小于等于，在剩余用户中按比例分配）
                if (!lessEqualRules.isEmpty() && candidateIndex < M) {
                    int remainingCandidates = M - candidateIndex;
                    int totalValue = lessEqualRules.stream().mapToInt(r -> r.group.getCountValue()).sum();
                    int assignedIndex = 0;
                    
                    for (int i = 0; i < lessEqualRules.size(); i++) {
                        RuleExecution r = lessEqualRules.get(i);
                        int value = r.group.getCountValue();
                        int assignCount = Math.min(value, (int) Math.round((double) remainingCandidates * value / totalValue));
                        
                        if (i == lessEqualRules.size() - 1) {
                            assignCount = remainingCandidates - assignedIndex;
                        }
                        
                        if (assignCount > 0 && assignedIndex + assignCount <= remainingCandidates) {
                            List<String> assignedUsers = allCandidates.subList(candidateIndex + assignedIndex, candidateIndex + assignedIndex + assignCount);
                            AssignResult signResult = result.signResults.get(r.signId);
                            
                            // === 严格检查：P1规则分配前检查签项剩余容量 ===
                            Integer signTotalCount = signTotalCounts.get(r.signId);
                            int currentSignAssigned = signResult.usedUserCodes.size();
                            int signRemainingCapacity = signTotalCount != null ? (signTotalCount - currentSignAssigned) : Integer.MAX_VALUE;
                            
                            // 如果签项已达到目标人数，跳过此规则
                            if (signTotalCount != null && currentSignAssigned >= signTotalCount) {
                                log.info("[抽签] 活动{} - P1规则{}分配前发现签项{}已达到目标人数{}人，跳过", 
                                    activityId, r.group.getId(), r.signId, signTotalCount);
                                continue;
                            }
                            
                            // 如果签项剩余容量不足，调整分配数量
                            int actualAssignCount = Math.min(assignCount, signRemainingCapacity);
                            if (actualAssignCount < assignCount) {
                                log.warn("[抽签] 活动{} - P1规则{}分配数量从{}调整为{}（签项{}剩余容量限制）", 
                                    activityId, r.group.getId(), assignCount, actualAssignCount, r.signId);
                                assignedUsers = allCandidates.subList(candidateIndex + assignedIndex, candidateIndex + assignedIndex + actualAssignCount);
                            }
                            
                            for (String user : assignedUsers) {
                                globalUserManager.assignUser(user, r.signId);
                                signResult.usedUserCodes.add(user);
                            }
                            signResult.groupToUsers.put(r.group.getId(), new ArrayList<>(assignedUsers));
                            if (isRuleSatisfied(r.group, assignedUsers.size())) {
                                signResult.satisfiedGroupCount++;
                            }
                            assignedIndex += actualAssignCount;
                            log.info("[抽签] 活动{} - 小于等于规则{}分配{}人（签项{}剩余容量：{}人）", 
                                activityId, r.group.getId(), actualAssignCount, r.signId, signRemainingCapacity);
                        }
                    }
                }
                
                log.info("[抽签] 活动{} - 智能分配完成，共分配{}人", activityId, candidateIndex);
                idx = j;
                continue;
            }
            // 否则走原有逻辑
            for (int k = idx; k < j; k++) {
                RuleExecution ruleExec = nonP1Rules.get(k);
                Long signId = ruleExec.signId;
                LotRuleGroup group = ruleExec.group;
                List<String> originalCandidates = ruleExec.candidates;
                AssignResult signResult = result.signResults.get(signId);
                Integer signTotalCount = signTotalCounts.get(signId);
                
                // === 严格检查：计算当前签项还需要多少人 ===
                int currentSignAssigned = signResult.usedUserCodes.size();
                int signRemainingCapacity = signTotalCount != null ? (signTotalCount - currentSignAssigned) : Integer.MAX_VALUE;
                
                // 如果签项已达到目标人数，直接跳过此规则
                if (signTotalCount != null && currentSignAssigned >= signTotalCount) {
                    log.info("[抽签] 活动{} - 签项{}已达到目标人数{}人，跳过规则{}", 
                        activityId, signId, signTotalCount, group.getId());
                    signResult.groupToUsers.put(group.getId(), new ArrayList<>());
                    continue;
                }
                
                // 如果签项剩余容量为0，跳过此规则
                if (signRemainingCapacity <= 0) {
                    log.info("[抽签] 活动{} - 签项{}剩余容量为0，跳过规则{}", 
                        activityId, signId, group.getId());
                    signResult.groupToUsers.put(group.getId(), new ArrayList<>());
                    continue;
                }
                
                List<String> availableCandidates = originalCandidates.stream()
                        .filter(user -> !globalUserManager.isUserAssigned(user))
                        .collect(Collectors.toList());
                Collections.shuffle(availableCandidates, random);
                
                int assignCount = 0;
                String symbol = group.getCountSymbol();
                int value = group.getCountValue();
                

                
                // 修复：按照签项总人数控制，而不是按group的人数
                // 首先计算规则要求的分配数量
                int ruleAssignCount = calculateRandomAssignCount(symbol, value, availableCandidates.size(), signRemainingCapacity, random);
                if (ruleAssignCount < 0) ruleAssignCount = 0;
                
                // 关键修复：以签项剩余容量为准，规则要求只是指导
                assignCount = Math.min(ruleAssignCount, signRemainingCapacity);
                
                // 如果签项还有剩余容量且有候选用户，但规则分配为0，则至少分配1人
                if (assignCount == 0 && signRemainingCapacity > 0 && availableCandidates.size() > 0) {
                    assignCount = Math.min(1, signRemainingCapacity);
                    log.info("[抽签] 活动{} - 规则{}按签项容量分配{}人（规则要求{}人）", 
                        activityId, group.getId(), assignCount, ruleAssignCount);
                }
                
                // 记录分配决策
                if (assignCount != ruleAssignCount) {
                    log.info("[抽签] 活动{} - 规则{}按签项容量调整: 规则要求{}人 -> 实际分配{}人", 
                        activityId, group.getId(), ruleAssignCount, assignCount);
                }
                

                
                // 记录分配为0的情况
                if (assignCount == 0 && availableCandidates.size() > 0) {
                    log.warn("[抽签] 活动{} - 规则{}分配为0，但候选用户有{}人，规则要求{}{}，剩余容量{}人", 
                        activityId, group.getId(), availableCandidates.size(), symbol, value, signRemainingCapacity);
                }
                
                List<String> assignedUsers = availableCandidates.subList(0, assignCount);
                for (String user : assignedUsers) {
                    globalUserManager.assignUser(user, signId);
                    signResult.usedUserCodes.add(user);
                }
                signResult.groupToUsers.put(group.getId(), new ArrayList<>(assignedUsers));
                if (isRuleSatisfied(group, assignedUsers.size())) {
                    signResult.satisfiedGroupCount++;
                }
                
                log.info("[抽签] 活动{} - 规则{}分配{}人（签项剩余容量：{}人）", 
                    activityId, group.getId(), assignedUsers.size(), signRemainingCapacity);
            }
            idx = j;
        }
        log.info("[抽签] 活动{} - 非P1规则分配完成", activityId);

        // 5. 处理没有规则组的签项（直接从lot_rule表获取规则）
        log.info("[抽签] 活动{} - 开始处理没有规则组的签项", activityId);
        processSignsWithoutRuleGroups(activityId, signGroups, signTotalCounts, globalUserManager, result);

        // 6. 处理签项容量补齐（强制达到目标人数）
        log.info("[抽签] 活动{} - 开始签项强制补齐分配", activityId);
        for (Map.Entry<Long, Integer> entry : signTotalCounts.entrySet()) {
            Long signId = entry.getKey();
            Integer totalCount = entry.getValue();
            
            // 确保签项结果已初始化
            if (!result.signResults.containsKey(signId)) {
                AssignResult signResult = new AssignResult();
                signResult.groupToUsers = new HashMap<>();
                signResult.usedUserCodes = new HashSet<>();
                signResult.satisfiedGroupCount = 0;
                result.signResults.put(signId, signResult);
            }
            
            AssignResult signResult = result.signResults.get(signId);
            int currentAssigned = signResult.usedUserCodes.size();
            int remaining = totalCount - currentAssigned;
            
            if (remaining > 0) {
                // 从全局用户池中获取未分配的用户
                List<String> availableUsers = globalUserPool.stream()
                        .filter(user -> !globalUserManager.isUserAssigned(user))
                        .collect(Collectors.toList());
                Collections.shuffle(availableUsers, random);
                
                // 强制补齐到目标人数
                int toFill = Math.min(remaining, availableUsers.size());
                if (toFill > 0) {
                    List<String> filledUsers = availableUsers.subList(0, toFill);
                    for (String user : filledUsers) {
                        globalUserManager.assignUser(user, signId);
                        signResult.usedUserCodes.add(user);
                    }
                    signResult.groupToUsers.computeIfAbsent(-1L, k -> new ArrayList<>()).addAll(filledUsers);
                    log.info("[抽签] 活动{} - 签项{}强制补齐{}人", activityId, signId, toFill);
                }
                
                // 如果还有剩余未达到目标，记录警告
                int finalAssigned = signResult.usedUserCodes.size();
                if (finalAssigned < totalCount) {
                    log.warn("[抽签] 活动{} - 签项{}补齐后仍不足: 当前{}人, 目标{}人, 缺少{}人", 
                        activityId, signId, finalAssigned, totalCount, totalCount - finalAssigned);
                } else {
                    log.info("[抽签] 活动{} - 签项{}补齐完成: {}人", activityId, signId, finalAssigned);
                }
            } else if (remaining < 0) {
                // 如果超出目标人数，记录错误
                log.error("[抽签] 活动{} - 签项{}超出目标人数: 当前{}人, 目标{}人, 超出{}人", 
                    activityId, signId, currentAssigned, totalCount, -remaining);
            } else {
                log.info("[抽签] 活动{} - 签项{}已达到目标人数: {}人", activityId, signId, currentAssigned);
            }
        }
        log.info("[抽签] 活动{} - 签项补齐分配完成", activityId);

        result.globalUserManager = globalUserManager;
        result.totalAssignedUsers = globalUserManager.getAssignedUsers();

        // 规则分配完成后进行全局验证
        log.info("[抽签] 活动{} - 规则分配完成，开始验证分配唯一性", activityId);
        globalUserManager.validateAllAssignments();
        log.info("[抽签] 活动{} - 分配验证通过，统计信息:\n{}", activityId, globalUserManager.getAssignmentStats());
        
        log.info("[抽签] 活动{} - 最终验证通过，统计信息:\n{}", activityId, globalUserManager.getAssignmentStats());
        
        // 最终人数验证：确保每个签项的人数与目标人数基本一致
        log.info("[抽签] 活动{} - 开始最终人数验证", activityId);
        
        // 验证每个签项的人数是否与要求基本一致（允许少量偏差）
        boolean allSignsValid = validateSignCountsWithTolerance(activityId, result, signTotalCounts);
        
        if (!allSignsValid) {
            // 如果验证失败，进行重试
            int maxRetries = 3; // 减少最大重试次数
            if (retryCount < maxRetries) {
                log.warn("[抽签] 活动{} - 签项人数验证失败，开始第{}次重试", activityId, retryCount + 2);
                return drawLotsMultiSignWithRetry(activityId, signGroups, signGroupDetails, signTotalCounts, globalUserPool, retryCount + 1);
            } else {
                log.warn("[抽签] 活动{} - 签项人数验证失败，已重试{}次，继续执行", activityId, maxRetries);
                // 不再抛出异常，而是继续执行
            }
        }
        
        log.info("[抽签] 活动{} - 开始写入数据库", activityId);
        
        // === 写入数据库前的兜底验证，确保每个签项人数严格等于目标值 ===
        log.info("[抽签] 活动{} - 开始写入数据库前兜底验证", activityId);
        
        // 构建兜底验证日志
        StringBuilder backupLog = new StringBuilder();
        backupLog.append("===============活动ID:").append(activityId).append(" 兜底验证开始==========\n");
        backupLog.append("验证时间:").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new java.util.Date())).append("\n\n");
        
        // 1. 统计每个签项的当前分配情况
        Map<Long, Integer> currentCounts = new HashMap<>();
        Map<Long, List<String>> signUsers = new HashMap<>();
        for (Map.Entry<Long, Integer> entry : signTotalCounts.entrySet()) {
            Long signId = entry.getKey();
            AssignResult signResult = result.signResults.get(signId);
            int currentCount = signResult != null ? signResult.usedUserCodes.size() : 0;
            currentCounts.put(signId, currentCount);
            if (signResult != null) {
                signUsers.put(signId, new ArrayList<>(signResult.usedUserCodes));
            }
        }
        
        // 2. 找出超出和不足的签项
        List<Long> overAssignedSigns = new ArrayList<>();
        List<Long> underAssignedSigns = new ArrayList<>();
        
        backupLog.append("===============兜底验证前状态检查==========\n");
        for (Map.Entry<Long, Integer> entry : signTotalCounts.entrySet()) {
            Long signId = entry.getKey();
            int target = entry.getValue();
            int current = currentCounts.get(signId);
            
            backupLog.append("签项ID:").append(signId).append(" - 目标:").append(target).append("人, 当前:").append(current).append("人");
            
            if (current > target) {
                overAssignedSigns.add(signId);
                int excess = current - target;
                backupLog.append(", 超出:").append(excess).append("人 ❌\n");
                log.warn("[抽签] 活动{} - 签项{}超出目标：当前{}人，目标{}人，超出{}人", 
                    activityId, signId, current, target, excess);
            } else if (current < target) {
                underAssignedSigns.add(signId);
                int shortage = target - current;
                backupLog.append(", 缺少:").append(shortage).append("人 ❌\n");
                log.warn("[抽签] 活动{} - 签项{}不足目标：当前{}人，目标{}人，缺少{}人", 
                    activityId, signId, current, target, shortage);
            } else {
                backupLog.append(", 人数正确 ✅\n");
                log.info("[抽签] 活动{} - 签项{}人数正确：{}人", activityId, signId, current);
            }
        }
        
        // 3. 执行兜底修正：从超出的签项转移用户到不足的签项
        if (!overAssignedSigns.isEmpty() && !underAssignedSigns.isEmpty()) {
            backupLog.append("\n===============开始兜底修正==========\n");
            backupLog.append("超出签项数量:").append(overAssignedSigns.size()).append(", 不足签项数量:").append(underAssignedSigns.size()).append("\n");
            log.info("[抽签] 活动{} - 开始兜底修正：从{}个超出签项转移用户到{}个不足签项", 
                activityId, overAssignedSigns.size(), underAssignedSigns.size());
            
            int totalTransfers = 0;
            for (Long underSignId : underAssignedSigns) {
                int underTarget = signTotalCounts.get(underSignId);
                int underCurrent = currentCounts.get(underSignId);
                int underNeed = underTarget - underCurrent;
                
                if (underNeed <= 0) continue;
                
                backupLog.append("\n--- 处理不足签项ID:").append(underSignId).append(" (需要").append(underNeed).append("人) ---\n");
                
                // 从超出的签项中转移用户
                for (Long overSignId : overAssignedSigns) {
                    if (underNeed <= 0) break;
                    
                    int overCurrent = currentCounts.get(overSignId);
                    int overTarget = signTotalCounts.get(overSignId);
                    int overExcess = overCurrent - overTarget;
                    
                    if (overExcess <= 0) continue;
                    
                    // 转移用户
                    List<String> overUsers = signUsers.get(overSignId);
                    int transferCount = Math.min(underNeed, overExcess);
                    
                    backupLog.append("  从签项ID:").append(overSignId).append(" (超出").append(overExcess).append("人) 转移").append(transferCount).append("人\n");
                    
                    for (int i = 0; i < transferCount; i++) {
                        if (overUsers.isEmpty()) break;
                        
                        // 随机选择一个用户进行转移
                        int randomIndex = random.nextInt(overUsers.size());
                        String userToTransfer = overUsers.remove(randomIndex);
                        
                        // 从原签项移除
                        AssignResult overSignResult = result.signResults.get(overSignId);
                        overSignResult.usedUserCodes.remove(userToTransfer);
                        for (List<String> groupList : overSignResult.groupToUsers.values()) {
                            groupList.remove(userToTransfer);
                        }
                        
                        // 添加到目标签项
                        AssignResult underSignResult = result.signResults.get(underSignId);
                        underSignResult.usedUserCodes.add(userToTransfer);
                        underSignResult.groupToUsers.computeIfAbsent(-1L, k -> new ArrayList<>()).add(userToTransfer);
                        underSignResult.backupAssignedUsers.add(userToTransfer); // 记录兜底分配
                        
                        // 更新全局管理器
                        result.globalUserManager.userToSignMap.put(userToTransfer, underSignId);
                        result.globalUserManager.signToUsersMap.computeIfAbsent(overSignId, k -> new HashSet<>()).remove(userToTransfer);
                        result.globalUserManager.signToUsersMap.computeIfAbsent(underSignId, k -> new HashSet<>()).add(userToTransfer);
                        
                        // 更新计数
                        currentCounts.put(overSignId, currentCounts.get(overSignId) - 1);
                        currentCounts.put(underSignId, currentCounts.get(underSignId) + 1);
                        underNeed--;
                        totalTransfers++;
                        
                        backupLog.append("    转移用户:").append(userToTransfer).append(" (签项").append(overSignId).append(" -> 签项").append(underSignId).append(")\n");
                        log.info("[抽签] 活动{} - 兜底转移：用户{}从签项{}转移到签项{}", 
                            activityId, userToTransfer, overSignId, underSignId);
                    }
                }
            }
            
            backupLog.append("\n===============兜底修正完成==========\n");
            backupLog.append("总转移用户数:").append(totalTransfers).append("人\n");
        } else {
            backupLog.append("\n===============无需兜底修正==========\n");
            if (overAssignedSigns.isEmpty()) {
                backupLog.append("没有超出目标的签项\n");
            }
            if (underAssignedSigns.isEmpty()) {
                backupLog.append("没有不足目标的签项\n");
            }
        }
        
        // 4. 最终验证：确保所有签项人数严格等于目标值
        backupLog.append("\n===============兜底验证后最终检查==========\n");
        boolean allCorrect = true;
        for (Map.Entry<Long, Integer> entry : signTotalCounts.entrySet()) {
            Long signId = entry.getKey();
            int target = entry.getValue();
            AssignResult signResult = result.signResults.get(signId);
            int actual = signResult != null ? signResult.usedUserCodes.size() : 0;
            
            backupLog.append("签项ID:").append(signId).append(" - 目标:").append(target).append("人, 实际:").append(actual).append("人");
            
            if (actual != target) {
                allCorrect = false;
                backupLog.append(" ❌ 验证失败\n");
                log.error("[抽签] 活动{} - ❌ 兜底验证失败：签项{}目标{}人，实际{}人", 
                    activityId, signId, target, actual);
            } else {
                backupLog.append(" ✅ 验证通过\n");
                log.info("[抽签] 活动{} - ✅ 兜底验证通过：签项{}人数正确{}人", 
                    activityId, signId, actual);
            }
        }
        
        backupLog.append("\n===============兜底验证结果==========\n");
        if (allCorrect) {
            backupLog.append("✅ 所有签项人数验证通过，可以安全写入数据库\n");
            log.info("[抽签] 活动{} - ✅ 兜底验证全部通过，开始写入数据库", activityId);
        } else {
            backupLog.append("❌ 存在人数不匹配的签项，拒绝写入数据库\n");
            log.error("[抽签] 活动{} - ❌ 兜底验证失败，存在人数不匹配的签项", activityId);
        }
        
        backupLog.append("===============兜底验证结束==========\n\n");
        
        // 写入lot-result.log
        lotResultLogger.info(backupLog.toString());
        
        if (!allCorrect) {
            throw new RuntimeException("❌ 兜底验证失败，存在人数不匹配的签项！");
        }
        
        // 写入lot_record表
        List<LotRecord> records = new ArrayList<>();
        Date now = new Date();
        for (Map.Entry<Long, AssignResult> entry : result.signResults.entrySet()) {
            Long signId = entry.getKey();
            AssignResult signResult = entry.getValue();
            for (Map.Entry<Long, List<String>> groupEntry : signResult.groupToUsers.entrySet()) {
                Long groupId = groupEntry.getKey();
                List<String> users = groupEntry.getValue();
                for (String user : users) {
                    LotRecord rec = new LotRecord();
                    rec.setActivityId(activityId);
                    rec.setSignId(signId);
                    rec.setUserCode(user);
                    rec.setDrawTime(now);
                    rec.setCreateTime(now);
                    if (groupId == -1L) {
                        rec.setRuleId(null);
                        rec.setDescription("系统补齐");
                    } else {
                        rec.setRuleId(groupId);
                        rec.setDescription(null);
                    }
                    records.add(rec);
                }
            }
        }
        if (!records.isEmpty()) {
            lotMapper.batchInsertLotRecords(records);
            log.info("[抽签] 活动{} - 已写入lot_record {}条", activityId, records.size());
        }
        // 日志输出（内聚到方法内）
        logDrawResult(activityId, result, signGroups, signGroupDetails, signTotalCounts, new ArrayList<>(globalUserPool), start);

        // 6. 全局均衡补齐（重写）
        log.info("[抽签] 活动{} - 开始全局均衡补齐分配", activityId);
        // 统计未分配用户
        Set<String> assignedUsers = result.globalUserManager.getAssignedUsers();
        List<String> unassignedUsers = globalUserPool.stream()
                .filter(u -> !assignedUsers.contains(u))
                .collect(Collectors.toList());
        Collections.shuffle(unassignedUsers, random);
        // 统计每个签项当前已分配人数
        Map<Long, Integer> signAssigned = new HashMap<>();
        for (Map.Entry<Long, Integer> entry : signTotalCounts.entrySet()) {
            Long signId = entry.getKey();
            AssignResult signResult = result.signResults.get(signId);
            signAssigned.put(signId, signResult == null ? 0 : signResult.usedUserCodes.size());
        }
        // 只要有签项未满且有剩余用户，就轮询补齐
        while (!unassignedUsers.isEmpty()) {
            // 找到所有未满的签项
            List<Long> notFullSigns = signTotalCounts.entrySet().stream()
                    .filter(e -> signAssigned.get(e.getKey()) < e.getValue())
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
            if (notFullSigns.isEmpty()) break;
            // 找到当前人数最少的签项
            Long minSign = notFullSigns.stream()
                    .min(Comparator.comparingInt(signAssigned::get))
                    .orElse(null);
            if (minSign == null) break;
            // 分配1个用户
            String user = unassignedUsers.remove(0);
            AssignResult signResult = result.signResults.get(minSign);
            if (signResult == null) {
                signResult = new AssignResult();
                signResult.groupToUsers = new HashMap<>();
                signResult.usedUserCodes = new HashSet<>();
                signResult.satisfiedGroupCount = 0;
                result.signResults.put(minSign, signResult);
            }
            result.globalUserManager.assignUser(user, minSign);
            signResult.usedUserCodes.add(user);
            signResult.groupToUsers.computeIfAbsent(-1L, k -> new ArrayList<>()).add(user);
            signAssigned.put(minSign, signAssigned.get(minSign) + 1);
        }
        // 记录补齐结果
        for (Map.Entry<Long, Integer> entry : signTotalCounts.entrySet()) {
            Long signId = entry.getKey();
            int assigned = signAssigned.get(signId);
            int total = entry.getValue();
            if (assigned < total) {
                log.warn("[抽签] 活动{} - 签项{}全局均衡补齐后仍不足: 当前{}人, 目标{}人, 缺少{}人", activityId, signId, assigned, total, total - assigned);
            } else if (assigned > total) {
                log.error("[抽签] 活动{} - 签项{}全局均衡补齐后超出目标: 当前{}人, 目标{}人, 超出{}人", activityId, signId, assigned, total, assigned - total);
            } else {
                log.info("[抽签] 活动{} - 签项{}全局均衡补齐完成: {}人", activityId, signId, assigned);
            }
        }

        // === 最终强制修正，确保每个签项分配人数严格等于目标 ===
        for (Map.Entry<Long, Integer> entry : signTotalCounts.entrySet()) {
            Long signId = entry.getKey();
            int target = entry.getValue();
            AssignResult signResult = result.signResults.get(signId);
            if (signResult == null) continue;
            int actual = signResult.usedUserCodes.size();
            if (actual > target) {
                // 超出，随机移除多余的
                List<String> toRemove = new ArrayList<>(signResult.usedUserCodes);
                Collections.shuffle(toRemove, random);
                for (int i = 0; i < actual - target; i++) {
                    String user = toRemove.get(i);
                    signResult.usedUserCodes.remove(user);
                    // 从groupToUsers里移除
                    for (List<String> groupList : signResult.groupToUsers.values()) {
                        groupList.remove(user);
                    }
                    // 从全局分配里移除
                    result.globalUserManager.getAssignedUsers().remove(user);
                }
                log.warn("[抽签] 活动{} - 签项{}分配超出，已强制移除{}人，目标{}人，实际{}人", activityId, signId, actual - target, target, actual);
            } else if (actual < target) {
                // 不足，尝试从全局未分配用户池补齐
                Set<String> assigned = result.globalUserManager.getAssignedUsers();
                List<String> unassigned = globalUserPool.stream().filter(u -> !assigned.contains(u)).collect(Collectors.toList());
                Collections.shuffle(unassigned, random);
                for (int i = 0; i < target - actual && i < unassigned.size(); i++) {
                    String user = unassigned.get(i);
                    signResult.usedUserCodes.add(user);
                    signResult.groupToUsers.computeIfAbsent(-1L, k -> new ArrayList<>()).add(user);
                    result.globalUserManager.assignUser(user, signId);
                }
                int after = signResult.usedUserCodes.size();
                if (after < target) {
                    log.error("[抽签] 活动{} - 签项{}分配不足，目标{}人，实际{}人，无法补齐", activityId, signId, target, after);
                } else {
                    log.warn("[抽签] 活动{} - 签项{}分配不足，已强制补齐，目标{}人，实际{}人", activityId, signId, target, after);
                }
            }
        }

        // === 全局唯一最终修正，确保每个签项分配人数严格等于目标且全局唯一 ===
        // 1. 重建新的GlobalUserManager，确保所有状态同步
        GlobalUserManager newGlobalUserManager = new GlobalUserManager();
        Set<String> assigned = new HashSet<>();
        
        for (Map.Entry<Long, Integer> entry : signTotalCounts.entrySet()) {
            Long signId = entry.getKey();
            int target = entry.getValue();
            AssignResult signResult = result.signResults.get(signId);
            if (signResult == null) {
                signResult = new AssignResult();
                signResult.groupToUsers = new HashMap<>();
                signResult.usedUserCodes = new HashSet<>();
                signResult.satisfiedGroupCount = 0;
                result.signResults.put(signId, signResult);
            }
            // 清空签项结果
            signResult.usedUserCodes.clear();
            signResult.groupToUsers.clear();
            
            // 从全局未分配池补齐
            List<String> unassigned = globalUserPool.stream().filter(u -> !assigned.contains(u)).collect(Collectors.toList());
            Collections.shuffle(unassigned, random);
            
            for (int i = 0; i < target && i < unassigned.size(); i++) {
                String user = unassigned.get(i);
                signResult.usedUserCodes.add(user);
                signResult.groupToUsers.computeIfAbsent(-1L, k -> new ArrayList<>()).add(user);
                assigned.add(user);
                // 使用新的GlobalUserManager进行分配
                newGlobalUserManager.assignUser(user, signId);
            }
            
            // 记录分配结果
            if (signResult.usedUserCodes.size() < target) {
                log.error("[抽签] 活动{} - 签项{}分配不足，目标{}人，实际{}人，无法补齐", activityId, signId, target, signResult.usedUserCodes.size());
            } else if (signResult.usedUserCodes.size() > target) {
                log.error("[抽签] 活动{} - 签项{}分配超出，目标{}人，实际{}人（不应出现）", activityId, signId, target, signResult.usedUserCodes.size());
            } else {
                log.info("[抽签] 活动{} - 签项{}分配完成，目标{}人，实际{}人", activityId, signId, target, signResult.usedUserCodes.size());
            }
        }
        
        // 2. 替换原有的GlobalUserManager
        result.globalUserManager = newGlobalUserManager;
        
        // 3. 更新totalAssignedUsers
        result.totalAssignedUsers.clear();
        result.totalAssignedUsers.addAll(assigned);
        
        // 4. 全局唯一性校验
        Map<String, Long> userToSignCheck = new HashMap<>();
        for (Map.Entry<Long, AssignResult> entry : result.signResults.entrySet()) {
            Long signId = entry.getKey();
            for (String user : entry.getValue().usedUserCodes) {
                if (userToSignCheck.containsKey(user)) {
                    log.error("[抽签] 活动{} - 用户{}被分配到多个签项：{}和{}，全局唯一性校验失败！", activityId, user, userToSignCheck.get(user), signId);
                } else {
                    userToSignCheck.put(user, signId);
                }
            }
        }
        
        log.info("[抽签] 活动{} - 全局唯一最终修正完成，总分配用户数：{}", activityId, assigned.size());

        // === 最终强制校验和修正，确保每个签项人数严格等于目标值 ===
        log.info("[抽签] 活动{} - 开始最终强制校验和修正", activityId);
        boolean needCorrection = false;
        
        // 先检查是否有任何签项人数不匹配
        for (Map.Entry<Long, Integer> entry : signTotalCounts.entrySet()) {
            Long signId = entry.getKey();
            int target = entry.getValue();
            AssignResult signResult = result.signResults.get(signId);
            int actual = signResult.usedUserCodes.size();
            
            if (actual != target) {
                needCorrection = true;
                log.warn("[抽签] 活动{} - 签项{}人数不匹配，目标{}人，实际{}人，需要强制修正", activityId, signId, target, actual);
            }
        }
        
        // 如果有任何不匹配，执行完全重新分配
        if (needCorrection) {
            log.warn("[抽签] 活动{} - 发现人数不匹配，执行完全重新分配", activityId);
            
            // 清空所有现有分配
            assigned.clear();
            newGlobalUserManager = new GlobalUserManager();
            
            // 重新初始化所有签项结果
            for (Map.Entry<Long, Integer> entry : signTotalCounts.entrySet()) {
                Long signId = entry.getKey();
                AssignResult signResult = result.signResults.get(signId);
                signResult.usedUserCodes.clear();
                signResult.groupToUsers.clear();
            }
            
            // 完全重新分配所有签项
            for (Map.Entry<Long, Integer> entry : signTotalCounts.entrySet()) {
                Long signId = entry.getKey();
                int target = entry.getValue();
                AssignResult signResult = result.signResults.get(signId);
                
                // 从全局未分配池中分配
                List<String> unassigned = globalUserPool.stream().filter(u -> !assigned.contains(u)).collect(Collectors.toList());
                Collections.shuffle(unassigned, random);
                
                for (int i = 0; i < target && i < unassigned.size(); i++) {
                    String user = unassigned.get(i);
                    signResult.usedUserCodes.add(user);
                    signResult.groupToUsers.computeIfAbsent(-1L, k -> new ArrayList<>()).add(user);
                    assigned.add(user);
                    newGlobalUserManager.assignUser(user, signId);
                }
                
                log.info("[抽签] 活动{} - 签项{}完全重新分配完成：目标{}人，实际{}人", 
                    activityId, signId, target, signResult.usedUserCodes.size());
            }
        } else {
            log.info("[抽签] 活动{} - 所有签项人数校验通过，无需修正", activityId);
        }
        
        if (needCorrection) {
            // 更新totalAssignedUsers
            result.totalAssignedUsers.clear();
            result.totalAssignedUsers.addAll(assigned);
            log.info("[抽签] 活动{} - 强制修正完成，最终总分配用户数：{}", activityId, assigned.size());
        } else {
            log.info("[抽签] 活动{} - 所有签项人数校验通过，无需修正", activityId);
        }
        
        // === 最终验证，确保所有签项人数严格等于目标值 ===
        boolean allValid = true;
        for (Map.Entry<Long, Integer> entry : signTotalCounts.entrySet()) {
            Long signId = entry.getKey();
            int target = entry.getValue();
            AssignResult signResult = result.signResults.get(signId);
            int actual = signResult.usedUserCodes.size();
            
            if (actual != target) {
                allValid = false;
                log.error("[抽签] 活动{} - ❌ 签项{}最终验证失败：目标{}人，实际{}人", activityId, signId, target, actual);
            } else {
                log.info("[抽签] 活动{} - ✅ 签项{}最终验证通过：{}人", activityId, signId, actual);
            }
        }
        
        if (!allValid) {
            throw new RuntimeException("❌ 签项人数最终验证失败，存在人数不匹配的签项！");
        }
        
        log.info("[抽签] 活动{} - ✅ 所有签项人数最终验证通过，分配完成", activityId);

        return result;
    }

        /**
     * 验证每个签项的人数是否与要求一致
     */
    private boolean validateSignCounts(Long activityId, MultiSignAssignResult result, Map<Long, Integer> signTotalCounts) {
        log.info("[抽签] 活动{} - 开始验证签项人数", activityId);
        boolean allValid = true;
        
        for (Map.Entry<Long, Integer> entry : signTotalCounts.entrySet()) {
            Long signId = entry.getKey();
            Integer targetCount = entry.getValue();
            AssignResult signResult = result.signResults.get(signId);
            int currentCount = signResult.usedUserCodes.size();
            
            if (currentCount != targetCount) {
                allValid = false;
                log.error("[抽签] 活动{} - 签项{}人数不匹配: 当前{}人, 目标{}人", activityId, signId, currentCount, targetCount);
            } else {
                log.info("[抽签] 活动{} - 签项{}人数验证通过: {}人", activityId, signId, currentCount);
            }
        }
        
        if (allValid) {
            log.info("[抽签] 活动{} - 所有签项人数验证通过", activityId);
        } else {
            log.error("[抽签] 活动{} - 存在签项人数不匹配", activityId);
        }
        
        return allValid;
    }
    
    private boolean validateSignCountsWithTolerance(Long activityId, MultiSignAssignResult result, Map<Long, Integer> signTotalCounts) {
        boolean allValid = true;
        int totalMismatch = 0;
        int totalTarget = 0;
        
        for (Map.Entry<Long, Integer> entry : signTotalCounts.entrySet()) {
            Long signId = entry.getKey();
            Integer targetCount = entry.getValue();
            AssignResult signResult = result.signResults.get(signId);
            int currentCount = signResult.usedUserCodes.size();
            
            totalTarget += targetCount;
            int mismatch = Math.abs(currentCount - targetCount);
            totalMismatch += mismatch;
            
            if (currentCount != targetCount) {
                log.warn("[抽签] 活动{} - 签项{}人数不匹配: 当前{}人, 目标{}人, 偏差{}人", 
                    activityId, signId, currentCount, targetCount, mismatch);
                allValid = false;
            } else {
                log.info("[抽签] 活动{} - 签项{}人数验证通过: {}人", activityId, signId, currentCount);
            }
        }
        
        // 计算总体偏差比例
        if (totalTarget > 0) {
            double mismatchRatio = (double) totalMismatch / totalTarget;
            log.info("[抽签] 活动{} - 总体偏差: {}/{}人, 偏差比例: {:.2%}", 
                activityId, totalMismatch, totalTarget, mismatchRatio);
            
            // 如果偏差比例小于5%，认为是可接受的
            if (mismatchRatio <= 0.05) {
                log.info("[抽签] 活动{} - 偏差在可接受范围内，验证通过", activityId);
                return true;
            }
        }
        
        return allValid;
    }
    
    /**
     * 处理没有规则组的签项，直接从lot_rule表获取规则并分配
     */
    private void processSignsWithoutRuleGroups(Long activityId,
                                                Map<Long, List<LotRuleGroup>> signGroups,
                                                Map<Long, Integer> signTotalCounts,
                                                GlobalUserManager globalUserManager,
                                                MultiSignAssignResult result) {
        Random random = ThreadLocalRandom.current();
        
        // 获取所有签项ID
        Set<Long> allSignIds = signTotalCounts.keySet();
        Set<Long> signIdsWithGroups = signGroups.keySet();
        
        // 找出没有规则组的签项
        Set<Long> signIdsWithoutGroups = new HashSet<>(allSignIds);
        signIdsWithoutGroups.removeAll(signIdsWithGroups);
        
        log.info("[抽签] 活动{} - 发现{}个签项没有规则组", activityId, signIdsWithoutGroups.size());
        
        for (Long signId : signIdsWithoutGroups) {
            // 初始化签项结果（如果还没有初始化）
            if (!result.signResults.containsKey(signId)) {
                AssignResult signResult = new AssignResult();
                signResult.groupToUsers = new HashMap<>();
                signResult.usedUserCodes = new HashSet<>();
                signResult.satisfiedGroupCount = 0;
                result.signResults.put(signId, signResult);
            }
            
            AssignResult signResult = result.signResults.get(signId);
            Integer signTotalCount = signTotalCounts.get(signId);
            int currentAssigned = signResult.usedUserCodes.size();
            int remaining = signTotalCount - currentAssigned;
            
            if (remaining > 0) {
                // 查询该签项的直接规则
                List<LotRule> directRules = lotMapper.getRulesBySignId(signId);
                
                if (!directRules.isEmpty()) {
                    log.info("[抽签] 活动{} - 签项{}有{}条直接规则，开始处理", activityId, signId, directRules.size());
                    
                    // 按优先级排序规则
                    directRules.sort((r1, r2) -> Integer.compare(r1.getPriority(), r2.getPriority()));
                    
                    for (LotRule rule : directRules) {
                        if (remaining <= 0) break;
                        
                        // 根据规则条件筛选候选用户
                        List<String> candidates = getCandidatesByRule(activityId, rule);
                        if (candidates.isEmpty()) {
                            log.warn("[抽签] 活动{} - 签项{}规则{}没有匹配的候选用户", activityId, signId, rule.getId());
                            continue;
                        }
                        
                        // 过滤已分配的用户
                        List<String> availableCandidates = candidates.stream()
                                .filter(user -> !globalUserManager.isUserAssigned(user))
                                .collect(Collectors.toList());
                        
                        if (availableCandidates.isEmpty()) {
                            log.warn("[抽签] 活动{} - 签项{}规则{}的候选用户都已分配", activityId, signId, rule.getId());
                            continue;
                        }
                        
                        // 计算分配数量
                        int assignCount = calculateAssignCount(rule, availableCandidates.size(), remaining);
                        if (assignCount <= 0) continue;
                        
                        // 随机选择用户
                        Collections.shuffle(availableCandidates, random);
                        List<String> assignedUsers = availableCandidates.subList(0, Math.min(assignCount, availableCandidates.size()));
                        
                        // 执行分配
                        for (String user : assignedUsers) {
                            globalUserManager.assignUser(user, signId);
                            signResult.usedUserCodes.add(user);
                        }
                        
                        // 记录分配结果
                        signResult.groupToUsers.put(rule.getId(), new ArrayList<>(assignedUsers));
                        if (isDirectRuleSatisfied(rule, assignedUsers.size())) {
                            signResult.satisfiedGroupCount++;
                        }
                        
                        remaining -= assignedUsers.size();
                        log.info("[抽签] 活动{} - 签项{}规则{}分配了{}人", activityId, signId, rule.getId(), assignedUsers.size());
                    }
                } else {
                    log.info("[抽签] 活动{} - 签项{}没有直接规则，将在后续补齐阶段处理", activityId, signId);
                }
            }
        }
        
        log.info("[抽签] 活动{} - 处理没有规则组的签项完成", activityId);
    }

    /**
     * 根据规则条件获取候选用户
     */
    private List<String> getCandidatesByRule(Long activityId, LotRule rule) {
        // 这里需要根据规则条件查询用户
        // 由于当前系统没有完整的用户查询逻辑，这里简化处理
        // 实际实现中需要根据fieldName、fieldOperator、fieldValue来查询用户
        List<String> allUsers = lotMapper.getAllJoinUserIdsByActivityId(activityId);
        
        // 简化实现：返回所有用户作为候选
        // TODO: 实现真正的用户筛选逻辑
        return allUsers;
    }

    /**
     * 计算规则分配数量
     */
    private int calculateAssignCount(LotRule rule, int availableCount, int remainingCapacity) {
        String symbol = rule.getCountSymbol();
        int value = rule.getCountValue();
        Random random = ThreadLocalRandom.current();
        
        return calculateRandomAssignCount(symbol, value, availableCount, remainingCapacity, random);
    }

    /**
     * 判断直接规则是否满足
     */
    private boolean isDirectRuleSatisfied(LotRule rule, int assignedCount) {
        String symbol = rule.getCountSymbol();
        int value = rule.getCountValue();

        switch (symbol) {
            case "=":
                return assignedCount == value;
            case ">=":
                return assignedCount >= value;
            case "<=":
                return assignedCount <= value;
            case "<":
                return assignedCount < value;
            case ">":
                return assignedCount > value;
            case "!=":
                return assignedCount != value;
            default:
                return false;
        }
    }

    /**
     * 对外暴露：根据活动ID自动组装参数并调用分配
     */
    public MultiSignAssignResult drawLotsMultiSignByActivityId(Long activityId) {
        // 1. 查询所有规则组
        List<LotRuleGroup> groupList = lotMapper.getRuleGroupsByActivityId(activityId);
        
        // 2. 查询活动下所有签项
        List<LotSign> allSigns = lotMapper.getSignsByActivityId(activityId);
        if (allSigns.isEmpty()) {
            throw new RuntimeException("活动" + activityId + "没有配置签项");
        }
        
        // 3. 如果规则组为空，检查lot_rule表是否有规则
        if (groupList.isEmpty()) {
            List<LotRule> rules = lotMapper.getRulesByActivityId(activityId);
            if (!rules.isEmpty()) {
                throw new RuntimeException("活动" + activityId + "存在规则但未配置规则组，请先配置规则组");
            }
            
            // 4. 如果lot_rule也为空，说明这个活动没有规则，直接随机分配
            log.info("[抽签] 活动{} - 无规则配置，执行随机分配", activityId);
            return drawLotsRandomByActivityId(activityId);
        }
        
        // 5. 有规则组，按原有逻辑处理
        // 按signId分组
        Map<Long, List<LotRuleGroup>> signGroups = new HashMap<>();
        for (LotRuleGroup group : groupList) {
            signGroups.computeIfAbsent(group.getSignId(), k -> new ArrayList<>()).add(group);
        }
        
        // 6. 查询每个groupId下的用户明细，组装 signGroupDetails
        Map<Long, Map<Long, List<String>>> signGroupDetails = new HashMap<>();
        for (Map.Entry<Long, List<LotRuleGroup>> entry : signGroups.entrySet()) {
            Long signId = entry.getKey();
            Map<Long, List<String>> groupDetails = new HashMap<>();
            for (LotRuleGroup group : entry.getValue()) {
                List<LotRuleGroupDetail> details = lotMapper.getRuleGroupDetailsByGroupId(group.getId());
                List<String> users = new ArrayList<>();
                for (LotRuleGroupDetail d : details) {
                    users.add(d.getUserCode());
                }
                groupDetails.put(group.getId(), users);
            }
            signGroupDetails.put(signId, groupDetails);
        }
        
        // 7. 查询每个签项容量（包括没有规则组的签项）
        Map<Long, Integer> signTotalCounts = new HashMap<>();
        for (LotSign sign : allSigns) {
            signTotalCounts.put(sign.getId(), sign.getTotalCount());
        }
        
        // 8. 查询全局可分配用户
        List<String> allUsers = lotMapper.getAllJoinUserIdsByActivityId(activityId);
        
        // 9. 调用主分配方法
        return drawLotsMultiSign(activityId, signGroups, signGroupDetails, signTotalCounts, allUsers);
    }

    /**
     * 无规则时的随机分配方法
     */
    private MultiSignAssignResult drawLotsRandomByActivityId(Long activityId) {
        long start = System.currentTimeMillis();
        Random random = ThreadLocalRandom.current();
        
        // 1. 查询活动下所有签项
        List<LotSign> signs = lotMapper.getSignsByActivityId(activityId);
        if (signs.isEmpty()) {
            throw new RuntimeException("活动" + activityId + "没有配置签项");
        }
        
        // 2. 查询所有参与用户
        List<String> allUsers = lotMapper.getAllJoinUserIdsByActivityId(activityId);
        if (allUsers.isEmpty()) {
            throw new RuntimeException("活动" + activityId + "没有参与用户");
        }
        
        // 3. 构建签项容量映射
        Map<Long, Integer> signTotalCounts = new HashMap<>();
        for (LotSign sign : signs) {
            signTotalCounts.put(sign.getId(), sign.getTotalCount());
        }
        
        // 4. 执行随机分配
        MultiSignAssignResult result = new MultiSignAssignResult();
        GlobalUserManager globalUserManager = new GlobalUserManager();
        
        // 打乱用户顺序
        List<String> shuffledUsers = new ArrayList<>(allUsers);
        Collections.shuffle(shuffledUsers, random);
        
        int userIndex = 0;
        for (LotSign sign : signs) {
            Long signId = sign.getId();
            Integer totalCount = sign.getTotalCount();
            
            // 初始化签项结果
            AssignResult signResult = new AssignResult();
            result.signResults.put(signId, signResult);
            
            // 随机分配用户到该签项
            int assignedCount = 0;
            List<String> assignedUsers = new ArrayList<>();
            
            while (assignedCount < totalCount && userIndex < shuffledUsers.size()) {
                String user = shuffledUsers.get(userIndex++);
                if (globalUserManager.assignUser(user, signId)) {
                    assignedUsers.add(user);
                    assignedCount++;
                }
            }
            
            // 记录分配结果
            signResult.usedUserCodes.addAll(assignedUsers);
            signResult.groupToUsers.put(-1L, assignedUsers); // -1L表示随机分配
        }
        
        result.globalUserManager = globalUserManager;
        result.totalAssignedUsers = globalUserManager.getAssignedUsers();
        
        // 5. 写入lot_record表
        List<LotRecord> records = new ArrayList<>();
        Date now = new Date();
        for (Map.Entry<Long, AssignResult> entry : result.signResults.entrySet()) {
            Long signId = entry.getKey();
            AssignResult signResult = entry.getValue();
            for (String user : signResult.usedUserCodes) {
                LotRecord rec = new LotRecord();
                rec.setActivityId(activityId);
                rec.setSignId(signId);
                rec.setUserCode(user);
                rec.setDrawTime(now);
                rec.setCreateTime(now);
                rec.setRuleId(null);
                rec.setDescription("随机分配");
                records.add(rec);
            }
        }
        if (!records.isEmpty()) {
            lotMapper.batchInsertLotRecords(records);
            log.info("[抽签] 活动{} - 随机分配已写入lot_record {}条", activityId, records.size());
        }
        
        // 6. 输出日志
        logDrawResult(activityId, result, new HashMap<>(), new HashMap<>(), signTotalCounts, allUsers, start);
        
        return result;
    }

    /**
     * P1规则全局优化结果
     */
    private static class P1OptimizationResult {
        private boolean successful;
        private Map<Long, List<String>> ruleAssignments = new HashMap<>(); // ruleId -> assignedUsers
        private List<String> conflictMessages = new ArrayList<>();

        public boolean isSuccessful() {
            return successful;
        }

        public void setSuccessful(boolean successful) {
            this.successful = successful;
        }

        public Map<Long, List<String>> getRuleAssignments() {
            return ruleAssignments;
        }

        public List<String> getConflictMessages() {
            return conflictMessages;
        }

        public void addConflictMessage(String message) {
            conflictMessages.add(message);
        }
    }

    /**
     * P1规则全局优化 - 使用智能启发式策略
     */
    private P1OptimizationResult optimizeP1Rules(List<RuleExecution> p1Rules, Map<Long, Integer> signTotalCounts, Long activityId) {
        P1OptimizationResult result = new P1OptimizationResult();
        Random random = ThreadLocalRandom.current();

        // 0. 尝试全局均匀分配策略（针对<=规则的特殊优化）
        if (tryGlobalEvenDistribution(p1Rules, result, random)) {
            return result;
        }

        // 1. 构建用户-规则冲突矩阵
        Map<String, List<RuleExecution>> userToRules = new HashMap<>();
        Map<RuleExecution, Set<String>> ruleToCandidates = new HashMap<>();

        for (RuleExecution rule : p1Rules) {
            Set<String> candidates = new HashSet<>(rule.candidates);
            // shuffle
            List<String> candidateList = new ArrayList<>(candidates);
            Collections.shuffle(candidateList, random);
            candidates = new LinkedHashSet<>(candidateList);
            ruleToCandidates.put(rule, candidates);

            for (String user : candidates) {
                userToRules.computeIfAbsent(user, k -> new ArrayList<>()).add(rule);
            }
        }

        // 2. 分析用户冲突程度（用户稀缺度）
        Map<String, Integer> userConflictCount = new HashMap<>();
        for (Map.Entry<String, List<RuleExecution>> entry : userToRules.entrySet()) {
            String user = entry.getKey();
            int conflictCount = entry.getValue().size();
            userConflictCount.put(user, conflictCount);
        }

        // 3. 使用智能贪心策略分配
        if (intelligentP1Assignment(p1Rules, ruleToCandidates, userConflictCount, signTotalCounts, result)) {
            result.setSuccessful(true);
        } else {
            result.setSuccessful(false);
            result.addConflictMessage("智能启发式策略未能找到满足所有P1规则的方案");

            // 备用方案：使用回溯算法（带时间限制）
            long startTime = System.currentTimeMillis();
            long timeLimit = 500; // 500ms时间限制
            
            log.info("[抽签] 活动{} - 开始回溯算法分配，时间限制{}ms", activityId, timeLimit);
            
            Map<Long, List<String>> assignments = new HashMap<>();
            Set<String> usedUsers = new HashSet<>();
            Map<Long, Integer> signUsedCounts = new HashMap<>();

            if (backtrackP1AssignmentWithTimeLimit(p1Rules, 0, assignments, usedUsers, 
                                                  signUsedCounts, signTotalCounts, startTime, timeLimit, result)) {
                result.setSuccessful(true);
                result.getConflictMessages().clear();
            }
        }

        // 放宽等价分配判断条件：只要所有P1规则的候选池有交集且总人数等于需求总和，也走全组合分配
        int totalNeed = 0;
        Set<String> allCandidates = new HashSet<>();
        Set<String> intersection = null;
        for (RuleExecution rule : p1Rules) {
            totalNeed += rule.group.getCountValue();
            Set<String> cands = new HashSet<>(ruleToCandidates.get(rule));
            allCandidates.addAll(cands);
            if (intersection == null) {
                intersection = new HashSet<>(cands);
            } else {
                intersection.retainAll(cands);
            }
        }
        boolean hasIntersection = intersection != null && !intersection.isEmpty();
        if (hasIntersection && allCandidates.size() == totalNeed) {
            // 安全检查：限制全排列算法的使用范围，避免内存溢出
            if (allCandidates.size() > 9) {
                log.warn("[抽签] 活动{} - 候选人数{}超过安全阈值9，跳过全排列算法使用多轮随机贪心策略", 
                         activityId, allCandidates.size());
                // 使用多轮随机贪心算法替代全排列
                if (multiRoundRandomAssignment(p1Rules, ruleToCandidates, signTotalCounts, result, 10)) {
                    result.setSuccessful(true);
                    return result;
                }
            } else {
                // 小规模数据：使用优化的回溯算法（找到第一个有效解即返回）
                log.info("[抽签] 活动{} - 候选人数{}较少，使用优化回溯算法快速寻找解", 
                         activityId, allCandidates.size());
                
                // 使用带时间限制的回溯算法
                long startTime = System.currentTimeMillis();
                long timeLimit = 500; // 500ms时间限制
                
                Map<Long, List<String>> assignments = new HashMap<>();
                Set<String> usedUsers = new HashSet<>();
                Map<Long, Integer> signUsedCounts = new HashMap<>();
                
                if (backtrackP1AssignmentWithTimeLimit(p1Rules, 0, assignments, usedUsers, 
                                                      signUsedCounts, signTotalCounts, startTime, timeLimit, result)) {
                    log.info("[抽签] 活动{} - 优化回溯算法成功找到有效解", activityId);
                    return result;
                } else {
                    log.warn("[抽签] 活动{} - 优化回溯算法未找到有效解", activityId);
                }
            }
        }

        return result;
    }

    /**
     * 尝试全局均匀分配策略 - 专门针对<=规则优化
     * 对于<=1规则，优先尝试让所有规则都满足1（最优解）
     */
    private boolean tryGlobalEvenDistribution(List<RuleExecution> p1Rules, P1OptimizationResult result, Random random) {
        // 检查是否所有规则都是<=1
        List<RuleExecution> lessEqualOneRules = new ArrayList<>();
        for (RuleExecution rule : p1Rules) {
            if ("<=".equals(rule.group.getCountSymbol()) && rule.group.getCountValue() == 1) {
                lessEqualOneRules.add(rule);
            }
        }
        
        // 如果<=1的规则数量少于2个，不适用此策略
        if (lessEqualOneRules.size() < 2) {
            return false;
        }
        
        // 收集所有候选用户
        Set<String> allCandidates = new HashSet<>();
        for (RuleExecution rule : lessEqualOneRules) {
            allCandidates.addAll(rule.candidates);
        }
        
        // 如果候选用户数量少于规则数量，无法让所有规则都满足1
        if (allCandidates.size() < lessEqualOneRules.size()) {
            // 回退策略：尽量分配，能分配多少算多少
            List<String> candidateList = new ArrayList<>(allCandidates);
            Collections.shuffle(candidateList, random);
            
            // 为每个<=1规则分配一个用户，直到用户用完
            for (int i = 0; i < Math.min(lessEqualOneRules.size(), candidateList.size()); i++) {
                RuleExecution rule = lessEqualOneRules.get(i);
                String user = candidateList.get(i);
                result.ruleAssignments.put(rule.group.getRuleId(), Arrays.asList(user));
            }
            
            result.setSuccessful(true);
            return true;
        }
        
        // 最优解：让所有<=1规则都满足1
        List<String> candidateList = new ArrayList<>(allCandidates);
        Collections.shuffle(candidateList, random);
        
        // 为每个<=1规则分配一个不同的用户
        for (int i = 0; i < lessEqualOneRules.size(); i++) {
            RuleExecution rule = lessEqualOneRules.get(i);
            String user = candidateList.get(i);
            result.ruleAssignments.put(rule.group.getRuleId(), Arrays.asList(user));
        }
        
        result.setSuccessful(true);
        return true;
    }

    /**
     * 智能P1规则分配 - 基于启发式策略的贪心算法
     */
    private boolean intelligentP1Assignment(List<RuleExecution> p1Rules,
                                            Map<RuleExecution, Set<String>> ruleToCandidates,
                                            Map<String, Integer> userConflictCount,
                                            Map<Long, Integer> signTotalCounts,
                                            P1OptimizationResult result) {
        Random random = ThreadLocalRandom.current();

        // 创建规则的工作副本
        Map<RuleExecution, Set<String>> workingCandidates = new HashMap<>();
        for (Map.Entry<RuleExecution, Set<String>> entry : ruleToCandidates.entrySet()) {
            List<String> candidateList = new ArrayList<>(entry.getValue());
            Collections.shuffle(candidateList, random);
            workingCandidates.put(entry.getKey(), new LinkedHashSet<>(candidateList));
        }

        Set<String> usedUsers = new HashSet<>();
        Map<Long, Integer> signUsedCounts = new HashMap<>();

        // 先随机打乱p1Rules，保证等价分数时顺序随机
        Collections.shuffle(p1Rules, random);
        // 按规则稀缺度排序：候选用户少的规则优先处理
        List<RuleExecution> sortedRules = p1Rules.stream()
                .sorted((r1, r2) -> {
                    int count1 = workingCandidates.get(r1).size();
                    int count2 = workingCandidates.get(r2).size();
                    // 候选用户少的优先
                    int candidateCompare = Integer.compare(count1, count2);
                    if (candidateCompare != 0) return candidateCompare;
                    // 需求量大的优先（严格等于符号优先）
                    String symbol1 = r1.group.getCountSymbol();
                    String symbol2 = r2.group.getCountSymbol();
                    boolean isExact1 = "=".equals(symbol1);
                    boolean isExact2 = "=".equals(symbol2);
                    if (isExact1 && !isExact2) return -1;
                    if (!isExact1 && isExact2) return 1;
                    // 同类型规则按需求量排序
                    return Integer.compare(r2.group.getCountValue(), r1.group.getCountValue());
                })
                .collect(Collectors.toList());

        // 逐个处理规则
        for (RuleExecution rule : sortedRules) {
            Long signId = rule.signId;
            LotRuleGroup group = rule.group;
            Set<String> availableCandidates = new HashSet<>(workingCandidates.get(rule));
            availableCandidates.removeAll(usedUsers);
            Integer signTotalCount = signTotalCounts.get(signId);
            // 计算需要分配的用户数量
            String symbol = group.getCountSymbol();
            int value = group.getCountValue();
            int minNeed, maxNeed;
            switch (symbol) {
                case "=":
                    minNeed = maxNeed = value;
                    break;
                case ">=":
                    minNeed = value;
                    maxNeed = signTotalCount != null ? Math.min(100, signTotalCount - signUsedCounts.getOrDefault(signId, 0)) : 100;
                    break;
                case "<=":
                    minNeed = 0;
                    maxNeed = value;
                    break;
                case "<":
                    minNeed = 0;
                    maxNeed = value - 1;
                    break;
                case ">":
                    minNeed = value + 1;
                    maxNeed = signTotalCount != null ? Math.min(100, signTotalCount - signUsedCounts.getOrDefault(signId, 0)) : 100;
                    break;
                case "!=":
                    minNeed = 1; // 简化处理，至少分配1人避免等于value
                    maxNeed = signTotalCount != null ? Math.min(100, signTotalCount - signUsedCounts.getOrDefault(signId, 0)) : 100;
                    if (maxNeed >= value) maxNeed = value - 1;
                    break;
                default:
                    return false;
            }
            // 检查签项容量限制
            if (signTotalCount != null) {
                int remainingCapacity = signTotalCount - signUsedCounts.getOrDefault(signId, 0);
                maxNeed = Math.min(maxNeed, remainingCapacity);
            }
            // 检查是否有足够候选用户
            if (availableCandidates.size() < minNeed) {
                return false;
            }
            maxNeed = Math.min(maxNeed, availableCandidates.size());
            // 优化分配数量：对于<=规则，尽量满足最大值而不是最小值
            int assignCount = minNeed; // 默认初始化为最小值
            if ("=".equals(symbol)) {
                assignCount = value;
            } else if ("<=".equals(symbol)) {
                // 对于<=规则，优先尝试满足最大值（最优解）
                if (maxNeed > minNeed) {
                    // 90%概率直接选择最大值，10%概率在[1, maxNeed]范围内加权随机
                    if (random.nextDouble() < 0.9) {
                        assignCount = maxNeed;
                    } else {
                        // 使用加权随机，让更大的值有更高概率
                        double[] weights = new double[maxNeed - minNeed + 1];
                        for (int i = 0; i < weights.length; i++) {
                            weights[i] = Math.pow(1.8, i); // 指数权重，让大值更可能被选中
                        }
                        double totalWeight = 0;
                        for (double weight : weights) {
                            totalWeight += weight;
                        }
                        double randomValue = random.nextDouble() * totalWeight;
                        double currentWeight = 0;
                        for (int i = 0; i < weights.length; i++) {
                            currentWeight += weights[i];
                            if (randomValue <= currentWeight) {
                                assignCount = minNeed + i;
                                break;
                            }
                        }
                        // 如果上面的循环没有设置assignCount，说明有精度问题，直接取最大值
                        if (assignCount == minNeed && maxNeed > minNeed) {
                            assignCount = maxNeed;
                        }
                    }
                } else {
                    // 如果maxNeed == minNeed，直接使用该值
                    assignCount = maxNeed;
                }
            } else {
                // 其他符号保持原有逻辑
                if (maxNeed >= minNeed) {
                    assignCount = minNeed + random.nextInt(maxNeed - minNeed + 1);
                }
            }
            // 基于用户稀缺度选择用户：稀缺度高的用户优先
            List<String> selectedUsers = selectBestUsers(availableCandidates, userConflictCount,
                    workingCandidates, usedUsers, assignCount);
            if (selectedUsers.size() < minNeed) {
                return false;
            }
            // 应用分配
            result.ruleAssignments.put(group.getRuleId(), new ArrayList<>(selectedUsers));
            usedUsers.addAll(selectedUsers);
            signUsedCounts.put(signId, signUsedCounts.getOrDefault(signId, 0) + selectedUsers.size());
            // 从所有规则的候选列表中移除已使用的用户
            for (String user : selectedUsers) {
                for (Set<String> candidates : workingCandidates.values()) {
                    candidates.remove(user);
                }
            }
        }
        return true;
    }

    /**
     * 基于用户稀缺度选择最佳用户组合
     */
    private List<String> selectBestUsers(Set<String> availableCandidates,
                                         Map<String, Integer> userConflictCount,
                                         Map<RuleExecution, Set<String>> workingCandidates,
                                         Set<String> usedUsers,
                                         int needCount) {
        if (availableCandidates.isEmpty() || needCount <= 0) return Collections.emptyList();
        // 计算每个用户的当前稀缺度
        Map<String, Integer> currentUserScarcity = new HashMap<>();
        for (String user : availableCandidates) {
            int scarcity = 0;
            for (Set<String> cands : workingCandidates.values()) {
                if (cands.contains(user)) scarcity++;
            }
            currentUserScarcity.put(user, scarcity);
        }
        // 找到最小稀缺度
        int minScarcity = currentUserScarcity.values().stream().min(Integer::compareTo).orElse(0);
        // 找到最小冲突度
        int minConflict = availableCandidates.stream().map(userConflictCount::get).min(Integer::compareTo).orElse(0);
        // 过滤出所有最优分数的候选人
        List<String> bestCandidates = new ArrayList<>();
        for (String user : availableCandidates) {
            if (currentUserScarcity.get(user) == minScarcity && userConflictCount.getOrDefault(user, 0) == minConflict) {
                bestCandidates.add(user);
            }
        }
        // 随机打乱
        Collections.shuffle(bestCandidates, java.util.concurrent.ThreadLocalRandom.current());
        // 如果最优候选人数量不足，则补充次优分数的用户
        List<String> result = new ArrayList<>();
        int remain = needCount;
        Iterator<String> it = bestCandidates.iterator();
        while (it.hasNext() && remain > 0) {
            result.add(it.next());
            remain--;
        }
        if (remain > 0) {
            // 补充非最优分数的用户
            List<String> others = new ArrayList<>(availableCandidates);
            others.removeAll(bestCandidates);
            it = others.iterator();
            while (it.hasNext() && remain > 0) {
                result.add(it.next());
                remain--;
            }
        }
        return result;
    }

    /**
     * 回溯算法实现P1规则分配
     */
    private boolean backtrackP1Assignment(List<RuleExecution> p1Rules, int ruleIndex,
                                          Map<Long, List<String>> assignments, Set<String> usedUsers,
                                          Map<Long, Integer> signUsedCounts, Map<Long, Integer> signTotalCounts,
                                          long startTime, long timeLimit) {

        // 所有规则都处理完毕
        if (ruleIndex >= p1Rules.size()) {
            return true;
        }

        RuleExecution currentRule = p1Rules.get(ruleIndex);
        LotRuleGroup group = currentRule.group;
        Long signId = currentRule.signId;

        // 获取当前签项已使用的用户数
        int currentSignUsed = signUsedCounts.getOrDefault(signId, 0);
        Integer signTotalCount = signTotalCounts.get(signId);

        // 计算当前规则需要分配的用户数量范围
        int minNeed, maxNeed;
        String symbol = group.getCountSymbol();
        int value = group.getCountValue();

        switch (symbol) {
            case "=":
                minNeed = maxNeed = value;
                break;
            case ">=":
                minNeed = value;
                maxNeed = signTotalCount != null ? Math.min(100, signTotalCount - currentSignUsed) : 100;
                break;
            case "<=":
                minNeed = 0;
                maxNeed = value;
                break;
            case "<":
                minNeed = 0;
                maxNeed = value - 1;
                break;
            case ">":
                minNeed = value + 1;
                maxNeed = signTotalCount != null ? Math.min(100, signTotalCount - currentSignUsed) : 100;
                break;
            case "!=":
                // != 情况比较复杂，暂时简化处理
                minNeed = 0;
                maxNeed = signTotalCount != null ? Math.min(100, signTotalCount - currentSignUsed) : 100;
                if (maxNeed >= value) maxNeed = value - 1; // 避免等于value
                break;
            default:
                return false;
        }

        // 获取可用候选用户
        List<String> availableCandidates = currentRule.candidates.stream()
                .filter(user -> !usedUsers.contains(user))
                .collect(Collectors.toList());

        // 检查签项容量限制
        if (signTotalCount != null) {
            int remainingCapacity = signTotalCount - currentSignUsed;
            maxNeed = Math.min(maxNeed, remainingCapacity);
        }

        // 检查是否有足够的候选用户
        if (availableCandidates.size() < minNeed) {
            return false; // 候选用户不足，无法满足最小需求
        }

        maxNeed = Math.min(maxNeed, availableCandidates.size());

        // 尝试不同的分配数量
        for (int assignCount = minNeed; assignCount <= maxNeed; assignCount++) {
            // 尝试所有可能的用户组合
            List<List<String>> combinations = getCombinations(availableCandidates, assignCount, startTime, timeLimit);

            for (List<String> combination : combinations) {
                // 尝试这个组合
                assignments.put(group.getRuleId(), new ArrayList<>(combination));
                for (String user : combination) {
                    usedUsers.add(user);
                }
                signUsedCounts.put(signId, currentSignUsed + assignCount);

                // 递归处理下一个规则
                if (backtrackP1Assignment(p1Rules, ruleIndex + 1, assignments, usedUsers, signUsedCounts, signTotalCounts, startTime, timeLimit)) {
                    return true; // 找到解决方案
                }

                // 回溯
                assignments.remove(group.getRuleId());
                for (String user : combination) {
                    usedUsers.remove(user);
                }
                signUsedCounts.put(signId, currentSignUsed);
            }
        }

        return false; // 没有找到解决方案
    }

    /**
     * 获取所有可能的用户组合（带时间限制和数量限制）
     */
    private List<List<String>> getCombinations(List<String> users, int count, long startTime, long timeLimit) {
        List<List<String>> result = new ArrayList<>();
        if (count == 0) {
            result.add(new ArrayList<>());
            return result;
        }
        if (count > users.size()) {
            return result;
        }

        // 检查组合数量是否过大（防止OOM）
        long combinationCount = calculateCombinationCount(users.size(), count);
        if (combinationCount > 10000) { // 限制最大组合数为10000
            log.warn("[抽签] 组合数量过大({})，跳过组合生成", combinationCount);
            return result;
        }

        generateCombinations(users, 0, count, new ArrayList<>(), result, startTime, timeLimit);
        return result;
    }

    /**
     * 计算组合数 C(n,k)
     */
    private long calculateCombinationCount(int n, int k) {
        if (k > n - k) {
            k = n - k;
        }
        long result = 1;
        for (int i = 0; i < k; i++) {
            result = result * (n - i) / (i + 1);
        }
        return result;
    }

    private void generateCombinations(List<String> users, int start, int count,
                                      List<String> current, List<List<String>> result,
                                      long startTime, long timeLimit) {
        // 检查时间限制
        if (System.currentTimeMillis() - startTime > timeLimit) {
            log.warn("[抽签] 组合生成超时，已生成{}个组合", result.size());
            return;
        }

        // 检查结果数量限制
        if (result.size() > 10000) {
            log.warn("[抽签] 组合数量达到上限({})，停止生成", result.size());
            return;
        }

        if (current.size() == count) {
            result.add(new ArrayList<>(current));
            return;
        }

        for (int i = start; i <= users.size() - (count - current.size()); i++) {
            current.add(users.get(i));
            generateCombinations(users, i + 1, count, current, result, startTime, timeLimit);
            current.remove(current.size() - 1);
        }
    }

    /**
     * 规则执行单元 - 包含签项信息的规则
     */
    private static class RuleExecution {
        final Long signId;
        final LotRuleGroup group;
        final List<String> candidates;

        public RuleExecution(Long signId, LotRuleGroup group, List<String> candidates) {
            this.signId = signId;
            this.group = group;
            this.candidates = candidates;
        }
    }

    /**
     * 直接规则执行单元 - 处理直接从lot_rule表获取的规则
     */
    private static class DirectRuleExecution {
        final Long signId;
        final LotRule rule;
        final List<String> candidates;

        public DirectRuleExecution(Long signId, LotRule rule, List<String> candidates) {
            this.signId = signId;
            this.rule = rule;
            this.candidates = candidates;
        }
    }

    /**
     * 判断规则是否满足
     */
    private boolean isRuleSatisfied(LotRuleGroup group, int assignedCount) {
        String symbol = group.getCountSymbol();
        int value = group.getCountValue();

        switch (symbol) {
            case "=":
                return assignedCount == value;
            case ">=":
                return assignedCount >= value;
            case "<=":
                return assignedCount <= value;
            case "<":
                return assignedCount < value;
            case ">":
                return assignedCount > value;
            case "!=":
                return assignedCount != value;
            default:
                return false;
        }
    }



    /**
     * 按优先级排序签项，采用多层排序策略：
     * 1. priority=1规则多的优先
     * 2. 最低优先级高的优先 (如最低P1 > 最低P2)
     * 3. 签项需求/候选用户比例高的优先 (资源稀缺度)
     * 4. 规则总数多的优先
     */
    private List<Long> sortSignsByPriority(Map<Long, List<LotRuleGroup>> signGroups) {
        return signGroups.entrySet().stream()
                .sorted((e1, e2) -> {
                    List<LotRuleGroup> groups1 = e1.getValue();
                    List<LotRuleGroup> groups2 = e2.getValue();

                    // 1. 计算priority=1规则数量
                    long p1Count1 = groups1.stream().filter(g -> g.getPriority() == 1).count();
                    long p1Count2 = groups2.stream().filter(g -> g.getPriority() == 1).count();

                    // priority=1规则多的优先
                    int priorityCompare = Long.compare(p1Count2, p1Count1);
                    if (priorityCompare != 0) return priorityCompare;

                    // 2. 比较最低优先级 (优先级数字越小越重要)
                    int minPriority1 = groups1.stream().mapToInt(g -> g.getPriority()).min().orElse(Integer.MAX_VALUE);
                    int minPriority2 = groups2.stream().mapToInt(g -> g.getPriority()).min().orElse(Integer.MAX_VALUE);
                    int minPriorityCompare = Integer.compare(minPriority1, minPriority2);
                    if (minPriorityCompare != 0) return minPriorityCompare;

                    // 3. 计算资源稀缺度: 签项需求 / 可用候选用户数
                    try {
                        Long signId1 = e1.getKey();
                        Long signId2 = e2.getKey();

                        LotSign sign1 = lotMapper.getSignById(signId1);
                        LotSign sign2 = lotMapper.getSignById(signId2);

                        if (sign1 != null && sign2 != null) {
                            int demand1 = sign1.getTotalCount();
                            int demand2 = sign2.getTotalCount();

                            // 计算可用候选用户数 (去重)
                            Set<String> candidates1 = new HashSet<>();
                            Set<String> candidates2 = new HashSet<>();

                            // 这里需要访问groupDetails，但当前方法中没有，所以跳过这个比较
                            // TODO: 如果需要精确的稀缺度比较，需要重构方法签名传入groupDetails

                            // 暂时按需求量排序，需求量大的优先
                            int demandCompare = Integer.compare(demand2, demand1);
                            if (demandCompare != 0) return demandCompare;
                        }
                    } catch (Exception e) {
                        // 忽略数据库访问异常，继续其他排序条件
                    }

                    // 4. 规则总数多的优先
                    int ruleCountCompare = Integer.compare(groups2.size(), groups1.size());
                    if (ruleCountCompare != 0) return ruleCountCompare;

                    // 5. 最后按signId排序保证确定性
                    return Long.compare(e1.getKey(), e2.getKey());
                })
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /**
     * 新的随机分配数量计算方法
     */
    private int calculateRandomAssignCount(String symbol, int value, int availableCount, int remainingCapacity, Random random) {
        int assignCount = 0;
        switch (symbol) {
            case "=":
                // 等于：精确匹配
                assignCount = Math.min(value, availableCount);
                break;
            case ">=":
                // 大于等于：在[value, min(availableCount, remainingCapacity)]范围内随机选择
                int maxForGreaterEqual = Math.min(availableCount, remainingCapacity);
                if (maxForGreaterEqual >= value) {
                    assignCount = value + random.nextInt(maxForGreaterEqual - value + 1);
                } else {
                    // 修复：当剩余容量不足时，严格限制分配数量
                    assignCount = Math.min(value, Math.min(availableCount, remainingCapacity));
                }
                break;
            case "<=":
                // 小于等于：尽量满足最大值，避免分配0
                int maxForLessEqual = Math.min(Math.min(value, availableCount), remainingCapacity);
                if (maxForLessEqual > 0) {
                    // 90%概率选择最大值，10%概率在[1, maxForLessEqual]范围内加权随机选择
                    if (random.nextDouble() < 0.9) {
                        assignCount = maxForLessEqual;
                    } else {
                        // 使用加权随机，让更大的值有更高概率
                        if (maxForLessEqual == 1) {
                            assignCount = 1;
                        } else {
                            double[] weights = new double[maxForLessEqual];
                            for (int i = 0; i < weights.length; i++) {
                                weights[i] = Math.pow(1.8, i); // 指数权重，让大值更可能被选中
                            }
                            double totalWeight = 0;
                            for (double weight : weights) {
                                totalWeight += weight;
                            }
                            double randomValue = random.nextDouble() * totalWeight;
                            double currentWeight = 0;
                            for (int i = 0; i < weights.length; i++) {
                                currentWeight += weights[i];
                                if (randomValue <= currentWeight) {
                                    assignCount = i + 1;
                                    break;
                                }
                            }
                            // 如果上面的循环没有设置assignCount，说明有精度问题，直接取最大值
                            if (assignCount == 0) {
                                assignCount = maxForLessEqual;
                            }
                        }
                    }
                } else {
                    // 修复：当remainingCapacity为0但availableCount > 0时，至少分配1人
                    // 这样可以确保<=规则在有候选用户时不会分配0人
                    if (availableCount > 0 && value > 0) {
                        assignCount = Math.min(1, Math.min(value, availableCount));
                    } else {
                        assignCount = 0;
                    }
                }
                
                // 额外保护：确保<=规则在有候选用户时不会分配0人
                if (assignCount == 0 && availableCount > 0 && value > 0 && remainingCapacity > 0) {
                    assignCount = Math.min(1, Math.min(value, Math.min(availableCount, remainingCapacity)));
                }
                break;
            case "<":
                // 小于：在[1, min(value-1, availableCount, remainingCapacity)]范围内随机选择
                int maxForLess = Math.min(Math.min(value - 1, availableCount), remainingCapacity);
                if (maxForLess > 0) {
                    assignCount = 1 + random.nextInt(maxForLess);
                } else {
                    assignCount = 0;
                }
                break;
            case ">":
                // 大于：在[value+1, min(availableCount, remainingCapacity)]范围内随机选择
                int minForGreater = value + 1;
                int maxForGreater = Math.min(availableCount, remainingCapacity);
                if (maxForGreater >= minForGreater) {
                    assignCount = minForGreater + random.nextInt(maxForGreater - minForGreater + 1);
                } else {
                    assignCount = Math.min(minForGreater, availableCount);
                }
                break;
            case "!=":
                // 不等于：避免等于value，在可用范围内随机选择
                if (availableCount == value) {
                    // 如果可用数量等于value，则选择value-1
                    assignCount = Math.min(value - 1, remainingCapacity);
                } else {
                    // 在[1, min(availableCount, remainingCapacity)]范围内随机选择，但避免等于value
                    int maxForNotEqual = Math.min(availableCount, remainingCapacity);
                    if (maxForNotEqual > 0) {
                        assignCount = 1 + random.nextInt(maxForNotEqual);
                        // 如果随机到了value，则选择其他值
                        if (assignCount == value && maxForNotEqual > 1) {
                            if (assignCount == maxForNotEqual) {
                                assignCount = assignCount - 1;
                            } else {
                                assignCount = assignCount + 1;
                            }
                        }
                    } else {
                        assignCount = 0;
                    }
                }
                break;
            default:
                assignCount = Math.min(value, availableCount);
        }
        return Math.max(0, assignCount);
    }

    /**
     * 多轮随机贪心分配算法 - 用于替代大规模全排列
     * 通过多次运行贪心算法，使用不同随机种子，提高解的质量和公平性
     */
    private boolean multiRoundRandomAssignment(List<RuleExecution> p1Rules,
                                             Map<RuleExecution, Set<String>> ruleToCandidates,
                                             Map<Long, Integer> signTotalCounts,
                                             P1OptimizationResult result,
                                             int maxRounds) {
        Random random = ThreadLocalRandom.current();
        List<Map<Long, List<String>>> validSolutions = new ArrayList<>();
        
        log.info("[抽签] 开始多轮随机贪心分配，最大轮次: {}", maxRounds);
        
        for (int round = 0; round < maxRounds; round++) {
            // 每轮使用不同的随机种子
            Random roundRandom = new Random(random.nextLong());
            
            // 创建规则的随机化副本
            Map<RuleExecution, Set<String>> shuffledCandidates = new HashMap<>();
            for (Map.Entry<RuleExecution, Set<String>> entry : ruleToCandidates.entrySet()) {
                List<String> candidateList = new ArrayList<>(entry.getValue());
                Collections.shuffle(candidateList, roundRandom);
                shuffledCandidates.put(entry.getKey(), new LinkedHashSet<>(candidateList));
            }
            
            // 随机打乱规则处理顺序
            List<RuleExecution> shuffledRules = new ArrayList<>(p1Rules);
            Collections.shuffle(shuffledRules, roundRandom);
            
            // 尝试贪心分配
            Map<Long, List<String>> roundAssignment = new HashMap<>();
            Set<String> usedUsers = new HashSet<>();
            Map<Long, Integer> signUsedCounts = new HashMap<>();
            boolean success = true;
            
            for (RuleExecution rule : shuffledRules) {
                Long ruleId = rule.group.getRuleId();
                Long signId = rule.signId;
                String symbol = rule.group.getCountSymbol();
                int value = rule.group.getCountValue();
                
                // 获取可用候选人（未被使用且符合规则）
                List<String> availableCandidates = shuffledCandidates.get(rule).stream()
                    .filter(user -> !usedUsers.contains(user))
                    .collect(Collectors.toList());
                
                // 检查签项容量限制
                Integer signTotalCount = signTotalCounts.get(signId);
                int currentSignUsed = signUsedCounts.getOrDefault(signId, 0);
                int signRemainingCapacity = signTotalCount != null ? 
                    (signTotalCount - currentSignUsed) : Integer.MAX_VALUE;
                
                // 计算本轮分配数量
                int assignCount = calculateOptimalAssignCount(symbol, value, 
                    availableCandidates.size(), signRemainingCapacity);
                
                if (assignCount > 0 && assignCount <= availableCandidates.size()) {
                    List<String> assigned = availableCandidates.subList(0, assignCount);
                    roundAssignment.put(ruleId, new ArrayList<>(assigned));
                    usedUsers.addAll(assigned);
                    signUsedCounts.put(signId, currentSignUsed + assignCount);
                } else if ("=".equals(symbol) || ">=".equals(symbol)) {
                    // 严格规则无法满足，本轮失败
                    success = false;
                    break;
                }
            }
            
            if (success && !roundAssignment.isEmpty()) {
                validSolutions.add(roundAssignment);
                log.debug("[抽签] 第{}轮分配成功，分配了{}个规则", round + 1, roundAssignment.size());
            }
        }
        
        if (!validSolutions.isEmpty()) {
            // 随机选择一个有效解
            Map<Long, List<String>> chosenSolution = validSolutions.get(
                random.nextInt(validSolutions.size()));
            result.ruleAssignments.putAll(chosenSolution);
            log.info("[抽签] 多轮随机贪心分配成功，共找到{}个有效解，随机选择其中一个", 
                validSolutions.size());
            return true;
        }
        
        log.warn("[抽签] 多轮随机贪心分配失败，{}轮尝试均未找到有效解", maxRounds);
        return false;
    }
    
    /**
     * 计算最优分配数量
     */
    private int calculateOptimalAssignCount(String symbol, int value, 
                                          int availableCount, int capacityLimit) {
        int maxPossible = Math.min(availableCount, capacityLimit);
        
        switch (symbol) {
            case "=":
                return value <= maxPossible ? value : 0;
            case ">=":
                return Math.min(value, maxPossible);
            case "<=":
                return Math.min(value, maxPossible);
            case ">":
                return value + 1 <= maxPossible ? value + 1 : 0;
            case "<":
                return Math.min(Math.max(0, value - 1), maxPossible);
            default:
                return Math.min(value, maxPossible);
        }
    }



    /**
     * 计算用户冲突程度
     */
    private Map<String, Integer> calculateUserConflictCount(List<RuleExecution> p1Rules, 
                                                          Map<RuleExecution, Set<String>> ruleToCandidates) {
        Map<String, Integer> userConflictCount = new HashMap<>();
        
        for (RuleExecution rule : p1Rules) {
            Set<String> candidates = ruleToCandidates.get(rule);
            for (String user : candidates) {
                userConflictCount.merge(user, 1, Integer::sum);
            }
        }
        
        return userConflictCount;
    }

    /**
     * 带时间限制的回溯算法
     */
    private boolean backtrackP1AssignmentWithTimeLimit(List<RuleExecution> p1Rules, 
                                                      P1OptimizationResult result,
                                                      Map<Long, Integer> signTotalCounts,
                                                      long startTime, long timeLimit) {
        Map<Long, List<String>> assignments = new HashMap<>();
        Set<String> usedUsers = new HashSet<>();
        Map<Long, Integer> signUsedCounts = new HashMap<>();
        
        return backtrackP1AssignmentWithTimeLimit(p1Rules, 0, assignments, usedUsers, 
                                                 signUsedCounts, signTotalCounts, startTime, timeLimit, result);
    }

    /**
     * 带时间限制的回溯算法实现
     */
    private boolean backtrackP1AssignmentWithTimeLimit(List<RuleExecution> p1Rules, int ruleIndex,
                                                      Map<Long, List<String>> assignments, Set<String> usedUsers,
                                                      Map<Long, Integer> signUsedCounts, Map<Long, Integer> signTotalCounts,
                                                      long startTime, long timeLimit, P1OptimizationResult result) {
        // 检查时间限制
        if (System.currentTimeMillis() - startTime > timeLimit) {
            log.warn("[抽签] 回溯算法超时，已耗时{}ms", System.currentTimeMillis() - startTime);
            return false;
        }

        // 所有规则都处理完毕
        if (ruleIndex >= p1Rules.size()) {
            result.setSuccessful(true);
            result.ruleAssignments.putAll(assignments);
            return true;
        }

        RuleExecution currentRule = p1Rules.get(ruleIndex);
        LotRuleGroup group = currentRule.group;
        Long signId = currentRule.signId;

        // 获取当前签项已使用的用户数
        int currentSignUsed = signUsedCounts.getOrDefault(signId, 0);
        Integer signTotalCount = signTotalCounts.get(signId);

        // 计算当前规则需要分配的用户数量范围
        int minNeed, maxNeed;
        String symbol = group.getCountSymbol();
        int value = group.getCountValue();

        switch (symbol) {
            case "=":
                minNeed = maxNeed = value;
                break;
            case ">=":
                minNeed = value;
                maxNeed = Math.min(currentRule.candidates.size(), 
                    signTotalCount != null ? signTotalCount - currentSignUsed : Integer.MAX_VALUE);
                break;
            case "<=":
                minNeed = 0;
                maxNeed = Math.min(value, currentRule.candidates.size());
                break;
            case ">":
                minNeed = value + 1;
                maxNeed = Math.min(currentRule.candidates.size(), 
                    signTotalCount != null ? signTotalCount - currentSignUsed : Integer.MAX_VALUE);
                break;
            case "<":
                minNeed = 0;
                maxNeed = Math.min(value - 1, currentRule.candidates.size());
                break;
            default:
                minNeed = maxNeed = 0;
        }

        // 获取可用候选人（未被使用且在规则候选池中）
        List<String> availableCandidates = currentRule.candidates.stream()
            .filter(user -> !usedUsers.contains(user))
            .collect(Collectors.toList());

        // 如果可用候选人不足，无法满足最小需求
        if (availableCandidates.size() < minNeed) {
            return false;
        }

        // 尝试所有可能的分配数量
        for (int assignCount = minNeed; assignCount <= maxNeed; assignCount++) {
            // 尝试所有可能的用户组合
            List<List<String>> combinations = getCombinations(availableCandidates, assignCount, startTime, timeLimit);

            for (List<String> combination : combinations) {
                // 尝试这个组合
                assignments.put(group.getRuleId(), new ArrayList<>(combination));
                for (String user : combination) {
                    usedUsers.add(user);
                }
                signUsedCounts.put(signId, currentSignUsed + assignCount);

                // 递归处理下一个规则
                if (backtrackP1AssignmentWithTimeLimit(p1Rules, ruleIndex + 1, assignments, usedUsers, 
                                                      signUsedCounts, signTotalCounts, startTime, timeLimit, result)) {
                    return true; // 找到解决方案
                }

                // 回溯
                assignments.remove(group.getRuleId());
                for (String user : combination) {
                    usedUsers.remove(user);
                }
                signUsedCounts.put(signId, currentSignUsed);
            }
        }

        return false; // 没有找到解决方案
    }
} 
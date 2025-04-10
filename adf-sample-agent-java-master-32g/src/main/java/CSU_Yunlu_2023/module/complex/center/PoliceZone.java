package CSU_Yunlu_2023.module.complex.center;

import adf.core.agent.communication.standard.bundle.centralized.CommandPolice;
import adf.core.agent.info.WorldInfo;
import rescuecore2.worldmodel.EntityID;
import rescuecore2.standard.entities.*;
import java.util.*;

public class PoliceZone {
    private int zoneID;
    private List<EntityID> policeAgents;
    private List<EntityID> blockades;
    private List<EntityID> roads;
    private double priority;
    private double clearanceRate;
    private double blockadeDensity;
    
    // 性能指标
    private double responseTime;
    private double policeUtilization;
    private double zoneCoverage;
    
    // 消息相关
    private class CommandInfo {
        private CommandPolice command;
        private int time;
        
        public CommandInfo(CommandPolice command, int time) {
            this.command = command;
            this.time = time;
        }
        
        public CommandPolice getCommand() { return command; }
        public int getTime() { return time; }
    }
    
    private List<CommandInfo> priorityCommands;
    private Map<EntityID, Integer> targetPriority;
    private WorldInfo worldInfo;
    
    public PoliceZone(int zoneID) {
        this.zoneID = zoneID;
        this.policeAgents = new ArrayList<>();
        this.blockades = new ArrayList<>();
        this.roads = new ArrayList<>();
        this.priority = 0.0;
        this.clearanceRate = 0.0;
        this.blockadeDensity = 0.0;
        this.responseTime = 0.0;
        this.policeUtilization = 0.0;
        this.zoneCoverage = 0.0;
        this.priorityCommands = new ArrayList<>();
        this.targetPriority = new HashMap<>();
    }
    
    public void setWorldInfo(WorldInfo worldInfo) {
        this.worldInfo = worldInfo;
    }
    
    public void updateStatus(int currentTime) {
        calculateClearanceRate();
        calculateBlockadeDensity();
        
        // 根据消息优先级调整分区优先级
        double messagePriority = calculateMessagePriority();
        
        // 综合计算最终优先级
        this.priority = messagePriority * 0.6 + 
                       (1 - clearanceRate) * 0.2 + 
                       blockadeDensity * 0.2;
        
        // 更新性能指标
        calculatePerformanceMetrics();
    }
    
    private void calculateClearanceRate() {
        int clearedCount = 0;
        int totalCount = 0;
        for (EntityID blockadeID : blockades) {
            StandardEntity entity = worldInfo.getEntity(blockadeID);
            if (entity instanceof Blockade) {
                Blockade blockade = (Blockade) entity;
                if (blockade.getRepairCost() == 0) { // 使用repairCost判断是否已清除
                    clearedCount++;
                }
                totalCount++;
            }
        }
        this.clearanceRate = totalCount == 0 ? 0.0 : (double) clearedCount / totalCount;
    }
    
    private void calculateBlockadeDensity() {
        this.blockadeDensity = roads.isEmpty() ? 0.0 : (double) blockades.size() / roads.size();
    }
    
    private void calculatePerformanceMetrics() {
        // 计算响应时间
        this.responseTime = calculateAverageResponseTime();
        
        // 计算警察利用率
        this.policeUtilization = calculatePoliceUtilization();
        
        // 计算分区覆盖率
        this.zoneCoverage = calculateZoneCoverage();
    }
    
    private double calculateAverageResponseTime() {
        // 实现响应时间计算逻辑
        return 0.0;
    }
    
    private double calculatePoliceUtilization() {
        // 实现警察利用率计算逻辑
        return policeAgents.isEmpty() ? 0.0 : (double) blockades.size() / policeAgents.size();
    }
    
    private double calculateZoneCoverage() {
        // 实现分区覆盖率计算逻辑
        return roads.isEmpty() ? 0.0 : (double) policeAgents.size() / roads.size();
    }
    
    public void addPriorityCommand(CommandPolice command, int currentTime) {
        priorityCommands.add(new CommandInfo(command, currentTime));
        updateTargetPriority(command.getTargetID(), command.getAction());
    }
    
    private void updateTargetPriority(EntityID targetID, int priority) {
        int currentPriority = targetPriority.getOrDefault(targetID, 0);
        targetPriority.put(targetID, Math.max(currentPriority, priority));
    }
    
    public int getTargetPriority(EntityID targetID) {
        return targetPriority.getOrDefault(targetID, 0);
    }
    
    public List<EntityID> getHighPriorityTargets() {
        List<EntityID> highPriorityTargets = new ArrayList<>();
        for (Map.Entry<EntityID, Integer> entry : targetPriority.entrySet()) {
            if (entry.getValue() >= 2) {
                highPriorityTargets.add(entry.getKey());
            }
        }
        return highPriorityTargets;
    }
    
    private double calculateMessagePriority() {
        double priority = 0.0;
        for (CommandInfo commandInfo : priorityCommands) {
            if (commandInfo.getCommand().getAction() == CommandPolice.ACTION_CLEAR) {
                priority += 1.0;
            }
        }
        return priority * (1.0 - priorityCommands.size() * 0.1);
    }
    
    public void clearExpiredCommands(int currentTime) {
        priorityCommands.removeIf(commandInfo -> currentTime - commandInfo.getTime() > 50);
        
        // 清理过期的目标优先级
        Set<EntityID> activeTargets = new HashSet<>();
        for (CommandInfo commandInfo : priorityCommands) {
            activeTargets.add(commandInfo.getCommand().getTargetID());
        }
        targetPriority.keySet().removeIf(target -> !activeTargets.contains(target));
    }
    
    // Getters
    public int getZoneID() { return zoneID; }
    public List<EntityID> getPoliceAgents() { return policeAgents; }
    public List<EntityID> getBlockades() { return blockades; }
    public List<EntityID> getRoads() { return roads; }
    public double getPriority() { return priority; }
    public double getClearanceRate() { return clearanceRate; }
    public double getBlockadeDensity() { return blockadeDensity; }
    public double getResponseTime() { return responseTime; }
    public double getPoliceUtilization() { return policeUtilization; }
    public double getZoneCoverage() { return zoneCoverage; }
    
    // Setters
    public void addPolice(EntityID policeID) { policeAgents.add(policeID); }
    public void removePolice(EntityID policeID) { policeAgents.remove(policeID); }
    public void addBlockade(EntityID blockadeID) { blockades.add(blockadeID); }
    public void removeBlockade(EntityID blockadeID) { blockades.remove(blockadeID); }
    public void addRoad(EntityID roadID) { roads.add(roadID); }
    public void removeRoad(EntityID roadID) { roads.remove(roadID); }
    
    public boolean containsEntity(EntityID entityID) {
        return policeAgents.contains(entityID) || 
               blockades.contains(entityID) || 
               roads.contains(entityID);
    }
} 
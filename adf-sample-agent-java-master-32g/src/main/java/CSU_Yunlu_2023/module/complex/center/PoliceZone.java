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
    
    // 添加被困智能体记录
    private List<EntityID> trappedHumans;
    
    // 添加分区中心点
    private Point2D centerPoint;
    
    private class Point2D {
        double x, y;
        
        public Point2D(double x, double y) {
            this.x = x;
            this.y = y;
        }
        
        public double getX() { return x; }
        public double getY() { return y; }
    }
    
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
        this.trappedHumans = new ArrayList<>();
        this.centerPoint = null;
    }
    
    public void setWorldInfo(WorldInfo worldInfo) {
        this.worldInfo = worldInfo;
    }
    
    public void updateStatus(int currentTime) {
        // 更新路障状态
        updateBlockades();
        
        // 更新被困智能体状态
        updateTrappedHumans();
        
        // 更新区域中心点
        if (centerPoint == null) {
            calculateCenterPoint();
        }
        
        calculateClearanceRate();
        calculateBlockadeDensity();
        
        // 根据消息优先级调整分区优先级
        double messagePriority = calculateMessagePriority();
        
        // 增加被困智能体优先级因素
        double trappedHumansFactor = calculateTrappedHumansFactor();
        
        // 综合计算最终优先级
        this.priority = messagePriority * 0.4 + 
                       (1 - clearanceRate) * 0.2 + 
                       blockadeDensity * 0.2 + 
                       trappedHumansFactor * 0.2;
        
        // 更新性能指标
        calculatePerformanceMetrics();
    }
    
    // 计算被困智能体因素
    private double calculateTrappedHumansFactor() {
        if (trappedHumans.isEmpty()) {
            return 0.0;
        }
        
        // 计算被困智能体数量与可能救援智能体数量的比例
        double rescuableAgentsCount = countRescuableAgents();
        double trappedCount = trappedHumans.size();
        
        // 如果没有可救援的智能体，则优先级设为最高
        if (rescuableAgentsCount == 0) {
            return 1.0;
        }
        
        // 返回被困智能体数量与可救援智能体数量的比例，值在0-1之间
        return Math.min(1.0, trappedCount / rescuableAgentsCount);
    }
    
    // 计算区域内可能参与救援的智能体数量（消防、救护、警察等）
    private double countRescuableAgents() {
        double count = 0;
        
        for (EntityID roadID : roads) {
            StandardEntity roadEntity = worldInfo.getEntity(roadID);
            if (roadEntity instanceof Road) {
                Road road = (Road) roadEntity;
                for (EntityID nearbyID : road.getNeighbours()) {
                    StandardEntity entity = worldInfo.getEntity(nearbyID);
                    if (entity instanceof Human && 
                        !(entity instanceof Civilian) && 
                        !trappedHumans.contains(entity.getID())) {
                        count++;
                    }
                }
            }
        }
        
        return count;
    }
    
    // 更新被困智能体状态
    private void updateTrappedHumans() {
        // 清除已不再被困的智能体
        trappedHumans.removeIf(humanID -> {
            StandardEntity entity = worldInfo.getEntity(humanID);
            if (entity instanceof Human) {
                Human human = (Human) entity;
                
                // 检查是否还在被困状态
                if (human.isPositionDefined()) {
                    EntityID positionID = human.getPosition();
                    StandardEntity position = worldInfo.getEntity(positionID);
                    
                    if (position instanceof Road) {
                        Road road = (Road) position;
                        if (!road.isBlockadesDefined() || worldInfo.getBlockades(road.getID()).isEmpty()) {
                            return true; // 移除不再被困的智能体
                        }
                    } else {
                        return true; // 不在道路上，认为不再被困
                    }
                }
            }
            return false;
        });
        
        // 添加新的被困智能体
        for (StandardEntity entity : worldInfo.getAllEntities()) {
            if (entity instanceof Human && !trappedHumans.contains(entity.getID())) {
                Human human = (Human) entity;
                
                if (human.isPositionDefined()) {
                    EntityID positionID = human.getPosition();
                    StandardEntity position = worldInfo.getEntity(positionID);
                    
                    if (position instanceof Road) {
                        Road road = (Road) position;
                        if (road.isBlockadesDefined() && !worldInfo.getBlockades(road.getID()).isEmpty()) {
                            // 检查是否在当前区域内
                            if (roads.contains(road.getID())) {
                                trappedHumans.add(human.getID());
                            }
                        }
                    }
                }
            }
        }
    }
    
    // 计算区域中心点
    private void calculateCenterPoint() {
        if (roads.isEmpty()) {
            return;
        }
        
        double sumX = 0, sumY = 0;
        int count = 0;
        
        for (EntityID roadID : roads) {
            StandardEntity entity = worldInfo.getEntity(roadID);
            if (entity instanceof Road) {
                Road road = (Road) entity;
                sumX += road.getX();
                sumY += road.getY();
                count++;
            }
        }
        
        if (count > 0) {
            centerPoint = new Point2D(sumX / count, sumY / count);
        }
    }
    
    private void calculateClearanceRate() {
        int clearedCount = 0;
        int totalCount = 0;
        
        for (EntityID blockadeID : blockades) {
            StandardEntity entity = worldInfo.getEntity(blockadeID);
            if (entity instanceof Blockade) {
                Blockade blockade = (Blockade) entity;
                if (blockade.getRepairCost() == 0) {
                    clearedCount++;
                }
                totalCount++;
            }
        }
        
        this.clearanceRate = totalCount == 0 ? 0.0 : (double) clearedCount / totalCount;
    }
    
    public void updateBlockades() {
        // 更新路障列表，移除已清除的路障
        blockades.removeIf(blockadeID -> {
            StandardEntity entity = worldInfo.getEntity(blockadeID);
            if (entity instanceof Blockade) {
                Blockade blockade = (Blockade) entity;
                return blockade.getRepairCost() == 0;
            }
            return false;
        });
        
        // 检查是否有新的路障需要添加
        for (StandardEntity entity : worldInfo.getAllEntities()) {
            if (entity instanceof Blockade) {
                Blockade blockade = (Blockade) entity;
                if (blockade.getRepairCost() > 0 && !blockades.contains(entity.getID())) {
                    // 检查路障是否在区域内
                    StandardEntity nearestRoad = findNearestRoad(entity);
                    if (nearestRoad != null && roads.contains(nearestRoad.getID())) {
                        blockades.add(entity.getID());
                    }
                }
            }
        }
    }
    
    private StandardEntity findNearestRoad(StandardEntity entity) {
        StandardEntity nearest = null;
        double minDistance = Double.MAX_VALUE;
        
        for (EntityID roadID : roads) {
            StandardEntity road = worldInfo.getEntity(roadID);
            if (road != null) {
                double distance = calculateDistance(entity, road);
                if (distance < minDistance) {
                    minDistance = distance;
                    nearest = road;
                }
            }
        }
        
        return nearest;
    }
    
    private double calculateDistance(StandardEntity entity1, StandardEntity entity2) {
        // 如果一个是Blockade，获取其位置
        if (entity1 instanceof Blockade) {
            Blockade blockade = (Blockade) entity1;
            if (blockade.isPositionDefined()) {
                EntityID positionID = blockade.getPosition();
                entity1 = worldInfo.getEntity(positionID);
            }
        }
        
        if (entity2 instanceof Blockade) {
            Blockade blockade = (Blockade) entity2;
            if (blockade.isPositionDefined()) {
                EntityID positionID = blockade.getPosition();
                entity2 = worldInfo.getEntity(positionID);
            }
        }
        
        // 普通距离计算
        if (!(entity1 instanceof Area) || !(entity2 instanceof Area)) {
            return Double.MAX_VALUE;
        }
        
        Area area1 = (Area) entity1;
        Area area2 = (Area) entity2;
        
        int x1 = area1.getX();
        int y1 = area1.getY();
        int x2 = area2.getX();
        int y2 = area2.getY();
        
        return Math.sqrt(Math.pow(x2 - x1, 2) + Math.pow(y2 - y1, 2));
    }
    
    // 计算从某点到区域中心的距离
    public double distanceToCenter(double x, double y) {
        if (centerPoint == null) {
            calculateCenterPoint();
        }
        
        if (centerPoint == null) {
            return Double.MAX_VALUE;
        }
        
        double dx = x - centerPoint.getX();
        double dy = y - centerPoint.getY();
        return Math.sqrt(dx * dx + dy * dy);
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
        if (priorityCommands.isEmpty()) {
            return 0.0;
        }
        
        double totalResponseTime = 0.0;
        int count = 0;
        
        for (CommandInfo commandInfo : priorityCommands) {
            StandardEntity target = worldInfo.getEntity(commandInfo.getCommand().getTargetID());
            if (target instanceof Blockade) {
                Blockade blockade = (Blockade) target;
                if (blockade.getRepairCost() == 0) {
                    totalResponseTime += commandInfo.getTime();
                    count++;
                }
            }
        }
        
        return count == 0 ? 0.0 : totalResponseTime / count;
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
        
        // 首先添加被困智能体路障
        for (EntityID humanID : trappedHumans) {
            StandardEntity entity = worldInfo.getEntity(humanID);
            if (entity instanceof Human) {
                Human human = (Human) entity;
                if (human.isPositionDefined()) {
                    EntityID positionID = human.getPosition();
                    StandardEntity position = worldInfo.getEntity(positionID);
                    
                    if (position instanceof Road) {
                        Road road = (Road) position;
                        if (road.isBlockadesDefined()) {
                            Collection<Blockade> roadBlockades = worldInfo.getBlockades(road.getID());
                            if (roadBlockades != null && !roadBlockades.isEmpty()) {
                                for (Blockade blockade : roadBlockades) {
                                    highPriorityTargets.add(blockade.getID());
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // 然后添加其他高优先级路障
        for (Map.Entry<EntityID, Integer> entry : targetPriority.entrySet()) {
            if (entry.getValue() >= 2 && !highPriorityTargets.contains(entry.getKey())) {
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
    public List<EntityID> getTrappedHumans() { return trappedHumans; }
    public Point2D getCenterPoint() { return centerPoint; }
    
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
               roads.contains(entityID) ||
               trappedHumans.contains(entityID);
    }
    
    public int getZoneId() {
        return zoneID;
    }
    
    public double calculateWorkload(int currentTime) {
        double workload = 0.0;
        
        // 考虑未处理的路障数量
        workload += blockades.size() * 1.0;
        
        // 考虑被困智能体（高优先级）
        workload += trappedHumans.size() * 3.0;
        
        // 考虑高优先级命令
        workload += priorityCommands.size() * 2.0;
        
        // 考虑警察数量
        if (!policeAgents.isEmpty()) {
            workload /= policeAgents.size();
        }
        
        return workload;
    }
    
    public int getPoliceCount() {
        return policeAgents.size();
    }
    
    public void setPriority(double priority) {
        this.priority = priority;
    }
} 
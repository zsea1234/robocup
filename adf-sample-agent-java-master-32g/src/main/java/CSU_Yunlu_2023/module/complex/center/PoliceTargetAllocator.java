package CSU_Yunlu_2023.module.complex.center;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.StandardMessage;
import adf.core.agent.communication.standard.bundle.centralized.CommandPolice;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.communication.CommunicationMessage;
import CSU_Yunlu_2023.module.algorithm.AStarPathPlanning;
import rescuecore2.worldmodel.EntityID;
import rescuecore2.standard.entities.*;
import java.util.*;

public class PoliceTargetAllocator extends adf.core.component.module.complex.PoliceTargetAllocator {
    private static final int MAX_ZONES = 10;
    private static final double REBALANCE_THRESHOLD = 0.3;
    
    private List<PoliceZone> zones;
    private Map<EntityID, PoliceZone> policeZoneMap;
    private Map<EntityID, EntityID> allocationResult;
    private int lastRebalanceTime;
    private int rebalanceInterval;
    
    // 路径规划
    private AStarPathPlanning pathPlanner;
    
    // 性能监控
    private PerformanceMonitor performanceMonitor;
    
    public PoliceTargetAllocator(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        initialize();
    }
    
    private void initialize() {
        zones = new ArrayList<>();
        policeZoneMap = new HashMap<>();
        allocationResult = new HashMap<>();
        lastRebalanceTime = 0;
        rebalanceInterval = 50;
        pathPlanner = new AStarPathPlanning(agentInfo, worldInfo, scenarioInfo, moduleManager, developData);
        performanceMonitor = new PerformanceMonitor();
        
        initializeZones();
    }
    
    private void initializeZones() {
        List<StandardEntity> roads = new ArrayList<>(worldInfo.getEntitiesOfType(StandardEntityURN.ROAD));
        List<StandardEntity> blockades = new ArrayList<>(worldInfo.getEntitiesOfType(StandardEntityURN.BLOCKADE));
        
        for (int i = 0; i < MAX_ZONES; i++) {
            PoliceZone zone = new PoliceZone(i);
            zone.setWorldInfo(worldInfo);
            zones.add(zone);
        }
        
        distributeEntities(roads, blockades);
    }
    
    private void distributeEntities(List<StandardEntity> roads, List<StandardEntity> blockades) {
        // 简单的分配策略：平均分配给各个区域
        int zoneCount = zones.size();
        int roadsPerZone = roads.size() / zoneCount;
        int blockadesPerZone = blockades.size() / zoneCount;
        
        for (int i = 0; i < zoneCount; i++) {
            PoliceZone zone = zones.get(i);
            int startRoad = i * roadsPerZone;
            int endRoad = (i == zoneCount - 1) ? roads.size() : (i + 1) * roadsPerZone;
            
            for (int j = startRoad; j < endRoad; j++) {
                StandardEntity road = roads.get(j);
                zone.addRoad(road.getID());
            }
            
            int startBlockade = i * blockadesPerZone;
            int endBlockade = (i == zoneCount - 1) ? blockades.size() : (i + 1) * blockadesPerZone;
            
            for (int j = startBlockade; j < endBlockade; j++) {
                StandardEntity blockade = blockades.get(j);
                zone.addBlockade(blockade.getID());
            }
        }
    }
    
    @Override
    public Map<EntityID, EntityID> getResult() {
        return allocationResult;
    }
    
    @Override
    public PoliceTargetAllocator calc() {
        int currentTime = agentInfo.getTime();
        
        // 更新分区状态
        updateZoneStatus();
        
        // 检查是否需要重新平衡
        if (shouldRebalance(currentTime)) {
            rebalanceZones();
            lastRebalanceTime = currentTime;
        }
        
        // 计算任务分配
        calculateAllocation();
        
        // 更新性能指标
        performanceMonitor.updateMetrics(zones);
        
        return this;
    }
    
    private void updateZoneStatus() {
        int currentTime = agentInfo.getTime();
        for (PoliceZone zone : zones) {
            zone.updateStatus(currentTime);
            zone.clearExpiredCommands(currentTime);
        }
    }
    
    private boolean shouldRebalance(int currentTime) {
        // 检查是否达到重新平衡的时间间隔
        if (currentTime - lastRebalanceTime >= rebalanceInterval) {
            return true;
        }
        
        // 检查分区负载是否不平衡
        double maxPriority = zones.stream()
            .mapToDouble(PoliceZone::getPriority)
            .max()
            .orElse(0.0);
            
        double minPriority = zones.stream()
            .mapToDouble(PoliceZone::getPriority)
            .min()
            .orElse(0.0);
            
        return (maxPriority - minPriority) > REBALANCE_THRESHOLD;
    }
    
    private void rebalanceZones() {
        // 实现分区重新平衡逻辑
        // 1. 计算每个分区的负载
        // 2. 识别过载和欠载分区
        // 3. 重新分配警察资源
    }
    
    private void calculateAllocation() {
        allocationResult.clear();
        
        // 获取所有警察和路障
        List<StandardEntity> police = new ArrayList<>(worldInfo.getEntitiesOfType(StandardEntityURN.POLICE_FORCE));
        List<StandardEntity> blockades = new ArrayList<>(worldInfo.getEntitiesOfType(StandardEntityURN.BLOCKADE));
        
        // 1. 首先处理高优先级目标
        processHighPriorityTargets(police);
        
        // 2. 然后处理普通路障
        processNormalBlockades(police, blockades);
    }
    
    private void processHighPriorityTargets(List<StandardEntity> police) {
        // 获取所有高优先级目标
        List<EntityID> highPriorityTargets = new ArrayList<>();
        for (PoliceZone zone : zones) {
            highPriorityTargets.addAll(zone.getHighPriorityTargets());
        }
        
        // 为每个高优先级目标分配最近的警察
        for (EntityID target : highPriorityTargets) {
            StandardEntity targetEntity = worldInfo.getEntity(target);
            if (targetEntity == null) continue;
            
            // 找到最近的可用警察
            StandardEntity nearestPolice = findNearestAvailablePolice(police, targetEntity);
            if (nearestPolice != null) {
                allocationResult.put(nearestPolice.getID(), target);
                police.remove(nearestPolice); // 从可用警察列表中移除
            }
        }
    }
    
    private void processNormalBlockades(List<StandardEntity> police, List<StandardEntity> blockades) {
        // 对剩余警察和路障进行分配
        for (StandardEntity policeEntity : police) {
            EntityID policeID = policeEntity.getID();
            PoliceZone zone = policeZoneMap.get(policeID);
            
            if (zone != null) {
                // 在分区内寻找最近的路障
                EntityID target = findNearestBlockade(policeEntity, zone);
                if (target != null) {
                    allocationResult.put(policeID, target);
                }
            }
        }
    }
    
    private double calculateDistance(StandardEntity entity1, StandardEntity entity2) {
        if (!(entity1 instanceof Area) || !(entity2 instanceof Area)) {
            return Double.MAX_VALUE;
        }
        
        // 使用A*算法计算实际路径距离
        List<EntityID> path = pathPlanner
            .setFrom(entity1.getID())
            .setDestination(entity2.getID())
            .calc()
            .getResult();
            
        if (path == null || path.isEmpty()) {
            return Double.MAX_VALUE;
        }
        
        return pathPlanner.getDistance();
    }
    
    private EntityID findNearestBlockade(StandardEntity police, PoliceZone zone) {
        EntityID nearest = null;
        double minDistance = Double.MAX_VALUE;
        
        for (EntityID blockadeID : zone.getBlockades()) {
            StandardEntity blockade = worldInfo.getEntity(blockadeID);
            if (blockade == null) continue;
            
            double distance = calculateDistance(police, blockade);
            if (distance < minDistance) {
                minDistance = distance;
                nearest = blockadeID;
            }
        }
        
        return nearest;
    }
    
    private StandardEntity findNearestAvailablePolice(List<StandardEntity> police, StandardEntity target) {
        StandardEntity nearest = null;
        double minDistance = Double.MAX_VALUE;
        
        for (StandardEntity policeEntity : police) {
            if (allocationResult.containsKey(policeEntity.getID())) continue;
            
            double distance = calculateDistance(policeEntity, target);
            if (distance < minDistance) {
                minDistance = distance;
                nearest = policeEntity;
            }
        }
        
        return nearest;
    }
    
    @Override
    public PoliceTargetAllocator updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        pathPlanner.updateInfo(messageManager);
        
        // 处理接收到的消息
        if (messageManager != null) {
            List<CommunicationMessage> messages = messageManager.getReceivedMessageList();
            if (messages != null && !messages.isEmpty()) {
                for (CommunicationMessage message : messages) {
                    if (message instanceof CommandPolice) {
                        processCommand((CommandPolice) message);
                    }
                }
            }
        }
        
        return this;
    }
    
    private void processCommand(CommandPolice command) {
        EntityID targetID = command.getTargetID();
        for (PoliceZone zone : zones) {
            if (zone.containsEntity(targetID)) {
                zone.addPriorityCommand(command, agentInfo.getTime());
                break;
            }
        }
    }
    
    // 性能监控内部类
    private class PerformanceMonitor {
        private Map<Integer, Double> responseTimeHistory;
        private Map<Integer, Double> utilizationHistory;
        private Map<Integer, Double> coverageHistory;
        
        public PerformanceMonitor() {
            responseTimeHistory = new HashMap<>();
            utilizationHistory = new HashMap<>();
            coverageHistory = new HashMap<>();
        }
        
        public void updateMetrics(List<PoliceZone> zones) {
            int currentTime = agentInfo.getTime();
            
            // 计算平均响应时间
            double avgResponseTime = zones.stream()
                .mapToDouble(PoliceZone::getResponseTime)
                .average()
                .orElse(0.0);
                
            // 计算平均利用率
            double avgUtilization = zones.stream()
                .mapToDouble(PoliceZone::getPoliceUtilization)
                .average()
                .orElse(0.0);
                
            // 计算平均覆盖率
            double avgCoverage = zones.stream()
                .mapToDouble(PoliceZone::getZoneCoverage)
                .average()
                .orElse(0.0);
                
            // 记录历史数据
            responseTimeHistory.put(currentTime, avgResponseTime);
            utilizationHistory.put(currentTime, avgUtilization);
            coverageHistory.put(currentTime, avgCoverage);
        }
        
        public void analyzePerformance() {
            // 实现性能分析逻辑
            // 可以生成报告或触发优化策略
        }
    }
} 
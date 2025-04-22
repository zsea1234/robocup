package CSU_Yunlu_2023.module.complex.center;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.centralized.CommandPolice;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.communication.CommunicationMessage;
import CSU_Yunlu_2023.module.algorithm.AStarPathPlanning;
import CSU_Yunlu_2023.util.Logger;
import rescuecore2.worldmodel.EntityID;
import rescuecore2.standard.entities.*;
import java.util.*;

public class CSUPoliceTargetAllocator extends adf.core.component.module.complex.PoliceTargetAllocator implements AutoCloseable {
    private static final double REBALANCE_THRESHOLD = 0.3;
    private static final double SATURATION_COEFFICIENT = 1.5;
    
    private List<PoliceZone> zones;
    private Map<EntityID, PoliceZone> policeZoneMap;
    private Map<EntityID, EntityID> allocationResult;
    private int lastRebalanceTime;
    private int rebalanceInterval;
    
    // 路径规划
    private AStarPathPlanning pathPlanner;
    
    // 性能监控
    private PerformanceMonitor performanceMonitor;
    
    // 日志
    private final Logger logger;
    
    // 添加动态警察列表
    private List<EntityID> dynamicPoliceAgents;
    
    public CSUPoliceTargetAllocator(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        logger = Logger.getLogger(CSUPoliceTargetAllocator.class);
        logger.info("初始化 PoliceTargetAllocator");
        initialize();
    }
    
    private void initialize() {
        logger.info("开始初始化...");
        zones = new ArrayList<>();
        policeZoneMap = new HashMap<>();
        allocationResult = new HashMap<>();
        lastRebalanceTime = 0;
        rebalanceInterval = 50;
        pathPlanner = new AStarPathPlanning(agentInfo, worldInfo, scenarioInfo, moduleManager, developData);
        performanceMonitor = new PerformanceMonitor();
        dynamicPoliceAgents = new ArrayList<>();
        
        initializeZones();
        logger.info("初始化完成，创建了 " + zones.size() + " 个区域");
    }
    
    private void initializeZones() {
        logger.info("开始初始化区域...");
        List<StandardEntity> roads = new ArrayList<>(worldInfo.getEntitiesOfType(StandardEntityURN.ROAD));
        List<StandardEntity> blockades = new ArrayList<>();
        List<StandardEntity> policeForces = new ArrayList<>(worldInfo.getEntitiesOfType(StandardEntityURN.POLICE_FORCE));
        
        // 详细检测路障
        for (StandardEntity road : roads) {
            if (road instanceof Road) {
                Road r = (Road) road;
                if (r.isBlockadesDefined()) {
                    Collection<Blockade> roadBlockades = worldInfo.getBlockades(r.getID());
                    if (roadBlockades != null) {
                        for (Blockade blockade : roadBlockades) {
                            if (blockade.isApexesDefined() && blockade.getRepairCost() > 0) {
                                blockades.add(blockade);
                                logger.debug("发现路障: " + blockade.getID() + ", 修复成本: " + blockade.getRepairCost() + ", 位置: " + blockade.getX() + "," + blockade.getY());
                            }
                        }
                    }
                }
            }
        }
        
        logger.info("发现 " + roads.size() + " 条道路和 " + blockades.size() + " 个路障");
        logger.info("发现 " + policeForces.size() + " 个警察");
        
        // 计算区域数量
        int policeCount = policeForces.size();
        int maxPartitionNumber = (int)(SATURATION_COEFFICIENT * policeCount);
        int sqrtMPN = (int)Math.floor(Math.sqrt(maxPartitionNumber));
        int actualPartitionNumber = sqrtMPN * (int)Math.ceil(maxPartitionNumber / (double)sqrtMPN);
        
        logger.info("根据警察数量 " + policeCount + " 计算得到最大分区数 " + maxPartitionNumber + ", 实际分区数 " + actualPartitionNumber);
        
        // 创建区域
        for (int i = 0; i < actualPartitionNumber; i++) {
            PoliceZone zone = new PoliceZone(i);
            zone.setWorldInfo(worldInfo);
            zones.add(zone);
        }
        
        distributeEntities(roads, blockades);
        distributePolice(policeForces);
        logger.info("区域初始化完成，每个区域平均分配 " + (roads.size() / actualPartitionNumber) + " 条道路和 " + (blockades.size() / actualPartitionNumber) + " 个路障");
    }
    
    private void distributeEntities(List<StandardEntity> roads, List<StandardEntity> blockades) {
        logger.info("开始分配实体到各个区域...");
        int zoneCount = zones.size();
        int roadsPerZone = roads.size() / zoneCount;
        int blockadesPerZone = blockades.size() / zoneCount;
        
        // 先分配道路
        for (int i = 0; i < zoneCount; i++) {
            PoliceZone zone = zones.get(i);
            int startRoad = i * roadsPerZone;
            int endRoad = (i == zoneCount - 1) ? roads.size() : (i + 1) * roadsPerZone;
            
            for (int j = startRoad; j < endRoad; j++) {
                StandardEntity road = roads.get(j);
                zone.addRoad(road.getID());
            }
        }
        
        // 然后分配路障，确保每个路障都被分配到最近的道路所在区域
        for (StandardEntity blockade : blockades) {
            StandardEntity nearestRoad = findNearestRoad(blockade, roads);
            if (nearestRoad != null) {
                int zoneIndex = findZoneForRoad(nearestRoad.getID());
                if (zoneIndex != -1) {
                    zones.get(zoneIndex).addBlockade(blockade.getID());
                    logger.debug("将路障 " + blockade.getID() + " 分配到区域 " + zoneIndex);
                }
            }
        }
        
        // 记录每个区域的分配情况
        for (int i = 0; i < zoneCount; i++) {
            PoliceZone zone = zones.get(i);
            logger.debug("区域 " + i + " 分配了 " + zone.getRoads().size() + " 条道路和 " + zone.getBlockades().size() + " 个路障");
        }
    }
    
    private StandardEntity findNearestRoad(StandardEntity blockade, List<StandardEntity> roads) {
        StandardEntity nearest = null;
        double minDistance = Double.MAX_VALUE;
        
        for (StandardEntity road : roads) {
            double distance = calculateDistance(blockade, road);
            if (distance < minDistance) {
                minDistance = distance;
                nearest = road;
            }
        }
        
        return nearest;
    }
    
    private int findZoneForRoad(EntityID roadID) {
        for (int i = 0; i < zones.size(); i++) {
            if (zones.get(i).getRoads().contains(roadID)) {
                return i;
            }
        }
        return -1;
    }
    
    private void distributePolice(List<StandardEntity> policeForces) {
        logger.info("开始分配警察到各个区域...");
        int zoneCount = zones.size();
        int staticPoliceCount = (int)(policeForces.size() * 0.8);
        
        
        for (int i = 0; i < zoneCount; i++) {
            PoliceZone zone = zones.get(i);
            int startPolice = i * staticPoliceCount / zoneCount;
            int endPolice = (i == zoneCount - 1) ? staticPoliceCount : (i + 1) * staticPoliceCount / zoneCount;
            
            for (int j = startPolice; j < endPolice && j < policeForces.size(); j++) {
                StandardEntity police = policeForces.get(j);
                zone.addPolice(police.getID());
                policeZoneMap.put(police.getID(), zone);
                logger.debug("将警察 " + police.getID() + " 分配到区域 " + i);
            }
        }
        
        // 剩余的警察作为动态警察
        for (int i = staticPoliceCount; i < policeForces.size(); i++) {
            StandardEntity police = policeForces.get(i);
            dynamicPoliceAgents.add(police.getID());
            logger.debug("将警察 " + police.getID() + " 设为动态警察");
        }
        
        logger.info("静态警察数量: " + staticPoliceCount + ", 动态警察数量: " + dynamicPoliceAgents.size());
    }
    
    @Override
    public Map<EntityID, EntityID> getResult() {
        return allocationResult;
    }
    
    @Override
    public CSUPoliceTargetAllocator calc() {
        logger.info("开始计算任务分配...");
        int currentTime = agentInfo.getTime();
        
        // 更新分区状态
        updateZoneStatus();
        
        // 检查是否需要重新平衡
        if (shouldRebalance(currentTime)) {
            logger.info("检测到需要重新平衡区域");
            rebalanceZones();
            lastRebalanceTime = currentTime;
        }
        
        // 计算任务分配
        calculateAllocation();
        
        // 更新性能指标
        performanceMonitor.updateMetrics(zones);
        
        logger.info("任务分配完成，共分配 " + allocationResult.size() + " 个任务");
        return this;
    }
    
    private void updateZoneStatus() {
        int currentTime = agentInfo.getTime();
        logger.debug("更新区域状态，当前时间: " + currentTime);
        
        // 更新每个区域的状态
        for (PoliceZone zone : zones) {
            zone.updateStatus(currentTime);
            zone.clearExpiredCommands(currentTime);
            
            // 计算区域优先级
            double priority = calculateZonePriority(zone);
            zone.setPriority(priority);
            
            logger.debug("区域 " + zone.getZoneId() + " 状态更新完成，优先级: " + priority);
        }
    }
    
    private double calculateZonePriority(PoliceZone zone) {
        double basePriority = 0.2;
        double workloadFactor = 0.0;
        double responseTimeFactor = 0.0;
        double policeUtilizationFactor = 0.0;
        
        // 计算工作负载因子
        int blockadeCount = zone.getBlockades().size();
        int policeCount = zone.getPoliceCount();
        if (policeCount > 0) {
            workloadFactor = (double) blockadeCount / policeCount;
        }
        
        // 计算响应时间因子
        double avgResponseTime = zone.getResponseTime();
        responseTimeFactor = Math.min(avgResponseTime / 100.0, 1.0); // 假设100是最大可接受响应时间
        
        // 计算警察利用率因子
        double utilization = zone.getPoliceUtilization();
        policeUtilizationFactor = 1.0 - utilization; // 利用率越低，优先级越高
        
        // 综合计算最终优先级
        double finalPriority = basePriority + 
                              workloadFactor * 0.3 + 
                              responseTimeFactor * 0.3 + 
                              policeUtilizationFactor * 0.2;
        
        // 确保优先级在合理范围内
        return Math.min(Math.max(finalPriority, 0.1), 1.0);
    }
    
    private boolean shouldRebalance(int currentTime) {
        if (currentTime - lastRebalanceTime >= rebalanceInterval) {
            logger.debug("达到重新平衡时间间隔");
            return true;
        }
        
        double maxPriority = zones.stream()
            .mapToDouble(PoliceZone::getPriority)
            .max()
            .orElse(0.0);
            
        double minPriority = zones.stream()
            .mapToDouble(PoliceZone::getPriority)
            .min()
            .orElse(0.0);
            
        double priorityDiff = maxPriority - minPriority;
        logger.debug("区域优先级差异: " + priorityDiff + " (阈值: " + REBALANCE_THRESHOLD + ")");
        
        return priorityDiff > REBALANCE_THRESHOLD;
    }
    
    private void rebalanceZones() {
        logger.info("开始重新平衡区域...");
        Map<PoliceZone, Double> zoneLoads = new HashMap<>();
        for (PoliceZone zone : zones) {
            double load = zone.calculateWorkload(agentInfo.getTime());
            zoneLoads.put(zone, load);
            logger.debug("区域 " + zone.getZoneId() + " 当前负载: " + load);
        }
        
        double avgLoad = zoneLoads.values().stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        logger.info("平均区域负载: " + avgLoad);
    }
    
    private void calculateAllocation() {
        logger.info("开始计算任务分配...");
        allocationResult.clear();
        
        // 获取所有需要处理的路障
        List<StandardEntity> blockades = new ArrayList<>();
        List<StandardEntity> roads = new ArrayList<>(worldInfo.getEntitiesOfType(StandardEntityURN.ROAD));
        
        for (StandardEntity road : roads) {
            if (road instanceof Road) {
                Road r = (Road) road;
                if (r.isBlockadesDefined()) {
                    Collection<Blockade> roadBlockades = worldInfo.getBlockades(r.getID());
                    if (roadBlockades != null) {
                        for (Blockade blockade : roadBlockades) {
                            if (blockade.isApexesDefined() && blockade.getRepairCost() > 0) {
                                blockades.add(blockade);
                                logger.debug("发现待处理路障: " + blockade.getID() + ", 修复成本: " + blockade.getRepairCost());
                            }
                        }
                    }
                }
            }
        }
        
        List<StandardEntity> police = new ArrayList<>(worldInfo.getEntitiesOfType(StandardEntityURN.POLICE_FORCE));
        
        logger.info("可用警察数量: " + police.size() + ", 待处理路障数量: " + blockades.size());
        
        // 处理高优先级目标
        processHighPriorityTargets(police);
        
        // 为静态警察分配任务
        allocateStaticPolice();
        
        // 为动态警察分配任务
        allocateDynamicPolice();
        
        logger.info("任务分配完成，共分配 " + allocationResult.size() + " 个任务");
    }
    
    private void allocateStaticPolice() {
        // 按区域优先级排序
        zones.sort(Comparator.comparingDouble(PoliceZone::getPriority).reversed());
        
        for (PoliceZone zone : zones) {
            List<EntityID> zoneBlockades = zone.getBlockades();
            List<EntityID> zonePolice = zone.getPoliceAgents();
            
            if (!zoneBlockades.isEmpty() && !zonePolice.isEmpty()) {
                // 为区域内的警察分配任务
                for (EntityID policeID : zonePolice) {
                    if (!allocationResult.containsKey(policeID) && !dynamicPoliceAgents.contains(policeID)) {
                        EntityID target = findNearestBlockadeInZone(policeID, zoneBlockades);
                        if (target != null) {
                            allocationResult.put(policeID, target);
                            logger.debug("为静态警察 " + policeID + " 分配路障 " + target);
                        }
                    }
                }
            }
        }
    }
    
    private void allocateDynamicPolice() {
        if (dynamicPoliceAgents.isEmpty()) {
            logger.debug("没有动态警察可分配");
            return;
        }
        
        // 计算每个区域的评估值
        Map<PoliceZone, Double> zoneEvaluations = calculateZoneEvaluations();
        
        // 按评估值排序区域
        List<Map.Entry<PoliceZone, Double>> sortedZones = new ArrayList<>(zoneEvaluations.entrySet());
        sortedZones.sort(Map.Entry.<PoliceZone, Double>comparingByValue().reversed());
        
        // 为动态警察分配任务
        for (EntityID policeID : dynamicPoliceAgents) {
            if (allocationResult.containsKey(policeID)) {
                continue;
            }
            
            // 选择评估值最高的区域
            for (Map.Entry<PoliceZone, Double> entry : sortedZones) {
                PoliceZone zone = entry.getKey();
                List<EntityID> zoneBlockades = zone.getBlockades();
                
                if (!zoneBlockades.isEmpty()) {
                    EntityID target = findNearestBlockadeInZone(policeID, zoneBlockades);
                    if (target != null) {
                        allocationResult.put(policeID, target);
                        logger.debug("为动态警察 " + policeID + " 分配区域 " + zone.getZoneId() + " 的路障 " + target + ", 区域评估值: " + entry.getValue());
                        break;
                    }
                }
            }
        }
    }
    
    private Map<PoliceZone, Double> calculateZoneEvaluations() {
        Map<PoliceZone, Double> evaluations = new HashMap<>();
        
        for (PoliceZone zone : zones) {
            // 实现线性加权评价模型
            double evaluation = 0.0;
            
            // 影响因子权重
            final double W_REFUGE = 0.1;       // countref - 避难所数量
            final double W_FIRE = 0.15;        // countfire - 着火建筑数量
            final double W_FB = 0.1;           // countfb - 消防队数量
            final double W_AT = 0.1;           // countat - 救护队数量
            final double W_PF = -0.1;          // countpf - 警察数量（负权重，警察越多评价越低）
            final double W_CIV = 0.1;          // countciv - 市民数量
            final double W_DIST = -0.1;        // distome - 与自身距离（负权重，距离越远评价越低）
            final double W_BLOCKREP = 0.15;    // blockrep - 完全性路障报告数
            final double W_LOCKEDREP = 0.2;    // lockedrep - 被困智能体报告数
            final double W_PATH = 0.1;         // countpath - 道路数量
            
            // 计算各因素值
            double countref = countEntitiesInZone(zone, StandardEntityURN.REFUGE);
            double countfire = countBurningBuildingsInZone(zone);
            double countfb = countEntitiesInZone(zone, StandardEntityURN.FIRE_BRIGADE);
            double countat = countEntitiesInZone(zone, StandardEntityURN.AMBULANCE_TEAM);
            double countpf = zone.getPoliceCount();
            double countciv = countEntitiesInZone(zone, StandardEntityURN.CIVILIAN);
            double distome = calculateAverageDistanceToZone(zone);
            double blockrep = zone.getBlockades().size();
            double lockedrep = countTrappedHumansInZone(zone);
            double countpath = zone.getRoads().size();
            
            // 归一化处理
            countref = normalize(countref, 0, 5);
            countfire = normalize(countfire, 0, 20);
            countfb = normalize(countfb, 0, 10);
            countat = normalize(countat, 0, 10);
            countpf = normalize(countpf, 0, 10);
            countciv = normalize(countciv, 0, 50);
            distome = normalize(distome, 0, 100000);
            blockrep = normalize(blockrep, 0, 30);
            lockedrep = normalize(lockedrep, 0, 10);
            countpath = normalize(countpath, 0, 100);
            
            // 线性加权求和
            evaluation = W_REFUGE * countref +
                         W_FIRE * countfire +
                         W_FB * countfb +
                         W_AT * countat +
                         W_PF * countpf +
                         W_CIV * countciv +
                         W_DIST * distome +
                         W_BLOCKREP * blockrep +
                         W_LOCKEDREP * lockedrep +
                         W_PATH * countpath;
            
            evaluations.put(zone, evaluation);
            logger.debug("区域 " + zone.getZoneId() + " 评估值: " + evaluation);
        }
        
        return evaluations;
    }
    
    // 归一化函数，将值映射到0-1之间
    private double normalize(double value, double min, double max) {
        if (max == min) return 0.5;
        return Math.min(1.0, Math.max(0.0, (value - min) / (max - min)));
    }
    
    private double countEntitiesInZone(PoliceZone zone, StandardEntityURN type) {
        int count = 0;
        List<EntityID> roadIDs = zone.getRoads();
        
        for (EntityID roadID : roadIDs) {
            Road road = (Road) worldInfo.getEntity(roadID);
            if (road != null) {
                for (EntityID nearbyID : road.getNeighbours()) {
                    StandardEntity entity = worldInfo.getEntity(nearbyID);
                    if (entity.getStandardURN() == type) {
                        count++;
                    }
                }
            }
        }
        
        return count;
    }
    
    private double countBurningBuildingsInZone(PoliceZone zone) {
        int count = 0;
        List<EntityID> roadIDs = zone.getRoads();
        
        for (EntityID roadID : roadIDs) {
            Road road = (Road) worldInfo.getEntity(roadID);
            if (road != null) {
                for (EntityID nearbyID : road.getNeighbours()) {
                    StandardEntity entity = worldInfo.getEntity(nearbyID);
                    if (entity instanceof Building) {
                        Building building = (Building) entity;
                        if (building.isOnFire()) {
                            count++;
                        }
                    }
                }
            }
        }
        
        return count;
    }
    
    private double calculateAverageDistanceToZone(PoliceZone zone) {
        double totalDistance = 0.0;
        int count = 0;
        
        for (EntityID dynamicPoliceID : dynamicPoliceAgents) {
            StandardEntity police = worldInfo.getEntity(dynamicPoliceID);
            if (police != null) {
                for (EntityID roadID : zone.getRoads()) {
                    StandardEntity road = worldInfo.getEntity(roadID);
                    if (road != null) {
                        totalDistance += calculateDistance(police, road);
                        count++;
                    }
                }
            }
        }
        
        return count > 0 ? totalDistance / count : Double.MAX_VALUE;
    }
    
    private double countTrappedHumansInZone(PoliceZone zone) {
        int count = 0;
        List<EntityID> roadIDs = zone.getRoads();
        
        for (EntityID roadID : roadIDs) {
            StandardEntity roadEntity = worldInfo.getEntity(roadID);
            if (roadEntity instanceof Road) {
                Road road = (Road) roadEntity;
                if (road.isBlockadesDefined()) {
                    Collection<Blockade> blockades = worldInfo.getBlockades(road.getID());
                    if (blockades != null && !blockades.isEmpty()) {
                        for (StandardEntity entity : worldInfo.getAllEntities()) {
                            if (entity instanceof Human) {
                                Human human = (Human) entity;
                                if (human.getPosition().equals(road.getID())) {
                                    count++;
                                }
                            }
                        }
                    }
                }
            }
        }
        
        return count;
    }
    
    private EntityID findNearestBlockadeInZone(EntityID policeID, List<EntityID> blockades) {
        StandardEntity police = worldInfo.getEntity(policeID);
        if (police == null) return null;
        
        EntityID nearest = null;
        double minDistance = Double.MAX_VALUE;
        
        for (EntityID blockadeID : blockades) {
            StandardEntity blockade = worldInfo.getEntity(blockadeID);
            if (blockade != null && !allocationResult.containsValue(blockadeID)) {
                double distance = calculateDistance(police, blockade);
                if (distance < minDistance) {
                    minDistance = distance;
                    nearest = blockadeID;
                }
            }
        }
        
        return nearest;
    }
    
    private void processHighPriorityTargets(List<StandardEntity> police) {
        logger.info("开始处理高优先级目标...");
        List<EntityID> highPriorityTargets = new ArrayList<>();
        for (PoliceZone zone : zones) {
            highPriorityTargets.addAll(zone.getHighPriorityTargets());
        }
        
        logger.info("发现 " + highPriorityTargets.size() + " 个高优先级目标");
        
        for (EntityID target : highPriorityTargets) {
            StandardEntity targetEntity = worldInfo.getEntity(target);
            if (targetEntity == null) {
                logger.warn("找不到目标实体 " + target);
                continue;
            }
            
            StandardEntity nearestPolice = findNearestAvailablePolice(police, targetEntity);
            if (nearestPolice != null) {
                allocationResult.put(nearestPolice.getID(), target);
                police.remove(nearestPolice);
                logger.debug("为高优先级目标 " + target + " 分配警察 " + nearestPolice.getID());
            } else {
                logger.warn("无法为高优先级目标 " + target + " 找到可用警察");
            }
        }
    }
    
    private double calculateDistance(StandardEntity entity1, StandardEntity entity2) {
        // 处理空值情况
        if (entity1 == null || entity2 == null) {
            logger.warn("计算距离时遇到空实体");
            return Double.MAX_VALUE;
        }

        // 如果实体是Blockade类型，获取其所在的Road
        if (entity1 instanceof Blockade) {
            EntityID roadID = ((Blockade) entity1).getPosition();
            if (roadID != null) {
                entity1 = worldInfo.getEntity(roadID);
            }
        }
        if (entity2 instanceof Blockade) {
            EntityID roadID = ((Blockade) entity2).getPosition();
            if (roadID != null) {
                entity2 = worldInfo.getEntity(roadID);
            }
        }

        // 如果两个实体都是Area类型，使用路径规划计算距离
        if (entity1 instanceof Area && entity2 instanceof Area) {
            List<EntityID> path = pathPlanner
                .setFrom(entity1.getID())
                .setDestination(entity2.getID())
                .calc()
                .getResult();
                
            if (path != null && !path.isEmpty()) {
                double distance = pathPlanner.getDistance();
                logger.debug("计算路径距离：从 " + entity1.getID() + " 到 " + entity2.getID() + " = " + distance);
                return distance;
            }
        }

        // 处理Human类型（包括PoliceForce）
        if (entity1 instanceof Human || entity2 instanceof Human) {
            // 获取Human的位置
            StandardEntity location1 = entity1 instanceof Human ? 
                worldInfo.getEntity(((Human) entity1).getPosition()) : entity1;
            StandardEntity location2 = entity2 instanceof Human ? 
                worldInfo.getEntity(((Human) entity2).getPosition()) : entity2;
            
            if (location1 instanceof Area && location2 instanceof Area) {
                List<EntityID> path = pathPlanner
                    .setFrom(location1.getID())
                    .setDestination(location2.getID())
                    .calc()
                    .getResult();
                
                if (path != null && !path.isEmpty()) {
                    double distance = pathPlanner.getDistance();
                    logger.debug("计算Human位置距离：从 " + location1.getID() + " 到 " + location2.getID() + " = " + distance);
                    return distance;
                }
            }
        }

        // 如果实体有坐标信息，使用欧几里得距离
        int x1 = getEntityX(entity1);
        int y1 = getEntityY(entity1);
        int x2 = getEntityX(entity2);
        int y2 = getEntityY(entity2);
        
        if (x1 != Integer.MIN_VALUE && y1 != Integer.MIN_VALUE && 
            x2 != Integer.MIN_VALUE && y2 != Integer.MIN_VALUE) {
            double dx = x2 - x1;
            double dy = y2 - y1;
            double distance = Math.sqrt(dx * dx + dy * dy);
            logger.debug("计算欧几里得距离：从 (" + x1 + "," + y1 + ") 到 (" + x2 + "," + y2 + ") = " + distance);
            return distance;
        }

        // 如果以上方法都失败，使用世界信息中的距离
        if (entity1.getID() != null && entity2.getID() != null) {
            int distance = worldInfo.getDistance(entity1.getID(), entity2.getID());
            if (distance != -1) {
                logger.debug("使用世界信息距离：从 " + entity1.getID() + " 到 " + entity2.getID() + " = " + distance);
                return distance;
            }
        }

        logger.debug("无法计算距离，使用最大值");
        return Double.MAX_VALUE;
    }
    
    // 辅助方法：获取实体的X坐标
    private int getEntityX(StandardEntity entity) {
        if (entity instanceof Human) {
            Human human = (Human) entity;
            return human.isXDefined() ? human.getX() : Integer.MIN_VALUE;
        } else if (entity instanceof Area) {
            Area area = (Area) entity;
            return area.getX();
        } else if (entity instanceof Blockade) {
            Blockade blockade = (Blockade) entity;
            return blockade.getX();
        }
        return Integer.MIN_VALUE;
    }
    
    // 辅助方法：获取实体的Y坐标
    private int getEntityY(StandardEntity entity) {
        if (entity instanceof Human) {
            Human human = (Human) entity;
            return human.isYDefined() ? human.getY() : Integer.MIN_VALUE;
        } else if (entity instanceof Area) {
            Area area = (Area) entity;
            return area.getY();
        } else if (entity instanceof Blockade) {
            Blockade blockade = (Blockade) entity;
            return blockade.getY();
        }
        return Integer.MIN_VALUE;
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
    public CSUPoliceTargetAllocator updateInfo(MessageManager messageManager) {
        try {
            logger.info("开始更新信息...");
            super.updateInfo(messageManager);
            pathPlanner.updateInfo(messageManager);
            
            if (messageManager != null) {
                processMessages(messageManager.getReceivedMessageList());
            }
            
            performanceMonitor.updateMetrics(zones);
            
            if (shouldRebalance(agentInfo.getTime())) {
                rebalanceZones();
            }
            
            logger.info("信息更新完成");
            return this;
        } catch (ClassCastException e) {
            logger.error("类型转换错误", e);
            return this;
        }
    }
    
    private void processMessages(List<CommunicationMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            logger.debug("没有收到新消息");
            return;
        }
        
        logger.info("收到 " + messages.size() + " 条新消息");
        for (CommunicationMessage message : messages) {
            if (message instanceof CommandPolice) {
                CommandPolice command = (CommandPolice) message;
                try {
                    processCommand(command);
                    logger.debug("成功处理警察命令: " + command.getAction() + " 目标: " + command.getTargetID());
                } catch (Exception e) {
                    logger.error("处理警察命令时出错", e);
                }
            }
        }
    }
    
    private void processCommand(CommandPolice command) {
        EntityID targetID = command.getTargetID();
        logger.debug("处理警察命令，目标ID: " + targetID);
        
        for (PoliceZone zone : zones) {
            if (zone.containsEntity(targetID)) {
                zone.addPriorityCommand(command, agentInfo.getTime());
                logger.debug("将命令添加到区域 " + zone.getZoneId());
                break;
            }
        }
    }
    
    @Override
    public void close() {
        try {
            logger.close();
        } catch (Exception e) {
            logger.error("关闭日志文件时出错", e);
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
            logger.debug("更新性能指标，当前时间: " + currentTime);
            
            double avgResponseTime = zones.stream()
                .mapToDouble(PoliceZone::getResponseTime)
                .average()
                .orElse(0.0);
                
            double avgUtilization = zones.stream()
                .mapToDouble(PoliceZone::getPoliceUtilization)
                .average()
                .orElse(0.0);
                
            double avgCoverage = zones.stream()
                .mapToDouble(PoliceZone::getZoneCoverage)
                .average()
                .orElse(0.0);
                
            responseTimeHistory.put(currentTime, avgResponseTime);
            utilizationHistory.put(currentTime, avgUtilization);
            coverageHistory.put(currentTime, avgCoverage);
            
            logger.logPerformance("平均响应时间", avgResponseTime);
            logger.logPerformance("平均利用率", avgUtilization);
            logger.logPerformance("平均覆盖率", avgCoverage);
        }
        
        public void analyzePerformance() {
            logger.info("开始分析性能数据...");
            // 实现性能分析逻辑
        }
    }
} 
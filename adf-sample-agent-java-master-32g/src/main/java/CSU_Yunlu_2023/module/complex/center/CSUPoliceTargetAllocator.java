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

import java.awt.*;
import java.util.*;
import java.util.List;
import java.lang.reflect.Method;

public class CSUPoliceTargetAllocator extends adf.core.component.module.complex.PoliceTargetAllocator implements AutoCloseable {
    private static final double REBALANCE_THRESHOLD = 0.3;
    private static final double SATURATION_COEFFICIENT = 0.5;
    
    // 添加关键道路识别和重要设施相关常量
    private static final double CRITICAL_ROAD_BONUS = 0.5;  // 关键道路优先级加成
    private static final double HYDRANT_BONUS = 0.3;        // 消防栓附近优先级加成
    private static final double REFUGE_VICINITY_BONUS = 0.4; // 避难所附近优先级加成
    
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
    
    // 添加此方法来初始化缓存变量
    private Map<String, Double> distanceCache = new HashMap<>();
    
    // 添加重要道路缓存
    private Set<EntityID> criticalRoads;
    
    // 添加上次清除路障统计
    private Map<EntityID, Integer> lastClearedTime;
    private Map<EntityID, Double> clearanceProgress;
    
    public CSUPoliceTargetAllocator(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        logger = Logger.getLogger(CSUPoliceTargetAllocator.class);
        logger.info("初始化 PoliceTargetAllocator");
        initialize();
    }
    
    @Override
    public void initialize() {
        super.initialize();
        this.dynamicPoliceAgents = new ArrayList<>();
        this.criticalRoads = new HashSet<>();  // 初始化关键道路集合
        this.policeRoadMap = new HashMap<>();
        
        // 调用关键道路识别方法
        identifyCriticalRoads();
        
        // 其他初始化代码
        zones = new ArrayList<>();
        policeZoneMap = new HashMap<>();
        allocationResult = new HashMap<>();
        lastRebalanceTime = 0;
        rebalanceInterval = 30; // 减小重分配间隔，使系统更灵敏
        pathPlanner = new AStarPathPlanning(agentInfo, worldInfo, scenarioInfo, moduleManager, developData);
        performanceMonitor = new PerformanceMonitor();
        lastClearedTime = new HashMap<>();
        clearanceProgress = new HashMap<>();
        
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
        
        // 计算区域数量 - 修改饱和系数以减少分区数量
        int policeCount = policeForces.size();
        // 保证至少有1个区域，防止下面计算错误导致0个区域的情况
        int maxPartitionNumber = Math.max(1, (int)(SATURATION_COEFFICIENT * policeCount));
        int sqrtMPN = Math.max(1, (int)Math.floor(Math.sqrt(maxPartitionNumber)));
        int actualPartitionNumber = sqrtMPN * (int)Math.ceil(maxPartitionNumber / (double)sqrtMPN);
        
        // 如果警察数量很少，最少保证2个分区
        actualPartitionNumber = Math.max(2, actualPartitionNumber);
        
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
        
        // 更智能的道路分配 - 按地理位置聚类
        distributeRoadsByLocation(roads);
        
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
    
    // 按地理位置分配道路
    private void distributeRoadsByLocation(List<StandardEntity> roads) {
        if (roads.isEmpty() || zones.isEmpty()) {
            return;
        }
        
        int zoneCount = zones.size();
        
        // 1. 计算地图的边界
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        
        for (StandardEntity entity : roads) {
            if (entity instanceof Road) {
                Road road = (Road) entity;
                int x = road.getX();
                int y = road.getY();
                
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
        }
        
        // 2. 计算每个区域的中心点
        int width = maxX - minX;
        int height = maxY - minY;
        
        // 确定网格大小
        int gridWidth, gridHeight;
        if (zoneCount == 1) {
            gridWidth = 1;
            gridHeight = 1;
        } else if (zoneCount <= 4) {
            gridWidth = 2;
            gridHeight = 2;
        } else {
            gridWidth = (int) Math.ceil(Math.sqrt(zoneCount));
            gridHeight = (int) Math.ceil((double) zoneCount / gridWidth);
        }
        
        double cellWidth = width / (double) gridWidth;
        double cellHeight = height / (double) gridHeight;
        
        // 3. 计算每个分区的中心点并分配道路
        for (StandardEntity entity : roads) {
            if (entity instanceof Road) {
                Road road = (Road) entity;
                int x = road.getX();
                int y = road.getY();
                
                // 找到最近的区域
                int gridX = Math.min(gridWidth - 1, (int) ((x - minX) / cellWidth));
                int gridY = Math.min(gridHeight - 1, (int) ((y - minY) / cellHeight));
                int zoneIndex = gridY * gridWidth + gridX;
                
                // 确保索引有效
                if (zoneIndex >= zoneCount) {
                    zoneIndex = zoneCount - 1;
                }
                
                zones.get(zoneIndex).addRoad(road.getID());
            }
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
        
        // 确保有足够的警察进行分配
        if (policeForces.isEmpty()) {
            logger.warn("没有可用的警察进行分配");
            return;
        }

        // 修改为70%静态/30%动态的比例
        int staticPoliceCount = Math.max(1, (int)(policeForces.size() * 0.7));
        
        // 计算每个区域的优先级 - 根据路障数量和道路数量
        Map<Integer, Double> zonePriorities = new HashMap<>();
        for (int i = 0; i < zoneCount; i++) {
            PoliceZone zone = zones.get(i);
            double blockadeCount = zone.getBlockades().size();
            double roadCount = zone.getRoads().size();
            double priority = blockadeCount * 0.7 + roadCount * 0.3;
            zonePriorities.put(i, priority);
        }
        
        // 按优先级排序区域索引
        List<Integer> sortedZoneIndices = new ArrayList<>(zonePriorities.keySet());
        sortedZoneIndices.sort((a, b) -> Double.compare(zonePriorities.get(b), zonePriorities.get(a)));
        
        // 计算每个区域应该分配的警察数量（按优先级加权）
        double totalPriority = zonePriorities.values().stream().mapToDouble(Double::doubleValue).sum();
        Map<Integer, Integer> policeAllocation = new HashMap<>();
        
        int remainingPolice = staticPoliceCount;
        for (int i = 0; i < zoneCount && remainingPolice > 0; i++) {
            int zoneIndex = sortedZoneIndices.get(i);
            double zonePriority = zonePriorities.get(zoneIndex);
            
            // 至少分配1个警察给每个区域
            int policeCount = Math.max(1, (int)Math.round((zonePriority / totalPriority) * staticPoliceCount));
            // 确保不超过剩余警察数量
            policeCount = Math.min(policeCount, remainingPolice);
            
            policeAllocation.put(zoneIndex, policeCount);
            remainingPolice -= policeCount;
        }
        
        // 如果还有未分配的警察，分配给优先级最高的区域
        if (remainingPolice > 0 && !sortedZoneIndices.isEmpty()) {
            int highestPriorityZone = sortedZoneIndices.get(0);
            policeAllocation.put(highestPriorityZone, policeAllocation.get(highestPriorityZone) + remainingPolice);
        }
        
        // 分配静态警察到各个区域
        int policeIndex = 0;
        for (int zoneIndex : sortedZoneIndices) {
            int count = policeAllocation.getOrDefault(zoneIndex, 0);
            for (int j = 0; j < count && policeIndex < staticPoliceCount; j++) {
                StandardEntity police = policeForces.get(policeIndex++);
                zones.get(zoneIndex).addPolice(police.getID());
                policeZoneMap.put(police.getID(), zones.get(zoneIndex));
                logger.debug("将警察 " + police.getID() + " 分配到区域 " + zoneIndex);
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
        
        // 清除缓存
        clearCaches();
        
        // 更新分区状态
        updateZoneStatus();
        
        // 更新清理进度
        updateClearanceProgress();
        
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
        
        // 添加额外日志，显示所有区域的优先级排名
        List<PoliceZone> sortedZones = new ArrayList<>(zones);
        sortedZones.sort(Comparator.comparingDouble(PoliceZone::getPriority).reversed());
        
        StringBuilder priorityLog = new StringBuilder("区域优先级排名: ");
        for (PoliceZone zone : sortedZones) {
            priorityLog.append("[区域").append(zone.getZoneId()).append("=").append(String.format("%.2f", zone.getPriority())).append("] ");
        }
        logger.info(priorityLog.toString());
    }
    
    private double calculateZonePriority(PoliceZone zone) {
        // 修改区域优先级计算方法，增加差异化
        double blockadeFactor = 0.0;
        double trappedHumansFactor = 0.0;
        double roadFactor = 0.0;
        double criticalRoadFactor = 0.0;
        
        // 计算路障因子 - 对路障数量更敏感
        int blockadeCount = zone.getBlockades().size();
        blockadeFactor = blockadeCount * 0.2;  // 每个路障提供0.2的基础优先级
        
        // 计算被困智能体因子 - 高权重
        int trappedCount = zone.getTrappedHumans() != null ? zone.getTrappedHumans().size() : 0;
        trappedHumansFactor = trappedCount * 0.6;  // 每个被困智能体提供0.6的高优先级
        
        // 计算道路因子 - 道路越多，潜在清障价值越高
        int roadCount = zone.getRoads().size();
        roadFactor = Math.log10(roadCount + 1) * 0.15;  // 使用对数避免道路数量过大导致权重过高
        
        // 计算关键道路因子 - 显著提高区域优先级
        int criticalRoadCount = countCriticalRoadsInZone(zone);
        criticalRoadFactor = criticalRoadCount * CRITICAL_ROAD_BONUS;
        
        // 检查区域是否包含关键设施附近的路障
        double facilityBonus = calculateFacilityBonus(zone);

        // 警察利用率因子 - 警察越少，优先级越高
        double policeCount = zone.getPoliceCount();
        double policeFactor = policeCount == 0 ? 0.5 : Math.max(0, 0.3 - (0.07 * policeCount));  // 增加权重
        
        // 组合所有因素
        double finalPriority = blockadeFactor + trappedHumansFactor + roadFactor + 
                              criticalRoadFactor + facilityBonus + policeFactor;
        
        // 确保优先级有意义的范围
        finalPriority = Math.max(0.1, finalPriority);
        
        // 添加详细日志以便调试
        logger.debug("区域 " + zone.getZoneId() + " 优先级计算: " +
                    "路障因子=" + blockadeFactor + 
                    ", 被困智能体因子=" + trappedHumansFactor + 
                    ", 道路因子=" + roadFactor + 
                    ", 关键道路因子=" + criticalRoadFactor + 
                    ", 设施奖励=" + facilityBonus + 
                    ", 警察因子=" + policeFactor + 
                    ", 总优先级=" + finalPriority);
        
        return finalPriority;
    }
    
    // 计算区域内关键道路数量
    private int countCriticalRoadsInZone(PoliceZone zone) {
        int count = 0;
        for (EntityID roadID : zone.getRoads()) {
            if (criticalRoads.contains(roadID)) {
                count++;
            }
        }
        return count;
    }
    
    // 计算区域内关键设施奖励
    private double calculateFacilityBonus(PoliceZone zone) {
        double bonus = 0.0;
        
        // 检查区域内的道路是否靠近重要设施
        for (EntityID roadID : zone.getRoads()) {
            StandardEntity roadEntity = worldInfo.getEntity(roadID);
            if (roadEntity instanceof Road) {
                Road road = (Road) roadEntity;
                for (EntityID neighborID : road.getNeighbours()) {
                    StandardEntity neighbor = worldInfo.getEntity(neighborID);
                    if (neighbor instanceof Refuge) {
                        bonus += REFUGE_VICINITY_BONUS;
                    } else if (neighbor instanceof Hydrant) {
                        bonus += HYDRANT_BONUS;
                    }
                }
            }
        }
        
        return Math.min(1.0, bonus); // 最大奖励为1.0
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
        
        // 计算各区域的负载和优先级
        Map<PoliceZone, Double> zoneLoads = new HashMap<>();
        Map<PoliceZone, Double> zonePriorities = new HashMap<>();
        
        for (PoliceZone zone : zones) {
            double load = zone.calculateWorkload(agentInfo.getTime());
            double priority = zone.getPriority();
            zoneLoads.put(zone, load);
            zonePriorities.put(zone, priority);
            logger.debug("区域 " + zone.getZoneId() + " 当前负载: " + load + ", 优先级: " + priority);
        }
        
        double avgLoad = zoneLoads.values().stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double avgPriority = zonePriorities.values().stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        
        logger.info("平均区域负载: " + avgLoad + ", 平均区域优先级: " + avgPriority);
        
        // 识别高负载/高优先级和低负载/低优先级区域
        List<PoliceZone> overloadedZones = new ArrayList<>();
        List<PoliceZone> underloadedZones = new ArrayList<>();
        
        for (PoliceZone zone : zones) {
            double normalizedLoad = zoneLoads.get(zone) / Math.max(0.01, avgLoad);
            double normalizedPriority = zonePriorities.get(zone) / Math.max(0.01, avgPriority);
            
            // 负载高于平均且优先级高于平均，或优先级非常高
            if ((normalizedLoad > 1.3 && normalizedPriority > 1.0) || normalizedPriority > 2.0) {
                overloadedZones.add(zone);
                logger.debug("区域 " + zone.getZoneId() + " 被标识为高负载/高优先级区域");
            }
            // 负载低于平均且优先级低于平均
            else if (normalizedLoad < 0.7 && normalizedPriority < 1.0) {
                underloadedZones.add(zone);
                logger.debug("区域 " + zone.getZoneId() + " 被标识为低负载/低优先级区域");
            }
        }
        
        // 尝试重新分配静态警察
        if (!overloadedZones.isEmpty() && !underloadedZones.isEmpty()) {
            logger.info("尝试从低负载区域向高负载区域重新分配警察");
            
            // 对区域按优先级排序
            overloadedZones.sort(Comparator.comparingDouble(zonePriorities::get).reversed());
            underloadedZones.sort(Comparator.comparingDouble(zonePriorities::get));
            
            for (PoliceZone overloadedZone : overloadedZones) {
                for (PoliceZone underloadedZone : underloadedZones) {
                    // 确保欠负载区域有足够的警察可以转移
                    if (underloadedZone.getPoliceCount() <= 1) {
                        continue; // 至少保留一个警察
                    }
                    
                    // 计算需要转移的警察数量（基于差异程度）
                    double loadDiff = zoneLoads.get(overloadedZone) / Math.max(0.01, zoneLoads.get(underloadedZone));
                    double priorityDiff = zonePriorities.get(overloadedZone) / Math.max(0.01, zonePriorities.get(underloadedZone));
                    
                    int transferCount = 0;
                    if (loadDiff > 2.0 || priorityDiff > 2.0) {
                        // 大差异，转移多个警察
                        transferCount = Math.min(2, underloadedZone.getPoliceCount() - 1);
                    } else if (loadDiff > 1.5 || priorityDiff > 1.5) {
                        // 中等差异，转移一个警察
                        transferCount = 1;
                    }
                    
                    if (transferCount > 0) {
                        // 执行警察转移
                        List<EntityID> policeToTransfer = new ArrayList<>();
                        List<EntityID> underloadedPolice = underloadedZone.getPoliceAgents();
                        
                        // 选择靠近目标区域的警察
                        for (int i = 0; i < Math.min(transferCount, underloadedPolice.size()) && policeToTransfer.size() < transferCount; i++) {
                            if (!dynamicPoliceAgents.contains(underloadedPolice.get(i))) {
                                policeToTransfer.add(underloadedPolice.get(i));
                            }
                        }
                        
                        // 执行转移
                        for (EntityID policeID : policeToTransfer) {
                            underloadedZone.removePolice(policeID);
                            overloadedZone.addPolice(policeID);
                            policeZoneMap.put(policeID, overloadedZone);
                            
                            logger.info("将警察 " + policeID + " 从区域 " + underloadedZone.getZoneId() + 
                                      " 转移到区域 " + overloadedZone.getZoneId());
                        }
                        
                        // 更新区域负载计算
                        zoneLoads.put(underloadedZone, underloadedZone.calculateWorkload(agentInfo.getTime()));
                        zoneLoads.put(overloadedZone, overloadedZone.calculateWorkload(agentInfo.getTime()));
                        
                        // 如果已经转移了足够的警察，退出内循环
                        if (policeToTransfer.size() == transferCount) {
                            break;
                        }
                    }
                }
            }
        }
        
        // 更新动态警察分配
        adjustDynamicPoliceAllocation(zonePriorities);
    }
    
    // 调整动态警察分配
    private void adjustDynamicPoliceAllocation(Map<PoliceZone, Double> zonePriorities) {
        if (dynamicPoliceAgents.isEmpty()) {
            return;
        }
        
        logger.info("调整动态警察分配，动态警察数量: " + dynamicPoliceAgents.size());
        
        // 清空当前动态警察的任务分配
        for (EntityID policeID : dynamicPoliceAgents) {
            allocationResult.remove(policeID);
        }
        
        // 按优先级排序区域
        List<Map.Entry<PoliceZone, Double>> sortedZones = new ArrayList<>(zonePriorities.entrySet());
        sortedZones.sort(Map.Entry.<PoliceZone, Double>comparingByValue().reversed());
        
        // 计算总优先级
        double totalPriority = zonePriorities.values().stream()
                .mapToDouble(Double::doubleValue)
                .sum();
        
        // 为每个分区分配动态警察比例
        Map<PoliceZone, Integer> zoneAllocations = new HashMap<>();
        int remainingPolice = dynamicPoliceAgents.size();
        
        logger.debug("按优先级排序的区域: " + 
                   sortedZones.stream()
                   .map(e -> "区域" + e.getKey().getZoneId() + "=" + String.format("%.2f", e.getValue()))
                   .reduce((a, b) -> a + ", " + b)
                   .orElse("无"));
        
        // 第一轮分配 - 根据优先级分配警察数量
            for (Map.Entry<PoliceZone, Double> entry : sortedZones) {
                PoliceZone zone = entry.getKey();
            double zonePriority = entry.getValue();
            
            // 计算应分配的警察数量
            double proportion = zonePriority / Math.max(0.001, totalPriority);
            int allocation = (int)Math.ceil(proportion * dynamicPoliceAgents.size());
            
            // 确保不会分配过多
            allocation = Math.min(allocation, remainingPolice);
            allocation = Math.max(1, allocation); // 至少分配1个
            
            zoneAllocations.put(zone, allocation);
            remainingPolice -= allocation;
            
            // 如果已经没有剩余警察，跳出循环
            if (remainingPolice <= 0) {
                        break;
            }
        }
        
        // 第二轮 - 实际分配任务
        for (Map.Entry<PoliceZone, Integer> entry : zoneAllocations.entrySet()) {
            PoliceZone zone = entry.getKey();
            int targetCount = entry.getValue();
            
            logger.debug("计划为区域 " + zone.getZoneId() + " 分配 " + targetCount + " 名动态警察");
            
            // 获取区域内的所有路障
            List<EntityID> zoneBlockades = new ArrayList<>(zone.getBlockades());
            
            // 首先分配关键道路上的路障
            List<EntityID> criticalBlockades = getCriticalBlockadesInZone(zone);
            
            // 实际分配
            int allocatedCount = 0;
            
            // 1. 先处理关键路障
            for (EntityID blockadeID : criticalBlockades) {
                if (allocatedCount >= targetCount) break;
                
                // 找到最近的可用警察
                EntityID nearestPolice = findNearestAvailableDynamicPolice(blockadeID);
                if (nearestPolice != null) {
                    allocationResult.put(nearestPolice, blockadeID);
                    allocatedCount++;
                    logger.debug("为关键路障 " + blockadeID + " 分配动态警察 " + nearestPolice);
                }
            }
            
            // 2. 然后处理其他路障
            for (EntityID blockadeID : zoneBlockades) {
                if (criticalBlockades.contains(blockadeID)) continue; // 跳过已分配的关键路障
                if (allocatedCount >= targetCount) break;
                
                // 找到最近的可用警察
                EntityID nearestPolice = findNearestAvailableDynamicPolice(blockadeID);
                if (nearestPolice != null) {
                    allocationResult.put(nearestPolice, blockadeID);
                    allocatedCount++;
                    logger.debug("为普通路障 " + blockadeID + " 分配动态警察 " + nearestPolice);
                }
            }
            
            logger.debug("区域 " + zone.getZoneId() + " 实际分配了 " + allocatedCount + " 名动态警察");
        }
        
        // 确保所有动态警察都有任务 - 未分配的分配给任何可用路障
        for (EntityID policeID : dynamicPoliceAgents) {
            if (!allocationResult.containsKey(policeID)) {
                // 搜索所有区域中未分配的路障
                EntityID target = findNearestUnassignedBlockade(policeID);
                if (target != null) {
                    allocationResult.put(policeID, target);
                    logger.debug("为未分配的动态警察 " + policeID + " 分配路障 " + target);
                }
            }
        }
    }
    
    // 获取区域内关键道路上的路障
    private List<EntityID> getCriticalBlockadesInZone(PoliceZone zone) {
        List<EntityID> criticalBlockades = new ArrayList<>();
        
        for (EntityID blockadeID : zone.getBlockades()) {
            Blockade blockade = (Blockade) worldInfo.getEntity(blockadeID);
            if (blockade != null && blockade.isPositionDefined()) {
                EntityID roadID = blockade.getPosition();
                if (criticalRoads.contains(roadID)) {
                    criticalBlockades.add(blockadeID);
                }
            }
        }
        
        return criticalBlockades;
    }
    
    // 找到最近的可用动态警察
    private EntityID findNearestAvailableDynamicPolice(EntityID targetID) {
        double minDistance = Double.MAX_VALUE;
        EntityID nearestPolice = null;
        
        StandardEntity target = worldInfo.getEntity(targetID);
        
        for (EntityID policeID : dynamicPoliceAgents) {
            // 跳过已分配任务的警察
            if (allocationResult.containsKey(policeID)) {
                continue;
            }
            
            StandardEntity police = worldInfo.getEntity(policeID);
            double distance = calculateDistance(police, target);
            
            if (distance < minDistance) {
                minDistance = distance;
                nearestPolice = policeID;
            }
        }
        
        return nearestPolice;
    }
    
    // 找到最近的未分配路障
    private EntityID findNearestUnassignedBlockade(EntityID policeID) {
        double minDistance = Double.MAX_VALUE;
        EntityID nearestBlockade = null;
        
        StandardEntity police = worldInfo.getEntity(policeID);
        
        // 检查所有区域的路障
        for (PoliceZone zone : zones) {
            for (EntityID blockadeID : zone.getBlockades()) {
                // 跳过已分配的路障
                if (allocationResult.containsValue(blockadeID)) {
                    continue;
                }
                
                StandardEntity blockade = worldInfo.getEntity(blockadeID);
                double distance = calculateDistance(police, blockade);
                
                if (distance < minDistance) {
                    minDistance = distance;
                    nearestBlockade = blockadeID;
                }
            }
        }
        
        return nearestBlockade;
    }
    
    // 覆盖原有的方法，优化静态警察分配
    private void allocateStaticPolice() {
        // 按区域优先级排序
        zones.sort(Comparator.comparingDouble(PoliceZone::getPriority).reversed());
        
        for (PoliceZone zone : zones) {
            List<EntityID> zoneBlockades = zone.getBlockades();
            List<EntityID> zonePolice = zone.getPoliceAgents();
            
            if (!zoneBlockades.isEmpty() && !zonePolice.isEmpty()) {
                // 获取区域内的关键路障
                List<EntityID> criticalBlockades = getCriticalBlockadesInZone(zone);
                
                // 首先处理关键路障
                for (EntityID blockadeID : criticalBlockades) {
                    // 找到最近的未分配警察
                    EntityID nearestPolice = findNearestAvailableStatic(blockadeID, zonePolice);
                    if (nearestPolice != null) {
                        allocationResult.put(nearestPolice, blockadeID);
                        logger.debug("为关键路障 " + blockadeID + " 分配静态警察 " + nearestPolice);
                    }
                }
                
                // 然后处理其他路障
                for (EntityID blockadeID : zoneBlockades) {
                    if (allocationResult.containsValue(blockadeID)) continue; // 跳过已分配的路障
                    
                    // 找到最近的未分配警察
                    EntityID nearestPolice = findNearestAvailableStatic(blockadeID, zonePolice);
                    if (nearestPolice != null) {
                        allocationResult.put(nearestPolice, blockadeID);
                        logger.debug("为普通路障 " + blockadeID + " 分配静态警察 " + nearestPolice);
                        }
                    }
                }
            }
        }
        
    // 找到最近的可用静态警察
    private EntityID findNearestAvailableStatic(EntityID targetID, List<EntityID> policeList) {
        double minDistance = Double.MAX_VALUE;
        EntityID nearestPolice = null;
        
        StandardEntity target = worldInfo.getEntity(targetID);
        
        for (EntityID policeID : policeList) {
            // 跳过动态警察和已分配任务的警察
            if (dynamicPoliceAgents.contains(policeID) || allocationResult.containsKey(policeID)) {
                continue;
            }
            
        StandardEntity police = worldInfo.getEntity(policeID);
            double distance = calculateDistance(police, target);
            
                if (distance < minDistance) {
                    minDistance = distance;
                nearestPolice = policeID;
            }
        }
        
        return nearestPolice;
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
            return Double.MAX_VALUE;
        }

        // 使用缓存键以避免重复计算
        String cacheKey = entity1.getID() + "-" + entity2.getID();
        if (distanceCache.containsKey(cacheKey)) {
            return distanceCache.get(cacheKey);
        }

        double distance = Double.MAX_VALUE;

        try {
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
                    distance = pathPlanner.getDistance();
            }
        }
            else if (entity1 instanceof Human || entity2 instanceof Human) {
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
                        distance = pathPlanner.getDistance();
                }
            }
        }

            // 如果以上方法失败，使用欧几里得距离
            if (distance == Double.MAX_VALUE) {
        int x1 = getEntityX(entity1);
        int y1 = getEntityY(entity1);
        int x2 = getEntityX(entity2);
        int y2 = getEntityY(entity2);
        
        if (x1 != Integer.MIN_VALUE && y1 != Integer.MIN_VALUE && 
            x2 != Integer.MIN_VALUE && y2 != Integer.MIN_VALUE) {
            double dx = x2 - x1;
            double dy = y2 - y1;
                    distance = Math.sqrt(dx * dx + dy * dy);
        }
                else if (entity1.getID() != null && entity2.getID() != null) {
        // 如果以上方法都失败，使用世界信息中的距离
                    int dist = worldInfo.getDistance(entity1.getID(), entity2.getID());
                    if (dist != -1) {
                        distance = dist;
                    }
                }
            }
        } catch (Exception e) {
            // 出现异常情况下维持最大距离
        }

        // 缓存计算结果
        distanceCache.put(cacheKey, distance);
        return distance;
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
    
    // 为重复计算的操作添加缓存
    private void clearCaches() {
        // 每次迭代开始前清除缓存
        distanceCache.clear();
    }
    
    // 优化日志输出频率，减少不必要的字符串拼接
    private void logDebug(String message) {
        if (agentInfo.getTime() % 5 == 0) { // 每5个周期记录一次日志
            logger.debug(message);
        }
    }
    
    // 优化性能监控类，减少不必要的计算
    private class PerformanceMonitor {
        private Map<Integer, Double> responseTimeHistory;
        private Map<Integer, Double> utilizationHistory;
        private Map<Integer, Double> coverageHistory;
        private int lastUpdateTime = 0;
        
        public PerformanceMonitor() {
            responseTimeHistory = new HashMap<>();
            utilizationHistory = new HashMap<>();
            coverageHistory = new HashMap<>();
        }
        
        public void updateMetrics(List<PoliceZone> zones) {
            int currentTime = agentInfo.getTime();
            
            // 减少更新频率，每10个周期更新一次
            if (currentTime - lastUpdateTime < 10) {
                return;
            }
            
            lastUpdateTime = currentTime;
            logDebug("更新性能指标，当前时间: " + currentTime);
            
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
            
            // 只在关键时刻记录性能信息
            if (currentTime % 50 == 0) {
            logger.logPerformance("平均响应时间", avgResponseTime);
            logger.logPerformance("平均利用率", avgUtilization);
            logger.logPerformance("平均覆盖率", avgCoverage);
            }
        }
        
        public void analyzePerformance() {
            // 性能分析仅在某些关键时刻执行
            if (agentInfo.getTime() % 100 == 0) {
            logger.info("开始分析性能数据...");
            // 实现性能分析逻辑
            }
        }
    }
    
    // 更新路障清理进度
    private void updateClearanceProgress() {
        int currentTime = agentInfo.getTime();
        
        // 遍历所有路障检查清理进度
        for (StandardEntity entity : worldInfo.getEntitiesOfType(StandardEntityURN.BLOCKADE)) {
            Blockade blockade = (Blockade) entity;
            EntityID blockadeID = blockade.getID();
            
            // 记录上次检查的修复成本
            double previousCost = clearanceProgress.getOrDefault(blockadeID, (double)blockade.getRepairCost());
            
            // 当前修复成本
            double currentCost = blockade.getRepairCost();
            
            // 如果修复成本减少，说明正在被清理
            if (currentCost < previousCost) {
                lastClearedTime.put(blockadeID, currentTime);
                clearanceProgress.put(blockadeID, currentCost);
                
                // 记录清理速度
                double clearRate = (previousCost - currentCost) / Math.max(1, currentTime - lastClearedTime.getOrDefault(blockadeID, currentTime-1));
                logger.debug("路障 " + blockadeID + " 清理进度: " + String.format("%.2f%%", (1 - currentCost/blockade.getApexes().length) * 100) + 
                            ", 速度: " + String.format("%.2f", clearRate) + " 点/时间步");
            }
            // 如果是新路障，初始化进度
            else if (!clearanceProgress.containsKey(blockadeID)) {
                clearanceProgress.put(blockadeID, currentCost);
            }
        }
    }
    
    // 识别关键道路方法
    private void identifyCriticalRoads() {
        logger.info("开始识别关键道路...");
        Map<EntityID, Integer> roadConnections = new HashMap<>();
        Map<EntityID, Set<StandardEntityURN>> nearbyFacilities = new HashMap<>();
        List<StandardEntity> roads = new ArrayList<>(worldInfo.getEntitiesOfType(StandardEntityURN.ROAD));
        
        // 统计每条道路的连接数和附近设施
        for (StandardEntity entity : roads) {
            if (entity instanceof Road) {
                Road road = (Road) entity;
                EntityID roadID = road.getID();
                
                // 连接数
                int connections = road.getNeighbours().size();
                roadConnections.put(roadID, connections);
                
                // 检查附近设施
                Set<StandardEntityURN> facilities = new HashSet<>();
                for (EntityID neighborID : road.getNeighbours()) {
                    StandardEntity neighbor = worldInfo.getEntity(neighborID);
                    if (neighbor instanceof Building) {
                        facilities.add(neighbor.getStandardURN());
                    }
                }
                nearbyFacilities.put(roadID, facilities);
                
                // 根据连接数和附近设施判断是否为关键道路
                if (connections >= 4 || 
                    facilities.contains(StandardEntityURN.REFUGE) ||
                    facilities.contains(StandardEntityURN.FIRE_STATION) ||
                    facilities.contains(StandardEntityURN.AMBULANCE_CENTRE) ||
                    facilities.contains(StandardEntityURN.POLICE_OFFICE) ||
                    facilities.contains(StandardEntityURN.HYDRANT)) {
                    
                    criticalRoads.add(roadID);
                    logger.debug("识别关键道路: " + roadID + ", 连接数: " + connections + 
                                ", 附近设施: " + facilities);
                }
            }
        }
        
        // 贪婪地添加一些连接关键设施的道路
        for (StandardEntity entity : worldInfo.getEntitiesOfType(StandardEntityURN.REFUGE)) {
            addConnectingRoads((Building)entity);
        }
        
        for (StandardEntity entity : worldInfo.getEntitiesOfType(StandardEntityURN.HYDRANT)) {
            addConnectingRoads((Building)entity);
        }
        
        logger.info("关键道路识别完成，共识别 " + criticalRoads.size() + " 条关键道路");
    }
    
    // 添加连接关键设施的道路
    private void addConnectingRoads(Building building) {
        for (EntityID neighborID : building.getNeighbours()) {
            StandardEntity neighbor = worldInfo.getEntity(neighborID);
            if (neighbor instanceof Road) {
                Road road = (Road) neighbor;
                criticalRoads.add(road.getID());
                
                // 再向外扩展一层
                for (EntityID nextRoadID : road.getNeighbours()) {
                    StandardEntity nextEntity = worldInfo.getEntity(nextRoadID);
                    if (nextEntity instanceof Road) {
                        criticalRoads.add(nextRoadID);
                    }
                }
            }
        }
    }
        }
    }
} 
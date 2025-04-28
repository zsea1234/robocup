package CSU_Yunlu_2023.module.complex.center;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.module.complex.PoliceTargetAllocator;
import adf.core.component.module.algorithm.PathPlanning;
import rescuecore2.worldmodel.EntityID;
import rescuecore2.standard.entities.Blockade;
import rescuecore2.standard.entities.PoliceForce;
import rescuecore2.standard.entities.Road;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;

import java.util.*;

public class CSUPoliceTargetAllocator extends PoliceTargetAllocator {

    private Map<EntityID, EntityID> result;
    
    // 记录已分配的目标，避免重复分配
    private Set<EntityID> assignedTargets;
    
    // 用于路径规划
    private PathPlanning pathPlanning;
    
    // 缓存已知的阻塞物
    private List<EntityID> knownBlockades;

    public CSUPoliceTargetAllocator(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        this.result = new HashMap<>();
        this.assignedTargets = new HashSet<>();
        this.knownBlockades = new ArrayList<>();
        
        // 初始化路径规划组件
        this.pathPlanning = moduleManager.getModule("TestPoliceTargetAllocator.PathPlanning", "adf.core.sample.module.algorithm.SamplePathPlanning");
    }

    @Override
    public PoliceTargetAllocator resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        if (this.getCountResume() >= 2) {
            return this;
        }
        
        // 可以从precomputeData中恢复一些预计算数据
        // 例如：this.knownBlockades = ...
        
        return this;
    }

    @Override
    public PoliceTargetAllocator preparate() {
        super.preparate();
        if (this.getCountPrecompute() >= 2) {
            return this;
        }
        
        // 预计算一些数据
        // 例如：对地图进行分析，识别潜在的关键道路
        
        return this;
    }

    @Override
    public Map<EntityID, EntityID> getResult() {
        // 返回计算结果而不是null
        return this.result;
    }

    @Override
    public PoliceTargetAllocator calc() {
        // 每次计算时重置结果
        this.result = new HashMap<>();
        this.assignedTargets = new HashSet<>();
        
        // 获取当前所有警察
        Collection<StandardEntity> policeForces = this.worldInfo.getEntitiesOfType(StandardEntityURN.POLICE_FORCE);
        if (policeForces.isEmpty()) {
            return this;
        }
        
        // 获取所有道路上的阻塞物
        List<EntityID> blockades = new ArrayList<>();
        for (StandardEntity entity : this.worldInfo.getEntitiesOfType(StandardEntityURN.ROAD)) {
            Road road = (Road) entity;
            if (road.isBlockadesDefined() && !road.getBlockades().isEmpty()) {
                blockades.addAll(road.getBlockades());
            }
        }
        
        // 如果没有阻塞物，为每个警察分配一个侦察目标
        if (blockades.isEmpty()) {
            for (StandardEntity entity : policeForces) {
                PoliceForce police = (PoliceForce) entity;
                if (police.isBuriednessDefined() && police.getBuriedness() > 0) {
                    continue;
                }
                // 获取警察当前位置
                EntityID position = police.getPosition();
                if (position != null) {
                    // 分配当前位置作为侦察目标
                    this.result.put(police.getID(), position);
                }
            }
            return this;
        }
        
        // 计算每个阻塞物的优先级（可以基于位置、大小等）
        Map<EntityID, Double> blockadePriorities = calculateBlockadePriorities(blockades);
        
        // 根据优先级对阻塞物进行排序
        List<EntityID> sortedBlockades = new ArrayList<>(blockades);
        sortedBlockades.sort((o1, o2) -> Double.compare(blockadePriorities.get(o2), blockadePriorities.get(o1)));
        
        // 遍历每个警察，分配最近的高优先级阻塞物
        for (StandardEntity entity : policeForces) {
            PoliceForce police = (PoliceForce) entity;
            
            // 如果警察被掩埋或无法行动，则跳过
            if (police.isBuriednessDefined() && police.getBuriedness() > 0) {
                continue;
            }
            
            // 寻找最佳目标
            EntityID bestTarget = findBestTarget(police, sortedBlockades);
            if (bestTarget != null) {
                this.result.put(police.getID(), bestTarget);
                this.assignedTargets.add(bestTarget);
            } else {
                // 如果没有找到合适的阻塞物目标，分配当前位置作为侦察目标
                EntityID position = police.getPosition();
                if (position != null) {
                    this.result.put(police.getID(), position);
                }
            }
        }
        
        return this;
    }

    @Override
    public PoliceTargetAllocator updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if (this.getCountUpdateInfo() >= 2) {
            return this;
        }
        
        // 处理从其他智能体接收的消息
        // 例如：更新已知的阻塞物列表
        // messageManager.getReceivedMessageList() ...
        
        return this;
    }
    
    /**
     * 计算每个阻塞物的优先级
     * @param blockades 阻塞物ID列表
     * @return 每个阻塞物ID对应的优先级值（值越大优先级越高）
     */
    private Map<EntityID, Double> calculateBlockadePriorities(List<EntityID> blockades) {
        Map<EntityID, Double> priorities = new HashMap<>();
        
        // 获取重要设施（救援中心、消防站、警察局等）
        List<StandardEntity> importantFacilities = new ArrayList<>();
        importantFacilities.addAll(this.worldInfo.getEntitiesOfType(
                StandardEntityURN.REFUGE,
                StandardEntityURN.FIRE_STATION,
                StandardEntityURN.POLICE_OFFICE,
                StandardEntityURN.AMBULANCE_CENTRE));
        
        for (EntityID blockadeID : blockades) {
            Blockade blockade = (Blockade) this.worldInfo.getEntity(blockadeID);
            if (blockade == null) {
                continue;
            }
            
            double priority = 0.0;
            Road road = (Road) this.worldInfo.getEntity(blockade.getPosition());
            if (road == null) {
                continue;
            }
            
            // 1. 道路的邻居数量 - 使用边数量作为连通性指标
            Collection<EntityID> neighbours = road.getNeighbours();
            if (neighbours != null) {
                priority += neighbours.size() * 5.0;
            }
            
            // 2. 考虑阻塞物的修复成本 - 通常较大的阻塞物需要更高的修复成本
            if (blockade.isRepairCostDefined()) {
                priority += blockade.getRepairCost() * 0.01; // 适当缩放修复成本
            }
            
            // 3. 通往重要设施的道路 - 检查是否通往或靠近重要设施
            for (StandardEntity facility : importantFacilities) {
                List<EntityID> path = findPathToTarget(road.getID(), facility.getID(), 5);
                if (path != null && !path.isEmpty()) {
                    // 路径越短，表示越靠近重要设施
                    priority += 100.0 / Math.max(1, path.size());
                }
            }
            
            // 4. 检查该道路是否在救援路线上
            if (isOnRescueRoute(road.getID())) {
                priority += 50.0;
            }
            
            // 5. 考虑道路上的阻塞物总数 - 完全阻塞的道路更重要
            Collection<EntityID> roadBlockades = road.getBlockades();
            if (roadBlockades != null) {
                // 如果一条道路上有多个阻塞物，它可能被完全阻塞，优先级更高
                priority += roadBlockades.size() * 10.0;
                
                // 计算该阻塞物在道路总阻塞中的比例（如果可以获取修复成本）
                if (blockade.isRepairCostDefined()) {
                    int totalRepairCost = 0;
                    for (EntityID otherBlockadeID : roadBlockades) {
                        Blockade otherBlockade = (Blockade) this.worldInfo.getEntity(otherBlockadeID);
                        if (otherBlockade != null && otherBlockade.isRepairCostDefined()) {
                            totalRepairCost += otherBlockade.getRepairCost();
                        }
                    }
                    
                    if (totalRepairCost > 0) {
                        // 该阻塞物占总阻塞的比例越高，优先级越高
                        priority += 20.0 * (blockade.getRepairCost() / (double) totalRepairCost);
                    }
                }
            }
            
            priorities.put(blockadeID, priority);
        }
        
        return priorities;
    }
    
    /**
     * 查找从起点到目标的路径，限制最大搜索深度
     * @param from 起点ID
     * @param to 终点ID
     * @param maxDepth 最大搜索深度
     * @return 如果找到路径则返回路径，否则返回null
     */
    private List<EntityID> findPathToTarget(EntityID from, EntityID to, int maxDepth) {
        this.pathPlanning.setFrom(from);
        this.pathPlanning.setDestination(to);
        // 可以设置路径规划的其他参数，如最大深度
        List<EntityID> path = this.pathPlanning.calc().getResult();
        
        // 如果路径太长，超出指定深度，则认为不是直接相关
        if (path != null && !path.isEmpty() && path.size() <= maxDepth) {
            return path;
        }
        return null;
    }
    
    /**
     * 检查道路是否在救援路线上
     */
    private boolean isOnRescueRoute(EntityID roadID) {
        Road road = (Road) this.worldInfo.getEntity(roadID);
        if (road == null) {
            return false;
        }
        
        // 获取所有救护车和消防车
        Collection<StandardEntity> rescueAgents = new ArrayList<>();
        rescueAgents.addAll(this.worldInfo.getEntitiesOfType(StandardEntityURN.AMBULANCE_TEAM));
        rescueAgents.addAll(this.worldInfo.getEntitiesOfType(StandardEntityURN.FIRE_BRIGADE));
        
        // 检查是否有救护车或消防车在该道路上
        for (StandardEntity agent : rescueAgents) {
            StandardEntity position = this.worldInfo.getPosition(agent.getID());
            if (position != null && position.getID().equals(roadID)) {
                return true;
            }
        }
        
        // 检查该道路是否连接到有救护车或消防车的道路
        Collection<EntityID> neighbours = road.getNeighbours();
        if (neighbours != null) {
            for (EntityID neighbourID : neighbours) {
                for (StandardEntity agent : rescueAgents) {
                    StandardEntity position = this.worldInfo.getPosition(agent.getID());
                    if (position != null && position.getID().equals(neighbourID)) {
                        return true;
                    }
                }
            }
        }
        
        return false;
    }
    
    /**
     * 为警察寻找最佳目标
     * @param police 警察单位
     * @param sortedBlockades 按优先级排序的阻塞物列表
     * @return 最佳目标ID，如果没有合适目标则返回null
     */
    private EntityID findBestTarget(PoliceForce police, List<EntityID> sortedBlockades) {
        // 如果没有阻塞物，则返回null
        if (sortedBlockades.isEmpty()) {
            return null;
        }
        
        EntityID policePosition = police.getPosition();
        
        // 尝试分配尚未分配且可达的最高优先级阻塞物
        for (EntityID blockadeID : sortedBlockades) {
            // 如果已分配给其他警察，则跳过
            if (this.assignedTargets.contains(blockadeID)) {
                continue;
            }
            
            // 获取阻塞物所在的位置
            Blockade blockade = (Blockade) this.worldInfo.getEntity(blockadeID);
            if (blockade == null) {
                continue;
            }
            
            // 检查是否可以到达该阻塞物
            EntityID blockadePosition = blockade.getPosition();
            this.pathPlanning.setFrom(policePosition);
            this.pathPlanning.setDestination(blockadePosition);
            List<EntityID> path = this.pathPlanning.calc().getResult();
            
            // 如果找到了路径，则选择该阻塞物作为目标
            if (path != null && !path.isEmpty()) {
                return blockadeID;
            }
        }
        
        // 如果没有找到合适的目标，可以考虑分配已经分配给其他人但优先级很高的目标
    
        
        return null;
    }
}


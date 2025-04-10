package CSU_Yunlu_2023.centralized.police;

import CSU_Yunlu_2023.module.algorithm.AStarPathPlanning;
import CSU_Yunlu_2023.module.algorithm.HungarianAgentAssign;
import adf.core.agent.communication.standard.bundle.centralized.CommandPolice;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.centralized.CommandPicker;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.module.algorithm.PathPlanning;
import rescuecore2.misc.Pair;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import java.util.*;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

public class CommandPickerPolice extends CommandPicker {

    private Collection<CommunicationMessage> messages;
    private Map<EntityID, EntityID> allocationData;
    
    // 路径规划模块
    private PathPlanning pathPlanning;
    
    // 重要性和距离缓存
    private Map<EntityID, Double> importanceCache;
    private Map<Pair<EntityID, EntityID>, Double> distanceCache;
    
    // 最后分配时间
    private int lastAllocationTime;
    
    // 重分配间隔（可调整）
    private static final int REALLOCATION_INTERVAL = 5; // 调大到5个时间步
    
    // 配置变量
    private boolean useDetailedCommandType = false; // 默认使用固定命令类型
    private boolean useExactPathDistance = false;   // 默认使用近似距离

    // 调试日志相关变量
    private static final boolean DEBUG_ENABLED = true;
    private static final String DEBUG_FILE = "police_command_picker_debug.log";
    private PrintWriter debugLogWriter;
    private long startTime;
    private int allocationCounter = 0;

    public CommandPickerPolice(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        this.messages = new ArrayList<>();
        this.allocationData = null;
        this.importanceCache = new HashMap<>();
        this.distanceCache = new HashMap<>();
        this.lastAllocationTime = 0;
        
        // 初始化路径规划模块
        this.pathPlanning = new AStarPathPlanning(ai, wi, si, moduleManager, developData);
        
        // 根据场景配置调整策略
        int totalAgents = countTotalAgents(si);
        if (totalAgents < 15) { // 小规模场景可以使用更复杂的计算
            useDetailedCommandType = true;
            useExactPathDistance = true;
        }

        // 初始化调试日志
        setupDebugLog();
    }
    
    /**
     * 设置调试日志
     */
    private void setupDebugLog() {
        if (!DEBUG_ENABLED) return;
        
        try {
            // 使用追加模式，避免覆盖之前的日志
            this.debugLogWriter = new PrintWriter(new FileWriter(DEBUG_FILE, true));
            
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            this.debugLogWriter.println("\n==================================================");
            this.debugLogWriter.println("CommandPickerPolice Debug Log - Started at " + dateFormat.format(new Date()));
            this.debugLogWriter.println("==================================================");
            this.debugLogWriter.println("Configuration:");
            this.debugLogWriter.println("- 重分配间隔: " + REALLOCATION_INTERVAL + " 时间步");
            this.debugLogWriter.println("- 使用详细命令类型: " + useDetailedCommandType);
            this.debugLogWriter.println("- 使用精确路径距离: " + useExactPathDistance);
            this.debugLogWriter.println("- 总智能体数量: " + countTotalAgents(this.scenarioInfo));
            this.debugLogWriter.println("==================================================\n");
            this.debugLogWriter.flush();
        } catch (IOException e) {
            System.err.println("无法创建调试日志文件: " + e.getMessage());
        }
        this.startTime = System.currentTimeMillis();
    }
    
    /**
     * 写入调试日志
     */
    private void debugLog(String message) {
        if (!DEBUG_ENABLED || this.debugLogWriter == null) return;
        
        this.debugLogWriter.println("[" + this.agentInfo.getTime() + "] " + message);
        this.debugLogWriter.flush();
    }
    
    /**
     * 记录性能数据
     */
    private void logPerformance(String operation, long startTimeMs) {
        if (!DEBUG_ENABLED) return;
        
        long timeTaken = System.currentTimeMillis() - startTimeMs;
        debugLog("性能 - " + operation + ": " + timeTaken + "ms");
    }

    @Override
    public CommandPicker setAllocatorResult(Map<EntityID, EntityID> allocationData) {
        this.allocationData = allocationData;
        
        if (DEBUG_ENABLED && allocationData != null) {
            debugLog("收到外部分配结果，共 " + allocationData.size() + " 条任务分配");
        }
        
        return this;
    }
    
    @Override
    public CommandPicker calc() {
        long calcStartTime = System.currentTimeMillis();
        this.messages.clear();
        
        if (DEBUG_ENABLED) {
            debugLog("===== 开始计算分配 [周期 " + this.agentInfo.getTime() + "] =====");
        }
        
        // 如果没有分配数据或需要重新分配
        boolean needReallocation = shouldReoptimizeAllocation();
        if(this.allocationData == null || needReallocation) {
            if (DEBUG_ENABLED) {
                if (this.allocationData == null) {
                    debugLog("没有现有分配，需要计算新分配");
                } else if (needReallocation) {
                    debugLog("需要重新分配 (上次分配时间: " + this.lastAllocationTime + 
                             ", 当前时间: " + this.agentInfo.getTime() + ")");
                }
            }
            
            long allocStartTime = System.currentTimeMillis();
            this.allocationData = calculateOptimalAllocation();
            this.lastAllocationTime = this.agentInfo.getTime();
            allocationCounter++;
            
            if (DEBUG_ENABLED) {
                logPerformance("分配计算", allocStartTime);
                if (this.allocationData != null) {
                    debugLog("生成了新分配，共 " + this.allocationData.size() + " 条任务分配 (第 " + allocationCounter + " 次分配)");
                } else {
                    debugLog("没有生成新分配，可能没有可用警察或任务");
                }
            }
        } else if (DEBUG_ENABLED) {
            debugLog("使用现有分配 (上次分配时间: " + this.lastAllocationTime + ")");
        }
        
        if(this.allocationData == null) {
            if (DEBUG_ENABLED) {
                debugLog("没有分配数据，跳过命令生成");
                logPerformance("总计算时间", calcStartTime);
            }
            return this;
        }
        
        // 生成命令 - 保持简单高效
        long cmdStartTime = System.currentTimeMillis();
        for(EntityID agentID : this.allocationData.keySet()) {
            StandardEntity agent = this.worldInfo.getEntity(agentID);
            if(agent != null && agent.getStandardURN() == StandardEntityURN.POLICE_FORCE) {
                StandardEntity target = this.worldInfo.getEntity(this.allocationData.get(agentID));
                if(target != null && target instanceof Area) {
                    // 默认使用AUTONOMY命令，除非配置为使用详细命令类型
                    int commandType = CommandPolice.ACTION_AUTONOMY;
                    if (useDetailedCommandType) {
                        commandType = determineCommandType(agent, target);
                    }
                    
                    CommandPolice command = new CommandPolice(
                            true,
                            agentID,
                            target.getID(),
                            commandType
                    );
                    this.messages.add(command);
                    
                    if (DEBUG_ENABLED) {
                        String commandTypeStr;
                        switch(commandType) {
                            case CommandPolice.ACTION_CLEAR: commandTypeStr = "CLEAR"; break;
                            case CommandPolice.ACTION_MOVE: commandTypeStr = "MOVE"; break;
                            case CommandPolice.ACTION_REST: commandTypeStr = "REST"; break;
                            case CommandPolice.ACTION_AUTONOMY: commandTypeStr = "AUTONOMY"; break;
                            default: commandTypeStr = "UNKNOWN"; break;
                        }
                        debugLog("生成命令: 警察 " + agentID.getValue() + " -> 目标 " + target.getID().getValue() + 
                                 " (命令类型: " + commandTypeStr + ")");
                    }
                }
            }
        }
        
        if (DEBUG_ENABLED) {
            logPerformance("命令生成", cmdStartTime);
            debugLog("共生成 " + this.messages.size() + " 条命令");
            logPerformance("总计算时间", calcStartTime);
            debugLog("===== 计算完成 =====\n");
        }
        
        return this;
    }
    
    /**
     * 计算最优任务分配 - 保持高效
     */
    private Map<EntityID, EntityID> calculateOptimalAllocation() {
        long methodStartTime = System.currentTimeMillis();
        
        // 获取警察和任务
        long agentsStartTime = System.currentTimeMillis();
        List<EntityID> agents = getAvailablePoliceAgents();
        List<EntityID> targets = getPriorityTargets();
        
        if (DEBUG_ENABLED) {
            logPerformance("获取警察和任务", agentsStartTime);
            debugLog("获取到 " + agents.size() + " 个可用警察和 " + targets.size() + " 个任务目标");
        }
        
        if(agents.isEmpty() || targets.isEmpty()) {
            if (DEBUG_ENABLED) {
                debugLog("警察或目标为空，无法分配");
            }
            return null;
        }
        
        int agentCount = agents.size();
        int targetCount = targets.size();
        
        // 创建成本矩阵
        long matrixStartTime = System.currentTimeMillis();
        double[][] costMatrix = new double[agentCount][targetCount];
        
        // 填充成本矩阵 - 使用简化计算
        for(int i = 0; i < agentCount; i++) {
            EntityID agentID = agents.get(i);
            for(int j = 0; j < targetCount; j++) {
                EntityID targetID = targets.get(j);
                costMatrix[i][j] = calculateSimpleCost(agentID, targetID);
            }
        }
        
        if (DEBUG_ENABLED) {
            logPerformance("构建成本矩阵", matrixStartTime);
            // 记录成本矩阵样本（前3个警察对前3个目标）
            StringBuilder matrixSample = new StringBuilder("成本矩阵样本:\n");
            for(int i = 0; i < Math.min(3, agentCount); i++) {
                for(int j = 0; j < Math.min(3, targetCount); j++) {
                    matrixSample.append(String.format("警察%d->目标%d: %.2f | ", 
                            agents.get(i).getValue(), targets.get(j).getValue(), costMatrix[i][j]));
                }
                matrixSample.append("\n");
            }
            debugLog(matrixSample.toString());
        }
        
        // 使用匈牙利算法求解
        long hungarianStartTime = System.currentTimeMillis();
        HungarianAgentAssign hungarian = new HungarianAgentAssign(costMatrix);
        int[] assignments = hungarian.execute();
        
        if (DEBUG_ENABLED) {
            logPerformance("匈牙利算法求解", hungarianStartTime);
        }
        
        // 转换结果
        long resultStartTime = System.currentTimeMillis();
        Map<EntityID, EntityID> result = new HashMap<>();
        for(int i = 0; i < agentCount; i++) {
            if(assignments[i] >= 0 && assignments[i] < targetCount) {
                result.put(agents.get(i), targets.get(assignments[i]));
            }
        }
        
        if (DEBUG_ENABLED) {
            logPerformance("转换结果", resultStartTime);
            logPerformance("总分配计算时间", methodStartTime);
            debugLog("最终分配了 " + result.size() + " 个任务");
            
            // 记录部分分配结果
            int logCount = Math.min(5, result.size());
            if (logCount > 0) {
                StringBuilder allocSample = new StringBuilder("分配样本 (前" + logCount + "项):\n");
                int count = 0;
                for(Map.Entry<EntityID, EntityID> entry : result.entrySet()) {
                    allocSample.append(String.format("警察%d -> 目标%d\n", 
                            entry.getKey().getValue(), entry.getValue().getValue()));
                    count++;
                    if (count >= logCount) break;
                }
                debugLog(allocSample.toString());
            }
        }
        
        return result;
    }
    
    /**
     * 计算简化的成本 - 不使用复杂的A*路径距离
     */
    private double calculateSimpleCost(EntityID agentID, EntityID targetID) {
        long methodStartTime = System.currentTimeMillis();
        
        // 使用缓存的距离或简单计算
        Pair<EntityID, EntityID> key = new Pair<>(agentID, targetID);
        if (distanceCache.containsKey(key)) {
            double cachedDistance = distanceCache.get(key);
            if (DEBUG_ENABLED) {
                debugLog("使用缓存距离: 警察" + agentID.getValue() + " -> 目标" + 
                         targetID.getValue() + " = " + cachedDistance);
            }
            return cachedDistance;
        }
        
        StandardEntity agent = this.worldInfo.getEntity(agentID);
        StandardEntity target = this.worldInfo.getEntity(targetID);
        
        // 计算距离 - 简化版
        double distance;

            distance = calculatePathDistance(agent, target);
            if (DEBUG_ENABLED) {
                debugLog("计算精确路径距离: 警察" + agentID.getValue() + " -> 目标" + 
                         targetID.getValue() + " = " + distance);
            }


        
        // 缓存结果
        distanceCache.put(key, distance);
        
        if (DEBUG_ENABLED) {
            logPerformance("计算距离", methodStartTime);
        }
        
        return distance;
    }
    
    /**
     * 计算路径距离 - 优化版
     */
    private double calculatePathDistance(StandardEntity agent, StandardEntity target) {
        if(agent instanceof Human && target instanceof Area) {
            Human human = (Human)agent;
            if(human.isPositionDefined()) {
                EntityID position = human.getPosition();
                
                this.pathPlanning.setFrom(position);
                List<EntityID> destinations = new ArrayList<>();
                destinations.add(target.getID());
                this.pathPlanning.setDestination(destinations);
                this.pathPlanning.calc();
                
                double distance = this.pathPlanning.getDistance();
                if(distance > 0) {
                    return distance;
                }
            }
        }
        
        return this.worldInfo.getDistance(agent, target);
    }
    
    /**
     * 获取可用警察
     */
    private List<EntityID> getAvailablePoliceAgents() {
        List<EntityID> result = new ArrayList<>();
        for(StandardEntity entity : this.worldInfo.getEntitiesOfType(StandardEntityURN.POLICE_FORCE)) {
            PoliceForce police = (PoliceForce)entity;
            if(police.isHPDefined() && police.getHP() > 0) {
                result.add(police.getID());
            }
        }
        return result;
    }
    
    /**
     * 获取优先目标 - 简化版
     */
    private List<EntityID> getPriorityTargets() {
        List<EntityID> result = new ArrayList<>();
        
        // 添加所有有阻塞的道路
        for(StandardEntity entity : this.worldInfo.getEntitiesOfType(StandardEntityURN.ROAD)) {
            Road road = (Road)entity;
            if(road.isBlockadesDefined() && !road.getBlockades().isEmpty()) {
                result.add(road.getID());
            }
        }
        
        return result;
    }
    
    /**
     * 确定命令类型 - 只在配置开启时使用
     */
    private int determineCommandType(StandardEntity agent, StandardEntity target) {
        if(agent instanceof PoliceForce && ((PoliceForce)agent).isDamageDefined() && ((PoliceForce)agent).getDamage() > 50) {
            return CommandPolice.ACTION_REST;
        } else if(target instanceof Road && ((Road)target).isBlockadesDefined() && !((Road)target).getBlockades().isEmpty()) {
            return CommandPolice.ACTION_CLEAR;
        } else {
            return CommandPolice.ACTION_MOVE;
        }
    }
    
    /**
     * 是否需要重新分配 - 减少重新分配频率
     */
    private boolean shouldReoptimizeAllocation() {
        return this.agentInfo.getTime() - this.lastAllocationTime >= REALLOCATION_INTERVAL;
    }

    @Override
    public Collection<CommunicationMessage> getResult() {
        if (DEBUG_ENABLED) {
            debugLog("返回 " + this.messages.size() + " 条命令消息");
        }
        return this.messages;
    }
    
    /**
     * 清理资源
     */
    public void cleanup() {
        if (DEBUG_ENABLED && this.debugLogWriter != null) {
            debugLog("CommandPickerPolice运行总时间: " + 
                     (System.currentTimeMillis() - this.startTime) + "ms");
            debugLog("总共执行 " + allocationCounter + " 次任务分配");
            this.debugLogWriter.close();
        }
    }

    /**
     * 计算场景中的总智能体数量
     */
    private int countTotalAgents(ScenarioInfo si) {
        return si.getScenarioAgentsFb() + // 消防队员
               si.getScenarioAgentsPf() + // 警察
               si.getScenarioAgentsAt() + // 救护队员
               si.getScenarioAgentsFs() + // 消防站
               si.getScenarioAgentsPo() + // 警察局
               si.getScenarioAgentsAc();  // 救护中心
    }
}


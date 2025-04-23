package CSU_Yunlu_2023.module.complex.center;

import CSU_Yunlu_2023.module.algorithm.AStarPathPlanning;
import CSU_Yunlu_2023.module.algorithm.SampleKMeans;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.module.complex.PoliceTargetAllocator;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;
import rescuecore2.misc.Pair;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class CSUPoliceTargetAllocator extends PoliceTargetAllocator {

    // Configuration parameters
    private static final int NUM_ACTIONS = 5;
    private static final int X_BIN_SIZE = 250;    // Reduced for finer resolution
    private static final int Y_BIN_SIZE = 250;
    private static final int DISTANCE_BIN = 400;  // Finer granularity
    private static final int MAX_DISTANCE = 100000;
    private static final double INITIAL_EPSILON = 0.95;
    private static final double MIN_EPSILON = 0.05;
    private static final double EPSILON_DECAY = 0.996;  // Faster decay for quicker convergence
    private static final double LEARNING_RATE = 0.6;    // Increased to adapt faster
    private static final double DISCOUNT_FACTOR = 0.9;  // Emphasize long-term rewards
    private static final int STATE_SPACE_SIZE = 1009;
    private static final int PRIORITY_RADIUS = 6000;    // Increased radius for priority areas
    private static final int TARGET_REASSIGNMENT_TIME = 4; // Reduced time for faster reassignment

    // System components
    private final Set<EntityID> priorityAreas = new HashSet<>();
    private final Set<EntityID> targetAreas = new HashSet<>();
    private final Map<EntityID, PoliceForceInfo> agentInfoMap = new HashMap<>();
    private final Map<EntityID, List<Road>> blockadeHistogram = new HashMap<>();
    private SampleKMeans kMeansClusterer;
    private AStarPathPlanning pathPlanner;

    // Reinforcement learning components
    private final Map<Integer, double[]> dynamicQ = new ConcurrentHashMap<>();
    private double epsilon = INITIAL_EPSILON;
    private final Map<EntityID, EntityID> previousActions = new ConcurrentHashMap<>();
    private final Map<EntityID, Double> targetValueCache = new HashMap<>();
    private int lastUpdateTime = 0;

    public CSUPoliceTargetAllocator(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
                                    ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        initializeComponents();
        loadPriorityAreas();
        loadTargetAreas();
        initializePoliceInfo();
    }

    private void initializeComponents() {
        this.kMeansClusterer = new SampleKMeans(agentInfo, worldInfo, scenarioInfo, moduleManager, developData);
        this.pathPlanner = new AStarPathPlanning(agentInfo, worldInfo, scenarioInfo, moduleManager, developData);
    }

    private void loadPriorityAreas() {
        // Add roads around critical facilities (refuges) as priority areas
        for (StandardEntity refuge : worldInfo.getEntitiesOfType(StandardEntityURN.REFUGE)) {
            addSurroundingRoads(refuge, priorityAreas, PRIORITY_RADIUS);
        }
        
        // Also add roads around fire stations and ambulance centers
        for (StandardEntity station : worldInfo.getEntitiesOfType(
                StandardEntityURN.FIRE_STATION, 
                StandardEntityURN.AMBULANCE_CENTRE)) {
            addSurroundingRoads(station, priorityAreas, PRIORITY_RADIUS / 2);
        }
    }

    private void loadTargetAreas() {
        // Add roads around buildings that might need evacuation routes
        for (StandardEntity building : worldInfo.getEntitiesOfType(
                StandardEntityURN.BUILDING,
                StandardEntityURN.GAS_STATION)) {
            addSurroundingRoads(building, targetAreas, PRIORITY_RADIUS / 3);
        }
        
        // Calculate blockade histogram for each road
        updateBlockadeHistogram();
    }

    private void updateBlockadeHistogram() {
        blockadeHistogram.clear();
        
        for (StandardEntity entity : worldInfo.getEntitiesOfType(StandardEntityURN.ROAD)) {
            Road road = (Road) entity;
            if (road.isBlockadesDefined() && !road.getBlockades().isEmpty()) {
                road.getBlockades().forEach(blockadeID -> {
                    EntityID roadID = road.getID();
                    blockadeHistogram.computeIfAbsent(roadID, k -> new ArrayList<>()).add(road);
                });
            }
        }
    }

    private void addSurroundingRoads(StandardEntity entity, Set<EntityID> targetSet, int radius) {
        for (EntityID neighborID : worldInfo.getObjectIDsInRange(entity.getID(), radius)) {
            StandardEntity neighbor = worldInfo.getEntity(neighborID);
            if (neighbor instanceof Road) {
                Road road = (Road) neighbor;
                if (road.isBlockadesDefined() && !road.getBlockades().isEmpty()) {
                    targetSet.add(neighborID);
                }
            }
        }
    }

    private void initializePoliceInfo() {
        for (EntityID id : worldInfo.getEntityIDsOfType(StandardEntityURN.POLICE_FORCE)) {
            StandardEntity entity = worldInfo.getEntity(id);
            if (entity instanceof PoliceForce && ((PoliceForce) entity).isPositionDefined()) {
                agentInfoMap.put(id, new PoliceForceInfo(id));
            }
        }
    }

    @Override
    public PoliceTargetAllocator calc() {
        // Update state if necessary
        if (agentInfo.getTime() > lastUpdateTime) {
            updateBlockadeHistogram();
            kMeansClusterer.calClusterAndAssign();
            lastUpdateTime = agentInfo.getTime();
            targetValueCache.clear(); // Clear cache when time advances
        }
        
        List<StandardEntity> availableAgents = getAvailableAgents();
        
        // Get all blocked roads for more efficient allocation
        List<EntityID> blockedRoads = getBlockedRoads();

        // Phase 1: Priority area assignments
        processPriorityAssignments(availableAgents, blockedRoads);

        // Phase 2: Regular area assignments with workload balancing
        processRegularAssignments(availableAgents, blockedRoads);

        return this;
    }

    private List<EntityID> getBlockedRoads() {
        List<EntityID> blockedRoads = new ArrayList<>();
        
        // First add roads from priority areas
        for (EntityID id : priorityAreas) {
            StandardEntity entity = worldInfo.getEntity(id);
            if (entity instanceof Road) {
                Road road = (Road) entity;
                if (road.isBlockadesDefined() && !road.getBlockades().isEmpty()) {
                    blockedRoads.add(id);
                }
            }
        }
        
        // Then add other blocked roads
        for (EntityID id : targetAreas) {
            if (!blockedRoads.contains(id)) {
                StandardEntity entity = worldInfo.getEntity(id);
                if (entity instanceof Road) {
                    Road road = (Road) entity;
                    if (road.isBlockadesDefined() && !road.getBlockades().isEmpty()) {
                        blockedRoads.add(id);
                    }
                }
            }
        }
        
        return blockedRoads;
    }

    private void processPriorityAssignments(List<StandardEntity> agents, List<EntityID> blockedRoads) {
        // Filter priority roads that are actually blocked
        List<EntityID> priorityTargets = blockedRoads.stream()
                .filter(priorityAreas::contains)
                .collect(Collectors.toList());
        
        if (priorityTargets.isEmpty()) return;
        
        // Sort agents by their suitability for priority tasks
        agents.sort(Comparator.comparingInt(a -> 
            worldInfo.getDistance(a.getID(), findNearestTarget(a, priorityTargets))));
            
        // Assign agents to priority targets using Hungarian algorithm
        Map<EntityID, EntityID> assignments = calculateOptimalAssignments(agents, priorityTargets);
        
        // Process assignments
        assignments.forEach((agentID, targetID) -> {
            StandardEntity agent = worldInfo.getEntity(agentID);
            processAgentAssignment(agent, targetID);
            priorityTargets.remove(targetID);
            agents.remove(agent);
        });
    }

    private Map<EntityID, EntityID> calculateOptimalAssignments(List<StandardEntity> agents, List<EntityID> targets) {
        Map<EntityID, EntityID> result = new HashMap<>();
        if (agents.isEmpty() || targets.isEmpty()) return result;
        
        // Simple greedy algorithm for smaller problem sizes
        if (agents.size() <= 3 || targets.size() <= 3) {
            for (StandardEntity agent : new ArrayList<>(agents)) {
                if (targets.isEmpty()) break;
                
                EntityID bestTarget = findBestTarget(agent, targets);
                if (bestTarget != null) {
                    result.put(agent.getID(), bestTarget);
                    targets.remove(bestTarget);
                }
            }
            return result;
        }
        
        // For larger sets, use more sophisticated matching
        PriorityQueue<AgentTargetPair> pairs = new PriorityQueue<>();
        
        for (StandardEntity agent : agents) {
            for (EntityID target : targets) {
                double value = calculateTargetValue(agent, target);
                pairs.add(new AgentTargetPair(agent.getID(), target, value));
            }
        }
        
        Set<EntityID> assignedAgents = new HashSet<>();
        Set<EntityID> assignedTargets = new HashSet<>();
        
        while (!pairs.isEmpty() && assignedAgents.size() < agents.size() 
               && assignedTargets.size() < targets.size()) {
            AgentTargetPair pair = pairs.poll();
            
            if (!assignedAgents.contains(pair.agentID) && !assignedTargets.contains(pair.targetID)) {
                result.put(pair.agentID, pair.targetID);
                assignedAgents.add(pair.agentID);
                assignedTargets.add(pair.targetID);
            }
        }
        
        return result;
    }

    private EntityID findBestTarget(StandardEntity agent, List<EntityID> targets) {
        return targets.stream()
            .max(Comparator.comparingDouble(t -> calculateTargetValue(agent, t)))
            .orElse(null);
    }

    private double calculateTargetValue(StandardEntity agent, EntityID targetID) {
        // Use cached value if available
        String key = agent.getID() + ":" + targetID;
        if (targetValueCache.containsKey(targetID)) {
            return targetValueCache.get(targetID);
        }
        
        double value = 0;
        
        // Distance factor (closer is better)
        double distance = worldInfo.getDistance(agent.getID(), targetID);
        double normalizedDistance = Math.min(distance / MAX_DISTANCE, 1.0);
        value += (1 - normalizedDistance) * 50;
        
        // Priority factor
        if (priorityAreas.contains(targetID)) {
            value += 30;
        }
        
        // Blockade factor
        StandardEntity targetEntity = worldInfo.getEntity(targetID);
        if (targetEntity instanceof Road) {
            Road road = (Road) targetEntity;
            if (road.isBlockadesDefined()) {
                value += road.getBlockades().size() * 5;
            }
        }
        
        // Cache the calculated value
        targetValueCache.put(targetID, value);
        
        return value;
    }

    private void processRegularAssignments(List<StandardEntity> agents, List<EntityID> blockedRoads) {
        // Filter out priority roads that are already being handled
        List<EntityID> regularTargets = blockedRoads.stream()
                .filter(id -> !priorityAreas.contains(id))
                .collect(Collectors.toList());
                
        if (regularTargets.isEmpty() || agents.isEmpty()) return;
        
        // Use cluster assignments first if available
        Map<EntityID, EntityID> clusterAssignments = new HashMap<>();
        for (StandardEntity agent : agents) {
            EntityID clusterCenter = kMeansClusterer.getClusterCenter(agent.getID());
            if (clusterCenter != null && regularTargets.contains(clusterCenter)) {
                clusterAssignments.put(agent.getID(), clusterCenter);
                regularTargets.remove(clusterCenter);
            }
        }
        
        // Process cluster assignments
        clusterAssignments.forEach((agentID, targetID) -> {
            StandardEntity agent = worldInfo.getEntity(agentID);
            processAgentAssignment(agent, targetID);
            agents.remove(agent);
        });
        
        // Process remaining assignments using RL
        if (!agents.isEmpty() && !regularTargets.isEmpty()) {
            Map<EntityID, EntityID> assignments = calculateOptimalAssignments(agents, regularTargets);
            
            assignments.forEach((agentID, targetID) -> {
                StandardEntity agent = worldInfo.getEntity(agentID);
                processAgentAssignment(agent, targetID);
            });
        }
    }

    private void processAgentAssignment(StandardEntity agent, EntityID target) {
        int state = generateState(agent, target);
        int action = selectAction(state);

        updateQLearning(state, action, agent, target);

        if (shouldAssignAction(action)) {
            assignAgentToTarget(agent.getID(), target);
            previousActions.put(agent.getID(), target);
        }
    }

    private int generateState(StandardEntity agent, EntityID target) {
        PoliceForce police = (PoliceForce) agent;
        Pair<Integer, Integer> agentLoc = worldInfo.getLocation(agent.getID());
        Pair<Integer, Integer> targetLoc = worldInfo.getLocation(target);

        // Coordinate binning
        int xBin = agentLoc.first() / X_BIN_SIZE;
        int yBin = agentLoc.second() / Y_BIN_SIZE;

        // Relative position
        int dx = (targetLoc.first() - agentLoc.first()) / X_BIN_SIZE;
        int dy = (targetLoc.second() - agentLoc.second()) / Y_BIN_SIZE;

        // Distance binning
        double distance = worldInfo.getDistance(agent.getID(), target);
        int distBin = (int) (distance / DISTANCE_BIN);
        
        // Agent state
        int agentState = 0;
        if (police.isDamageDefined() && police.getDamage() > 0) {
            agentState = 1;
        }

        // State hashing with improved distribution
        return Math.abs((xBin * 31 + yBin * 17 + dx * 7 + dy * 13 + distBin * 5 + agentState * 23)) % STATE_SPACE_SIZE;
    }

    private int selectAction(int state) {
        ensureQState(state);
        epsilon = Math.max(epsilon * EPSILON_DECAY, MIN_EPSILON);

        // Epsilon-greedy policy
        if (Math.random() < epsilon) {
            return (int) (Math.random() * NUM_ACTIONS);
        }
        return findBestAction(state);
    }

    private void updateQLearning(int state, int action, StandardEntity agent, EntityID target) {
        double reward = calculateReward(agent, target);
        int nextState = generateState(agent, target);

        // Double Q-learning update
        double maxNextQ = Arrays.stream(getQValues(nextState)).max().orElse(0);
        double newValue = (1 - LEARNING_RATE) * getQValue(state, action)
                + LEARNING_RATE * (reward + DISCOUNT_FACTOR * maxNextQ);

        updateQValue(state, action, newValue);
    }

    private double calculateReward(StandardEntity agent, EntityID target) {
        if (agent == null || target == null) return 0;

        PoliceForceInfo info = agentInfoMap.get(agent.getID());
        if (info == null) return 0;

        double reward = 0;

        // Target validity and criticality rewards
        StandardEntity targetEntity = worldInfo.getEntity(target);
        if (targetEntity instanceof Road) {
            Road road = (Road) targetEntity;
            
            // Check if there are blockades to clear
            if (road.isBlockadesDefined() && !road.getBlockades().isEmpty()) {
                reward += 25;
                
                // Higher reward for critical areas
                if (isCriticalArea(target)) {
                    reward += 40;
                }
                
                // Higher reward for roads with more blockades
                reward += Math.min(road.getBlockades().size() * 3, 15);
            }
        }
        
        // Distance-based reward (closer is better)
        double distance = worldInfo.getDistance(agent.getID(), target);
        double normalizedDistance = Math.min(distance / MAX_DISTANCE, 1.0);
        reward += (1 - normalizedDistance) * 20;
        
        // Previous commitment reward (to encourage finishing tasks)
        if (info.getCurrentTarget() != null && target.equals(info.getCurrentTarget())) {
            reward += 15;
        }
        
        // Health status penalty/reward
        PoliceForce police = (PoliceForce) agent;
        if (police.isDamageDefined() && police.getDamage() > 0) {
            // Penalize assigning damaged agents to distant targets
            reward -= police.getDamage() * normalizedDistance * 0.1;
        }

        return reward;
    }

    private EntityID findNearestTarget(StandardEntity agent, List<EntityID> candidates) {
        if (candidates == null || candidates.isEmpty()) return null;

        return candidates.stream()
                .filter(Objects::nonNull)
                .min(Comparator.comparingDouble(t ->
                        worldInfo.getDistance(agent.getID(), t)))
                .orElse(null);
    }

    private EntityID findNearestPriorityTarget(StandardEntity agent, List<EntityID> targets) {
        return targets.stream()
                .min(Comparator.comparingDouble(t -> worldInfo.getDistance(agent.getID(), t)))
                .orElse(null);
    }

    private void assignAgentToTarget(EntityID agentID, EntityID target) {
        PoliceForceInfo info = agentInfoMap.get(agentID);
        if (info != null) {
            info.updateAssignment(target, agentInfo.getTime());
        }
    }

    private List<StandardEntity> getAvailableAgents() {
        List<StandardEntity> agents = new ArrayList<>();
        for (StandardEntity entity : worldInfo.getEntitiesOfType(StandardEntityURN.POLICE_FORCE)) {
            PoliceForce police = (PoliceForce) entity;
            PoliceForceInfo info = agentInfoMap.get(entity.getID());
            
            if (info != null && info.isAvailable()) {
                agents.add(entity);
            }
        }
        return agents;
    }

    // Q-table management
    private void ensureQState(int state) {
        dynamicQ.putIfAbsent(state, new double[NUM_ACTIONS]);
    }

    private double[] getQValues(int state) {
        ensureQState(state);
        return dynamicQ.get(state);
    }

    private double getQValue(int state, int action) {
        return getQValues(state)[action];
    }

    private void updateQValue(int state, int action, double value) {
        getQValues(state)[action] = value;
    }

    private int findBestAction(int state) {
        double[] qValues = getQValues(state);
        int bestAction = 0;
        double bestValue = qValues[0];
        
        for (int i = 1; i < qValues.length; i++) {
            if (qValues[i] > bestValue) {
                bestValue = qValues[i];
                bestAction = i;
            }
        }
        return bestAction;
    }

    private boolean shouldAssignAction(int action) {
        // Actions 0, 2, and 3 lead to assignments
        return action == 0 || action == 2 || action == 3;
    }

    private boolean isCriticalArea(EntityID target) {
        return priorityAreas.contains(target);
    }

    private class AgentTargetPair implements Comparable<AgentTargetPair> {
        EntityID agentID;
        EntityID targetID;
        double value;
        
        AgentTargetPair(EntityID agent, EntityID target, double value) {
            this.agentID = agent;
            this.targetID = target;
            this.value = value;
        }
        
        @Override
        public int compareTo(AgentTargetPair other) {
            // Higher values come first in PriorityQueue
            return Double.compare(other.value, this.value);
        }
    }

    private class PoliceForceInfo {
        EntityID agentID;
        EntityID currentTarget;
        int lastUpdate;
        boolean available;

        public EntityID getCurrentTarget() {
            return currentTarget;
        }

        boolean isTargetValid() {
            return currentTarget != null
                    && worldInfo.getEntity(currentTarget) instanceof Road;
        }

        PoliceForceInfo(EntityID id) {
            this.agentID = id;
            this.available = true;
        }

        void updateAssignment(EntityID target, int timestamp) {
            this.currentTarget = target;
            this.lastUpdate = timestamp;
            this.available = false;
        }

        boolean isAvailable() {
            // Faster reassignment cycle
            return available || (currentTarget == null) || 
                   (agentInfo.getTime() - lastUpdate) > TARGET_REASSIGNMENT_TIME;
        }
    }

    // Framework methods
    @Override
    public PoliceTargetAllocator resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        return this;
    }

    @Override
    public PoliceTargetAllocator preparate() {
        super.preparate();
        return this;
    }

    @Override
    public Map<EntityID, EntityID> getResult() {
        Map<EntityID, EntityID> result = new HashMap<>();
        agentInfoMap.forEach((id, info) -> {
            if (info.currentTarget != null) {
                result.put(id, info.currentTarget);
            }
        });
        return result;
    }

    @Override
    public PoliceTargetAllocator updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        return this;
    }
}
package CSU_Yunlu_2023.module.complex.at;

import CSU_Yunlu_2023.CSUConstants;
import CSU_Yunlu_2023.LogHelper;
import CSU_Yunlu_2023.debugger.DebugHelper;
import CSU_Yunlu_2023.util.ambulancehelper.CSUDistanceSorter;
import CSU_Yunlu_2023.util.ambulancehelper.CSUHurtHumanClassifier;
import CSU_Yunlu_2023.util.ambulancehelper.CSUSelectorTargetByDis;
import CSU_Yunlu_2023.world.CSUWorldHelper;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.PathPlanning;
import adf.core.component.module.complex.HumanDetector;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import java.io.Serializable;
import java.util.*;

public class CSUHumanDetector extends HumanDetector {

    private EntityID result;
    private int clusterIndex;

    private CSUSelectorTargetByDis targetSelector;
    private CSUHurtHumanClassifier CSU_HurtHumanClassifier;
    private Clustering clustering;

    private Map<EntityID, Building> sentBuildingMap;
    private Map<EntityID, Integer> sentTimeMap;

    private Map<StandardEntity, Integer> blockedVictims;
    private PathPlanning pathPlanning;
    private Random rnd = new Random(System.currentTimeMillis());

    private EntityID lastPosition;
    private EntityID nowPosition;
    private List<EntityID> way;

    private Map<EntityID, Integer> hangUpMap;
    private Set<EntityID> deadHumanSet;
    private Set<EntityID> visited;
    private int savedTime = 0;
    private LogHelper logHelper;
    private Map<EntityID, StandardEntity> invalidHumanPosition;
    private CSUWorldHelper world;
    private CSUATHumanSystem csuatHumanSystem;

    private static final int AGENT_MOVEMENT = 7000;

    public CSUHumanDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);

        switch (scenarioInfo.getMode()) {
            case PRECOMPUTATION_PHASE, PRECOMPUTED, NON_PRECOMPUTE:
                this.pathPlanning = moduleManager.getModule("SampleRoadDetector.PathPlanning", "adf.impl.module.algorithm.DijkstraPathPlanning");
                this.clustering = moduleManager.getModule("SampleRoadDetector.Clustering", "adf.impl.module.algorithm.KMeansClustering");
                break;
        }
        this.blockedVictims = new HashMap<>();
        this.clusterIndex = -1;
        this.sentBuildingMap = new HashMap<>();
        this.sentTimeMap = new HashMap<>();
        this.agentInfo = ai;
        this.worldInfo = wi;
        this.scenarioInfo = si;


        this.hangUpMap = new HashMap<>();
        this.visited = new HashSet<>();
        this.invalidHumanPosition = new HashMap<>();
        this.deadHumanSet = new HashSet<>();
        targetSelector = new CSUSelectorTargetByDis(ai, wi, si, moduleManager, developData);
        logHelper = new LogHelper("at_log/detector", agentInfo, "CSUHumanDetector");
        world = moduleManager.getModule("WorldHelper.Default", CSUConstants.WORLD_HELPER_DEFAULT);
        this.csuatHumanSystem = new CSUATHumanSystem(world,ai,wi,si,moduleManager,developData,clustering,pathPlanning);
        this.registerModule(csuatHumanSystem);
        initClassifier();
    }

    @Override
    public HumanDetector calc() {
        Set<EntityID> changedEntities = this.worldInfo.getChanged().getChangedEntities();
        StandardEntity position = this.worldInfo.getPosition((Human) this.agentInfo.me());
        if (position instanceof Building && !(position instanceof Refuge)){
            Building building = (Building) position;
            if (building.isBrokennessDefined()&&building.getBrokenness()>0){
                for (EntityID entityID : changedEntities){
                    StandardEntity entity = this.worldInfo.getEntity(entityID);
                    if (entity.getID().equals(this.agentInfo.getID())){
                        continue;
                    }
                    if (entity instanceof Civilian){

                        Civilian civilian = (Civilian) entity;
                        if (civilian.getPosition().equals(position.getID())
                                &&civilian.isBuriednessDefined()
                                &&civilian.getBuriedness()==0&&
                                civilian.isHPDefined()&&civilian.getHP()>0){
                            this.result = civilian.getID();
                            return this;
                        }
                    }
                }
            }
        }

        result = csuatHumanSystem.getTargetByDistance();
        if (result == null){
            result = csuatHumanSystem.getTargetByTimeAndBuriedness();
        }
        return this;
    }



    @Override
    public EntityID getTarget() {
        return this.result;
    }

    private void initClassifier() {
        CSU_HurtHumanClassifier = new CSUHurtHumanClassifier(worldInfo, agentInfo);
    }

    private EntityID failedClusteringCalc() {
        List<Human> targets = new ArrayList<>();
        for (StandardEntity next : worldInfo.getEntitiesOfType(
                StandardEntityURN.CIVILIAN,
                StandardEntityURN.AMBULANCE_TEAM,
                StandardEntityURN.POLICE_FORCE,
                StandardEntityURN.FIRE_BRIGADE
        )) {
            Human h = (Human) next;
            if (agentInfo.getID() == h.getID()) {
                continue;
            }
            if (h.isHPDefined()
                    && h.isDamageDefined()
                    && h.isPositionDefined()
                    && h.getHP() > 0
                    && (h.getBuriedness() > 0 || h.getDamage() > 0)) {
                targets.add(h);
            }
        }
        targets.sort(new CSUDistanceSorter(this.worldInfo, this.agentInfo.getPositionArea()));
        return targets.isEmpty() ? null : targets.get(0).getID();
    }

    private boolean isReachable(EntityID targetID) {
        EntityID from = agentInfo.getPosition();
        StandardEntity entity = worldInfo.getPosition(targetID);
        logHelper.writeAndFlush("当前位置:" + agentInfo.getPosition() + ",当前目标位置:" + entity);
        if (entity != null) {
            EntityID destination = entity.getID();
            if (from.equals(destination)) {
                return true;
            }
            List<EntityID> result = pathPlanning.setFrom(from).setDestination(destination).calc().getResult();
            return result != null;
        } else {
            return false;
        }
    }
    private boolean needToChange(EntityID result) {
        StandardEntity area = worldInfo.getPosition(result);
        StandardEntity entity = worldInfo.getEntity(result);
        Human human = (Human) entity;
        if (human.isHPDefined() && human.getHP() <= 1) {
            deadHumanSet.add(human.getID());
            return true;
        } else {
            int distance = this.worldInfo.getDistance(agentInfo.getID(), result);
            int movingTime = distance / AGENT_MOVEMENT;
            int remainHp = human.getHP() - movingTime * human.getDamage();
            if (remainHp <= 1) {
                return true;
            }
        }
        if (human.isBuriednessDefined() && human.getBuriedness() > 0) {
            return true;
        }
        if (entity instanceof Civilian) {
            Civilian civ = (Civilian) entity;
            logHelper.writeAndFlush("当前目标(" + civ.getID() + ")位置定义:" + civ.isPositionDefined() + ",位置:" + civ.getPosition());
            if (area instanceof AmbulanceTeam) {
                logHelper.writeAndFlush("当前目标(" + civ.getID() + ")已经被别的at(" + area + ")背走");
                return true;
            }
            if (area instanceof Refuge) {
                logHelper.writeAndFlush("当前目标(" + civ.getID() + ")已经在refuge里了");
                if (CSUConstants.DEBUG_AT_SEARCH) {
                    System.out.println("[第" + agentInfo.getTime() + "回合]   " + agentInfo.getID() + ":当前目标在refugee里，换目标");
                }
                return true;
            }
        } else {
        }
        return false;
    }

    private void hangUp(EntityID id) {
        int postponeTime = rnd.nextInt(6) + 20;
        hangUpMap.put(id, postponeTime);
    }

    private boolean isTargetPositionValid() {
        StandardEntity entity = worldInfo.getPosition(result);
        if (agentInfo.getPosition().equals(entity.getID())) {
            Set<EntityID> set = worldInfo.getChanged().getChangedEntities();
            logHelper.writeAndFlush("changedEntityIDs:" + set);
            return set.contains(result);
        } else {
            return true;
        }
    }

    private EntityID findHumanToRescue() {
        logHelper.writeAndFlush("找人救");
        List<Human> targets = new ArrayList<>();
        List<Human> exclude = new ArrayList<>();

        EntityID agentPositionID = agentInfo.getPosition();
        StandardEntity standardEntity = worldInfo.getEntity(agentPositionID);
        if (standardEntity instanceof Building && !(standardEntity instanceof Refuge)) {
            Building building = (Building) standardEntity;
            if (building.isBrokennessDefined() && building.getBrokenness() > 0) {
                for (EntityID id : worldInfo.getChanged().getChangedEntities()) {
                    StandardEntity e = worldInfo.getEntity(id);
                    if (e instanceof Civilian) {
                        if (((Civilian) e).getPosition().equals(agentPositionID) &&
                                (((Civilian) e).isBuriednessDefined() && ((Civilian) e).getBuriedness() <= 0)) {
                            logHelper.writeAndFlush("chose because someone(" + id + ") is need to rescue");
                            return id;
                        }
                    }
                }
            }
        }


        for (StandardEntity next : worldInfo.getEntitiesOfType(
                StandardEntityURN.CIVILIAN
        )) {
            Human h = (Human) next;
            if (agentInfo.getID().equals(h.getID())) {
                continue;
            }
            if (h.isHPDefined() && h.isBuriednessDefined() && h.isPositionDefined() && h.getHP() > 0 && h.getDamage() > 0 && h.getBuriedness()<=20) {

                targets.add(h);
            } else {
                exclude.add(h);
            }
        }

        targets.removeIf(human -> invalidHumanPosition.keySet().contains(human.getID()));
        targets.removeIf(human -> hangUpMap.keySet().contains(human.getID()));
        targets.removeIf(human -> noNeed(human.getID()));

        targets.removeIf(human -> visited.contains(human.getID()));

        targets.removeIf(human -> (human.isBuriednessDefined() && human.getBuriedness() > 0));

        targets.removeIf(human -> deadHumanSet.contains(human.getID()));
        targets.removeIf(human -> {
            StandardEntity area = worldInfo.getPosition(human.getID());
            return area instanceof AmbulanceTeam;
        });

//        if (DebugHelper.DEBUG_MODE) {
//            List<Integer> elements = new ArrayList<>();
//
//            for (Human human : targets) {
//                elements.add(human.getID().getValue());
//            }
//            DebugHelper.VD_CLIENT.drawAsync(agentInfo.getID().getValue(), "ATPerceiveHumans", (Serializable) elements);
//        }
        List<Human> invalidHuman = new ArrayList<>();
        int indexOfAgent = clustering.getClusterIndex(agentInfo.getID());
        for (Human outRangeHuman : targets) {
            StandardEntity position = this.worldInfo.getPosition(outRangeHuman);

            int indexOfHuman = clustering.getClusterIndex(position.getID());
            if (indexOfAgent == indexOfHuman) { //如果在同一聚类里面 不管
                continue;
            } else {
                invalidHuman.add(outRangeHuman);
            }
        }
        targets.removeAll(invalidHuman);

        targets.sort(new CSUDistanceSorter(this.worldInfo, this.agentInfo.getPositionArea()));

//        if (DebugHelper.DEBUG_MODE) {
//            List<Integer> elements = new ArrayList<>();
//
//            for (Human human : targets) {
//                elements.add(human.getID().getValue());
//            }
//            DebugHelper.VD_CLIENT.drawAsync(agentInfo.getID().getValue(), "ATTargetHumans", (Serializable) elements);
//        }
//        logHelper.writeAndFlush("可以选择的target有" + targets);
//        logHelper.writeAndFlush("exclude target:" + exclude);

        return targets.isEmpty() ? null : targets.get(0).getID();
    }

    private boolean noNeed(EntityID result) {
        StandardEntity entity = worldInfo.getEntity(result);
        if (entity instanceof Area) return true;
        if (entity instanceof Human) {
            Human human = (Human) entity;
            if (worldInfo.getEntity(human.getPosition()) instanceof Refuge) {
                return true;
            }else if(worldInfo.getEntity(human.getPosition()) instanceof AmbulanceTeam){
                return true;
            }
            return false;
        }
        return false;
    }

    private void visualDebug() {
        if (DebugHelper.DEBUG_MODE) {
            try {
                DebugHelper.drawDetectorTarget(worldInfo, agentInfo.getID(), result);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    static class Com implements Comparator<Integer> {
        @Override
        public int compare(Integer o1, Integer o2) {
            return o1 - o2;
        }
    }

    private boolean isBestAT(EntityID result) {
        StandardEntity entity = worldInfo.getEntity(result);
        Human human = (Human) entity;
        AmbulanceTeam bestAt = getBestAT(human);
        if(bestAt.getID() != this.agentInfo.getID()){
            return false;
        }else {
            return true;
        }
    }

    public  AmbulanceTeam getBestAT(Human human){
        Collection<EntityID> ambulances = this.worldInfo.getEntityIDsOfType(StandardEntityURN.AMBULANCE_TEAM);//这个里面包括自己
        AmbulanceTeam bestAT = null;
        int bestDisatance = Integer.MAX_VALUE;

        Iterator iterator = ambulances.iterator();
        while (iterator.hasNext()){
            AmbulanceTeam ambulanceTeam = (AmbulanceTeam) this.worldInfo.getEntity((EntityID) iterator.next());

            if(human.getID() == ambulanceTeam.getID()){
                iterator.remove();
                continue;
            }
            if(ambulanceTeam.getID() != this.agentInfo.getID())//判断是不是本身，因为本身一定是有目标的
            {
                if(false){
                    iterator.remove();
                    continue;
                }
            }

            if (ambulanceTeam.isPositionDefined()){
                EntityID from = ambulanceTeam.getPosition();
                EntityID destination = human.getID();
                List<EntityID> result = pathPlanning.setFrom(from).setDestination(destination).calc().getResult();
                if (result != null)
                {
                    int temp = worldInfo.getDistance(human, ambulanceTeam);
                    if(temp < bestDisatance)
                    {
                        bestDisatance = temp;
                        bestAT = ambulanceTeam;
                    }

                }else {
                    iterator.remove();
                }
            }
        }
        return bestAT;

    }

    @Override
    public HumanDetector updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        return this;
    }

        private void updateInvalidPositionMap(){
        Set<EntityID> toRemove = new HashSet<>();
        for (EntityID target : invalidHumanPosition.keySet()){
            StandardEntity entity = worldInfo.getPosition(target);
            if(!entity.equals(invalidHumanPosition.get(target))){
                toRemove.add(target);
            }
        }
        invalidHumanPosition.keySet().removeAll(toRemove);
    }


}



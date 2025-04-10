package CSU_Yunlu_2023.module.complex.fb;


import CSU_Yunlu_2023.debugger.DebugHelper;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.PathPlanning;
import adf.core.component.module.complex.HumanDetector;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import java.io.Serializable;
import java.util.*;

public class CSUFBHumanDetector extends HumanDetector {

    private EntityID result;
    private Clustering clustering;

    private PathPlanning pathPlanning;

    private Random rnd = new Random(System.currentTimeMillis());

    private EntityID lastPosition;
    private EntityID nowPosition;
    private Map<EntityID, Integer> hangUpMap;
    private Set<EntityID> deadHumanSet;

    private Map<EntityID, StandardEntity> invalidHumanPosition;
    private CSUFBHumanSystem csufbHumanSystem;
    public CSUFBHumanDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);

        switch (scenarioInfo.getMode()) {
            case PRECOMPUTATION_PHASE:
                this.pathPlanning = moduleManager.getModule("SampleRoadDetector.PathPlanning", "adf.core.sample.module.algorithm.SamplePathPlanning");
                this.clustering = moduleManager.getModule("SampleHumanDetector.Clustering", "adf.core.sample.module.algorithm.SampleKMeans");
                break;
            case PRECOMPUTED:
                this.pathPlanning = moduleManager.getModule("SampleRoadDetector.PathPlanning", "adf.core.sample.module.algorithm.SamplePathPlanning");
                this.clustering = moduleManager.getModule("SampleHumanDetector.Clustering", "adf.core.sample.module.algorithm.SampleKMeans");
                break;
            case NON_PRECOMPUTE:
                this.pathPlanning = moduleManager.getModule("SampleRoadDetector.PathPlanning", "adf.core.sample.module.algorithm.SamplePathPlanning");
                this.clustering = moduleManager.getModule("SampleHumanDetector.Clustering", "adf.core.sample.module.algorithm.SampleKMeans");
                break;
        }
        this.hangUpMap = new HashMap<>();
        this.invalidHumanPosition = new HashMap<>();
        this.deadHumanSet = new HashSet<>();
        this.csufbHumanSystem = new CSUFBHumanSystem(ai,wi,si,moduleManager,developData,clustering,pathPlanning);
    }

    @Override
    public HumanDetector calc() {
        FireBrigade fireBrigade = (FireBrigade) this.agentInfo.me();
        if (fireBrigade.isBuriednessDefined()&&fireBrigade.getBuriedness()>0){
            return this;
        }
        Set<CSUFBHuman> seenCivilian = new HashSet<>();
        for (EntityID entityID: this.agentInfo.getChanged().getChangedEntities()){
            StandardEntity standardEntity = this.worldInfo.getEntity(entityID);
            if (standardEntity instanceof Civilian){
                Civilian civilian = (Civilian) standardEntity;
                if (civilian.isPositionDefined()&&civilian.getPosition().equals(this.agentInfo.getPosition())){
                    if (civilian.isHPDefined()&&civilian.getHP()>0&&civilian.isBuriednessDefined()&&civilian.getBuriedness()>0){
                        seenCivilian.add(this.csufbHumanSystem.getAllHuman().get(civilian.getID()));
                    }
                }
            }
        }
        if (!seenCivilian.isEmpty()){
            TreeSet<CSUFBHuman> objects = new TreeSet<>(((o1, o2) ->Double.compare(o1.getDeadTime(), o2.getDeadTime())));
            objects.addAll(seenCivilian);
            this.result = objects.first().getHuman().getID();
        }else {
            this.result = csufbHumanSystem.getTargetByDistance();
        }
        return this;
    }
    private boolean noNeed(EntityID result) {
        StandardEntity entity = worldInfo.getEntity(result);
        if (entity instanceof Area) return true;
        if (entity instanceof Human) {
            Human human = (Human) entity;
            if (worldInfo.getEntity(human.getPosition()) instanceof Refuge || worldInfo.getEntity(human.getPosition()) instanceof AmbulanceTeam) {
                return true;
            }
            return false;
        }
        return false;
    }

    private boolean needToChange(EntityID result) {
        StandardEntity entity = worldInfo.getEntity(result);
        Human human = (Human) entity;
        if (human.isHPDefined() && human.getHP() <= 1) {
            deadHumanSet.add(human.getID());
            return true;
        }
        if (human.isBuriednessDefined()) {
            return human.getBuriedness() <= 0;
        }

        return false;
    }

    //
    private EntityID findHumanToRescue() {
        List<Human> targets = new ArrayList<>();
        List<Human> exclude = new ArrayList<>();
        EntityID agentPositionID = agentInfo.getPosition();
        StandardEntity standardEntity = worldInfo.getEntity(agentPositionID);
        if (standardEntity instanceof Building) {
            Building building = (Building) standardEntity;
            if (building.isBrokennessDefined() && building.getBrokenness() > 0) {
                for (EntityID id : worldInfo.getChanged().getChangedEntities()) {
                    StandardEntity e = worldInfo.getEntity(id);
                    if (e instanceof Civilian) {
                        Civilian civilian = (Civilian) e;
                        if (civilian.getPosition().equals(agentPositionID) && civilian.isBuriednessDefined()
                                &&civilian.getBuriedness() > 0 && civilian.isHPDefined()&&civilian.getHP()>0) {
                            return id;
                        }
                    }
                }
            }
        }

        for (StandardEntity next : worldInfo.getEntitiesOfType(
                StandardEntityURN.CIVILIAN,
                StandardEntityURN.FIRE_BRIGADE,
                StandardEntityURN.POLICE_FORCE,
                StandardEntityURN.AMBULANCE_TEAM)
        ) {
            Human h = (Human) next;
            if (agentInfo.getID().equals(h.getID())) {
                continue;
            }
            if (h.isHPDefined()
                    && h.isBuriednessDefined()
                    && h.isPositionDefined()
                    && h.getHP() > 0
                    && h.getBuriedness() > 0) {
                targets.add(h);
            } else {
                exclude.add(h);
            }
        }

        if (DebugHelper.DEBUG_MODE) {
            List<Integer> elements = new ArrayList<>();
            for (Human human : targets){
                elements.add(human.getID().getValue());
            }
            DebugHelper.VD_CLIENT.drawAsync(agentInfo.getID().getValue(), "FBPerceiveHumans", (Serializable) elements);
        }

        targets.removeIf(human -> invalidHumanPosition.keySet().contains(human.getID()));
        targets.removeIf(human -> hangUpMap.keySet().contains(human.getID()));
        targets.removeIf(human -> noNeed(human.getID()));
        List<Human> invalidHuman = new ArrayList<>();
        int indexOfAgent = clustering.getClusterIndex(agentInfo.getID());
        for (Human outRangeHuman : targets) {
            StandardEntity position = this.worldInfo.getPosition(outRangeHuman);

            int indexOfHuman = clustering.getClusterIndex(position.getID());
            if (indexOfAgent == indexOfHuman) {
                continue;
            } else {
                invalidHuman.add(outRangeHuman);
            }
        }
        targets.removeAll(invalidHuman);
        targets.sort(new CSUDistanceSorter(this.worldInfo, this.agentInfo.getPositionArea()));

        if (DebugHelper.DEBUG_MODE) {
            List<Integer> elements = new ArrayList<>();

            for (Human human : targets){
                elements.add(human.getID().getValue());
            }
            DebugHelper.VD_CLIENT.drawAsync(agentInfo.getID().getValue(), "FBTargetHumans", (Serializable) elements);
        }

        for (Human human : targets){
            if(this.isReachable(human.getID())){
                if (this.isTargetPositionValid(human.getID())){
                    return human.getID();
                }
            }
        }

        return null;
    }



    @Override
    public EntityID getTarget() {
        return this.result;
    }

    @Override
    public HumanDetector precompute(PrecomputeData precomputeData) {
        super.precompute(precomputeData);

        if (this.getCountPrecompute() >= 2)
        {
            return this;
        }

        this.clustering.precompute(precomputeData);
        return this;
    }

    @Override
    public HumanDetector resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);

        this.clustering.resume(precomputeData);
        return this;
    }

    @Override
    public HumanDetector preparate() {
        super.preparate();
        if (this.getCountPreparate() >= 2)
        {
            return this;
        }
        this.clustering.preparate();
        return this;
    }


    @Override
    public HumanDetector updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if (this.getCountUpdateInfo() >= 2) {
            return this;
        }

        lastPosition = nowPosition;
        nowPosition = agentInfo.getPosition();
        this.clustering.updateInfo(messageManager);
        this.csufbHumanSystem.updateInfo(messageManager);
        passHangUp(messageManager);
        updateInvalidPositionMap();
        return this;
    }

    private boolean isReachable(EntityID targetID) {
        EntityID from = agentInfo.getPosition();
        StandardEntity entity = worldInfo.getPosition(targetID);

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

    private void passHangUp(MessageManager messageManager) {
        Set<EntityID> toRemove = new HashSet<>();
        for (EntityID id : hangUpMap.keySet()) {
            int i = hangUpMap.get(id) - 1;
            if (i <= 0) {
                toRemove.add(id);
            } else {
                hangUpMap.put(id, i);
            }
        }
        hangUpMap.keySet().removeAll(toRemove);
    }

    private void hangUp(EntityID id) {
        int postponeTime = rnd.nextInt(6) + 20;
        hangUpMap.put(id, postponeTime);
    }

    private boolean isTargetPositionValid(EntityID entityID) {
        StandardEntity entity = worldInfo.getPosition(entityID);
        if (agentInfo.getPosition().equals(entity.getID())) {
            Set<EntityID> set = worldInfo.getChanged().getChangedEntities();
            if (!set.contains(entityID)){
                invalidHumanPosition.put(entityID, worldInfo.getPosition(entityID));
                return false;
            }
        }
        return true;
    }

    private void updateInvalidPositionMap() {
        Set<EntityID> toRemove = new HashSet<>();
        for (EntityID target : invalidHumanPosition.keySet()) {
            StandardEntity entity = worldInfo.getPosition(target);
            if (!entity.equals(invalidHumanPosition.get(target))) {
                toRemove.add(target);
            }
        }
        invalidHumanPosition.keySet().removeAll(toRemove);
    }
}

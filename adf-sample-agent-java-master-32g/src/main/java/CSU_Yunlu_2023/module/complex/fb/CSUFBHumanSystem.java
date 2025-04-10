package CSU_Yunlu_2023.module.complex.fb;

import CSU_Yunlu_2023.debugger.DebugHelper;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.MessageUtil;
import adf.core.agent.communication.standard.bundle.StandardMessage;
import adf.core.agent.communication.standard.bundle.StandardMessagePriority;
import adf.core.agent.communication.standard.bundle.information.MessageAmbulanceTeam;
import adf.core.agent.communication.standard.bundle.information.MessageCivilian;
import adf.core.agent.communication.standard.bundle.information.MessageFireBrigade;
import adf.core.agent.communication.standard.bundle.information.MessagePoliceForce;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.module.AbstractModule;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.PathPlanning;
import rescuecore2.standard.entities.AmbulanceTeam;
import rescuecore2.standard.entities.Civilian;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.worldmodel.EntityID;

import java.io.Serializable;
import java.util.*;

public class CSUFBHumanSystem extends AbstractModule {

    private Clustering clustering;
    private PathPlanning pathPlanning;
    private Set<Civilian> allCivilian;

    public Map<EntityID, CSUFBHuman> getAllHuman() {
        return allHuman;
    }

    private Map<EntityID, CSUFBHuman> allHuman;
    private Set<Civilian> targetsCivilian;
    private Set<CSUFBHuman> firstLevelCivilian;
    private Set<CSUFBHuman> topLevelCivilian;
    private Set<CSUFBHuman> secondLevelCivilian;
    private Set<CSUFBHuman> targetHumans;
    private Set<CSUFBHuman> removeHuman;
    private Set<Civilian> sendCivilian;
    private Set<Civilian> notNeedSendCivilian;
    public CSUFBHumanSystem(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData, Clustering clustering, PathPlanning pathPlanning) {
        super(ai, wi, si, moduleManager, developData);
        this.allCivilian = new HashSet<>();
        this.allHuman = new HashMap<>();
        this.targetHumans = new HashSet<>();
        this.targetsCivilian = new HashSet<>();
        this.clustering = clustering;
        this.pathPlanning = pathPlanning;
        this.removeHuman = new HashSet<>();
        this.topLevelCivilian = new HashSet<>();
        this.firstLevelCivilian = new HashSet<>();
        this.secondLevelCivilian = new HashSet<>();
        this.sendCivilian = new HashSet<>();
        this.notNeedSendCivilian = new HashSet<>();
    }

    @Override
    public AbstractModule calc() {
        return null;
    }

    @Override
    public AbstractModule updateInfo(MessageManager messageManager) {
        if (this.getCountUpdateInfo() >= 2) {
            return this;
        }
        targetsCivilian.clear();
        targetHumans.clear();
        topLevelCivilian.clear();
        firstLevelCivilian.clear();
        secondLevelCivilian.clear();
        Set<EntityID> changed = this.agentInfo.getChanged().getChangedEntities();
        for (EntityID entityID : changed) {
            StandardEntity entity = this.worldInfo.getEntity(entityID);
            if (entity instanceof Human && !this.allHuman.containsKey(entityID)) {
                Human human = (Human) entity;
                if ((human.isBuriednessDefined() && human.getBuriedness() > 0) && (human.isHPDefined() && human.getHP() > 0)) {
                    CSUFBHuman csufbHuman = new CSUFBHuman(human, agentInfo, worldInfo, scenarioInfo, pathPlanning, clustering);
                    this.allHuman.put(entityID, csufbHuman);
                }
            }
            if (entity instanceof Civilian){
                Civilian civilian = (Civilian) entity;
                for (EntityID entityID1 : changed){
                    StandardEntity standardEntity = this.worldInfo.getEntity(entityID);
                    if (standardEntity instanceof AmbulanceTeam && ((AmbulanceTeam) standardEntity).getPosition().equals(civilian.getPosition())){
                        this.notNeedSendCivilian.add(civilian);
                    }
                }
                if (civilian.isBuriednessDefined()&&civilian.getBuriedness()==0&& civilian.isDamageDefined() && civilian.getDamage()>0&&civilian.isHPDefined()&&civilian.getHP()>0){
                    if (this.notNeedSendCivilian.contains(civilian)){
                        continue;
                    }
                    this.sendCivilian.add(civilian);
                }
            }
            if (entity instanceof AmbulanceTeam){
                for (Civilian civilian : this.sendCivilian){
                    if (this.notNeedSendCivilian.contains(civilian)){
                        continue;
                    }
                    if (civilian.isBuriednessDefined()&&civilian.getBuriedness()==0&& civilian.isDamageDefined() && civilian.getDamage()>0&&civilian.isHPDefined()&&civilian.getHP()>0){
                        messageManager.addMessage(new MessageCivilian(false, StandardMessagePriority.HIGH,civilian));
                    }
                }
                sendCivilian.clear();
            }
        }

        for (CommunicationMessage message : messageManager
                .getReceivedMessageList(MessageCivilian.class, MessageFireBrigade.class, MessagePoliceForce.class, MessageAmbulanceTeam.class)) {
            StandardEntity entity = null;
            entity = MessageUtil.reflectMessage(worldInfo,
                    (StandardMessage) message);
            if ((entity instanceof Human) && !this.allHuman.containsKey(entity.getID())) {
                Human human = (Human) entity;
                if ((human.isBuriednessDefined() && human.getBuriedness() > 0) && (human.isHPDefined() && human.getHP() > 0)) {
                    CSUFBHuman csufbHuman = new CSUFBHuman(human, agentInfo, worldInfo, scenarioInfo, pathPlanning, clustering);
                    this.allHuman.put(entity.getID(), csufbHuman);
                }
            }
        }
        //

        EntityID position = this.agentInfo.getPosition();
        for (CSUFBHuman csufbHuman : this.allHuman.values()) {
            Human human = csufbHuman.getHuman();
            if ((human.isBuriednessDefined() && human.getBuriedness() <= 0) || (human.isHPDefined() && human.getHP() <= 0)) {
                removeHuman.add(csufbHuman);
            } else if (human.getPosition().getValue() == position.getValue() && !changed.contains(human.getID())) {
                removeHuman.add(csufbHuman);
            }
        }
        for (CSUFBHuman csufbHuman : removeHuman) {
            this.allHuman.remove(csufbHuman.getHuman().getID());
        }
        int indexOfAgent = clustering.getClusterIndex(agentInfo.getID());
        for (CSUFBHuman csufbHuman : this.allHuman.values()) {
            csufbHuman.updateInfo(messageManager);

            if (csufbHuman.isReachable() && csufbHuman.isCanRescue()){
                if ((agentInfo.getTime()<100&&!(csufbHuman.getHuman() instanceof Civilian)) ||
                        ((csufbHuman.getHuman() instanceof Civilian)&&!(csufbHuman.getDeadTime()>200))){
                    if (csufbHuman.getDistanceToMe()<50000&&csufbHuman.getDeadTime()<60){
                        this.topLevelCivilian.add(csufbHuman);
                    }
                    if (csufbHuman.getDeadTime()<30&&csufbHuman.getDistanceToMe()<240000){
                        this.firstLevelCivilian.add(csufbHuman);
                    }else if(csufbHuman.getDeadTime()<60&&csufbHuman.getDistanceToMe()<120000){
                        this.secondLevelCivilian.add(csufbHuman);
                    }
                }
                this.targetHumans.add(csufbHuman);
            }
        }
        if (DebugHelper.DEBUG_MODE) {
            List<Integer> elements = new ArrayList<>();

            for (CSUFBHuman csufbHuman : this.allHuman.values()) {
                elements.add(csufbHuman.getHuman().getID().getValue());
            }
            DebugHelper.VD_CLIENT.drawAsync(agentInfo.getID().getValue(), "ATPerceiveHumans", (Serializable) elements);
        }
        if (DebugHelper.DEBUG_MODE) {
            List<Integer> elements = new ArrayList<>();

            for (CSUFBHuman csufbHuman : this.targetHumans) {
                elements.add(csufbHuman.getHuman().getID().getValue());
            }
            DebugHelper.VD_CLIENT.drawAsync(agentInfo.getID().getValue(), "ATTargetHumans", (Serializable) elements);
        }
        return this;
    }

    public EntityID getTargetByDistance() {
        EntityID target = null;
        TreeSet<CSUFBHuman> objects3 = new TreeSet<>(
                ((o1, o2) -> Double.compare(o2.getValue(),o1.getValue())));
        objects3.addAll(this.topLevelCivilian);
        if (!objects3.isEmpty()){
            target = objects3.first().getHuman().getID();
            return target;
        }
        TreeSet<CSUFBHuman> objects = new TreeSet<>(
                ((o1, o2) -> Double.compare(o2.getValue(),o1.getValue())));
        objects.addAll(this.firstLevelCivilian);
        if (!objects.isEmpty()){
            target = objects.first().getHuman().getID();
            return target;
        }
        TreeSet<CSUFBHuman> objects1 = new TreeSet<>(
                ((o1, o2) -> Double.compare(o2.getValue(),o1.getValue())));
        objects1.addAll(this.secondLevelCivilian);
        if (!objects1.isEmpty()){
            target = objects1.first().getHuman().getID();
        }
        TreeSet<CSUFBHuman> objects2 = new TreeSet<>(
                ((o1, o2) -> Double.compare(o2.getValue(),o1.getValue())));
        objects2.addAll(this.targetHumans);
        if (!objects2.isEmpty()){
            target = objects2.first().getHuman().getID();
        }
        return target;
    }


}


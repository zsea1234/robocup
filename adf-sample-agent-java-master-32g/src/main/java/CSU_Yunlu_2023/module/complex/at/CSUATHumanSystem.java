package CSU_Yunlu_2023.module.complex.at;

import CSU_Yunlu_2023.CSUConstants;
import CSU_Yunlu_2023.debugger.DebugHelper;
import CSU_Yunlu_2023.util.ambulancehelper.CSUSelectorTargetByDis;
import CSU_Yunlu_2023.world.CSUWorldHelper;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.MessageUtil;
import adf.core.agent.communication.standard.bundle.StandardMessage;
import adf.core.agent.communication.standard.bundle.StandardMessagePriority;
import adf.core.agent.communication.standard.bundle.centralized.CommandPolice;
import adf.core.agent.communication.standard.bundle.information.MessageAmbulanceTeam;
import adf.core.agent.communication.standard.bundle.information.MessageCivilian;
import adf.core.agent.communication.standard.bundle.information.MessageFireBrigade;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.module.AbstractModule;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.PathPlanning;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import java.io.Serializable;
import java.util.*;

public class CSUATHumanSystem extends AbstractModule {

    private Clustering clustering;
    private PathPlanning pathPlanning;
    private Map<Civilian,Integer> allCivilian;
    private Set<Civilian> targetsCivilian;
    private CSUSelectorTargetByDis csuSelectorTargetByDis;
    private MessageManager messageManager;
    private Set<Building> notNeedBuilding;
    private CSUWorldHelper csuWorldHelper;
    private Set<Civilian> sendCivilian;
    private Set<EntityID> sendCommandPolice;
    private int maxDistance;
    public CSUATHumanSystem(CSUWorldHelper csuWorldHelper,AgentInfo ai, WorldInfo wi,
                            ScenarioInfo si, ModuleManager moduleManager,
                            DevelopData developData, Clustering clustering, PathPlanning pathPlanning) {
        super(ai, wi, si, moduleManager, developData);
        this.allCivilian = new HashMap<>();
        this.targetsCivilian = new HashSet<>();
        this.notNeedBuilding = new HashSet<>();
        this.clustering = clustering;
        this.pathPlanning = pathPlanning;
        this.csuSelectorTargetByDis = new CSUSelectorTargetByDis(ai, wi, si, moduleManager, developData);
        this.sendCivilian = new HashSet<>();
        this.sendCommandPolice = new HashSet<>();
        this.csuWorldHelper = csuWorldHelper;
        this.maxDistance =(int)(
                Math.sqrt(csuWorldHelper.getMapHeight()*csuWorldHelper.getMapWidth()/si.getScenarioAgentsAt())*1.5
        );
    }

    @Override
    public AbstractModule calc() {
        return null;
    }

    @Override
    public AbstractModule updateInfo(MessageManager messageManager) {
        if (this.messageManager==null){
            this.messageManager = messageManager;
        }
        if (this.getCountUpdateInfo()>=2){
            return this;
        }
        this.targetsCivilian.clear();
        this.notNeedBuilding.clear();
        for (EntityID entityID : this.csuWorldHelper.getCiviliansSeen()) {
            Civilian civilian = (Civilian) worldInfo.getEntity(entityID);
            if (civilian.isHPDefined() && civilian.getHP() > 0 ) {
                StandardEntity position = worldInfo.getEntity(civilian.getPosition());
                if (this.agentInfo.getPosition().equals(position.getID())){
                    continue;
                }
                if (!(position instanceof AmbulanceTeam) && !(position instanceof Refuge) && position instanceof Building) {
                    Building building = (Building) position;
                    if(building.isBrokennessDefined() && building.getBrokenness()>0){
                        List<EntityID> path = this.pathPlanning.setFrom(this.agentInfo.getPosition()).setDestination(position.getID()).calc().getResult();
                        if (path == null || path.size() ==0 ){
                            this.sendCommandPolice.add(position.getID());
                        }
                    }
                }
            }
        }
        Set<EntityID> changed = this.agentInfo.getChanged().getChangedEntities();
        for (EntityID entityID : changed){
            StandardEntity entity = this.worldInfo.getEntity( entityID );
            if (entity instanceof Civilian){
                this.allCivilian.put((Civilian) entity,this.agentInfo.getTime());
            }
            if (entity instanceof Civilian){
                Civilian civilian = (Civilian) entity;
                if (civilian.isBuriednessDefined()&&civilian.getBuriedness()>0&&civilian.isHPDefined()&&civilian.getHP()>0){
                    Boolean flag = true;
                    for (EntityID entityID1 : changed){
                        StandardEntity standardEntity = this.worldInfo.getEntity(entityID);
                        if (standardEntity instanceof FireBrigade && ((FireBrigade) standardEntity).getPosition().equals(civilian.getPosition())){
                            flag =false;
                        }
                    }
                    if (flag){
                        this.sendCivilian.add(civilian);
                    }
                }
            }
            if (entity instanceof FireBrigade){
                for (Civilian civilian : this.sendCivilian){
                    if (civilian.isBuriednessDefined()&&civilian.getBuriedness()>0&&civilian.isHPDefined()&&civilian.getHP()>0){
                        messageManager.addMessage(new MessageCivilian(false, StandardMessagePriority.HIGH,civilian));
                    }
                }
                sendCivilian.clear();
            }else if (entity instanceof PoliceForce){
                for (EntityID entityID1 : this.sendCommandPolice){
                    messageManager.addMessage( new CommandPolice
                            ( false, StandardMessagePriority.HIGH,null,
                            entityID1, CommandPolice.ACTION_CLEAR ) );
                }
                this.sendCommandPolice.clear();
            }
        }

        for ( CommunicationMessage message : messageManager
                .getReceivedMessageList(MessageCivilian.class) ) {
            StandardEntity entity = null;
            entity = MessageUtil.reflectMessage( worldInfo,
                    (StandardMessage) message );

            if (entity instanceof Civilian ){
                Civilian civilian = (Civilian) entity;
                if (civilian.isPositionDefined()){
                    this.allCivilian.put((Civilian) entity,this.agentInfo.getTime());
                }
            }
        }

        EntityID position = this.agentInfo.getPosition();
        Set<Civilian> removeCivilian = new HashSet<>();
        for (Civilian civilian : this.allCivilian.keySet()){
            if(civilian.getPosition().getValue() == position.getValue() && !changed.contains(civilian.getID())){
                removeCivilian.add(civilian);
            }
        }

        for (CommunicationMessage communicationMessage: messageManager.getReceivedMessageList(MessageAmbulanceTeam.class)){
            MessageAmbulanceTeam ma = (MessageAmbulanceTeam) communicationMessage;
            EntityID target = ma.getTargetID();
            AmbulanceTeam ambulanceTeam = (AmbulanceTeam)this.worldInfo.getEntity(ma.getAgentID());
            StandardEntity standardEntity = this.worldInfo.getEntity(target);
            if (standardEntity instanceof Building){
                Building building = (Building) standardEntity;
                double distance1 = this.pathPlanning.setFrom(ambulanceTeam.getPosition()).setDestination(building.getID()).calc().getDistance();
                double distance2 = this.pathPlanning.setFrom(this.agentInfo.getPosition()).setDestination(building.getID()).calc().getDistance();
                if (distance2>distance1){
                    this.notNeedBuilding.add(building);
                }
            }
            if (ma.getAction() == MessageAmbulanceTeam.ACTION_LOAD ){
                Civilian civilian = (Civilian) this.worldInfo.getEntity(ma.getTargetID());
                if (civilian !=null && civilian.isPositionDefined()){
                    removeCivilian.add(civilian);
                }
            }
        }
        for (Civilian civilian : removeCivilian){
            this.allCivilian.remove(civilian);
        }
        int indexOfAgent = clustering.getClusterIndex(agentInfo.getID());

        for (Civilian civilian : this.allCivilian.keySet()){
            if (civilian.isHPDefined() && civilian.getHP()>0 ){
                StandardEntity civilianPosition = this.worldInfo.getPosition(civilian);

                if (civilianPosition instanceof Building){
                    Building building = (Building) civilianPosition;
                    if (
                            (
                                    civilian.isDamageDefined()
                                            &&civilian.getDamage()>0||building.isBrokennessDefined()&&building.getBrokenness()>0
                            )
                    ){





                        if (civilian.isBuriednessDefined() && civilian.getBuriedness()==0 || (civilian.getBuriedness()<60 && this.shouldLoad(civilian))) {
                            if (!(civilianPosition instanceof AmbulanceTeam) && !(civilianPosition instanceof Refuge)) {
                                int indexOfHuman = clustering.getClusterIndex(civilianPosition.getID());
                                if (indexOfAgent == indexOfHuman) {
                                    this.targetsCivilian.add(civilian);
                                } else {
                                    double distance = this.pathPlanning.setFrom(this.agentInfo.getPosition()).setDestination(civilianPosition.getID()).calc().getDistance();
                                    if (isReachable(civilian)) {
                                        if (distance  < this.maxDistance) {
                                            this.targetsCivilian.add(civilian);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Set<Civilian> hasAmbulanceTeamCivilian = new HashSet<>();
        for (Civilian civilian : this.targetsCivilian){
            if (this.hasAmbulanceTeam(civilian)&&!this.agentInfo.getPosition().equals(civilian.getPosition())){
               hasAmbulanceTeamCivilian.add(civilian);
            }
        }
        this.targetsCivilian.removeAll(hasAmbulanceTeamCivilian);

        if (DebugHelper.DEBUG_MODE) {
            List<Integer> elements = new ArrayList<>();

            for (Human human : this.allCivilian.keySet()) {
                elements.add(human.getID().getValue());
            }
            DebugHelper.VD_CLIENT.drawAsync(agentInfo.getID().getValue(), "ATPerceiveHumans", (Serializable) elements);
        }
        if (DebugHelper.DEBUG_MODE) {
            List<Integer> elements = new ArrayList<>();

            for (Human human : this.targetsCivilian) {
                elements.add(human.getID().getValue());
            }
            DebugHelper.VD_CLIENT.drawAsync(agentInfo.getID().getValue(), "ATTargetHumans", (Serializable) elements);
        }
        return this;
    }


    public Boolean hasFireBrigade(Civilian civilian){
        for(StandardEntity standardEntity : this.worldInfo.getEntitiesOfType(StandardEntityURN.FIRE_BRIGADE)){
            FireBrigade fireBrigade = (FireBrigade) standardEntity;
            if (fireBrigade.getPosition().getValue() == civilian.getPosition().getValue()){
                return true;
            }
        }
        return false;
    }

    public Boolean shouldLoad(Civilian civilian){
        int numOfFireBrigade = 0;
        int rescueTime = 0;
        int arriveTime = 0;
        for (CommunicationMessage cm : messageManager.getReceivedMessageList(MessageFireBrigade.class)){
            MessageFireBrigade messageFireBrigade = (MessageFireBrigade) cm;
            if (messageFireBrigade.getAction()==MessageFireBrigade.ACTION_RESCUE&&messageFireBrigade.getTargetID().equals(civilian.getID())){
                numOfFireBrigade++;
            }
        }

        double distance = this.pathPlanning.setFrom(this.agentInfo.getPosition()).setDestination(civilian.getID()).calc().getDistance();
        arriveTime = (int)Math.ceil(distance/CSUConstants.MEAN_VELOCITY_OF_MOVING);
        if (numOfFireBrigade !=0 && civilian.isBuriednessDefined()){
            rescueTime = civilian.getBuriedness() / numOfFireBrigade;
        }else {
            return false;
        }
        if (rescueTime-arriveTime<10){
            return true;
        }

        return false;
    }

    public Boolean hasAmbulanceTeam(Civilian civilian){
        int numOfCivilian = 0;
        int numOfAmbulanceTeam = 0;
        for (Civilian civilian1 : this.targetsCivilian){
            if (civilian1.getPosition().equals(civilian.getPosition())){
                numOfCivilian++;
            }
        }
        for(StandardEntity standardEntity : this.worldInfo.getEntitiesOfType(StandardEntityURN.AMBULANCE_TEAM)){
            AmbulanceTeam ambulanceTeam = (AmbulanceTeam) standardEntity;
            if (ambulanceTeam.getID().equals(this.agentInfo.getID())){
                continue;
            }
            if (ambulanceTeam.getPosition().getValue() == civilian.getPosition().getValue()){
                numOfAmbulanceTeam++;
            }
        }
        if (numOfAmbulanceTeam>=numOfCivilian){
            return true;
        }
        return false;
    }
    public EntityID getTargetByDistance(){
        EntityID target = null;
        TreeSet<Civilian> objects = new TreeSet<>(
                (
                        (o1, o2) ->
                                Double.compare(
                                        this.worldInfo.getDistance(o1.getID(),this.agentInfo.getID()), this.worldInfo.getDistance(o2.getID(),this.agentInfo.getID())
                                )
                )
        );
        objects.addAll(this.targetsCivilian);

        int repeat = 0;
        for (Civilian civilian : objects){
            StandardEntity position = this.worldInfo.getPosition(civilian);

            if (this.isReachable(civilian)&&repeat<50){
                target = civilian.getID();
                break;
            }
            repeat--;
        }
        return target;
    }

    public EntityID getTargetByTimeAndBuriedness(){
        Set<Civilian> targetSet = new HashSet<>();
        for (Civilian civilian : this.allCivilian.keySet()){
            if (civilian.isBuriednessDefined()&&civilian.getBuriedness()>0){
                int numOfFireBrigade = 0;
                int maxTime = 1;
                for (StandardEntity standardEntity :this.worldInfo.getEntitiesOfType(StandardEntityURN.FIRE_BRIGADE)){
                    FireBrigade fireBrigade = (FireBrigade) standardEntity;
                    if (fireBrigade.getPosition().equals(civilian.getPosition())){
                        numOfFireBrigade++;
                    }
                }
                if (numOfFireBrigade!=0){
                    maxTime = numOfFireBrigade;
                }
                if (this.allCivilian.get(civilian)+(civilian.getBuriedness()/maxTime)<this.agentInfo.getTime()){
                    targetSet.add(civilian);
                }
            }
        }
        EntityID target = null;
        TreeSet<Civilian> objects = new TreeSet<>(
                ((o1, o2) ->Double.compare(this.worldInfo.getDistance(o1.getID(),this.agentInfo.getID()), this.worldInfo.getDistance(o2.getID(),this.agentInfo.getID()))));
        objects.addAll(targetSet);
        int repeat = 0;
        for (Civilian civilian : objects){
            StandardEntity position = this.worldInfo.getPosition(civilian);

            if (this.isReachable(civilian)&&repeat<50){
                target = civilian.getID();
                break;
            }
            repeat--;
        }
        return target;
    }

    public Boolean isReachable(Civilian civilian){
        EntityID from = this.agentInfo.getPosition();
        EntityID destination = civilian.getPosition();
        if (from.equals(destination)){
            return true;
        }
        List<EntityID> result = this.pathPlanning.setFrom(from).setDestination(civilian.getID()).calc().getResult();
        return result != null;
    }

    public Map<String, Double> calculateTime(Civilian civilian, EntityID entityID){
        Map<String, Double> timeMap = new HashMap<>();
        int rescumeNumbers = 0;
        double arrivingTime = this.pathPlanning.getDistance(civilian.getID(), entityID) / CSUConstants.MEAN_VELOCITY_OF_MOVING;
        Collection<StandardEntity> fbs = worldInfo.getEntitiesOfType(StandardEntityURN.FIRE_BRIGADE);
        for (StandardEntity fb : fbs){
            if(worldInfo.getPosition(fb.getID()).equals(worldInfo.getPosition(civilian.getID()))){
                rescumeNumbers++;
            }
        }
        double discoveriedTime;
        if (rescumeNumbers !=0){
            discoveriedTime = Math.ceil(civilian.getBuriedness() / rescumeNumbers);
        }else {
            discoveriedTime = civilian.getBuriedness();
        }
        double movingTime = this.pathPlanning.getDistance(civilian.getPosition(), csuSelectorTargetByDis.findNearestRefuge(civilian.getPosition())) / CSUConstants.MEAN_VELOCITY_OF_MOVING;
        double remainHp = civilian.getHP() - civilian.getDamage() * (arrivingTime + movingTime);
        timeMap.put("arrivingTime", arrivingTime);
        timeMap.put("discoveriedTime", discoveriedTime);
        timeMap.put("remainHp", remainHp);
        return timeMap;
    }
}


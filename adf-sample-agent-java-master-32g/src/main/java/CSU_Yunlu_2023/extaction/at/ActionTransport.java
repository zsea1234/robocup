package CSU_Yunlu_2023.extaction.at;


import CSU_Yunlu_2023.CSUConstants;
import CSU_Yunlu_2023.LogHelper;
import adf.core.agent.action.Action;
import adf.core.agent.action.ambulance.ActionLoad;
import adf.core.agent.action.ambulance.ActionUnload;
import adf.core.agent.action.common.ActionMove;
import adf.core.agent.action.common.ActionRest;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.information.MessageFireBrigade;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.extaction.ExtAction;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.PathPlanning;
import rescuecore2.config.NoSuchConfigOptionException;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static rescuecore2.standard.entities.StandardEntityURN.*;

/**
 * 修改calc calcRescue calcUnload策略
 */
public class ActionTransport extends ExtAction {

    private PathPlanning pathPlanning;
    private int thresholdRest;
    private int kernelTime;
    private EntityID target;
    private ExtAction actionExtMove;
    private Clustering clustering;
    private LogHelper logHelper;
    private int unloadTime = 0;
    private MessageManager messageManager;
    private static final boolean debug = false;

    public ActionTransport(AgentInfo agentInfo, WorldInfo worldInfo, ScenarioInfo scenarioInfo,
                           ModuleManager moduleManager, DevelopData developData) {
        super(agentInfo, worldInfo, scenarioInfo, moduleManager, developData);

        this.target = null;
        this.thresholdRest = developData.getInteger("ActionTransport.rest", 100);
        switch (scenarioInfo.getMode()) {
            case PRECOMPUTATION_PHASE:
                this.pathPlanning = moduleManager.getModule("DefaultExtActionClear.PathPlanning",
                        "adf.impl.module.algorithm.DijkstraPathPlanning");
                this.actionExtMove = moduleManager.getExtAction("TacticsFireBrigade.ActionExtMove", "adf.impl.extaction.DefaultExtActionMove");
                break;
            case PRECOMPUTED:
                this.pathPlanning = moduleManager.getModule("DefaultExtActionClear.PathPlanning",
                        "adf.impl.module.algorithm.DijkstraPathPlanning");
                this.actionExtMove = moduleManager.getExtAction("TacticsFireBrigade.ActionExtMove", "adf.impl.extaction.DefaultExtActionMove");
                break;
            case NON_PRECOMPUTE:
                this.pathPlanning = moduleManager.getModule("DefaultExtActionClear.PathPlanning",
                        "adf.impl.module.algorithm.DijkstraPathPlanning");
                this.actionExtMove = moduleManager.getExtAction("TacticsFireBrigade.ActionExtMove", "adf.impl.extaction.DefaultExtActionMove");
                break;
        }

        logHelper = new LogHelper("at_log/actionTransport",agentInfo,"ActionTransport");
    }

    public ExtAction precompute(PrecomputeData precomputeData) {
        super.precompute(precomputeData);
        if (this.getCountPrecompute() >= 2) {
            return this;
        }
        this.pathPlanning.precompute(precomputeData);
        try {
            this.kernelTime = this.scenarioInfo.getKernelTimesteps();
        } catch (NoSuchConfigOptionException e) {
            this.kernelTime = -1;
        }
        return this;
    }

    public ExtAction resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        if (this.getCountResume() >= 2) {
            return this;
        }
        this.pathPlanning.resume(precomputeData);
        try {
            this.kernelTime = this.scenarioInfo.getKernelTimesteps();
        } catch (NoSuchConfigOptionException e) {
            this.kernelTime = -1;
        }
        return this;
    }

    public ExtAction preparate() {
        super.preparate();
        if (this.getCountPreparate() >= 2) {
            return this;
        }
        this.pathPlanning.preparate();
        try {
            this.kernelTime = this.scenarioInfo.getKernelTimesteps();
        } catch (NoSuchConfigOptionException e) {
            this.kernelTime = -1;
        }
        return this;
    }

    public ExtAction updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if (this.messageManager==null){
            this.messageManager = messageManager;
        }
        if (this.getCountUpdateInfo() >= 2) {
            return this;
        }
        this.pathPlanning.updateInfo(messageManager);
        return this;
    }

    @Override// 设置目标，通过HumanDetector传入的target来进行设置目标
    public ExtAction setTarget(EntityID target) {
        this.target = null;
        if (target != null) {
            StandardEntity entity = this.worldInfo.getEntity(target);
            if (entity instanceof Human || entity instanceof Area) {
                this.target = target;
                return this;
            }
        }
        return this;
    }


    @Override
    public ExtAction calc() {
        logHelper.writeAndFlush("========================transport start=======================");
        this.result = null;
        AmbulanceTeam me = (AmbulanceTeam) this.agentInfo.me();
        Human transportHuman = this.agentInfo.someoneOnBoard();

        if (transportHuman != null) {
            logHelper.writeAndFlush("----背着人("+transportHuman+"),计算unload----");
            this.result = this.calcUnload(me, this.pathPlanning, transportHuman, this.target);
        }
        if ((this.result == null) && this.needRest(me)) {
            logHelper.writeAndFlush("----需要休息----");
            EntityID areaID = this.convertArea(this.target);//获得当前目标所在Area
            ArrayList<EntityID> targets = new ArrayList<>();
            if (areaID != null) {
                targets.add(areaID);
            }
            this.result = this.calcRefugeAction(me, this.pathPlanning, targets, false);
            if(result == null) {
                logHelper.writeAndFlush("没找到避难所。。。。");
                if(CSUConstants.DEBUG_AT_SEARCH && debug){
                    System.out.println("要回避难所但是找不到避难所");
                }
            }
            logHelper.writeAndFlush("前往休息");
        }

        if ((this.result == null) && this.target != null) {
            logHelper.writeAndFlush("----有目标，计算救人----");
            this.result = this.calcRescue(me, this.pathPlanning, this.target);
        }
        logHelper.writeAndFlush("========================transport   end=======================");
        return this;
    }

    protected boolean willDiedWhenRscued(Human human) {
        if (human.isBuriednessDefined() && human.getBuriedness() == 0)
            return false;

        int deadtime = estimatedDeathTime(human.getHP(), human.getDamage(), agentInfo.getTime());
        int resuceTime = 10000;
        if (deadtime > resuceTime)
            return true;

        return false;
    }

    public int estimatedDeathTime(int hp, double dmg, int updatetime) {
        int agenttime = 1000;
        int count = agenttime - updatetime;
        if ((count <= 0) || (dmg == 0.0D)) {
            return hp;
        }
        double kbury = 3.5E-05D;
        double kcollapse = 0.00025D;
        double darsadbury = -0.0014D * updatetime + 0.64D;
        double burydamage = dmg * darsadbury;
        double collapsedamage = dmg - burydamage;

        while (count > 0) {
            int time = agenttime - count;

            burydamage += kbury * burydamage * burydamage + 0.11D;
            collapsedamage += kcollapse * collapsedamage * collapsedamage + 0.11D;
            dmg = burydamage + collapsedamage;
            count--;
            hp = (int) (hp - dmg);

            if (hp <= 0)
                return time;
        }
        return 1000;
    }

    private Action calcRescue(AmbulanceTeam agent, PathPlanning pathPlanning, EntityID target) {
        StandardEntity targetEntity = this.worldInfo.getEntity(target);

        if (targetEntity == null) {
            logHelper.writeAndFlush("calcRescue:当前target的Entity为null");
            return null;
        }

        EntityID agentPosition = agent.getPosition();

        if (targetEntity instanceof Human) {
            Human human = (Human) targetEntity;

            if (!human.isPositionDefined()) {
                if(CSUConstants.DEBUG_AT_SEARCH && debug){
                    System.out.println(agent.getID()+"不知道"+human.getID()+"的位置，不救");
                }
                logHelper.writeAndFlush("calcRescue:当前target的位置未知");
                return null;
            }

            if (human.isHPDefined() && human.getHP() == 0 ) {
                if(CSUConstants.DEBUG_AT_SEARCH && debug){
                    System.out.println(agent.getID()+"觉得"+human.getID()+"已经死了，不救");
                }
                logHelper.writeAndFlush("calcRescue:当前target的已经死了");
                return null;
            }

            EntityID targetPosition = worldInfo.getPosition(human).getID();
            logHelper.writeAndFlush("当前位置为:"
                    +agent.getPosition()+",目标("+human.getID()+")位置为:"+targetPosition);


            if (agentPosition.getValue() == targetPosition.getValue()) {
                logHelper.writeAndFlush("已经走到目标位置");
                if (human.isBuriednessDefined() && human.getBuriedness() > 0) {
                    logHelper.writeAndFlush("calcRescue: human:" + human.getID() +"还被埋着，不救");
                    if (targetEntity instanceof Civilian){
                        Civilian civilian = (Civilian) targetEntity;
                        if (this.shouldWait(civilian)){
                            return new ActionRest();
                        }
                    }
                    return null;
                } else if (human.getStandardURN() == CIVILIAN) {
                    if (CSUConstants.DEBUG_AT_SEARCH && debug) {
                        System.out.println("[第"+agentInfo.getTime()+"回合]   "+agent.getID()+"觉得"+human.getID()+"已经挖出来了，背起来");
                    }
                    logHelper.writeAndFlush("觉得"+human.getID()+"已经挖出来了，背起来");
                    return new ActionLoad(human.getID());
                }

            } else {
                List<EntityID> path = pathPlanning.setFrom(agentPosition).setDestination(targetPosition).calc().getResult();
                logHelper.writeAndFlush("还未走到目标"+human.getID()+"位置");
                if(CSUConstants.DEBUG_AT_SEARCH && debug){
                    System.out.println("[第"+agentInfo.getTime()+"回合]   "+agent.getID()+"觉得还没走到"+human.getID()+"的位置，继续走");
                }
                if (path != null && path.size() > 0) {
                    Action action = getMoveAction(path);
                    if(CSUConstants.DEBUG_AT_SEARCH && debug){
                        if(path!= null && action == null){
                            System.out.println("[第"+agentInfo.getTime()+"回合]   "+agent.getID()+":way为:"+path+",action却为null");
                        }
                    }
                    logHelper.writeAndFlush("走到目标位置,way:"+path+",action:"+action);
                    return action;
                }
                if(CSUConstants.DEBUG_AT_SEARCH && debug){
                    System.out.println("[第"+agentInfo.getTime()+"回合]   "+agent.getID()+"觉得还没走到"+human.getID()+"的位置，但是没路到");
                }
            }
            logHelper.writeAndFlush("没有路到达目标("+human.getID()+")");
            return null;
        }

        if (targetEntity.getStandardURN() == BLOCKADE) {
            Blockade blockade = (Blockade) targetEntity;
            if (blockade.isPositionDefined()) {//如果已知位置，赋给targetEntity
                targetEntity = this.worldInfo.getEntity(blockade.getPosition());
            }
        }
        if (targetEntity instanceof Area) {
            List<EntityID> path = pathPlanning.getResult(agentPosition, targetEntity.getID());
            if (path != null && path.size() > 0) {
                this.result = getMoveAction(path);
            }
        }
        return null;
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
    public Boolean shouldWait(Civilian civilian){
        int numOfFireBrigade = 0;
        for (CommunicationMessage cm : messageManager.getReceivedMessageList(MessageFireBrigade.class)){
            MessageFireBrigade messageFireBrigade = (MessageFireBrigade) cm;
            if (messageFireBrigade.getAction()==MessageFireBrigade.ACTION_RESCUE&&messageFireBrigade.getTargetID().equals(civilian.getID())){
                numOfFireBrigade++;
            }
        }
        if (numOfFireBrigade!=0&&civilian.isBuriednessDefined()&&civilian.getBuriedness()/numOfFireBrigade<10){
            return true;
        }
        return false;
    }
    private Action calcUnload(AmbulanceTeam agent, PathPlanning pathPlanning, Human transportHuman, EntityID targetID) {
        if (transportHuman == null) {
            return null;
        }
        if (transportHuman.isHPDefined() && transportHuman.getHP() == 0) {
            if (this.unloadTime>=6){
                this.unloadTime =0;
                logHelper.writeAndFlush("因为 "+transportHuman.getID()+" hp=0，unload");
                return new ActionUnload();
            }else {
                this.unloadTime++;
            }
        }
        EntityID agentPosition = agent.getPosition();
        StandardEntity position = this.worldInfo.getEntity(agentPosition);
        logHelper.writeAndFlush("背上的人:"+transportHuman+"damage:"+transportHuman.isDamageDefined()+","+transportHuman.getDamage()+"");
        if (position != null && position.getStandardURN() == REFUGE) {
//            //6.23
//            Refuge refuge = (Refuge) position;
//            if(refuge.getOccupiedBeds() == refuge.getBedCapacity()){
//                //
//
//            }
            logHelper.writeAndFlush("因为 "+transportHuman.getID()+" 当前在refuge，unload");
            return new ActionUnload();//放下
        } else {
            pathPlanning.setFrom(agentPosition);
            if ((getVaildRefuges(this.worldInfo.getEntityIDsOfType(REFUGE))==null)||(this.worldInfo.getEntityIDsOfType(REFUGE).isEmpty())){
                pathPlanning.setDestination(this.worldInfo.getEntityIDsOfType(REFUGE));
            }else {
                pathPlanning.setDestination(getVaildRefuges(this.worldInfo.getEntityIDsOfType(REFUGE)));
            }
            List<EntityID> path = pathPlanning.calc().getResult();
            if (path != null && path.size() > 0) {
                return getMoveAction(path);
            }else{
                logHelper.writeAndFlush("没有路到refuge.");
            }
        }

        logHelper.writeAndFlush("calcUnload异常，damage:"+transportHuman.getDamage());
        return null;
    }

    private boolean needRest(Human agent) {
        int hp = agent.getHP();
        int damage = agent.getDamage();
        if (hp == 0 || damage == 0) {
            return false;
        }
        int activeTime = (hp / damage) + ((hp % damage) != 0 ? 1 : 0);
        if (this.kernelTime == -1) {
            try {
                this.kernelTime = this.scenarioInfo.getKernelTimesteps();
            } catch (NoSuchConfigOptionException e) {
                this.kernelTime = -1;
            }
        }
        return damage >= this.thresholdRest || (activeTime + this.agentInfo.getTime()+20) < this.kernelTime;
    }

    private EntityID convertArea(EntityID targetID) {
        StandardEntity entity = this.worldInfo.getEntity(targetID);
        if (entity == null) {
            return null;
        }
        if (entity instanceof Human) {
            Human human = (Human) entity;
            if (human.isPositionDefined()) {
                EntityID position = human.getPosition();
                if (this.worldInfo.getEntity(position) instanceof Area) {
                    return position;
                }
            }
        } else if (entity instanceof Area) {
            return targetID;
        } else if (entity.getStandardURN() == BLOCKADE) {
            Blockade blockade = (Blockade) entity;
            if (blockade.isPositionDefined()) {
                return blockade.getPosition();
            }
        }
        return null;
    }

    private Action calcRefugeAction(Human human, PathPlanning pathPlanning, Collection<EntityID> targets,//为了如果在避难所里就放人
                                    boolean isUnload) {
        EntityID position = human.getPosition();
        Collection<EntityID> refuges = this.worldInfo.getEntityIDsOfType(StandardEntityURN.REFUGE);
        int size = refuges.size();

        if (refuges.contains(position)) {
            return isUnload ? new ActionUnload() : new ActionRest();
        }

        refuges  = getVaildRefuges(refuges);
        //todo:需要改进   人满不一定要去掉,应该是找最优的庇护所
        List<EntityID> firstResult = null;
        while (refuges.size() > 0) {
            pathPlanning.setFrom(position);
            pathPlanning.setDestination(refuges);
            List<EntityID> path = pathPlanning.calc().getResult();
            if (path != null && path.size() > 0) {
                if (firstResult == null)
                {
                    firstResult = new ArrayList<>(path);
                    if (targets == null || targets.isEmpty())
                    {
                        break;
                    }
                }
                EntityID refugeID = path.get(path.size() - 1);
                pathPlanning.setFrom(refugeID);
                pathPlanning.setDestination(targets);
                List<EntityID> fromRefugeToTarget = pathPlanning.calc().getResult();
                if (fromRefugeToTarget != null && fromRefugeToTarget.size() > 0) {
                    return getMoveAction(path);
                }
                refuges.remove(refugeID);
                if (size == refuges.size()) {
                    break;
                }
                size = refuges.size();
            } else {
                break;
            }
        }
        return firstResult != null ? getMoveAction(firstResult) : null;
    }

    private Action getMoveAction(PathPlanning pathPlanning, EntityID from, EntityID target) {
        pathPlanning.setFrom(from);
        pathPlanning.setDestination(target);
        List<EntityID> path = pathPlanning.calc().getResult();
        return getMoveAction(path);
    }

    private Action getMoveAction(List<EntityID> path) {
        if (path != null && path.size() > 0) {
            ActionMove moveAction = (ActionMove) actionExtMove.setTarget(path.get(path.size() - 1)).calc().getAction();
            return moveAction;
        }
        return null;
    }

    public  Collection<EntityID> getVaildRefuges(Collection<EntityID> refuges){

        Collection<EntityID> targetRefuges = new ArrayList<>();
        for (EntityID entityID : refuges){
            Refuge refuge = (Refuge) this.worldInfo.getEntity(entityID);
            if (refuge.getOccupiedBeds()<refuge.getBedCapacity()){
                targetRefuges.add(entityID);
            }
        }
        return targetRefuges;
    }

}
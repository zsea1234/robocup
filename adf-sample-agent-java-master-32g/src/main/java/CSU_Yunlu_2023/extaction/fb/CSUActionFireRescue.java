package CSU_Yunlu_2023.extaction.fb;

import CSU_Yunlu_2023.CSUConstants;
import CSU_Yunlu_2023.LogHelper;
import adf.core.agent.action.Action;
import adf.core.agent.action.ambulance.ActionUnload;
import adf.core.agent.action.common.ActionMove;
import adf.core.agent.action.common.ActionRest;
import adf.core.agent.action.fire.ActionRescue;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.extaction.ExtAction;
import adf.core.component.module.algorithm.PathPlanning;
import rescuecore2.config.NoSuchConfigOptionException;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static rescuecore2.standard.entities.StandardEntityURN.BLOCKADE;

public class CSUActionFireRescue extends ExtAction {

    private PathPlanning pathPlanning;
    private int thresholdRest;
    private int kernelTime;
    private EntityID target;
    private ExtAction actionExtMove;
    private LogHelper logHelper;

    private static final boolean debug = false;

    public CSUActionFireRescue(AgentInfo agentInfo, WorldInfo worldInfo, ScenarioInfo scenarioInfo,
                               ModuleManager moduleManager, DevelopData developData) {
        super(agentInfo, worldInfo, scenarioInfo, moduleManager, developData);
        this.target = null;
        this.thresholdRest = developData.getInteger("ActionTransport.rest", 100);
        switch (scenarioInfo.getMode()) {
            case PRECOMPUTATION_PHASE:
                this.pathPlanning = moduleManager.getModule("ActionTransport.PathPlanning",
                        "adf.core.sample.module.algorithm.SamplePathPlanning");
                this.actionExtMove = moduleManager.getExtAction("TacticsFireBrigade.ActionExtMove", "adf.core.sample.extaction.ActionExtMove");
                break;
            case PRECOMPUTED:
                this.pathPlanning = moduleManager.getModule("ActionTransport.PathPlanning",
                        "adf.core.sample.module.algorithm.SamplePathPlanning");
                this.actionExtMove = moduleManager.getExtAction("TacticsFireBrigade.ActionExtMove", "adf.core.sample.extaction.ActionExtMove");
                break;
            case NON_PRECOMPUTE:
                this.pathPlanning = moduleManager.getModule("ActionTransport.PathPlanning",
                        "adf.core.sample.module.algorithm.SamplePathPlanning");
                this.actionExtMove = moduleManager.getExtAction("TacticsFireBrigade.ActionExtMove", "adf.core.sample.extaction.ActionExtMove");
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
        if (this.getCountUpdateInfo() >= 2) {
            return this;
        }
        this.pathPlanning.updateInfo(messageManager);
        return this;
    }

    @Override
    public ExtAction setTarget(EntityID target) {
        this.target = null;//？？？
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
        FireBrigade me = (FireBrigade) this.agentInfo.me();
        if ((this.result == null) && this.needRest(me)) {
            logHelper.writeAndFlush("----需要休息----");
            EntityID areaID = this.convertArea(this.target);
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

    private Action calcRescue(FireBrigade agent, PathPlanning pathPlanning, EntityID target) {
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
                    logHelper.writeAndFlush("觉得"+human.getID()+"被埋了，挖出来");
                    return new ActionRescue(human);
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

    private EntityID convertArea(EntityID targetID) {// 返回ID的位置，position
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

    private Action calcRefugeAction(Human human, PathPlanning pathPlanning, Collection<EntityID> targets,
                                    boolean isUnload) {
        EntityID position = human.getPosition();
        Collection<EntityID> refuges = this.worldInfo.getEntityIDsOfType(StandardEntityURN.REFUGE);
        int size = refuges.size();//refuge的数量
        if (refuges.contains(position)) {//如果当前agent在refuge里，有人放人，没人休息。
            return isUnload ? new ActionUnload() : new ActionRest();
        }
        List<EntityID> firstResult = null;
        while (refuges.size() > 0) {
            pathPlanning.setFrom(position);
            pathPlanning.setDestination(refuges);
            List<EntityID> path = pathPlanning.calc().getResult();
            if (path != null && path.size() > 0) {
                if (firstResult == null) {//？？？
                    firstResult = new ArrayList<>(path);
                    if (targets == null || targets.isEmpty()) {
                        break;
                    }
                }
                EntityID refugeID = path.get(path.size() - 1);
                pathPlanning.setFrom(refugeID);
                pathPlanning.setDestination(targets);
                List<EntityID> fromRefugeToTarget = pathPlanning.calc().getResult();//计算从refuge到传入的target的路径
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

    private Action getMoveAction(List<EntityID> path) {
        if (path != null && path.size() > 0) {
            ActionMove moveAction = (ActionMove) actionExtMove.setTarget(path.get(path.size() - 1)).calc().getAction();
            return moveAction;
        }
        return null;
    }
}
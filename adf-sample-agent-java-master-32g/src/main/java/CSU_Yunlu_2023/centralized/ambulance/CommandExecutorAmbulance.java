package CSU_Yunlu_2023.centralized.ambulance;

import adf.core.agent.action.Action;
import adf.core.agent.action.common.ActionMove;
import adf.core.agent.action.common.ActionRest;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.centralized.CommandAmbulance;
import adf.core.agent.communication.standard.bundle.centralized.MessageReport;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.centralized.CommandExecutor;
import adf.core.component.extaction.ExtAction;
import adf.core.component.module.algorithm.PathPlanning;
import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.worldmodel.EntityID;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import static rescuecore2.standard.entities.StandardEntityURN.*;

/**
 * 未作修改
 */
public class CommandExecutorAmbulance extends CommandExecutor<CommandAmbulance> {



    private static final int ACTION_UNKNOWN = -1;
    private static final int ACTION_REST = CommandAmbulance.ACTION_REST;
    private static final int ACTION_MOVE = CommandAmbulance.ACTION_MOVE;
    private static final int ACTION_RESCUE = CommandAmbulance.ACTION_RESCUE;
    private static final int ACTION_LOAD = CommandAmbulance.ACTION_LOAD;
    private static final int ACTION_UNLOAD = CommandAmbulance.ACTION_UNLOAD;
    private static final int ACTION_AUTONOMY = CommandAmbulance.ACTION_AUTONOMY;

    private PathPlanning pathPlanning;

    private ExtAction actionTransport;
    private ExtAction actionExtMove;

    private int type;
    private EntityID target;
    private EntityID commanderID;

    public CommandExecutorAmbulance(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        this.type = ACTION_UNKNOWN;
        switch (scenarioInfo.getMode()) {
            case PRECOMPUTATION_PHASE, PRECOMPUTED, NON_PRECOMPUTE:
                this.pathPlanning = moduleManager.getModule("DefaultCommandExecutorAmbulance.PathPlanning", "CSU_Yunlu_2023.module.algorithm.AStarPathPlanning");
                this.actionTransport = moduleManager.getExtAction("DefaultCommandExecutorAmbulance.ExtActionTransport", "CSU_Yunlu_2023.extaction.at.ActionTransport");
                this.actionExtMove = moduleManager.getExtAction("DefaultCommandExecutorAmbulance.ExtActionMove", "CSU_Yunlu_2023.extaction.ActionExtMove");
                break;
        }
    }

    @Override
    public CommandExecutor setCommand(CommandAmbulance command) {
        EntityID agentID = this.agentInfo.getID();
        if (command.isToIDDefined() && Objects.requireNonNull(command.getToID()).getValue() == agentID.getValue()) {
            this.type = command.getAction();
            this.target = command.getTargetID();
            this.commanderID = command.getSenderID();
        }
        return this;
    }

//11.4看到此处
    @Override
    public CommandExecutor updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if (this.getCountUpdateInfo() >= 2) {
            return this;
        }
        this.pathPlanning.updateInfo(messageManager);
        this.actionTransport.updateInfo(messageManager);
        this.actionExtMove.updateInfo(messageManager);

        if (this.isCommandCompleted()) {
            if (this.type != ACTION_UNKNOWN) {
                messageManager.addMessage(new MessageReport(true, true, false, this.commanderID));
                if (this.type == ACTION_LOAD) {
                    this.type = ACTION_UNLOAD;
                    this.target = null;
                } else {
                    this.type = ACTION_UNKNOWN;
                    this.target = null;
                    this.commanderID = null;
                }
            }
        }
        return this;
    }

    @Override
    public CommandExecutor precompute(PrecomputeData precomputeData) {
        super.precompute(precomputeData);
        if (this.getCountPrecompute() >= 2) {
            return this;
        }
        this.pathPlanning.precompute(precomputeData);
        this.actionTransport.precompute(precomputeData);
        this.actionExtMove.precompute(precomputeData);
        return this;
    }

    @Override
    public CommandExecutor resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        if (this.getCountResume() >= 2) {
            return this;
        }
        this.pathPlanning.resume(precomputeData);
        this.actionTransport.resume(precomputeData);
        this.actionExtMove.resume(precomputeData);
        return this;
    }

    @Override
    public CommandExecutor preparate() {
        super.preparate();
        if (this.getCountPreparate() >= 2) {
            return this;
        }
        this.pathPlanning.preparate();
        this.actionTransport.preparate();
        this.actionExtMove.preparate();
        return this;
    }

    @Override
    public CommandExecutor calc() {
        this.result = null;
        final boolean DEBUG = false;
        switch (this.type) {
            case ACTION_REST:
                if (DEBUG) System.out.println(this.agentInfo.getID() + ": ACTION_REST");
                EntityID position = this.agentInfo.getPosition();
                if (this.target == null) {
                    Collection<EntityID> refuges = this.worldInfo.getEntityIDsOfType(REFUGE);
                    if (refuges.contains(position)) {
                        this.result = new ActionRest();
                    } else {
                        this.pathPlanning.setFrom(position);
                        this.pathPlanning.setDestination(refuges);
                        List<EntityID> path = this.pathPlanning.calc().getResult();
                        if (path != null && path.size() > 0) {
                            this.result = new ActionMove(path);
                        } else {
                            this.result = new ActionRest();
                        }
                    }
                    return this;
                }
                if (position.getValue() != this.target.getValue()) {
                    List<EntityID> path = this.pathPlanning.getResult(position, this.target);
                    if (path != null && path.size() > 0) {
                        this.result = new ActionMove(path);
                        return this;
                    }
                }
                this.result = new ActionRest();
                return this;
            case ACTION_MOVE:
                if (DEBUG) System.out.println(this.agentInfo.getID() + ": ACTION_MOVE");
                if (this.target != null) {
                    this.result = this.actionExtMove.setTarget(this.target).calc().getAction();
                }
                return this;
            case ACTION_RESCUE:
                if (DEBUG) System.out.println(this.agentInfo.getID() + ": ACTION_RESCUE");
                if (this.target != null) {
                    this.result = this.actionTransport.setTarget(this.target).calc().getAction();
                }
                return this;
            case ACTION_LOAD:
                if (DEBUG) System.out.println(this.agentInfo.getID() + ": ACTION_LOAD");
                if (this.target != null) {
                    this.result = this.actionTransport.setTarget(this.target).calc().getAction();
                }
                return this;
            case ACTION_UNLOAD:
                if (DEBUG) System.out.println(this.agentInfo.getID() + ": ACTION_UNLOAD");
                if (this.target != null) {
                    this.result = this.actionTransport.setTarget(this.target).calc().getAction();
                }
                return this;
            case ACTION_AUTONOMY:
                if (DEBUG) System.out.println(this.agentInfo.getID() + ": ACTION_AUTONOMY");
                if (this.target != null) {
                    return this;
                }
                StandardEntity targetEntity = this.worldInfo.getEntity(this.target);
                if (targetEntity instanceof Area) {
                    if (this.agentInfo.someoneOnBoard() == null) {
                        this.result = this.actionExtMove.setTarget(this.target).calc().getAction();
                    } else {
                        this.result = this.actionTransport.setTarget(this.target).calc().getAction();
                    }
                } else if (targetEntity instanceof Human) {
                    this.result = this.actionTransport.setTarget(this.target).calc().getAction();
                }
        }
        return this;
    }

    private boolean isCommandCompleted() {
        Human agent = (Human) this.agentInfo.me();
        switch (this.type) {
            case ACTION_REST:
                if (this.target == null) {
                    return (agent.getDamage() == 0);
                }
                if (Objects.requireNonNull(this.worldInfo.getEntity(this.target)).getStandardURN() == REFUGE) {
                    if (agent.getPosition().getValue() == this.target.getValue()) {
                        return (agent.getDamage() == 0);
                    }
                }
                return false;
            case ACTION_MOVE:
                return this.target == null || this.agentInfo.getPosition().getValue() == this.target.getValue();
            case ACTION_RESCUE:
                if (this.target == null) {
                    return true;
                }
                Human human = (Human) Objects.requireNonNull(this.worldInfo.getEntity(this.target));
                return human.isBuriednessDefined() && human.getBuriedness() == 0 || (human.isHPDefined() && human.getHP() == 0);
            case ACTION_LOAD:
                if (this.target == null) {
                    return true;
                }
                Human human1 = (Human) Objects.requireNonNull(this.worldInfo.getEntity(this.target));
                if ((human1.isHPDefined() && human1.getHP() == 0)) {
                    return true;
                }
                if (human1.getStandardURN() != CIVILIAN) {
                    this.type = ACTION_RESCUE;
                    return this.isCommandCompleted();
                }
                if (human1.isPositionDefined()) {
                    EntityID position = human1.getPosition();
                    if (this.worldInfo.getEntityIDsOfType(AMBULANCE_TEAM).contains(position)) {
                        return true;
                    } else if (this.worldInfo.getEntity(position).getStandardURN() == REFUGE) {
                        return true;
                    }
                }
                return false;
            case ACTION_UNLOAD:
                if (this.target != null) {
                    StandardEntity entity = this.worldInfo.getEntity(this.target);
                    if (entity != null && entity instanceof Area) {
                        if (this.target.getValue() != this.agentInfo.getPosition().getValue()) {
                            return false;
                        }
                    }
                }
                return (this.agentInfo.someoneOnBoard() == null);
            case ACTION_AUTONOMY:
                if (this.target != null) {
                    StandardEntity targetEntity = this.worldInfo.getEntity(this.target);
                    if (targetEntity instanceof Area) {
                        this.type = this.agentInfo.someoneOnBoard() == null ? ACTION_MOVE : ACTION_UNLOAD;
                        return this.isCommandCompleted();
                    } else if (targetEntity instanceof Human) {
                        Human h = (Human) targetEntity;
                        if ((h.isHPDefined() && h.getHP() == 0)) {
                            return true;
                        }
                        this.type = h.getStandardURN() == CIVILIAN ? ACTION_LOAD : ACTION_RESCUE;
                        return this.isCommandCompleted();
                    }
                }
                return true;
        }
        return true;
    }

    private class DistanceSorter implements Comparator<StandardEntity> {
        private StandardEntity reference;
        private WorldInfo worldInfo;

        DistanceSorter(WorldInfo wi, StandardEntity reference) {
            this.reference = reference;
            this.worldInfo = wi;
        }

        public int compare(StandardEntity a, StandardEntity b) {
            int d1 = this.worldInfo.getDistance(this.reference, a);
            int d2 = this.worldInfo.getDistance(this.reference, b);
            return d1 - d2;
        }
    }
}



package CSU_Yunlu_2023.centralized.ambulance;

import adf.core.agent.action.common.ActionMove;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.centralized.CommandScout;
import adf.core.agent.communication.standard.bundle.centralized.MessageReport;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.centralized.CommandExecutor;
import adf.core.component.module.algorithm.PathPlanning;
import rescuecore2.standard.entities.Area;
import rescuecore2.worldmodel.AbstractEntity;
import rescuecore2.worldmodel.EntityID;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static rescuecore2.standard.entities.StandardEntityURN.REFUGE;

/**
 * 未作修改
 */
public class CommandExecutorScout extends CommandExecutor<CommandScout>{




	    private static final int ACTION_UNKNOWN = -1;
	    private static final int ACTION_SCOUT = 1;

	    private PathPlanning pathPlanning;

	    private int type;
	    private Collection<EntityID> scoutTargets;
	    private EntityID commanderID;

	    public CommandExecutorScout(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
	        super(ai, wi, si, moduleManager, developData);
	        this.type = ACTION_UNKNOWN;
	        switch  (scenarioInfo.getMode()) {
	            case PRECOMPUTATION_PHASE, PRECOMPUTED, NON_PRECOMPUTE:
	                this.pathPlanning = moduleManager.getModule("CommandExecutorScout.PathPlanning", "adf.core.sample.module.algorithm.SamplePathPlanning");
	                break;
            }
	    }

	    @Override
	    public CommandExecutor setCommand(CommandScout command) {
	        EntityID agentID = this.agentInfo.getID();
	        if(command.isToIDDefined() && (Objects.requireNonNull(command.getToID()).getValue() == agentID.getValue())) {
	            EntityID target = command.getTargetID();
	            if(target == null) {
	                target = this.agentInfo.getPosition();
	            }
	            this.type = ACTION_SCOUT;
	            this.commanderID = command.getSenderID();
	            this.scoutTargets = new HashSet<>();
	            this.scoutTargets.addAll(
	                    worldInfo.getObjectsInRange(target, command.getRange())
	                            .stream()
	                            .filter(e -> e instanceof Area && e.getStandardURN() != REFUGE)
	                            .map(AbstractEntity::getID)
	                            .collect(Collectors.toList())
	            );
	        }
	        return this;
	    }

	    @Override
	    public CommandExecutor updateInfo(MessageManager messageManager){
	        super.updateInfo(messageManager);
	        if(this.getCountUpdateInfo() >= 2) {
	            return this;
	        }
	        this.pathPlanning.updateInfo(messageManager);

	        if(this.isCommandCompleted()) {
	            if(this.type != ACTION_UNKNOWN) {
	                messageManager.addMessage(new MessageReport(true, true, false, this.commanderID));
	                this.type = ACTION_UNKNOWN;
	                this.scoutTargets = null;
	                this.commanderID = null;
	            }
	        }
	        return this;
	    }

	    @Override
	    public CommandExecutor precompute(PrecomputeData precomputeData) {
	        super.precompute(precomputeData);
	        if(this.getCountPrecompute() >= 2) {
	            return this;
	        }
	        this.pathPlanning.precompute(precomputeData);
	        return this;
	    }

	    @Override
	    public CommandExecutor resume(PrecomputeData precomputeData) {
	        super.resume(precomputeData);
	        if(this.getCountResume() >= 2) {
	            return this;
	        }
	        this.pathPlanning.resume(precomputeData);
	        return this;
	    }

	    @Override
	    public CommandExecutor preparate() {
	        super.preparate();
	        if(this.getCountPreparate() >= 2) {
	            return this;
	        }
	        this.pathPlanning.preparate();
	        return this;
	    }

	    @Override
	    public CommandExecutor calc() {
	        this.result = null;
	        if(this.type == ACTION_SCOUT) {
	            if(this.scoutTargets == null || this.scoutTargets.isEmpty()) {
	                return this;
	            }
	            this.pathPlanning.setFrom(this.agentInfo.getPosition());
	            this.pathPlanning.setDestination(this.scoutTargets);
	            List<EntityID> path = this.pathPlanning.calc().getResult();
	            if(path != null) {
	                this.result = new ActionMove(path);
	            }
	        }
	        return this;
	    }

	    private boolean isCommandCompleted() {
	        if(this.type ==  ACTION_SCOUT) {
	            if(this.scoutTargets != null) {
	                this.scoutTargets.removeAll(this.worldInfo.getChanged().getChangedEntities());
	            }
	            return (this.scoutTargets == null || this.scoutTargets.isEmpty());
	        }
	        return true;
	    }
	}



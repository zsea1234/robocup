package CSU_Yunlu_2023.centralized.fire;

import adf.core.agent.communication.standard.bundle.centralized.CommandScout;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.centralized.CommandExecutor;
import adf.core.component.module.algorithm.PathPlanning;
import rescuecore2.worldmodel.EntityID;

import java.util.Collection;


public class CommandExecutorScout extends CommandExecutor<CommandScout> {


    private static final int ACTION_UNKNOWN = -1;
    private static final int ACTION_SCOUT = 1;

    private PathPlanning pathPlanning;

    private int type;
    private Collection<EntityID> scoutTargets;
    private EntityID commanderID;

    public CommandExecutorScout(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
    }

    @Override
    public CommandExecutor setCommand(CommandScout command) {
        return null;
    }

    @Override
    public CommandExecutor calc() {
        return null;
    }


}

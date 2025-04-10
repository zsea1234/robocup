package CSU_Yunlu_2023.module.complex.at;

import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.PathPlanning;
import adf.core.component.module.complex.Search;
import rescuecore2.worldmodel.EntityID;

import java.util.Collection;

public class CSUSearch extends Search {
    private boolean debug = false;
    private PathPlanning pathPlanning;
    private Clustering clustering;
    private EntityID result;
    private Collection<EntityID> unvisitedBuildingIDs;

    public CSUSearch(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
    }

    @Override
    public EntityID getTarget() {
        return null;
    }

    @Override
    public Search calc() {
        return null;
    }

}

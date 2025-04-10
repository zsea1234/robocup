package CSU_Yunlu_2023.module.complex.at;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.PathPlanning;
import rescuecore2.worldmodel.EntityID;

import java.util.Set;

public class CSUSearchRecorder {
    private Set<EntityID> allBuildings;
    private Set<EntityID> allCivilians;
    private MessageManager messageManager;

    private EntityID lastPosition;
    private EntityID nowPosition;
    private EntityID target;
    private int nowPriority;
    private int strategyType;
    private WorldInfo worldInfo;
    public AgentInfo agentInfo;
    private ScenarioInfo scenarioInfo;
    private int voiceRange;
    private int lastClusterIndex = -1;

    private Clustering clustering;
    private PathPlanning pathPlanning;
}

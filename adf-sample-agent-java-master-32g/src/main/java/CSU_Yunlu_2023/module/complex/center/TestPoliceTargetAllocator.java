package CSU_Yunlu_2023.module.complex.center;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.module.complex.PoliceTargetAllocator;
import rescuecore2.worldmodel.EntityID;

import java.util.List;
import java.util.Map;


public class TestPoliceTargetAllocator extends PoliceTargetAllocator {

    public TestPoliceTargetAllocator(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
    }

    @Override
    public PoliceTargetAllocator resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        if (this.getCountResume() >= 2) {
            return this;
        }
        return this;
    }

    @Override
    public PoliceTargetAllocator preparate() {
        super.preparate();
        if (this.getCountPrecompute() >= 2) {
            return this;
        }
        return this;
    }

    @Override
    public Map<EntityID, EntityID> getResult() {
        return null;
    }

    @Override
    public PoliceTargetAllocator calc() {
        return this;
    }

    @Override
    public PoliceTargetAllocator updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if (this.getCountUpdateInfo() >= 2) {
            return this;
        }
        return this;
    }
}


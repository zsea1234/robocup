package CSU_Yunlu_2023.module.complex.fb.search;

import CSU_Yunlu_2023.CSUConstants;
import CSU_Yunlu_2023.module.complex.fb.targetSelection.FireBrigadeTarget;
import CSU_Yunlu_2023.world.CSUFireBrigadeWorld;
import CSU_Yunlu_2023.world.CSUWorldHelper;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.module.AbstractModule;
import adf.core.component.module.algorithm.PathPlanning;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

import java.util.List;
import java.util.Set;

import static rescuecore2.standard.entities.StandardEntityURN.*;

public class SearchHelper extends AbstractModule {

    private CSUFireBrigadeWorld world;
    private SearchAroundFireDecider searchAroundFireDecider;
    private PathPlanning pathPlanning;
    private FireBrigadeTarget target;
    private EntityID result;

    public SearchHelper(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        this.world = moduleManager.getModule("WorldHelper.FireBrigade", CSUConstants.WORLD_HELPER_FIRE_BRIGADE);
        StandardEntityURN agentURN = ai.me().getStandardURN();
        switch (si.getMode()) {
            case PRECOMPUTATION_PHASE:
                if (agentURN == AMBULANCE_TEAM) {
                    this.pathPlanning = moduleManager.getModule("SampleSearch.PathPlanning.Ambulance", "adf.core.sample.module.algorithm.SamplePathPlanning");
                } else if (agentURN == FIRE_BRIGADE) {
                    this.pathPlanning = moduleManager.getModule("SampleSearch.PathPlanning.Fire", "adf.core.sample.module.algorithm.SamplePathPlanning");
                } else if (agentURN == POLICE_FORCE) {
                    this.pathPlanning = moduleManager.getModule("SampleSearch.PathPlanning.Police", "adf.core.sample.module.algorithm.SamplePathPlanning");
                }
                break;
            case PRECOMPUTED:
                if (agentURN == AMBULANCE_TEAM) {
                    this.pathPlanning = moduleManager.getModule("SampleSearch.PathPlanning.Ambulance", "adf.core.sample.module.algorithm.SamplePathPlanning");
                } else if (agentURN == FIRE_BRIGADE) {
                    this.pathPlanning = moduleManager.getModule("SampleSearch.PathPlanning.Fire", "adf.core.sample.module.algorithm.SamplePathPlanning");
                } else if (agentURN == POLICE_FORCE) {
                    this.pathPlanning = moduleManager.getModule("SampleSearch.PathPlanning.Police", "adf.core.sample.module.algorithm.SamplePathPlanning");
                }
                break;
            case NON_PRECOMPUTE:
                if (agentURN == AMBULANCE_TEAM) {
                    this.pathPlanning = moduleManager.getModule("SampleSearch.PathPlanning.Ambulance", "adf.core.sample.module.algorithm.SamplePathPlanning");
                } else if (agentURN == FIRE_BRIGADE) {
                    this.pathPlanning = moduleManager.getModule("SampleSearch.PathPlanning.Fire", "adf.core.sample.module.algorithm.SamplePathPlanning");
                } else if (agentURN == POLICE_FORCE) {
                    this.pathPlanning = moduleManager.getModule("SampleSearch.PathPlanning.Police", "adf.core.sample.module.algorithm.SamplePathPlanning");
                }
                break;
        }
        searchAroundFireDecider = new SearchAroundFireDecider(world);
    }

    public boolean isTimeToSearch(FireBrigadeTarget fireBrigadeTarget) {
        return isTimeToSearch(fireBrigadeTarget.getCsuBuilding().getId());
    }

    public boolean isTimeToSearch(EntityID target) {
        return SearchHelper.isTimeToSearch(world, target);
    }

    public static boolean isTimeToSearch(CSUWorldHelper world, EntityID target) {
        int lastSeenTime = world.getCsuBuilding(target).getLastSeenTime();
        int lastUpdateTime = world.getCsuBuilding(target).getLastUpdateTime();
        return world.getTime() - lastSeenTime > CSUConstants.MAX_SEARCH_INTERVAL_BETWEEN_LAST_SEEN &&
                world.getTime() - lastUpdateTime > CSUConstants.MAX_SEARCH_INTERVAL_BETWEEN_LAST_SEEN;
    }

    public void setTarget(FireBrigadeTarget fireBrigadeTarget) {
        this.target = fireBrigadeTarget;
    }

    public EntityID getResult() {
        return this.result;
    }

    @Override
    public AbstractModule calc() {
        searchAroundFireDecider.setTargetFire(this.target);
        searchAroundFireDecider.calc(false);
        Set<EntityID> searchTargets = searchAroundFireDecider.getSearchTargets();
        List<EntityID> path;
        if (result != null && searchTargets.contains(result)){
            path = pathPlanning.setFrom(world.getSelfPositionId()).setDestination(this.result).calc().getResult();
            if (path != null && !path.isEmpty()) {
                this.result = path.get(path.size() - 1);
                return this;
            }
        }
        path = pathPlanning.setFrom(world.getSelfPositionId()).setDestination(searchTargets).calc().getResult();
        if (path != null && !path.isEmpty()) {
            this.result = path.get(path.size() - 1);
            return this;
        }
        Set<EntityID> visibleFrom = target.getCsuBuilding().getVisibleFrom();
        if (result != null && visibleFrom.contains(result)) {
            path = pathPlanning.setFrom(world.getSelfPositionId()).setDestination(this.result).calc().getResult();
            if (path != null && !path.isEmpty()) {
                this.result = path.get(path.size() - 1);
                return this;
            }
        }
        path = pathPlanning.setFrom(world.getSelfPositionId()).setDestination(visibleFrom).calc().getResult();
        if (path != null && !path.isEmpty()) {
            this.result = path.get(path.size() - 1);
            return this;
        }
        this.result = null;
        return this;
    }

    @Override
    public AbstractModule updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if (getCountUpdateInfo() >= 2) {
            return this;
        }

        this.world.updateInfo(messageManager);
        this.pathPlanning.updateInfo(messageManager);
        return null;
    }

    @Override
    public AbstractModule precompute(PrecomputeData precomputeData) {
        super.precompute(precomputeData);
        if (getCountPrecompute() >= 2) {
            return this;
        }
        this.world.precompute(precomputeData);
        this.pathPlanning.precompute(precomputeData);
        return this;
    }

    @Override
    public AbstractModule resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        if (getCountResume() >= 2) {
            return this;
        }
        this.world.resume(precomputeData);
        this.pathPlanning.resume(precomputeData);
        return this;
    }

    @Override
    public AbstractModule preparate() {
        super.preparate();
        if (getCountPreparate() >= 2) {
            return this;
        }
        this.world.preparate();
        this.pathPlanning.preparate();
        return this;
    }

}

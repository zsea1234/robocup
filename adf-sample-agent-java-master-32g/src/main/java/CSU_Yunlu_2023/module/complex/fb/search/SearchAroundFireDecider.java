package CSU_Yunlu_2023.module.complex.fb.search;

import CSU_Yunlu_2023.CSUConstants;
import CSU_Yunlu_2023.module.complex.fb.targetSelection.FireBrigadeTarget;
import CSU_Yunlu_2023.module.complex.fb.tools.FbUtilities;
import CSU_Yunlu_2023.world.CSUWorldHelper;
import CSU_Yunlu_2023.world.object.CSUBuilding;
import javolution.util.FastSet;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.worldmodel.EntityID;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

public class SearchAroundFireDecider {

    protected CSUWorldHelper world;
    private FireBrigadeTarget fireBrigadeTarget;
    private Set<EntityID> searchTargets;
    private final FbUtilities fbUtilities;

    public SearchAroundFireDecider(CSUWorldHelper world) {
        this.world = (world);
        fbUtilities = new FbUtilities(world);
        this.searchTargets = new HashSet<>();
    }

    public void calc(boolean exploreAll) {
        update();
        calcSearchTargets(exploreAll);
    }

    private void update() {
        searchTargets.remove(world.getSelfPosition().getID());
    }

    public void setTargetFire(FireBrigadeTarget fireBrigadeTarget) {
        this.fireBrigadeTarget = fireBrigadeTarget;
    }

    public void calcSearchTargets(boolean searchAll) {
        Set<CSUBuilding> borderBuildings = new FastSet<>();
        Set<CSUBuilding> allBuildings = new FastSet<>();

        borderBuildings.add(fireBrigadeTarget.getCsuBuilding());
        allBuildings.add(fireBrigadeTarget.getCsuBuilding());

        if (searchAll) {
            Set<StandardEntity> clusterBorderEntities = new FastSet<>(fireBrigadeTarget.getCluster().getBorderEntities());

            for (StandardEntity entity : clusterBorderEntities) {
                borderBuildings.add(world.getCsuBuilding(entity.getID()));
                allBuildings.add(world.getCsuBuilding(entity.getID()));
            }
        }

        for (CSUBuilding neighbour : borderBuildings) {
            for (CSUBuilding b : neighbour.getConnectedBuildings()) {
                if (world.getDistance(b.getSelfBuilding(), neighbour.getSelfBuilding()) < world.getConfig().viewDistance) {
                    allBuildings.add(b);
                }
            }
        }
        allBuildings = allBuildings.stream().filter(e -> {
            return world.getTime() - e.getLastSeenTime() > CSUConstants.MAX_SEARCH_INTERVAL_BETWEEN_LAST_SEEN;
        }).collect(Collectors.toSet());

        searchTargets = fbUtilities.findMaximalCovering(allBuildings);
    }

    public Set<EntityID> getSearchTargets() {
        return searchTargets;
    }

}

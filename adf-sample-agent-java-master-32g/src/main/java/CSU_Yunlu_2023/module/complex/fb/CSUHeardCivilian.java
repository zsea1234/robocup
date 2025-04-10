package CSU_Yunlu_2023.module.complex.fb;

import CSU_Yunlu_2023.debugger.DebugHelper;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.StandardMessagePriority;
import adf.core.agent.communication.standard.bundle.centralized.CommandPolice;
import adf.core.agent.info.AgentInfo;
import adf.core.component.module.algorithm.PathPlanning;
import rescuecore2.worldmodel.EntityID;

import java.io.Serializable;
import java.util.*;

public class CSUHeardCivilian {
    private Set<FireBrigadeBuilding> possibleBuilding;
    private EntityID entityID;
    private AgentInfo agentInfo;
    private PathPlanning pathPlanning;
    private MessageManager messageManager;
    public CSUHeardCivilian(EntityID entityID, AgentInfo agentInfo, PathPlanning pathPlanning, MessageManager messageManager) {
        this.possibleBuilding = new HashSet<>();
        this.agentInfo = agentInfo;
        this.entityID = entityID;
        this.pathPlanning = pathPlanning;
        this.messageManager = messageManager;
    }


    public Set<FireBrigadeBuilding> getPossibleBuilding() {
        return possibleBuilding;
    }


    public Set<FireBrigadeBuilding> merge(Set<FireBrigadeBuilding> newPossibleBuilding) {
        if (possibleBuilding == null || possibleBuilding.isEmpty()) {
            this.possibleBuilding.addAll(newPossibleBuilding);
        } else {
            Set<FireBrigadeBuilding> removeFireBrigadeBuilding = new HashSet<>();
            for (Iterator var = possibleBuilding.iterator(); var.hasNext();){
                FireBrigadeBuilding fireBrigadeBuilding = (FireBrigadeBuilding) var.next();
                fireBrigadeBuilding.remove(entityID);
                if (!newPossibleBuilding.contains(fireBrigadeBuilding)) {
                    removeFireBrigadeBuilding.add(fireBrigadeBuilding);
                }
            }
            this.possibleBuilding.removeAll(removeFireBrigadeBuilding);
        }
        Boolean flag = true;
        if (possibleBuilding!=null&&!possibleBuilding.isEmpty()&&possibleBuilding.size()<=1){
            for (FireBrigadeBuilding fireBrigadeBuilding: possibleBuilding){
                if (agentInfo.getPosition().equals(fireBrigadeBuilding.getId())){
                    continue;
                }
                List<EntityID> path = this.pathPlanning.setFrom(this.agentInfo.getPosition()).setDestination(fireBrigadeBuilding.getId()).calc().getResult();
                if (path!=null){
                    flag = false;
                }
            }
        }
        if (flag){
            for (FireBrigadeBuilding fireBrigadeBuilding: possibleBuilding){
                messageManager.addMessage( new CommandPolice( true, StandardMessagePriority.NORMAL,null,
                        fireBrigadeBuilding.getId(), CommandPolice.ACTION_CLEAR ) );
            }
        }
        List<Integer> noBrokennessBuildingsElements = new ArrayList<>();
        if (DebugHelper.DEBUG_MODE){
            for (FireBrigadeBuilding fireBrigadeBuilding : this.possibleBuilding) {
                noBrokennessBuildingsElements.add(fireBrigadeBuilding.getId().getValue());
            }
            try {
                DebugHelper.VD_CLIENT.drawAsync(this.agentInfo.getID().getValue(), "FBNoBrokennessBuildings", (Serializable) noBrokennessBuildingsElements);
            } catch (Exception e) {
                e.printStackTrace();
            }

        }
        Integer num = possibleBuilding.size();
        for (FireBrigadeBuilding fireBrigadeBuilding : possibleBuilding) {
            fireBrigadeBuilding.addCivilian(num,this.entityID);
        }
        return possibleBuilding;
    }

    public EntityID getEntityID() {
        return entityID;
    }
}

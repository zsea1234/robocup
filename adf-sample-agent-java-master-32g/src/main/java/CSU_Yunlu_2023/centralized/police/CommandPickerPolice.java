package CSU_Yunlu_2023.centralized.police;

import adf.core.agent.communication.standard.bundle.centralized.CommandPolice;
import adf.core.agent.communication.standard.bundle.centralized.CommandScout;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.centralized.CommandPicker;
import adf.core.component.communication.CommunicationMessage;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;


public class CommandPickerPolice extends CommandPicker {

    private Collection<CommunicationMessage> messages;
    private Map<EntityID, EntityID> allocationData;
    private int scoutDistance;

    public CommandPickerPolice(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        this.messages = new ArrayList<>();
        this.allocationData = null;
        this.scoutDistance = developData.getInteger("CommandPickerPolice.scoutDistance", 40000);
    }

    @Override
    public CommandPicker setAllocatorResult(Map<EntityID, EntityID> allocationData) {
        this.allocationData = allocationData;
        return this;
    }

    @Override
    public CommandPicker calc() {
        this.messages.clear();
        if (this.allocationData == null) {
            return this;
        }
        
        for (EntityID agentID : this.allocationData.keySet()) {
            StandardEntity agent = this.worldInfo.getEntity(agentID);
            if (agent != null && agent.getStandardURN() == StandardEntityURN.POLICE_FORCE) {
                EntityID targetID = this.allocationData.get(agentID);
                StandardEntity target = this.worldInfo.getEntity(targetID);
                
                if (target != null) {
                    // 如果目标是警察当前位置，创建侦察命令
                    if (targetID.getValue() == agentID.getValue()) {
                        CommandScout command = new CommandScout(
                                true,
                                agentID,
                                targetID,
                                this.scoutDistance
                        );
                        this.messages.add(command);
                        continue;
                    }
                    
                    // 根据目标类型创建不同命令
                    if (target instanceof Area) {
                        // 对于道路等区域，创建常规清障命令
                        CommandPolice command = new CommandPolice(
                                true,
                                agentID,
                                targetID,
                                CommandPolice.ACTION_AUTONOMY
                        );
                        this.messages.add(command);
                    } else if (target instanceof Blockade) {
                        // 对于明确的路障，创建清障命令
                        Blockade blockade = (Blockade) target;
                        if (blockade.isPositionDefined()) {
                            CommandPolice command = new CommandPolice(
                                    true,
                                    agentID,
                                    targetID,
                                    CommandPolice.ACTION_CLEAR
                            );
                            this.messages.add(command);
                        }
                    } else if (target instanceof Human && ((Human) target).isPositionDefined()) {
                        // 对于被困人员，创建清障命令指向其位置
                        Human human = (Human) target;
                        EntityID position = human.getPosition();
                        CommandPolice command = new CommandPolice(
                                true,
                                agentID,
                                position,
                                CommandPolice.ACTION_CLEAR
                        );
                        this.messages.add(command);
                    } else if (target instanceof Refuge) {
                        // 对于避难所，创建休息命令
                        CommandPolice command = new CommandPolice(
                                true,
                                agentID,
                                targetID,
                                CommandPolice.ACTION_REST
                        );
                        this.messages.add(command);
                    } else {
                        // 对于其他情况，创建侦察命令
                        CommandScout command = new CommandScout(
                                true,
                                agentID,
                                targetID,
                                this.scoutDistance
                        );
                        this.messages.add(command);
                    }
                }
            }
        }
        return this;
    }

    @Override
    public Collection<CommunicationMessage> getResult() {
        return this.messages;
    }
}
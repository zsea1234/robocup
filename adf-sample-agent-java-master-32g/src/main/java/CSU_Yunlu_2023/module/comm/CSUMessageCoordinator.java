package CSU_Yunlu_2023.module.comm;

import CSU_Yunlu_2023.CSUConstants;
import CSU_Yunlu_2023.debugger.CountMessage;
import CSU_Yunlu_2023.debugger.DebugHelper;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.StandardMessage;
import adf.core.agent.communication.standard.bundle.StandardMessagePriority;
import adf.core.agent.communication.standard.bundle.centralized.*;
import adf.core.agent.communication.standard.bundle.information.*;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.communication.MessageCoordinator;
import rescuecore2.standard.entities.Civilian;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;


public class CSUMessageCoordinator extends MessageCoordinator {

    private CSUChannelSubscriber csuChannelSubscriber;
    private static int ALLOWED_TO_SEND_DISTANCE_THRESHOLD = 10000;

    public CSUMessageCoordinator() {
        this.csuChannelSubscriber = new CSUChannelSubscriber();
    }

    @Override
    public void coordinate(AgentInfo agentInfo, WorldInfo worldInfo, ScenarioInfo scenarioInfo, MessageManager messageManager,
                           ArrayList<CommunicationMessage> sendMessageList, List<List<CommunicationMessage>> channelSendMessageList) {

        if (csuChannelSubscriber.getSendMessageAgentsRatio() == 0) {
            csuChannelSubscriber.initSendMessageAgentsRatio(worldInfo, scenarioInfo);
        }
        ArrayList<CommunicationMessage> policeMessages = new ArrayList<>();
        ArrayList<CommunicationMessage> ambulanceMessages = new ArrayList<>();
        ArrayList<CommunicationMessage> fireBrigadeMessages = new ArrayList<>();

        ArrayList<CommunicationMessage> voiceMessages = new ArrayList<>();

        StandardEntityURN agentType = getAgentType(agentInfo, worldInfo);

        for (CommunicationMessage msg : sendMessageList) {
            if (msg instanceof StandardMessage && !((StandardMessage)msg).isRadio()) {
                voiceMessages.add(msg);
            }
            else {
                if (msg instanceof MessageBuilding) {
                    fireBrigadeMessages.add(msg);
                }
                else if (msg instanceof MessageCivilian) {
                    if (((MessageCivilian) msg).getSendingPriority() != StandardMessagePriority.LOW){
                        ambulanceMessages.add(msg);
                        fireBrigadeMessages.add(msg);
                        policeMessages.add(msg);
                    }
                } else if (msg instanceof MessageRoad) {
                    if (((MessageRoad) msg).getSendingPriority() != StandardMessagePriority.LOW){
                        fireBrigadeMessages.add(msg);
                        ambulanceMessages.add(msg);
                        policeMessages.add(msg);
                    }
                } else if (msg instanceof CommandAmbulance) {
                    ambulanceMessages.add(msg);
                } else if (msg instanceof CommandFire) {
                    fireBrigadeMessages.add(msg);
                }
                else if (msg instanceof CommandPolice) {
                    if (agentType == StandardEntityURN.POLICE_OFFICE || agentType == StandardEntityURN.POLICE_FORCE) {
                        policeMessages.add(msg);
                    }
                }
                else if (msg instanceof CommandScout) {
                    if (agentType == StandardEntityURN.FIRE_STATION) {
                        fireBrigadeMessages.add(msg);
                    } else if (agentType == StandardEntityURN.POLICE_OFFICE || agentType == StandardEntityURN.POLICE_FORCE) {
                        policeMessages.add(msg);
                    } else if (agentType == StandardEntityURN.AMBULANCE_CENTRE) {
                        ambulanceMessages.add(msg);
                    }
                } else if (msg instanceof MessageReport) {
                    if (agentType == StandardEntityURN.FIRE_BRIGADE) {
                        fireBrigadeMessages.add(msg);
                    } else if (agentType == StandardEntityURN.POLICE_FORCE || agentType == StandardEntityURN.POLICE_OFFICE) {
                        policeMessages.add(msg);
                    } else if (agentType == StandardEntityURN.AMBULANCE_TEAM) {
                        ambulanceMessages.add(msg);
                    }
                }
                else if (msg instanceof MessageFireBrigade) {
                    fireBrigadeMessages.add(msg);
                    ambulanceMessages.add(msg);
                    policeMessages.add(msg);
                } else if (msg instanceof MessagePoliceForce) {
                    ambulanceMessages.add(msg);
                    policeMessages.add(msg);
                    fireBrigadeMessages.add(msg);
                } else if (msg instanceof MessageAmbulanceTeam) {
                    ambulanceMessages.add(msg);
                    policeMessages.add(msg);
                    fireBrigadeMessages.add(msg);
                }
            }
        }

        if (CSUConstants.DEBUG_MESSAGE_COUNT) {
            CountMessage.countFBMessage.addAndGet((long) Math.ceil(
                    fireBrigadeMessages.stream().mapToLong(
                    CommunicationMessage::getByteArraySize).sum() / 8.0
            ));
            CountMessage.countPFMessage.addAndGet((long) Math.ceil(policeMessages.stream().mapToLong(
                    CommunicationMessage::getByteArraySize).sum() / 8.0));
            CountMessage.countATMessage.addAndGet((long) Math.ceil(ambulanceMessages.stream().mapToLong(
                    CommunicationMessage::getByteArraySize).sum() / 8.0));

            if (agentInfo.getTime() % 20 == 0) {
                System.out.println("time: " + agentInfo.getTime() + " avgFbMessageBytes: " +
                        CountMessage.countFBMessage.doubleValue() / agentInfo.getTime() / CSUChannelSubscriber.getScenarioAgents(scenarioInfo));
                System.out.println("time: " + agentInfo.getTime() + " avgPfMessageBytes: " +
                        CountMessage.countPFMessage.doubleValue() / agentInfo.getTime() / CSUChannelSubscriber.getScenarioAgents(scenarioInfo));
                System.out.println("time: " + agentInfo.getTime() + " avgAtMessageBytes: " +
                        CountMessage.countATMessage.doubleValue() / agentInfo.getTime() / CSUChannelSubscriber.getScenarioAgents(scenarioInfo));
            }
        }

        if (scenarioInfo.getCommsChannelsCount() > 1) {
            int[] channelSize = new int[scenarioInfo.getCommsChannelsCount() - 1];

            List<StandardEntityURN> priority = CSUChannelSubscriber.getPriority(scenarioInfo);
            if (allowedToSendRadioMessage(worldInfo, agentInfo, scenarioInfo)) {
            }else {
                fireBrigadeMessages.removeIf(e -> !(e instanceof CommandPolice || e instanceof CommandFire || e instanceof CommandAmbulance || e instanceof MessageFireBrigade));
                policeMessages.removeIf(e -> !(e instanceof CommandPolice || e instanceof CommandFire || e instanceof CommandAmbulance));
                ambulanceMessages.removeIf(e -> !(e instanceof CommandPolice || e instanceof CommandFire || e instanceof CommandAmbulance || e instanceof MessageFireBrigade || e instanceof MessageAmbulanceTeam));
            }
            for (StandardEntityURN urn : priority) {
                if (urn == StandardEntityURN.FIRE_BRIGADE || urn == StandardEntityURN.FIRE_STATION) {
                    setSendMessages(scenarioInfo, StandardEntityURN.FIRE_BRIGADE, agentInfo, worldInfo, fireBrigadeMessages,
                            channelSendMessageList, channelSize);
                } else if (urn == StandardEntityURN.POLICE_FORCE || urn == StandardEntityURN.POLICE_OFFICE) {
                    setSendMessages(scenarioInfo, StandardEntityURN.POLICE_FORCE, agentInfo, worldInfo, policeMessages,
                            channelSendMessageList, channelSize);
                } else if (urn == StandardEntityURN.AMBULANCE_TEAM || urn == StandardEntityURN.AMBULANCE_CENTRE) {
                    setSendMessages(scenarioInfo, StandardEntityURN.AMBULANCE_TEAM, agentInfo, worldInfo, ambulanceMessages,
                            channelSendMessageList, channelSize);
                }
            }
        }

        ArrayList<StandardMessage> voiceMessageLowList = new ArrayList<>();
        ArrayList<StandardMessage> voiceMessageNormalList = new ArrayList<>();
        ArrayList<StandardMessage> voiceMessageHighList = new ArrayList<>();

        for (CommunicationMessage msg : voiceMessages) {
            if (msg instanceof StandardMessage) {
                StandardMessage m = (StandardMessage) msg;
                switch (m.getSendingPriority()) {
                    case LOW:
                        voiceMessageLowList.add(m);
                        break;
                    case NORMAL:
                        voiceMessageNormalList.add(m);
                        break;
                    case HIGH:
                        voiceMessageHighList.add(m);
                        break;
                }
            }
        }

        channelSendMessageList.get(0).addAll(voiceMessageHighList);
        channelSendMessageList.get(0).addAll(voiceMessageNormalList);
        channelSendMessageList.get(0).addAll(voiceMessageLowList);
    }

    protected int[] getChannelsByAgentType(StandardEntityURN agentType, AgentInfo agentInfo,
                                           WorldInfo worldInfo, ScenarioInfo scenarioInfo, int channelIndex) {
        int numChannels = scenarioInfo.getCommsChannelsCount()-1;
        int maxChannelCount = 0;
        boolean isPlatoon = isPlatoonAgent(agentInfo, worldInfo);
        if (isPlatoon) {
            maxChannelCount = scenarioInfo.getCommsChannelsMaxPlatoon();
        } else {
            maxChannelCount = scenarioInfo.getCommsChannelsMaxOffice();
        }
        int[] channels = new int[maxChannelCount];

        for (int i = 0; i < maxChannelCount; i++) {
            channels[i] = csuChannelSubscriber.getChannelNumber(agentType, i, numChannels, agentInfo, worldInfo, scenarioInfo);
        }
        return channels;
    }

    protected boolean isPlatoonAgent(AgentInfo agentInfo, WorldInfo worldInfo) {
        StandardEntityURN agentType = getAgentType(agentInfo, worldInfo);
        if (agentType == StandardEntityURN.FIRE_BRIGADE ||
                agentType == StandardEntityURN.POLICE_FORCE ||
                agentType == StandardEntityURN.AMBULANCE_TEAM) {
            return true;
        }
        return false;
    }

    protected StandardEntityURN getAgentType(AgentInfo agentInfo, WorldInfo worldInfo) {
        StandardEntityURN agentType = worldInfo.getEntity(agentInfo.getID()).getStandardURN();
        return agentType;
    }

    protected void setSendMessages(ScenarioInfo scenarioInfo, StandardEntityURN agentType, AgentInfo agentInfo,
                                   WorldInfo worldInfo, List<CommunicationMessage> messages,
                                   List<List<CommunicationMessage>> channelSendMessageList,
                                   int[] channelSize) {
        int channelIndex = 0;
        int[] channels = getChannelsByAgentType(agentType, agentInfo, worldInfo, scenarioInfo, channelIndex);
        int channel = channels[channelIndex];
        int channelCapacity = scenarioInfo.getCommsChannelBandwidth(channel);
        int allocatedCapacity = (int) (channelCapacity /
                (CSUChannelSubscriber.getScenarioAgents(scenarioInfo) *
                        csuChannelSubscriber.getSendMessageAgentsRatio()));
        switch (agentType) {
            case FIRE_BRIGADE:
            case FIRE_STATION:
                messages.sort(new FBMessageComparator());
                break;
            case POLICE_FORCE:
            case POLICE_OFFICE:
                messages.sort(new PFMessageComparator());
                break;
            case AMBULANCE_TEAM:
            case AMBULANCE_CENTRE:
                messages.sort(new ATMessageComparator());
                break;
        }
        for (int i = StandardMessagePriority.values().length - 1; i >= 0; i--) {
            for (CommunicationMessage msg : messages) {
                StandardMessage smsg = (StandardMessage) msg;
                int byteSize = smsg.getByteArraySize() / 8;
                if (smsg.getSendingPriority() == StandardMessagePriority.values()[i]) {
                    while (channelIndex < channels.length) {
                        channelSize[channel - 1] += byteSize;
                        if (channelSize[channel - 1] > allocatedCapacity) {
                            channelSize[channel - 1] -= byteSize;
                            channelIndex++;
                            if (channelIndex < channels.length) {
                                channel = channels[channelIndex];
                                channelCapacity = scenarioInfo.getCommsChannelBandwidth(channel);
                                allocatedCapacity = (int) (channelCapacity /
                                        (CSUChannelSubscriber.getScenarioAgents(scenarioInfo) * csuChannelSubscriber.getSendMessageAgentsRatio()));
                            }
                        } else if (!channelSendMessageList.get(channel).contains(smsg)) {
                            channelSendMessageList.get(channel).add(smsg);
                            break;
                        }
                    }

                }
            }
        }
    }
    private boolean allowedToSendRadioMessage(WorldInfo worldInfo, AgentInfo agentInfo, ScenarioInfo scenarioInfo) {
        if (CSUChannelSubscriber.isBandWidthSufficient(scenarioInfo)) {
            return true;
        }
        EntityID allowedToSendID = new EntityID(Integer.MAX_VALUE);
        Collection<StandardEntity> objectIDsInRange = worldInfo.getObjectsInRange(agentInfo.getID(), scenarioInfo.getPerceptionLosMaxDistance());
        for (StandardEntity entity : objectIDsInRange) {
            if (entity instanceof Human && !(entity instanceof Civilian)) {
                boolean notBuried = ((Human) entity).isBuriednessDefined() && ((Human) entity).getBuriedness() <= 0;
                boolean inDistanceThreshold = worldInfo.getDistance(agentInfo.getID(), entity.getID()) < ALLOWED_TO_SEND_DISTANCE_THRESHOLD;
                boolean inSameAreaAndSeenRange = agentInfo.getPosition().equals(worldInfo.getPosition(entity.getID())) &&
                        worldInfo.getDistance(agentInfo.getID(), entity.getID()) < scenarioInfo.getPerceptionLosMaxDistance() * 0.5;


                if (notBuried && (inDistanceThreshold || inSameAreaAndSeenRange)) {
                    if (entity.getID().getValue() < allowedToSendID.getValue()) {
                        allowedToSendID = entity.getID();
                    }
                }
            }
        }
        if (DebugHelper.DEBUG_MODE) {
            ArrayList<Integer> elements = new ArrayList<>();
            if (!(allowedToSendID.getValue() == Integer.MAX_VALUE)) {
                elements.add(allowedToSendID.getValue());
            }
        }
        return allowedToSendID.equals(agentInfo.getID());
    }

    public static class FBMessageComparator implements Comparator<CommunicationMessage> {
        @Override
        public int compare(CommunicationMessage t0, CommunicationMessage t1) {
            if (t0 instanceof MessageBuilding && !(t1 instanceof MessageBuilding)) {
                return -1;
            } else if (!(t0 instanceof MessageBuilding) && t1 instanceof MessageBuilding) {
                return 1;
            }else {
                return 0;
            }
        }
    }

    public static class ATMessageComparator implements Comparator<CommunicationMessage> {
        @Override
        public int compare(CommunicationMessage t0, CommunicationMessage t1) {
            if (t0 instanceof MessageCivilian && !(t1 instanceof MessageCivilian)) {
                return -1;
            } else if (!(t0 instanceof MessageCivilian) && t1 instanceof MessageCivilian) {
                return 1;
            }else {
                return 0;
            }
        }
    }

    public static class PFMessageComparator implements Comparator<CommunicationMessage> {
        @Override
        public int compare(CommunicationMessage t0, CommunicationMessage t1) {
            if (t0 instanceof CommandPolice && !(t1 instanceof CommandPolice)) {
                return -1;
            } else if (!(t0 instanceof CommandPolice) && t1 instanceof CommandPolice) {
                return 1;
            }else {
                return 0;
            }
        }
    }

}

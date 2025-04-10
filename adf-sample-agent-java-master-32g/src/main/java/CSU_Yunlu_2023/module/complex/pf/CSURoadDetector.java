package CSU_Yunlu_2023.module.complex.pf;

import CSU_Yunlu_2023.CSUConstants;
import CSU_Yunlu_2023.extaction.pf.guidelineHelper;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.MessageUtil;
import adf.core.agent.communication.standard.bundle.StandardMessagePriority;
import adf.core.agent.communication.standard.bundle.centralized.CommandPolice;
import adf.core.agent.communication.standard.bundle.information.*;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.PathPlanning;
import adf.core.component.module.complex.RoadDetector;
import rescuecore2.misc.geometry.GeometryTools2D;
import rescuecore2.misc.geometry.Line2D;
import rescuecore2.misc.geometry.Point2D;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import java.util.*;
import java.util.stream.Collectors;

import static rescuecore2.standard.entities.StandardEntityURN.*;

public class CSURoadDetector extends RoadDetector {
	public static final String KEY_JUDGE_ROAD = "RoadDetector.judge_road";
	public static final String KEY_START_X = "RoadDetector.start_x";
	public static final String KEY_START_Y = "RoadDetector.start_y";
	public static final String KEY_END_X = "RoadDetector.end_x";
	public static final String KEY_END_Y = "RoadDetector.end_y";
	public static final String KEY_ROAD_SIZE = "RoadDetector.road_size";
	public static final String KEY_ISENTRANCE = "ActionExtClear.is_entrance";

	private int roadsize;
	private Set<EntityID> Target_Road =  new HashSet<>();
	private Set<EntityID> firstTargetAreas = new HashSet<>();
	private Set<EntityID> secondTargetAreas = new HashSet<>();
	private PathPlanning pathPlanning;
	private Clustering clustering;
	private EntityID result = null;
	private EntityID CenterID = null;
	//commonRoad : no agents,no civilian,not entrence
	private Boolean needClearCommonRoad = true;
	private Boolean Initial = true;
	private Set<Civilian> sendCivilian;
	private final double timeThreshold = 3;
	public List<guidelineHelper> judgeRoad = new ArrayList<>();
	private Collection<EntityID> clusterEntitiesID = new ArrayList<>();
	private Set<EntityID> firstLevelBlockedRoad = new HashSet<>();
	private Set<EntityID> SOSroad = new HashSet<>();
	private Set<EntityID> SOSCivilianRoad = new HashSet<>();
	private Set<EntityID> topLevelBlockedRoad = new HashSet<>();
	private Set<EntityID> halfTopLevelBlockedRoad = new HashSet<>();
	private Set<EntityID> midLevelBlockedRoad = new HashSet<>();
	private Set<EntityID> halfLowLevelBlockedRoad = new HashSet<>();
	private Set<EntityID> lowLevelBlockedRoad = new HashSet<>();
	private Set<EntityID> noNeedToClear = new HashSet<>();
	private Set<EntityID> TOPTOPRoad = new HashSet<>();
	private MessageManager messageManager = null;
	private GuidelineCreator guidelineCreator;
	private Boolean Cluster_Flag = false;
	private Boolean Clear_Flag = false;
	private int times = 0;
	private Integer Cluster_Range = 0;//聚类的范围，即警察活动的范围
	private Point2D Cluster_Center = null;//聚类中心坐标
	public CSURoadDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
		super(ai, wi, si, moduleManager, developData);
		switch (scenarioInfo.getMode()) {
			case PRECOMPUTATION_PHASE:
				this.pathPlanning = moduleManager.getModule("SampleRoadDetector.PathPlanning",
						CSUConstants.A_STAR_PATH_PLANNING);
				this.clustering = moduleManager.getModule("SampleRoadDetector.Clustering",
						"adf.core.sample.module.algorithm.SampleKMeans");
				break;
			case PRECOMPUTED:
				this.pathPlanning = moduleManager.getModule("SampleRoadDetector.PathPlanning",
						CSUConstants.A_STAR_PATH_PLANNING);
				this.clustering = moduleManager.getModule("SampleRoadDetector.Clustering",
						"adf.core.sample.module.algorithm.SampleKMeans");
				break;
			case NON_PRECOMPUTE:
				this.pathPlanning = moduleManager.getModule("SampleRoadDetector.PathPlanning",
						CSUConstants.A_STAR_PATH_PLANNING);
				this.clustering = moduleManager.getModule("SampleRoadDetector.Clustering",
						"adf.core.sample.module.algorithm.SampleKMeans");
				break;
		}
		this.sendCivilian = new HashSet<>();
		registerModule(this.pathPlanning);
		registerModule(this.clustering);
		this.guidelineCreator = moduleManager.getModule("GuidelineCreator.Default", CSUConstants.GUIDE_LINE_CREATOR);
		this.result = null;
	}

	private boolean is_area_blocked(EntityID id) {
		Road road = (Road) this.worldInfo.getEntity(id);
		if (!road.isBlockadesDefined() || this.isRoadPassable(road)) return false;
		return true;
	}
	private class DistanceHumanIDSorter implements Comparator<EntityID> {//人的排序
		private WorldInfo worldInfo;
		private EntityID reference;
		private Collection<EntityID> refuges;
		DistanceHumanIDSorter(WorldInfo worldInfo, EntityID reference) {
			this.worldInfo = worldInfo;
			this.refuges = worldInfo.getEntityIDsOfType(StandardEntityURN.REFUGE);
			this.reference = reference;
		}

		DistanceHumanIDSorter(WorldInfo worldInfo, StandardEntity reference) {
			this.worldInfo = worldInfo;
			this.reference = reference.getID();
		}

		public int compare(EntityID a, EntityID b) {
			int d1 = this.the_shortest_save_distance(refuges,a);
			int d2 = this.the_shortest_save_distance(refuges,b);
			return d1 - d2;
		}
		private int the_shortest_save_distance(Collection<EntityID> refuges,EntityID a)
		{
			double sum = Double.MAX_VALUE;
			for(EntityID re : refuges){
				int dis = this.worldInfo.getDistance(re,a);
				if(dis < sum)
				{
					sum = dis;
				}
			}
			return (int)sum;
		}
	}
	private class DistanceIDSorter implements Comparator<EntityID> {
		private WorldInfo worldInfo;
		private EntityID reference;
		private PathPlanning pathPlanning;
		DistanceIDSorter(WorldInfo worldInfo, EntityID reference) {
			this.worldInfo = worldInfo;
			this.reference = reference;
		}

		DistanceIDSorter(WorldInfo worldInfo, EntityID reference,PathPlanning pathPlanning) {
			this.worldInfo = worldInfo;
			this.reference = reference;
			this.pathPlanning = pathPlanning;
		}
		DistanceIDSorter(WorldInfo worldInfo, StandardEntity reference) {
			this.worldInfo = worldInfo;
			this.reference = reference.getID();
		}

		public int compare(EntityID a, EntityID b) {
//			int d1 = this.worldInfo.getDistance(this.reference, a);
			int d1 = (int)this.pathPlanning.setFrom(this.worldInfo.getPosition(this.reference).getID()).setDestination(a).calc().getDistance();
			int d2 = (int)this.pathPlanning.setFrom(this.worldInfo.getPosition(this.reference).getID()).setDestination(b).calc().getDistance();

			return d1 - d2;
		}
	}

	private double getManhattanDistance(double fromX, double fromY, double toX, double toY) {
		double dx = fromX - toX;
		double dy = fromY - toY;
		return Math.abs(dx) + Math.abs(dy);
	}


	private EntityID getClosestEntityID(Collection<EntityID> IDs, EntityID reference) {
		if (IDs.isEmpty()) {
			return null;
		}
		double minDistance = Double.MAX_VALUE;
		EntityID closestID = null;
		for (EntityID id : IDs) {
			double distance = this.worldInfo.getDistance(reference, id);
			if (distance < minDistance) {
				minDistance = distance;
				closestID = id;
			}
		}
		return closestID;
	}

	private RoadDetector getRoadDetector(EntityID positionID, Set<EntityID> entityIDSet) {
		this.pathPlanning.setFrom(positionID);
		this.pathPlanning.setDestination(entityIDSet);
		List<EntityID> path = this.pathPlanning.calc().getResult();
		if (path != null && path.size() > 0) {
			this.result = path.get(path.size() - 1);
		}
		return this;
	}

	private RoadDetector getPathTo(EntityID positionIDfrom, EntityID positionIDto) {
		this.pathPlanning.setFrom(positionIDfrom);
		this.pathPlanning.setDestination(positionIDto);
		List<EntityID> path = this.pathPlanning.calc().getResult();
		if (path != null && path.size() > 0) {
			this.result = path.get(path.size() - 1);
		}
		return this;
	}


	boolean search_flag = false;
	boolean arrive_flag = false;
	StandardEntity nearest_refuge = null;

	private void Find_Refuge() {
		//检测离每个refuge最近的警察
		if (!this.search_flag) {
			if (scenarioInfo.getCommsChannelsCount() > 1) {
				for (StandardEntity SE : worldInfo.getEntitiesOfType(StandardEntityURN.REFUGE)) {
					boolean nearest_flag = true;
					Refuge refuge = (Refuge) SE;
					double distance = this.getManhattanDistance(this.agentInfo.getX(), this.agentInfo.getY(), refuge.getX(), refuge.getY());
					for (StandardEntity se : worldInfo.getEntitiesOfType(StandardEntityURN.POLICE_FORCE)) {
						PoliceForce police = (PoliceForce) se;
						double dist = this.getManhattanDistance(police.getX(), police.getY(), refuge.getX(), refuge.getY());
						if (dist < distance) {
							nearest_flag = false;
							break;
						}
					}
					if (nearest_flag) {
						this.nearest_refuge = SE;
						break;
					} else {
						continue;
					}
				}
			} else {
				Collection<StandardEntity> clusterEntities = null;
				if (this.clustering != null) {
					int clusterIndex = this.clustering.getClusterIndex(this.agentInfo.getID());
					if (clusterIndex != -1) {
						clusterEntities = this.clustering.getClusterEntities(clusterIndex);
					}
					for (StandardEntity SE : worldInfo.getEntitiesOfType(StandardEntityURN.REFUGE)) {
						if (clusterEntities.contains(SE.getID())) {
							this.nearest_refuge = SE;
						}
					}
				}
			}
		}
		this.search_flag = true;
	}

	private RoadDetector Get_To_Refuge(EntityID positionID) {
		if (!arrive_flag) {
			this.Find_Refuge();
			if (this.nearest_refuge != null) {
				Refuge refuge = (Refuge) nearest_refuge;
				if (this.agentInfo.getPosition().getValue() == refuge.getID().getValue()) {
					this.arrive_flag = true;
					return null;
				} else {
					return this.getPathTo(positionID, refuge.getID());
				}
			}
		}
		return null;
	}

	/**
	 * @Description: 具有优先级路线的RoadDetector
	 * @Author: Bochun-Yue
	 * @Date: 2/25/20
	 */

	@Override
	public RoadDetector calc() {
		EntityID positionID = this.agentInfo.getPosition();
		if(Initial)
		{
			Initial = false;
			if(this.clustering != null) {
				Collection<StandardEntity> clusterEntities = this.clustering.getClusterEntities(
															 	this.clustering.getClusterIndex(this.agentInfo.getID()));
				clusterEntitiesID = this.clustering.getClusterEntityIDs(
										this.clustering.getClusterIndex(this.agentInfo.getID()));
				if(clusterEntities != null && clusterEntities.size() > 0){
					double x_sum = 0, y_sum = 0, number = 0;
					for(StandardEntity standardEntity : clusterEntities){
						if(standardEntity instanceof Area)
						{
							Area area = (Area) standardEntity;
							x_sum += area.getX();
							y_sum += area.getY();
							number ++;
						}
						if(standardEntity instanceof Refuge)//refuge
						{
							Target_Road.add(standardEntity.getID());
						}
					}
					Cluster_Center = new Point2D(x_sum/number,y_sum/number);
					Integer min_dis = Integer.MAX_VALUE;
					for(StandardEntity standardEntity : clusterEntities){
						if(standardEntity instanceof Area)
						{
							int sum = GetDistance((Area)standardEntity,Cluster_Center);
							if(sum > Cluster_Range)
								Cluster_Range = sum;
							if(sum < min_dis)
							{
								min_dis = sum;
								CenterID = standardEntity.getID();
							}
						}
					}
				}
			}
		}
		this.update_roads();
//		this.topLevelBlockedRoad.removeAll(SOSroad);
		this.halfTopLevelBlockedRoad.removeAll(SOSroad);
		this.midLevelBlockedRoad.removeAll(SOSroad);
		this.halfLowLevelBlockedRoad.removeAll(SOSroad);
		this.lowLevelBlockedRoad.removeAll(SOSroad);
//		this.firstLevelBlockedRoad.removeAll(SOSroad);
		StandardEntity position = this.worldInfo.getEntity(this.agentInfo.getPosition());
		if (position instanceof Road || position instanceof Hydrant) {
			Road road = (Road) position;
			if (this.isRoadPassable(road)) {
				this.noNeedToClear.add(road.getID());
			}
		}
		if(!Cluster_Flag && (this.clusterEntitiesID.contains(positionID) || CenterID == null))//到聚类中了
		{
			result = null;
			Cluster_Flag = true;
		}
		if (agentInfo.getTime() > scenarioInfo.getKernelAgentsIgnoreuntil() + 5 && !this.arrive_flag)//优先最高refuge
			this.Get_To_Refuge(positionID);

		if (nearest_refuge != null && this.result != null && !this.arrive_flag) {
			if (nearest_refuge.getID().getValue() == this.result.getValue()) {
				return this;
			}
		}
		//TOPTOPRoad
		if (this.result != null && this.TOPTOPRoad.contains(this.result)) {
			if (this.TOPTOPRoad.contains(this.agentInfo.getPosition())) {
				this.TOPTOPRoad.remove(this.agentInfo.getPosition());
				this.noNeedToClear.add(this.agentInfo.getID());
			}
			return this;
		}

		if (!this.TOPTOPRoad.isEmpty()) {
			List<EntityID> sortList = new ArrayList<>(this.TOPTOPRoad);
			sortList.sort(new DistanceIDSorter(this.worldInfo, this.agentInfo.getID(),this.pathPlanning));
//			System.out.println("SOSOSOSOSO:" + sortList.get(0).getValue());
			return this.getPathTo(positionID, sortList.get(0));
		}
		if(!Cluster_Flag)
		{
			result = CenterID;
			return this;
		}
//		if(this.agentInfo.getID().getValue() == 1962675462) {
////			System.out.println("topsize:" + this.topLevelBlockedRoad.size());
////			System.out.println("halftopsize:" + this.halfTopLevelBlockedRoad.size());
////			System.out.println("midsize:" + this.midLevelBlockedRoad.size());
////			System.out.println("lowsize:" + this.lowLevelBlockedRoad.size());
////			System.out.println("targetRoadsize:" + this.targetAreas.size());
//			if(this.result!=null){
//				System.out.println("result:"+this.result.getValue());
////				if(this.noNeedToClear.contains(this.result)){
////					System.out.println("noneedtoclearhas:"+this.result);
////				}
////				if(this.topLevelBlockedRoad.contains(this.result)){
////					System.out.println("TOPTOPTOP");
////				}
////				if(this.halfTopLevelBlockedRoad.contains(this.result)){
////					System.out.println("hththt");
////				}
////				if(this.midLevelBlockedRoad.contains(this.result)){
////					System.out.println("mmmmmmmmm");
////				}
////				if(this.halfLowLevelBlockedRoad.contains(this.result)){
////					System.out.println("hlhlhlhlhh");
////				}
////				if(this.lowLevelBlockedRoad.contains(this.result)){
////					System.out.println("llllllllllllll");
////				}
////				if(this.targetAreas.containConstantss(this.result)){
////					System.out.println("tartartar");
////				}
//			}
////			System.out.println();
//		}
		//firstLevelBlockedRoad
		if (this.result != null && this.firstLevelBlockedRoad.contains(this.result)
				&& !this.noNeedToClear.contains(this.result)) {
			return this;
		}

		if (!this.firstLevelBlockedRoad.isEmpty()) {
			List<EntityID> sortList = new ArrayList<>(this.firstLevelBlockedRoad);
			sortList.sort(new DistanceIDSorter(this.worldInfo, this.agentInfo.getID(),this.pathPlanning));
			List<EntityID> nearPolice = new ArrayList<>();
			for (StandardEntity se : worldInfo.getEntitiesOfType(StandardEntityURN.POLICE_FORCE)) {
				nearPolice.add(se.getID());
			}
			for (int i = 0; i < sortList.size(); ++i) {
				EntityID id = this.getClosestEntityID(nearPolice, sortList.get(i));
				if (id.equals(this.agentInfo.getID())) {
					return this.getPathTo(positionID, sortList.get(i));
				}
			}
		}
			//SOSroad
		if (this.result != null && this.SOSroad.contains(this.result)) {
			if (this.SOSroad.contains(this.agentInfo.getPosition())) {
				if (position instanceof Road && isRoadPassable((Road) position)) {
					this.SOSroad.remove(this.agentInfo.getPosition());
					this.noNeedToClear.add(this.agentInfo.getID());
				}
			}
//			return this;
		}


		if (!this.SOSroad.isEmpty()) {
			List<EntityID> sortList = new ArrayList<>(this.SOSroad);
			sortList.sort(new DistanceIDSorter(this.worldInfo, this.agentInfo.getID(),this.pathPlanning));
//			System.out.println("SOSOSOSOSO:" + sortList.get(0).getValue());
			return this.getPathTo(positionID, sortList.get(0));
		}
		//topLevelBlockedRoad
		if (this.result != null && this.topLevelBlockedRoad.contains(this.result)
				&& !this.noNeedToClear.contains(this.result)) {
			return this;
		}
		if (!this.topLevelBlockedRoad.isEmpty()) {
			List<EntityID> sortList = new ArrayList<>(this.topLevelBlockedRoad);
			sortList.sort(new DistanceIDSorter(this.worldInfo, this.agentInfo.getID(),this.pathPlanning));
			List<EntityID> nearPolice = new ArrayList<>();
			for (StandardEntity se : worldInfo.getEntitiesOfType(StandardEntityURN.POLICE_FORCE)) {
				nearPolice.add(se.getID());
			}
			for (int i = 0; i < sortList.size(); ++i) {
				EntityID id = this.getClosestEntityID(nearPolice, sortList.get(i));
				if (id.equals(this.agentInfo.getID())) {
					return this.getPathTo(positionID, sortList.get(i));
				}
			}
		}




		//halfTopLevelBlockedRoad
		if (this.result != null && this.halfTopLevelBlockedRoad.contains(this.result)
				&& !this.noNeedToClear.contains(this.result)) {
			return this;
		}

		if (!this.halfTopLevelBlockedRoad.isEmpty()) {
			List<EntityID> sortList = new ArrayList<>(this.halfTopLevelBlockedRoad);
			sortList.sort(new DistanceIDSorter(this.worldInfo, this.agentInfo.getID(),this.pathPlanning));
			List<EntityID> nearPolice = new ArrayList<>();
			for (StandardEntity se : worldInfo.getEntitiesOfType(StandardEntityURN.POLICE_FORCE)) {
				nearPolice.add(se.getID());
			}
			for (int i = 0; i < sortList.size(); ++i) {
				EntityID id = this.getClosestEntityID(nearPolice, sortList.get(i));
				if (id.equals(this.agentInfo.getID())) {
					return this.getPathTo(positionID, sortList.get(i));
				}
			}
		}



		//midLevelBlockedRoad
		if (this.result != null && this.midLevelBlockedRoad.contains(this.result)
				&& !this.noNeedToClear.contains(this.result)) {
			return this;
		}
		//处理市民
		if (!this.midLevelBlockedRoad.isEmpty()) {
			List<EntityID> sortList = new ArrayList<>(this.midLevelBlockedRoad);
			sortList.sort(new DistanceIDSorter(this.worldInfo, this.agentInfo.getID(),this.pathPlanning));
			List<EntityID> nearPolice = new ArrayList<>();
			for (StandardEntity se : worldInfo.getEntitiesOfType(StandardEntityURN.POLICE_FORCE)) {
				nearPolice.add(se.getID());
			}
			for (int i = 0; i < sortList.size(); ++i) {
				EntityID id = this.getClosestEntityID(nearPolice, sortList.get(i));
				if (id.equals(this.agentInfo.getID())) {
					return this.getPathTo(positionID, sortList.get(i));
				}
			}
		}




		//halfLowLevelBlockedRoad
		if (this.result != null && this.halfLowLevelBlockedRoad.contains(this.result)
				&& !this.noNeedToClear.contains(this.result)) {
			return this;
		}

		if (!this.halfLowLevelBlockedRoad.isEmpty()) {
			List<EntityID> sortList = new ArrayList<>(this.halfLowLevelBlockedRoad);
			sortList.sort(new DistanceIDSorter(this.worldInfo, this.agentInfo.getID(),this.pathPlanning));
			List<EntityID> nearPolice = new ArrayList<>();
			for (StandardEntity se : worldInfo.getEntitiesOfType(StandardEntityURN.POLICE_FORCE)) {
				nearPolice.add(se.getID());
			}
			for (int i = 0; i < sortList.size(); ++i) {
				EntityID id = this.getClosestEntityID(nearPolice, sortList.get(i));
				if (id.equals(this.agentInfo.getID())) {
					return this.getPathTo(positionID, sortList.get(i));
				}
			}
		}


		//lowLevelBlockedRoad
		if (this.result != null && this.lowLevelBlockedRoad.contains(this.result)
				&& !this.noNeedToClear.contains(this.result)) {
			return this;
		}

		if (!this.lowLevelBlockedRoad.isEmpty()) {
			List<EntityID> sortList = new ArrayList<>(this.lowLevelBlockedRoad);
			sortList.sort(new DistanceIDSorter(this.worldInfo, this.agentInfo.getID(),this.pathPlanning));
			List<EntityID> nearPolice = new ArrayList<>();
			for (StandardEntity se : worldInfo.getEntitiesOfType(StandardEntityURN.POLICE_FORCE)) {
				nearPolice.add(se.getID());
			}
			for (int i = 0; i < sortList.size(); ++i) {
				EntityID id = this.getClosestEntityID(nearPolice, sortList.get(i));
				if (id.equals(this.agentInfo.getID())) {
					return this.getPathTo(positionID, sortList.get(i));
				}
			}
		}
		this.times++;
		if(this.times > 10)
		{
			Clear_Flag = true;//聚类中清理完毕了
		}
		this.getResultWhenNull();

		return this;
	}

	private int GetDistance(Area standardEntity, Point2D cluster_center) {
		double x_dis = standardEntity.getX() - cluster_center.getX();
		double y_dis = standardEntity.getY() - cluster_center.getY();
		return (int)(Math.sqrt(x_dis*x_dis + y_dis*y_dis));
	}


	private RoadDetector getResultWhenNull() {
		if (this.agentInfo.getTime() > scenarioInfo.getKernelAgentsIgnoreuntil()) {
			if (this.result != null && this.firstTargetAreas.contains(this.result)
					&& !this.noNeedToClear.contains(this.result)) {
				return this;
			}
			if (this.result != null && this.secondTargetAreas.contains(this.result)
					&& !this.noNeedToClear.contains(this.result)) {
				return this;
			}
			//去PF自己cluster的各类道路
			Collection<StandardEntity> clusterEntities = null;
			if (this.clustering != null) {
				int clusterIndex = this.clustering.getClusterIndex(this.agentInfo.getID());
				if (clusterIndex != -1) {
					clusterEntities = this.clustering.getClusterEntities(clusterIndex);
				}
				//先看civilian
				for (StandardEntity civilianEntity : worldInfo.getEntitiesOfType(StandardEntityURN.CIVILIAN)) {
					Civilian civilian = (Civilian) civilianEntity;
					if (!civilian.isPositionDefined() || !civilian.isHPDefined() ) {
						continue;
					}
					StandardEntity positionEntity = this.worldInfo.getEntity(civilian.getPosition());
					if (clusterEntities.contains(positionEntity)) {
						if (positionEntity instanceof Building) {
							Building building = (Building) positionEntity;
							if(!building.isBrokennessDefined() || building.getBrokenness() <= 0)//不管损坏为0的建筑
								continue;
							for (EntityID neighbourId : this.get_all_Bloacked_Entrance_of_Building(building)) {
								StandardEntity neighbour = this.worldInfo.getEntity(neighbourId);
								if (neighbour instanceof Road) {
									if (!this.noNeedToClear.contains(neighbourId)) {
										this.firstTargetAreas.add(neighbourId);
									}
								}
							}
						}
					}
				}

				//再看AT、FB
				for (StandardEntity agentEntity : worldInfo.getEntitiesOfType(StandardEntityURN.AMBULANCE_TEAM, StandardEntityURN.FIRE_BRIGADE)) {
					Human human = (Human) agentEntity;
					if (!human.isPositionDefined() || !human.isHPDefined() ) {
						continue;
					}
					StandardEntity positionEntity = worldInfo.getEntity(human.getPosition());
					if (clusterEntities.contains(positionEntity)) {
						if (positionEntity instanceof Building) {
							Building building = (Building) positionEntity;
							for (EntityID neighbourId : this.get_all_Bloacked_Entrance_of_Building(building)) {
								StandardEntity neighbour = this.worldInfo.getEntity(neighbourId);
								if (neighbour instanceof Road ) {
									if (!this.noNeedToClear.contains(neighbourId)) {
										this.firstTargetAreas.add(neighbourId);
									}
								}
							}
						} else if (positionEntity instanceof Road ) {
							Road road = (Road) positionEntity;
							if (!this.noNeedToClear.contains(road.getID())) {
								this.firstTargetAreas.add(road.getID());
							}
						}
					}
				}




				//建筑入口
				for (StandardEntity se : clusterEntities) {
					if (se instanceof Building) {
						for (EntityID id : ((Building) se).getNeighbours()) {
							StandardEntity neighbour = this.worldInfo.getEntity(id);
							if (neighbour instanceof Road || neighbour instanceof Hydrant) {
								if (!this.noNeedToClear.contains(id)) {
									this.secondTargetAreas.add(id);
								}
							}
						}
					}
				}

				//common roads
				for (StandardEntity se : clusterEntities) {
					if (se instanceof Road ) {
						if (!this.noNeedToClear.contains(se.getID())
								&& !this.secondTargetAreas.contains(se.getID())) {
							this.firstTargetAreas.add(se.getID());
						}
					}
				}

				for (EntityID id : this.worldInfo.getChanged().getChangedEntities()) {
					StandardEntity entity = this.worldInfo.getEntity(id);
					if (entity instanceof Road || entity instanceof Hydrant) {
						Road road = (Road) entity;
						if (this.isRoadPassable(road)) {
							this.firstTargetAreas.remove(road.getID());
							this.secondTargetAreas.remove(road.getID());
							this.noNeedToClear.add(road.getID());
						}
					}
				}

			} else {
				//in case of null cluster
				for (StandardEntity e : this.worldInfo.getEntitiesOfType(REFUGE, BUILDING, GAS_STATION)) {
					for (EntityID id : ((Building) e).getNeighbours()) {
						StandardEntity neighbour = this.worldInfo.getEntity(id);
						if (neighbour instanceof Road) {
							this.firstTargetAreas.add(id);
						}
					}
				}
			}
			if (this.firstTargetAreas != null && !this.firstTargetAreas.isEmpty()) {
				List<EntityID> sortList = new ArrayList<>(this.firstTargetAreas);
				sortList.sort(new DistanceIDSorter(this.worldInfo, this.agentInfo.getID(),this.pathPlanning));
				return this.getPathTo(this.agentInfo.getPosition(),sortList.get(0));
			}
			if (this.secondTargetAreas != null && !this.secondTargetAreas.isEmpty()) {
				List<EntityID> sortList = new ArrayList<>(this.secondTargetAreas);
				sortList.sort(new DistanceIDSorter(this.worldInfo, this.agentInfo.getID(),this.pathPlanning));
				return this.getPathTo(this.agentInfo.getPosition(),sortList.get(0));
			}
		}
		this.result = null;
		return this;
	}

	private boolean inRange(PoliceForce a, PoliceForce b) {
		return (a.getX() - 3000 < b.getX()) && (a.getX() + 3000 > b.getX()) && (a.getY() - 3000 < b.getY()) && (a.getY() + 3000 > b.getY());
	}

	@Override
	public EntityID getTarget() {
		return this.result;
	}

	@Override
	public RoadDetector precompute(PrecomputeData precomputeData) {

		super.precompute(precomputeData);
		this.pathPlanning.precompute(precomputeData);
		this.clustering.precompute(precomputeData);
		this.guidelineCreator.precompute(precomputeData);
		if (this.getCountPrecompute() >= 2) {
			return this;
		}
		return this;
	}

	@Override
	public RoadDetector resume(PrecomputeData precomputeData) {
		super.resume(precomputeData);
		this.pathPlanning.resume(precomputeData);
		this.clustering.resume(precomputeData);
		//this.clustering.resume(precomputeData);
		if (this.getCountResume() >= 2) {
			return this;
		}
		this.guidelineCreator.resume(precomputeData);
		this.judgeRoad = guidelineCreator.getJudgeRoad();

		int policeCount = 1;
		double agentX = this.agentInfo.getX();
		double agentY = this.agentInfo.getY();
		for(StandardEntity se : this.worldInfo.getEntitiesOfType(POLICE_FORCE)){
			PoliceForce police = (PoliceForce) se;
			if(!police.getID().equals(this.agentInfo.getID())){
				if(agentX > police.getX() - 30000 && agentX < police.getX() + 30000
					&& agentY > police.getY() - 30000 && agentY < police.getY() + 30000){
					++policeCount;
				}
			}
		}
		if(policeCount > 5){
			this.needClearCommonRoad = false;
		}
		if(this.needClearCommonRoad && CSUConstants.DEBUG_NEED_CLEAR_COMMON){
			System.out.println("need clear common");
		}else if (CSUConstants.DEBUG_NEED_CLEAR_COMMON){
			System.out.println("NO NEED TO CLEAR");
		}

		return this;
	}

	@Override
	public RoadDetector preparate() {

		super.preparate();
		this.pathPlanning.preparate();
		this.clustering.preparate();
		if (this.getCountPreparate() >= 2) {
			return this;
		}
		this.guidelineCreator.preparate();
		this.judgeRoad = guidelineCreator.getJudgeRoad();

		int policeCount = 1;
		double agentX = this.agentInfo.getX();
		double agentY = this.agentInfo.getY();
		for(StandardEntity se : this.worldInfo.getEntitiesOfType(POLICE_FORCE)){
			PoliceForce police = (PoliceForce) se;
			if(!police.getID().equals(this.agentInfo.getID())){
				if(agentX > police.getX() - 30000 && agentX < police.getX() + 30000
						&& agentY > police.getY() - 30000 && agentY < police.getY() + 30000){
					++policeCount;
				}
			}
		}
		if(policeCount > 5){
			this.needClearCommonRoad = false;
		}
		if(this.needClearCommonRoad && CSUConstants.DEBUG_NEED_CLEAR_COMMON){
			System.out.println("need clear common");
		}else if (CSUConstants.DEBUG_NEED_CLEAR_COMMON){
			System.out.println("NO NEED TO CLEAR");
		}


		return this;
	}

	@Override
	public RoadDetector updateInfo(MessageManager messageManager) {
		this.messageManager = messageManager;
		super.updateInfo(messageManager);
		if (this.getCountUpdateInfo() >= 2) {
			return this;
		}
		Set<EntityID> changed = this.agentInfo.getChanged().getChangedEntities();
		for (EntityID entityID : changed){
			StandardEntity entity = this.worldInfo.getEntity( entityID );
			if (entity instanceof Civilian){
				Civilian civilian = (Civilian) entity;
				if (civilian.isBuriednessDefined()&&civilian.getBuriedness()>0&&civilian.isHPDefined()&&civilian.getHP()>0){
					Boolean flag = true;
					for (EntityID entityID1 : changed){
						StandardEntity standardEntity = this.worldInfo.getEntity(entityID);
						if (standardEntity instanceof FireBrigade && ((FireBrigade) standardEntity).getPosition().equals(civilian.getPosition())){
							flag =false;
						}
					}
					if (flag){
						this.sendCivilian.add(civilian);
					}
				}
			}
			if (entity instanceof FireBrigade){
				for (Civilian civilian : this.sendCivilian){
					if (civilian.isBuriednessDefined()&&civilian.getBuriedness()>0&&civilian.isHPDefined()&&civilian.getHP()>0){
						messageManager.addMessage(new MessageCivilian(false, StandardMessagePriority.HIGH,civilian));
					}
				}
				sendCivilian.clear();
			}
		}

		this.Reflect_Message();
		this.pathPlanning.updateInfo(messageManager);
		this.clustering.updateInfo(messageManager);
		this.guidelineCreator.updateInfo(messageManager);

		return this;
	}


	private Set<EntityID> get_all_Bloacked_Entrance_of_Building(Building building) {
		Set<EntityID> all_Bloacked_Entrance = new HashSet<>();
		EntityID buildingID = building.getID();
		get_all_Bloacked_Entrance_result(all_Bloacked_Entrance, buildingID);
		return all_Bloacked_Entrance;

	}
	private Set<EntityID> get_all_Entrance_of_Building(Building building) {
		Set<EntityID> all_Bloacked_Entrance = new HashSet<>();
		EntityID buildingID = building.getID();
		get_all_Entrance_result(all_Bloacked_Entrance, buildingID);
		return all_Bloacked_Entrance;

	}
	private void get_all_Entrance_result(Set<EntityID> all_Bloacked_Entrance, EntityID id) {
		StandardEntity target = this.worldInfo.getEntity(id);
		Queue<StandardEntity> visited = new LinkedList<>();;
		Queue<StandardEntity> open_list = new LinkedList<>();;
		open_list.add(target);
		while(!open_list.isEmpty()){
			StandardEntity standardEntity = open_list.poll();
			Building building = (Building) standardEntity;
			visited.add(standardEntity);
			for (EntityID neighbourID : building.getNeighbours()) {
				StandardEntity neighbour = this.worldInfo.getEntity(neighbourID);
				if (neighbour instanceof Road || neighbour instanceof Hydrant) {
					all_Bloacked_Entrance.add(neighbourID);
				}
				else if(neighbour instanceof Building)
				{
					if(!visited.contains(neighbour))
					{
						open_list.add(neighbour);
					}
				}
			}
		}
	}
	private void get_all_Bloacked_Entrance_result(Set<EntityID> all_Bloacked_Entrance, EntityID id) {
		StandardEntity target = this.worldInfo.getEntity(id);
		Queue<StandardEntity> visited = new LinkedList<>();;
		Queue<StandardEntity> open_list = new LinkedList<>();;
		open_list.add(target);
		while(!open_list.isEmpty()){
			StandardEntity standardEntity = open_list.poll();
			Building building = (Building) standardEntity;
			visited.add(standardEntity);
			for (EntityID neighbourID : building.getNeighbours()) {
				StandardEntity neighbour = this.worldInfo.getEntity(neighbourID);
				if (neighbour instanceof Road || neighbour instanceof Hydrant) {
					Road road = (Road) neighbour;
					if (!this.noNeedToClear.contains(road.getID()))
						all_Bloacked_Entrance.add(neighbourID);
				}
				else if(neighbour instanceof Building)
				{
					if(!visited.contains(neighbour))
					{
						open_list.add(neighbour);
					}
				}
			}
		}
	}

	/**
	 * @Description: 路况更新，对于路线优先级的判别
	 * @Author: Bochun-Yue
	 * @Date: 2/25/20
	 */

	private void update_roads() {
//		Target_Road.addAll(this.worldInfo.getChanged().getChangedEntities());
		Target_Road = this.worldInfo.getChanged().getChangedEntities();
		Collection<StandardEntity> allHuman = this.worldInfo.getEntitiesOfType(CIVILIAN,AMBULANCE_TEAM,POLICE_FORCE,FIRE_BRIGADE);
		for (EntityID id : Target_Road) {
			StandardEntity entity = this.worldInfo.getEntity(id);
			//road
			if (entity instanceof Road) {
				Road road = (Road) entity;
				if (this.isRoadPassable(road)) {
					this.TOPTOPRoad.remove(road.getID());
//					this.SOSroad.remove(road.getID());
					this.topLevelBlockedRoad.remove(road.getID());
					this.halfTopLevelBlockedRoad.remove(road.getID());
					this.midLevelBlockedRoad.remove(road.getID());
					this.halfLowLevelBlockedRoad.remove(road.getID());
					this.lowLevelBlockedRoad.remove(road.getID());
					this.firstLevelBlockedRoad.remove(road.getID());
					this.noNeedToClear.add(road.getID());
					continue;
				}
			}
			if (Cluster_Center != null && !Clear_Flag) {
				int dist = 0;
				if (entity instanceof Area) {
					dist = GetDistance((Area) entity, Cluster_Center);
					if (dist > Cluster_Range)
						continue;
				} else if (entity instanceof Human) {
					Human human = (Human) entity;
					EntityID position = human.getPosition();
					StandardEntity positionEntity = this.worldInfo.getEntity(position);
					if(!(positionEntity instanceof Area))
						continue;
					dist = GetDistance((Area) positionEntity, Cluster_Center);
					if (dist > Cluster_Range) 
						continue;
				}
			}
			//refuge
			if (entity != null && entity instanceof Refuge) {
				this.TOPTOPRoad.addAll(this.get_all_Bloacked_Entrance_of_Building((Building) entity));
			}


			if(entity != null && entity instanceof GasStation) {
				Collection<StandardEntity> clusterEntities = null;
				if (this.clustering != null) {
					int clusterIndex = this.clustering.getClusterIndex(entity.getID());
					if (clusterIndex != -1) {
						clusterEntities = this.clustering.getClusterEntities(clusterIndex);
					}
					if(!clusterEntities.isEmpty()) {
						for (StandardEntity se : clusterEntities) {
							if (se instanceof Road || se instanceof Hydrant) {
								Road road = (Road) se;
								double dis = this.worldInfo.getDistance(road.getID(),entity.getID());
								if(dis < 50000 && ! this.isRoadPassable(road) && !this.noNeedToClear.contains(road)){
									this.topLevelBlockedRoad.add(road.getID());
								}
							}
						}
					}
				}
			}



			//firebrigade和ambulanceteam
			if (entity instanceof FireBrigade || entity instanceof AmbulanceTeam) {
				Human human = (Human) entity;
				EntityID position = human.getPosition();
				StandardEntity positionEntity = this.worldInfo.getEntity(position);
				if (positionEntity instanceof Road) {
					//堵了埋了或者离blockade距离小于1000
					if (this.is_agent_stucked(human, (Road) positionEntity) || this.is_agent_Buried(human, (Road) positionEntity)) {
						this.firstLevelBlockedRoad.add(position);
					}
				} else if (positionEntity instanceof Building) {
					Set<EntityID> entrances = this.get_all_Bloacked_Entrance_of_Building((Building) positionEntity);
					// if(entrances.size() == (Area)positionEntity.getEdge())
					this.firstLevelBlockedRoad.addAll(entrances);
				}
			}
			//civilian
			else if (entity instanceof Civilian) {
				Human human = (Human) entity;
				if (!human.isPositionDefined() || !human.isHPDefined()){//|| human.getDamage() <= 25) {//没有damage或者400s内不会死亡就不用揪
					continue;
				}
				EntityID position = human.getPosition();
				StandardEntity positionEntity = this.worldInfo.getEntity(position);
				if (positionEntity instanceof Road) {
					//堵了埋了或者离blockade距离小于1000
					if (this.is_agent_stucked(human, (Road) positionEntity) || this.is_agent_Buried(human, (Road) positionEntity)) {
						this.midLevelBlockedRoad.add(position);
					}
				} else if (positionEntity instanceof Building) {
					if (((Building) positionEntity).isBrokennessDefined()&& ((Building) positionEntity).getBrokenness()>0){
						Set<EntityID> entrances = this.get_all_Bloacked_Entrance_of_Building((Building) positionEntity);
//						this.midLevelBlockedRoad.addAll(entrances);
						this.firstLevelBlockedRoad.addAll(entrances);
					}
				}
			}
			//building
			else if (entity instanceof Building){
				Building building = (Building) entity;
				if (!(building.isBrokennessDefined()&&building.getBrokenness()>0)){
					continue;
				}

				boolean flag = false;
				for (StandardEntity standardEntity : allHuman){
					Human human = (Human) standardEntity;
					if (human.getPosition().equals(building.getID())){
						flag = true;
					}
				}
				if (flag){
					Set<EntityID> entrances = this.get_all_Bloacked_Entrance_of_Building((Building) entity);
//						this.midLevelBlockedRoad.addAll(entrances);
					this.firstLevelBlockedRoad.addAll(entrances);
				}
			}
//			road
			if (entity instanceof Road) {
				Road road = (Road) entity;
				if (!this.noNeedToClear.contains(road.getID())){
					if(this.needClearCommonRoad) {
						Boolean buildingFlag = false;
						for (EntityID neighbourid : road.getNeighbours()) {
							StandardEntity neighbour = this.worldInfo.getEntity(neighbourid);
							if (neighbour instanceof Building) {
								buildingFlag = true;
								break;
							}
						}
						if (!buildingFlag) {
							this.halfLowLevelBlockedRoad.add(road.getID());
						}
					}
				}
			}
		}
		Target_Road.removeAll(this.noNeedToClear);
		Target_Road.removeAll(this.firstLevelBlockedRoad);
		Target_Road.removeAll(this.topLevelBlockedRoad);
		Target_Road.removeAll(this.halfTopLevelBlockedRoad);
		Target_Road.removeAll(this.midLevelBlockedRoad);
		Target_Road.removeAll(this.lowLevelBlockedRoad);
		Target_Road.removeAll(this.halfLowLevelBlockedRoad);
		Target_Road.removeAll(this.TOPTOPRoad);

	}

	/**
	 * @Description: 道路可否通过，以guideline是否被覆盖为判断标准
	 * @Author: Bochun-Yue
	 * @Date: 3/7/20
	 */
	private boolean isRoadPassable(Road road) {
		if(this.agentInfo.getTime() <= this.scenarioInfo.getKernelAgentsIgnoreuntil()){
			return false;
		}
		if (!road.isBlockadesDefined() || road.getBlockades().isEmpty()) {
			return true;
		}
		Collection<Blockade> blockades = this.worldInfo.getBlockades(road).stream().filter(Blockade::isApexesDefined)
				.collect(Collectors.toSet());
		Line2D guideline = null;
		for (guidelineHelper r : this.judgeRoad) {
			if (r.getSelfID().equals(road.getID())) {
				guideline = r.getGuideline();
			}
		}
		if (guideline != null) {
			for (Blockade blockade : blockades) {
				List<Point2D> Points = GeometryTools2D.vertexArrayToPoints(blockade.getApexes());
				for (int i = 0; i < Points.size(); ++i) {
					if (i != Points.size() - 1) {
						double crossProduct1 = this.getCrossProduct(guideline, Points.get(i));
						double crossProduct2 = this.getCrossProduct(guideline, Points.get(i + 1));
						if (crossProduct1 < 0 && crossProduct2 > 0 || crossProduct1 > 0 && crossProduct2 < 0) {
							Line2D line = new Line2D(Points.get(i), Points.get(i + 1));
							Point2D intersect = GeometryTools2D.getIntersectionPoint(line, guideline);
							if (intersect != null) {
								return false;
							}
						}
					} else {
						double crossProduct1 = this.getCrossProduct(guideline, Points.get(i));
						double crossProduct2 = this.getCrossProduct(guideline, Points.get(0));
						if (crossProduct1 < 0 && crossProduct2 > 0 || crossProduct1 > 0 && crossProduct2 < 0) {
							Line2D line = new Line2D(Points.get(i), Points.get(0));
							Point2D intersect = GeometryTools2D.getIntersectionPoint(line, guideline);
							if (intersect != null) {
								return false;
							}
						}
					}
				}
			}
			return true;
		}
		return false;
	}

	/**
	 * @Description: 叉积
	 * @Author: Bochun-Yue
	 * @Date: 3/7/20
	 */
	private double getCrossProduct(Line2D line, Point2D point) {

		double X = point.getX();
		double Y = point.getY();
		double X1 = line.getOrigin().getX();
		double Y1 = line.getOrigin().getY();
		double X2 = line.getEndPoint().getX();
		double Y2 = line.getEndPoint().getY();

		return ((X2 - X1) * (Y - Y1) - (X - X1) * (Y2 - Y1));
	}


	/**
	 * @Description: 根据changedEntities的信息进行通讯等操作
	 * @Date: 2/28/20
	 */
	private void preProcessChangedEntities(MessageManager messageManager) {
		for (EntityID id : this.worldInfo.getChanged().getChangedEntities()) {
			StandardEntity entity = worldInfo.getEntity(id);
			if (entity != null && entity instanceof Building) {
				Building building = (Building) worldInfo.getEntity(id);
				if (building.isFierynessDefined() && building.getFieryness() > 0 && building.getFieryness() != 4) {
					messageManager.addMessage(new MessageBuilding(true, building));
					messageManager.addMessage(new MessageBuilding(false, building));
				}
			} else if (entity != null && entity instanceof Civilian) {
				Civilian civilian = (Civilian) entity;
				if ((civilian.isHPDefined() && civilian.getHP() > 1000 && civilian.isDamageDefined() && civilian.getDamage() > 0)
						|| ((civilian.isPositionDefined() && !(worldInfo.getEntity(civilian.getPosition()) instanceof Refuge))
						&& (worldInfo.getEntity(civilian.getPosition()) instanceof Building))) {
					messageManager.addMessage(new MessageCivilian(true, civilian));
					messageManager.addMessage(new MessageCivilian(false, civilian));
				}
			}
		}
	}


	private boolean is_inside_blocks(Human human, Blockade blockade) {

		if (blockade.isApexesDefined() && human.isXDefined() && human.isYDefined()) {
			if (blockade.getShape().contains(human.getX(), human.getY())) {
				return true;
			}
		}
		return false;
	}

	private boolean is_agent_stucked(Human agent, Road road) {
		Collection<Blockade> blockades = this.worldInfo.getBlockades(road);
		for (Blockade blockade : blockades) {
			if (this.is_inside_blocks(agent, blockade)) {
				return true;
			}
		}
		return false;
	}

	private boolean is_agent_Buried(Human human, Road road) {

		if (human.isBuriednessDefined() && human.getBuriedness() > 0)
			return true;
		return false;
	}

	private void Reflect_Message() {

		// reflectMessage
		Set<EntityID> changedEntities = this.worldInfo.getChanged().getChangedEntities();
		for (CommunicationMessage message : messageManager.getReceivedMessageList()) {
			Class<? extends CommunicationMessage> messageClass = message.getClass();
			if (messageClass == MessageAmbulanceTeam.class) {
				this.reflectMessage((MessageAmbulanceTeam) message);
			} else if (messageClass == MessageFireBrigade.class) {
				this.reflectMessage((MessageFireBrigade) message);
			} else if (messageClass == MessageRoad.class) {
				this.reflectMessage((MessageRoad) message, changedEntities);
			} else if (messageClass == MessagePoliceForce.class) {
				this.reflectMessage((MessagePoliceForce) message);
			} else if (messageClass == CommandPolice.class) {
				this.reflectMessage((CommandPolice) message);
			}
		}
	}

	private void reflectMessage(MessageRoad messageRoad, Collection<EntityID> changedEntities) {
		if (messageRoad.isBlockadeDefined() && !changedEntities.isEmpty() && !changedEntities.contains(messageRoad.getBlockadeID())) {
			MessageUtil.reflectMessage(this.worldInfo, messageRoad);
		}
		if (messageRoad.isPassable()) {
			this.topLevelBlockedRoad.remove(messageRoad.getRoadID());
			this.SOSroad.remove(messageRoad.getRoadID());
			this.halfTopLevelBlockedRoad.remove(messageRoad.getRoadID());
			this.midLevelBlockedRoad.remove(messageRoad.getRoadID());
			this.halfLowLevelBlockedRoad.remove(messageRoad.getRoadID());
			this.lowLevelBlockedRoad.remove(messageRoad.getRoadID());
			this.halfTopLevelBlockedRoad.remove(messageRoad.getRoadID());
			this.noNeedToClear.add(messageRoad.getRoadID());
			Road road = (Road) this.worldInfo.getEntity(messageRoad.getRoadID());
		}
	}

	private void reflectMessage(MessageAmbulanceTeam messageAmbulanceTeam) {
//		if (messageAmbulanceTeam.getPosition() == null) {
//			return;
//		}
//		if (messageAmbulanceTeam.getAction() == MessageAmbulanceTeam.ACTION_RESCUE) {
//			StandardEntity position = this.worldInfo.getEntity(messageAmbulanceTeam.getPosition());
//			if (position != null && position instanceof Building) {
//			this.noNeedToClear.addAll(((Building) position).getNeighbours());
//			this.targetAreas.removeAll(((Building) position).getNeighbours());
//			}
//		} else if (messageAmbulanceTeam.getAction() == MessageAmbulanceTeam.ACTION_LOAD) {
//			StandardEntity position = this.worldInfo.getEntity(messageAmbulanceTeam.getPosition());
//			if (position != null && position instanceof Building) {
//				this.targetAreas.removeAll(((Building) position).getNeighbours());
//			this.noNeedToClear.addAll(((Building) position).getNeighbours());
//}
//		} else if (messageAmbulanceTeam.getAction() == MessageAmbulanceTeam.ACTION_MOVE) {
//			if (messageAmbulanceTeam.getTargetID() == null) {
//				return;
//			}
//			StandardEntity target = this.worldInfo.getEntity(messageAmbulanceTeam.getTargetID());
//			if (target instanceof Building) {
//				for (EntityID id : ((Building) target).getNeighbours()) {
//					StandardEntity neighbour = this.worldInfo.getEntity(id);
//					if (neighbour instanceof Road) {
//						if(this.is_area_blocked(id)) {
////					 		this.topLevelBlockedRoad.add(id);
//							this.midLevelBlockedRoad.add(id);
//						}
//					}
//				}
//			} else if (target instanceof Human) {
//				Human human = (Human) target;
//				if (human.isPositionDefined()) {
//					StandardEntity position = this.worldInfo.getPosition(human);
//					if (position instanceof Building) {
//						for (EntityID id : ((Building) position).getNeighbours()) {
//							StandardEntity neighbour = this.worldInfo.getEntity(id);
//							if (neighbour instanceof Road) {
//								if(this.is_area_blocked(id)) {
//									this.midLevelBlockedRoad.add(id);
//								}
//							}
//						}
//					}
//				}
//			}
//		}
	}

	private void reflectMessage(MessageFireBrigade messageFireBrigade) {
//		if (messageFireBrigade.getTargetID() == null) {
//			return;
//		}
//		if (messageFireBrigade.getAction() == MessageFireBrigade.ACTION_REFILL) {
//			StandardEntity target = this.worldInfo.getEntity(messageFireBrigade.getTargetID());
//			if (target instanceof Building) {
//				for (EntityID id : ((Building) target).getNeighbours()) {
//					StandardEntity neighbour = this.worldInfo.getEntity(id);
//					if (neighbour instanceof Road) {
//						if(this.is_area_blocked(id)) {
//							this.midLevelBlockedRoad.add(id);
//						}
//					}
//				}
//			} else if (target.getStandardURN() == StandardEntityURN.HYDRANT) {
//				this.entrance_of_Refuge_and_Hydrant.add(target.getID());
//			}
//		}
	}

	private void reflectMessage(MessagePoliceForce messagePoliceForce) {
//		if (messagePoliceForce.getAction() == MessagePoliceForce.ACTION_CLEAR) {
//			if (messagePoliceForce.getAgentID().getValue() != this.agentInfo.getID().getValue()) {
//				if (messagePoliceForce.isTargetDefined()) {
//					EntityID targetID = messagePoliceForce.getTargetID();
//					if (targetID == null) {
//						return;
//					}
//					StandardEntity entity = this.worldInfo.getEntity(targetID);
//					if (entity == null) {
//						return;
//					}
//
//					if (entity instanceof Area) {
//						this.midLevelBlockedRoad.remove(targetID);
//						this.noNeedToClear.add(targetID);
//						if (this.result != null && this.result.getValue() == targetID.getValue()) {
//							if (this.agentInfo.getID().getValue() < messagePoliceForce.getAgentID().getValue()) {
//								this.result = null;
//								//this.result = this.get_new_result();
//							}
//						}
//					} else if (entity.getStandardURN() == StandardEntityURN.BLOCKADE) {
//						EntityID position = ((Blockade) entity).getPosition();
//						if(position!=null) {
//							this.midLevelBlockedRoad.remove(targetID);
//							this.noNeedToClear.add(targetID);
//							if (this.result != null && this.result.getValue() == position.getValue()) {
//								if (this.agentInfo.getID().getValue() < messagePoliceForce.getAgentID().getValue()) {
//									this.result = null;
//									//this.result = this.get_new_result();
//								}
//							}
//						}
//					}
//
//				}
//			}
//		}
	}

	Boolean withinSOSRange(PoliceForce police, EntityID SOStarget) {
		Boolean needToSave = false;
		StandardEntity entity = this.worldInfo.getEntity(SOStarget);
		if (entity != null && (entity instanceof Road || entity instanceof Hydrant || entity instanceof Building)) {
//			Road targetRoad = (Road) entity;

			double manhattanDistance = this.getManhattanDistance(police.getX(), police.getY(), ((Area) entity).getX(), ((Area) entity).getY());
			//31445.392 in CSUConstants
			double time = manhattanDistance / 31445.392;
			if (time < this.timeThreshold) {
				needToSave = true;
			}

		}
		return needToSave;
	}

	private Boolean SOSinMyCluster(PoliceForce police, EntityID SOStarget) {
		Boolean needToSave = false;
		StandardEntity entity = this.worldInfo.getEntity(SOStarget);
		if (entity != null) {
			Collection<StandardEntity> clusterEntities = null;
			List<EntityID> clusterEntityIDs = new ArrayList<>();
			if (this.clustering != null) {
				int clusterIndex = this.clustering.getClusterIndex(this.agentInfo.getID());
				if (clusterIndex != -1) {
					clusterEntities = this.clustering.getClusterEntities(clusterIndex);
				}
				if(clusterEntities!= null && !clusterEntities.isEmpty()) {
					for (StandardEntity se : clusterEntities) {
						clusterEntityIDs.add(se.getID());
					}
				}
				if (clusterEntityIDs.contains(entity.getID())) needToSave = true;
			}
		}
		return needToSave;
	}


	private void reflectMessage(CommandPolice commandPolice) {
		boolean flag = false;
		PoliceForce police = (PoliceForce) this.agentInfo.me();
		EntityID SOStarget = commandPolice.getTargetID();
		if(this.withinSOSRange(police,SOStarget) || this.SOSinMyCluster(police,SOStarget)){
			flag = true;
		}
		if (flag && commandPolice.getAction() == CommandPolice.ACTION_CLEAR) {
			if (commandPolice.getTargetID() == null) {
				return;
			}
//			System.out.println("called Police :"+this.agentInfo.getID().getValue());
//			System.out.println("targetSOSroad:"+SOStarget.getValue());
			StandardEntity target = this.worldInfo.getEntity(commandPolice.getTargetID());
			if (target instanceof Road || target instanceof Hydrant) {
//				if (!this.noNeedToClear.contains(target.getID())) {
//					this.SOSroad.add(target.getID());
//				}
				this.SOSroad.add(target.getID());
			} else if (target.getStandardURN() == StandardEntityURN.BLOCKADE) {
				Blockade blockade = (Blockade) target;
				if (blockade.isPositionDefined()) {
					StandardEntity position = worldInfo.getEntity(blockade.getPosition());
					if (position != null) {
						if (position instanceof Road || position instanceof Hydrant) {
//							if (!this.noNeedToClear.contains(position.getID())) {
//								this.SOSroad.add(position.getID());
//							}
							this.SOSroad.add(position.getID());
						}
					}
				}
			} else if (target instanceof Building) {

				Queue<StandardEntity> visited = new LinkedList<>();;
				Queue<StandardEntity> open_list = new LinkedList<>();;
				open_list.add(target);
				while(!open_list.isEmpty()){
					StandardEntity standardEntity = open_list.poll();
					Building building = (Building) standardEntity;
					visited.add(standardEntity);
					for (EntityID neighbourID : building.getNeighbours()) {
						StandardEntity neighbour = this.worldInfo.getEntity(neighbourID);
						if (neighbour instanceof Road || neighbour instanceof Hydrant) {
//							if (!this.noNeedToClear.contains(neighbourID) ) {
//								this.SOSroad.add(neighbourID);
////								this.SOSCivilianRoad.add(neighbourID);
//							}
							Road road = (Road) neighbour;
							if (road.isBlockadesDefined()&&(road.getBlockades().isEmpty()||road.getBlockades()==null)){
								continue;
							}
//							if (!this.noNeedToClear.contains(neighbourID)){
							this.SOSroad.add(neighbourID);
//							}
						}
						else if(neighbour instanceof Building)
						{
							if(!visited.contains(neighbour))
							{
								open_list.add(neighbour);
							}
						}
					}
				}
			}
		}
	}
}


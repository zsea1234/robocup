package CSU_Yunlu_2023.module.complex.fb.tools;

import CSU_Yunlu_2023.standard.Ruler;
import CSU_Yunlu_2023.world.CSUWorldHelper;
import CSU_Yunlu_2023.world.object.CSUBuilding;
import javolution.util.FastMap;
import javolution.util.FastSet;
import rescuecore2.misc.Pair;
import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.FireBrigade;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.worldmodel.EntityID;

import java.util.*;


public class FbUtilities {
	private static final int EXTINGUISH_DISTANCE_THRESHOLD = 5000;

	private CSUWorldHelper worldHelper;

	// constructor
	public FbUtilities(CSUWorldHelper worldHelper) {
		this.worldHelper = worldHelper;
	}
	

	public static int waterNeededToExtinguish(CSUBuilding building) {
		int groundArea = building.getSelfBuilding().getGroundArea();
    	int floors = building.getSelfBuilding().getFloors();
    	int buildingCode = building.getSelfBuilding().getBuildingCode();
    	double temperature = building.getEstimatedTemperature();
    	
    	return WaterCoolingEstimator.getWaterNeeded(groundArea, floors, buildingCode, temperature, 20);
	}

	public static int calculateWaterPower(CSUWorldHelper world, CSUBuilding building) {
		int agentWater = ((FireBrigade) world.getSelfHuman()).getWater();
		int maxPower = world.getConfig().maxPower;

		return Math.min(agentWater, maxPower);
	}


	public SortedSet<Pair<Pair<EntityID, Double>, Double>> reRankBuildings(
            SortedSet<Pair<Pair<EntityID, Double>, Double>> buildings, FireBrigade fbAgent) {
		CSUBuilding csuBuilding;
		EntityID agentId = fbAgent.getID();
		SortedSet<Pair<Pair<EntityID, Double>, Double>> result = new TreeSet<>(pairComparator_new);
		Set<CSUBuilding> buildingsExtinguishable = getBuildingInExtinguishableRange(this.worldHelper, agentId);
		boolean inMyExtiguishableRange;

		int i = 0, AstarCount = 10;
		if (worldHelper.isMapMedium())
			AstarCount = 5;
		if (worldHelper.isMapHuge())
			AstarCount = 3;

		for (Pair<Pair<EntityID, Double>, Double> next : buildings) {
			csuBuilding = worldHelper.getCsuBuilding(next.first().first());

			if (i >= AstarCount) {
				result.add(new Pair<Pair<EntityID, Double>, Double>(next.first(), csuBuilding.BUILDING_VALUE));
				i++;
				continue;
			}

			inMyExtiguishableRange = buildingsExtinguishable.contains(csuBuilding);
			EntityID location = getNearest(worldHelper,csuBuilding.getAreasInExtinguishableRange(), agentId);

			if (inMyExtiguishableRange /*&& isReacable(worldHelper, router, location)*/) {
				result.add(new Pair<Pair<EntityID, Double>, Double>(next.first(), csuBuilding.BUILDING_VALUE));
				i++;
			} else {
				csuBuilding.BUILDING_VALUE -= 10000; ///*
				result.add(new Pair<Pair<EntityID, Double>, Double>(next.first(), csuBuilding.BUILDING_VALUE));
				i++;
			}
		}
		return result;
	}

	public static Set<CSUBuilding> getBuildingInExtinguishableRange(CSUWorldHelper world, EntityID source) {
		Set<CSUBuilding> result = new FastSet<>();
		double distance = world.getConfig().extinguishableDistance - EXTINGUISH_DISTANCE_THRESHOLD;
		Collection<StandardEntity> inRange = world.getObjectsInRange(source, (int)(distance * 1.5));
		for (StandardEntity entity : inRange) {
			if (entity instanceof Building) {
				if (world.getDistance(entity.getID(), source) < distance)
					result.add(world.getCsuBuilding(entity.getID()));
			}
		}
		return result;
	}
	
	public static List<EntityID> getAreaIdInExtinguishableRange(CSUWorldHelper world, EntityID source) {
		List<EntityID> result = new ArrayList<>();
		double distance = world.getConfig().extinguishableDistance - EXTINGUISH_DISTANCE_THRESHOLD;
		Collection<StandardEntity> inRange = world.getObjectsInRange(source, (int)(distance * 1.5));
		
		for (StandardEntity entity : inRange) {
			if (entity instanceof Area && world.getDistance(entity.getID(), source) < distance)
				result.add(entity.getID());
		}
		return result;
	}

	public static EntityID getNearest(CSUWorldHelper world, List<EntityID> locations, EntityID start) {
		EntityID result = null;
		double minDistance = Double.MAX_VALUE;
		for (EntityID next : locations) {
			double distance = world.getDistance(start, next);
			if (distance < minDistance) {
				minDistance = distance;
				result = next;
			}
		}
		return result;
	}
	
	public static CSUBuilding getNearest(List<CSUBuilding> buildings, Pair<Integer, Integer> source){
		CSUBuilding targetBuilding = null;
		Building building;
		Pair<Integer, Integer> buildingLocation;
		double minDistance = Double.MAX_VALUE;
		double distance;
		for (CSUBuilding next : buildings) {
			building = next.getSelfBuilding();
			buildingLocation = new Pair<Integer, Integer>(building.getX(), building.getY());
			distance = Ruler.getDistance(source, buildingLocation);
			if (distance < minDistance) {
				minDistance = distance;
				targetBuilding = next;
			}
		}
		return targetBuilding;
	}

	public static Comparator<Pair<EntityID, Double>> pairComparator = new Comparator<Pair<EntityID, Double>>() {
		@Override
		public int compare(Pair<EntityID, Double> o1, Pair<EntityID, Double> o2) {
			double value1 = o1.second();
			double value2 = o2.second();
			if (value1 < value2) ///>
				return 1;
			if (value1 > value2) ///<
				return -1;
			return 0;
		}
	};  
	
	public static Comparator<Pair<Pair<EntityID, Double>, Double>> pairComparator_new =
			new Comparator<Pair<Pair<EntityID, Double>, Double>>() {

		@Override
		public int compare(Pair<Pair<EntityID, Double>, Double> o1, Pair<Pair<EntityID, Double>, Double> o2) {
			if (o1.second().doubleValue() < o2.second().doubleValue()) ///>
				return 1;
			if (o1.second().doubleValue() > o2.second().doubleValue()) ///<
				return -1;
			
			if (o1.second().doubleValue() == o2.second().doubleValue()) {
				if (o1.first().second().doubleValue() > o2.first().second().doubleValue()) {
					return 1;
				}
				if (o1.first().second().doubleValue() < o2.first().second().doubleValue()) {
					return -1;
				}
				
				if (o1.first().second().doubleValue() == o2.first().second().doubleValue()) {
					return -1;
				}
			}
			
			return 0;
		}
	};

	public CSUBuilding findNewestIgniteBuilding(List<CSUBuilding> buildings) {
		int minTime = Integer.MAX_VALUE;
		int tempTime;
		CSUBuilding newestIgniteBuilding = null;
		
		for (CSUBuilding next : buildings) {
			tempTime = worldHelper.getTime() - next.getIgnitionTime();
			if (tempTime < minTime) {
				minTime = tempTime;
				newestIgniteBuilding = next;
				if (minTime == 0)
					break;
			}
		}
		return newestIgniteBuilding;
	}

	public Set<EntityID> findMaximalCovering(Set<CSUBuilding> buildings) {
		Map<EntityID, Set<CSUBuilding>> areasMap = new FastMap<EntityID, Set<CSUBuilding>>(); // visibleFrom - buildings
		Map<CSUBuilding, Set<EntityID>> buildingsMap = new FastMap<CSUBuilding, Set<EntityID>>(); // building - visibleFroms


		for (CSUBuilding building : buildings) {
			buildingsMap.put(building, new FastSet<EntityID>(building.getVisibleFrom()));
			for (EntityID id : building.getVisibleFrom()) {
				Set<CSUBuilding> bs = areasMap.get(id);
				if (bs == null) {
					bs = new FastSet<CSUBuilding>();
				}
				bs.add(building);
				areasMap.put(id, bs);
			}
		}


		return processMatrix(buildingsMap, areasMap);
	}

	private Set<EntityID> processMatrix(Map<CSUBuilding, Set<EntityID>> buildingsMap, Map<EntityID, Set<CSUBuilding>> areasMap) {

		Set<CSUBuilding> buildingsToRemove = new FastSet<CSUBuilding>();
		int i = 0, j;
		for (CSUBuilding building1 : buildingsMap.keySet()) {
			j = 0;
			if (!buildingsToRemove.contains(building1)) {
				for (CSUBuilding building2 : buildingsMap.keySet()) {
					if (i > j++ || building1.equals(building2) || buildingsToRemove.contains(building2)) { //continue;
					} else if (buildingsMap.get(building1).containsAll(buildingsMap.get(building2))) {
						buildingsToRemove.add(building1);
					} else if (buildingsMap.get(building2).containsAll(buildingsMap.get(building1))) {
						buildingsToRemove.add(building2);
					}
				}
			}
			i++;
		}
		for (CSUBuilding b : buildingsToRemove) {
			buildingsMap.remove(b);
			for (Set<CSUBuilding> bs : areasMap.values()) {
				bs.remove(b);
			}
		}

		i = 0;
		Set<EntityID> areasToRemove = new FastSet<EntityID>();
		for (EntityID area1 : areasMap.keySet()) {
			j = 0;
			if (!areasToRemove.contains(area1)) {
				for (EntityID area2 : areasMap.keySet()) {
					if (i > j++ || area1.equals(area2) || areasToRemove.contains(area2)) { //continue;
					} else if (areasMap.get(area1).containsAll(areasMap.get(area2))) {
						areasToRemove.add(area2);
					} else if (areasMap.get(area2).containsAll(areasMap.get(area1))) {
						areasToRemove.add(area1);
					}
				}
			}
			i++;
		}
		for (EntityID id : areasToRemove) {
			areasMap.remove(id);
			for (Set<EntityID> ids : buildingsMap.values()) {
				ids.remove(id);
			}
		}

		if (!areasToRemove.isEmpty() || !buildingsToRemove.isEmpty()) {
			return processMatrix(buildingsMap, areasMap);
		}
		return areasMap.keySet();
	}
}

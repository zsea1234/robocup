package CSU_Yunlu_2023.standard;

import adf.core.agent.info.WorldInfo;
import rescuecore2.misc.collections.LazyMap;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import java.io.Serializable;
import java.util.*;

@SuppressWarnings("serial")
final public class EntranceHelper extends HashMap<Building, Set<Road>> implements Serializable{
	protected WorldInfo world = null;
	private Set<Road> allEntrance;

	private Map<Road, List<Building>> entranceBuildingMap = new LazyMap<Road, List<Building>>() {

		@Override
		public List<Building> createValue() {
			return new LinkedList<Building>();
		}
	};

	public EntranceHelper(WorldInfo world) {
		this.world = world;
		this.allEntrance = new HashSet<Road>();
		for (StandardEntity entity : world.getEntitiesOfType(StandardEntityURN.BUILDING)) {
			Building building = (Building) entity;
			Set<Road> entrances = setEntrance(building);
			for (Road road : entrances) {
				this.entranceBuildingMap.get(road).add(building);
			}
			this.allEntrance.addAll(entrances);
			this.put((Building) entity, entrances);
		}
	}

	private Set<Road> setEntrance(Building building) {
		Set<Road> roads = new HashSet<Road>();
		Stack<Area> stack = new Stack<Area>();
		Set<Area> visited = new HashSet<Area>();
		stack.add(building);
		do {
			Area entity = stack.pop();
			visited.add(entity);
			if (entity instanceof Road) {
				roads.add((Road) entity);
				// world.getCsuRoad(entity.getID()).setEntrance(true);
			} else if (entity instanceof Building) {
				Building bld = (Building) entity;
				for (EntityID id : bld.getNeighbours()) {
					Area e = (Area) world.getEntity(id);
					if (!visited.contains(e)) {
						stack.push(e);
					}
				}
			}
		} while (!stack.isEmpty());

		return roads;
	}

	public Set<Road> getEntrance(Building building) {
		return this.get(building);
	}

	public List<Building> getBuilding(Road road) {
		return this.entranceBuildingMap.get(road);
	}

	public boolean isEntrance(Road road) {
		return allEntrance.contains(road);
	}

}

package CSU_Yunlu_2023.module.complex.fb.targetSelection;

import CSU_Yunlu_2023.module.algorithm.fb.Cluster;
import CSU_Yunlu_2023.world.object.CSUBuilding;
import rescuecore2.standard.entities.Area;


public class FireBrigadeTarget {
	private CSUBuilding csuBuilding;
	private Cluster cluster;
	private Area locationToExtinguish;
	
	public FireBrigadeTarget(Cluster cluster, CSUBuilding csuBuilding) {
		this.cluster = cluster;
		this.csuBuilding = csuBuilding;
	}
	
	public void setLocationToExtinguish(Area locationToExtinguish) {
		this.locationToExtinguish = locationToExtinguish;
	}
	
	public Area getLocationToExtinguish() {
		return this.locationToExtinguish;
	}
	
	public Cluster getCluster() {
		return this.cluster;
	}
	
	public CSUBuilding getCsuBuilding() {
		return this.csuBuilding;
	}
}

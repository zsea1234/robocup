package CSU_Yunlu_2023.module.complex.at;

import rescuecore2.worldmodel.EntityID;

import java.util.Set;

public class ATBuilding {
    private int priority = 0;
    private final EntityID id;


    private int fieriness;
    private int brokenness;

    private boolean isOccupied;
    private boolean isBurning;
    private boolean isReachable;
    private boolean isWayBurning;

    private EntityID wayBurningBuilding;

    //用来判断有没有搜的必要
    private boolean isBurnt; //与fieriness绑定
    private boolean isBroken; //与brokenness绑定
    private boolean isVisited;

    private Set<EntityID> humanMayBe;
    private Set<EntityID> humanConfirmed;

    public ATBuilding(EntityID id) {
        this.id = id;
    }
}

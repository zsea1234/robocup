package CSU_Yunlu_2023.world;

import CSU_Yunlu_2023.CSUConstants;
import CSU_Yunlu_2023.module.algorithm.fb.CSUFireClustering;
import CSU_Yunlu_2023.world.object.CSUBuilding;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.module.AbstractModule;
import javolution.util.FastSet;
import rescuecore.Simulator;
import rescuecore2.misc.Pair;
import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.worldmodel.EntityID;

import java.awt.*;
import java.io.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * @description: 改进自csu_2016
 * @Date: 03/07/2020
 */
public class CSUFireBrigadeWorld extends CSUWorldHelper{
    private CSUFireClustering fireClustering;
    private Simulator simulator;
    private float rayRate = 0.0025f;
    private CurrentState currentState = CurrentState.notExplored;

    private Set<CSUBuilding> estimatedBurningBuildings = new FastSet<CSUBuilding>();
    private String buildingConnectedFilename;

    public CSUFireBrigadeWorld(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        buildingConnectedFilename = getUniqueMapNumber() + ".cnd";
    }

    @Override
    public AbstractModule precompute(PrecomputeData precomputeData) {
        super.precompute(precomputeData);
        if (this.getCountPrecompute() >= 2) {
            return this;
        }
        try {
            long before = System.currentTimeMillis();
            processConnected(PrecomputeData.PRECOMP_DATA_DIR.getAbsolutePath() + File.separator + buildingConnectedFilename);
            long after = System.currentTimeMillis();
            System.out.println("Creation of cnd took " + (after - before) + "ms");
        } catch (IOException e) {
            e.printStackTrace();
        }
        return this;
    }

    @Override
    public CSUWorldHelper resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        if (this.getCountResume() >= 2) {
            return this;
        }
        loadCND(PrecomputeData.PRECOMP_DATA_DIR.getAbsolutePath() + File.separator + buildingConnectedFilename);
        return this;
    }

    @Override
    public CSUWorldHelper preparate() {
        super.preparate();
        if (this.getCountPreparate() >= 2) {
            return this;
        }
        loadCND(PrecomputeData.PRECOMP_DATA_DIR.getAbsolutePath() + File.separator + buildingConnectedFilename);
        return this;
    }

    @Override
    public CSUWorldHelper updateInfo(MessageManager messageManager) {
        return super.updateInfo(messageManager);
    }

    public Set<EntityID> getAreaInShape(Shape shape) {
        Set<EntityID> result = new FastSet<>();
        for (StandardEntity next : getBuildingsWithURN(worldInfo)) {
            Area area = (Area) next;
            if (!(area.isXDefined() && area.isYDefined()))
                continue;
            Point p = new Point(area.getX(), area.getY());
            if (shape.contains(p))
                result.add(area.getID());
        }
        return result;
    }

    public Simulator getSimulator() {
        return this.simulator;
    }


    public Set<CSUBuilding> getEstimatedBurningBuildings() {
        return this.estimatedBurningBuildings;
    }

    public Set<EntityID> getBuildingsInNESW(int dir, EntityID position) {
        Set<EntityID> buildingsInDir = new FastSet<>();
        Pair<Integer, Integer> location = worldInfo.getLocation(position);
        int x = location.first();
        int y = location.second();
        int range = (int)(this.config.extinguishableDistance*0.9);
        Point pointLT = new Point(x-range, y+range);
        Point pointT = new Point(x, y+range);
        Point pointR = new Point(x+range, y);
        Point pointRB = new Point(x+range, y-range);
        Point pointB = new Point(x, y-range);
        Point pointL = new Point(x-range, y);
        Collection<StandardEntity> entities;
        switch(dir) {
            case 0:
                entities = worldInfo.getObjectsInRange(pointLT.x, pointLT.y, pointR.x, pointR.y);
                break;
            case 1:
                entities = worldInfo.getObjectsInRange(pointT.x, pointT.y, pointRB.x, pointRB.y);
                break;
            case 2:
                entities = worldInfo.getObjectsInRange(pointL.x, pointL.y, pointRB.x, pointRB.y);
                break;
            case 3:
                entities = worldInfo.getObjectsInRange(pointLT.x, pointLT.y, pointB.x, pointB.y);
                break;
            default:
                entities = worldInfo.getObjectsInRange(x-range, y-range, x+range, y+range);
        }

        for(StandardEntity se : entities) {
            if(se instanceof Building) {
                EntityID id = se.getID();
                buildingsInDir.add(id);
            }
        }
        return buildingsInDir;
    }

    public enum CurrentState {
        notExplored,
        searched,
        burning,
        extiniguished,
        needResearched
    }

    public void updateCurrentState() {

    }

    public CSUFireClustering getFireClustering() {
        return fireClustering;
    }

    public void setFireClustering(CSUFireClustering fireClustering) {
        this.fireClustering = fireClustering;
    }

    private void loadCND(String fileName) {
        File f = new File(fileName);
        if (!f.exists() || !f.canRead()) {
            processConnected();
            return;
        }
        try {
            BufferedReader br = new BufferedReader(new FileReader(f));
            String nl;
            while (null != (nl = br.readLine())) {
                int x = Integer.parseInt(nl);
                int y = Integer.parseInt(br.readLine());
                int quantity = Integer.parseInt(br.readLine());
                double hitRate = Double.parseDouble(br.readLine());
                List<CSUBuilding> bl = new ArrayList<CSUBuilding>();
                List<EntityID> bIDs = new ArrayList<EntityID>();
                List<Float> weight = new ArrayList<Float>();
                for (int c = 0; c < quantity; c++) {
                    int ox = Integer.parseInt(br.readLine());
                    int oy = Integer.parseInt(br.readLine());
                    Building building =  getBuildingInPoint(ox, oy);
                    if (building == null) {
                        System.err.println("building not found: " + ox + "," + oy);
                        br.readLine();
                    } else {
                        bl.add(getCsuBuilding(building.getID()));
                        bIDs.add(building.getID());
                        weight.add(Float.parseFloat(br.readLine()));
                    }

                }
                Building b = getBuildingInPoint(x, y);
                CSUBuilding building = getCsuBuilding(b.getID());
                building.setConnectedBuildins(bl);
                building.setConnectedValues(weight);
                building.setHitRate(hitRate);
            }
            br.close();
            System.out.println("Read from file:" + fileName);
        } catch (IOException ex) {
            processConnected();
            ex.printStackTrace();
        }
    }

    private void processConnected() {
        if (CSUConstants.DEBUG_INIT_CND) {
            System.out.println("  Init CND .... ");
        }
        getCsuBuildings().parallelStream().forEach(csuBuilding -> {
            csuBuilding.initWallValue(this);
        });
    }

    private void processConnected(String fileName) throws IOException {
        processConnected();
        File f = new File(fileName);

        f.delete();
        f.createNewFile();

        final BufferedWriter bw = new BufferedWriter(new FileWriter(f));
        getCsuBuildings().forEach(csuBuilding -> {

            try {
                bw.write(csuBuilding.getSelfBuilding().getX() + "\n");
                bw.write(csuBuilding.getSelfBuilding().getY() + "\n");
                bw.write(csuBuilding.getConnectedBuildings().size() + "\n");

                bw.write(csuBuilding.getHitRate() + "\n");

                for (int c = 0; c < csuBuilding.getConnectedBuildings().size(); c++) {
                    CSUBuilding building = csuBuilding.getConnectedBuildings().get(c);
                    Float val = csuBuilding.getConnectedValues().get(c);
                    bw.write(building.getSelfBuilding().getX() + "\n");
                    bw.write(building.getSelfBuilding().getY() + "\n");
                    bw.write(val + "\n");
                }
            } catch (IOException ex) {
                ex.printStackTrace();
            }

        });

        bw.close();

        System.out.println("CND is created.");
    }
}

package CSU_Yunlu_2023.world.object;

import java.awt.*;
import java.awt.geom.Line2D;
import java.util.Hashtable;
import java.util.Random;

public class CSUWall {

	private static Random random = new Random(23);

	private static double RAY_RATE = 0.0025;

	private static double MAX_RAY_DISTANCE = 50000;
	
	public static final int MAX_SAMPLE_DISTANCE = 100000;

	public int x1;
	public int y1;
	public int x2;
	public int y2;
	
	public CSUBuilding owner;
	
	public int rays;
	

	public int hits;

	public int selfHits;

	public int strange;
	
	public double length;
	public Point startPoint;
	public Point endPoint;

	
	public CSUWall(int x1, int y1, int x2, int y2, CSUBuilding owner) {
		this.x1 = x1;
		this.y1 = y1;
		this.x2 = x2;
		this.y2 = y2;
		this.startPoint = new Point(x1, y1);
		this.endPoint = new Point(x2, y2);
		this.length = startPoint.distance(endPoint);
		
		this.owner = owner;
		this.hits = 0;
		this.rays = (int)Math.ceil(length * RAY_RATE);
	}
	
	public boolean validate() {
		return !(startPoint.x == endPoint.x && startPoint.y == endPoint.y);
	}
	
	public void findHits(CSUBuilding building) {
		this.selfHits = 0; 
		this.strange = 0;  
		for (int emitted = 0; emitted < this.rays; emitted++) {
			Point start = getRandomPoint(startPoint, endPoint);
			if (start == null) {
				strange++;
				continue;
			}
			Point end = getRandomPoint(startPoint, MAX_RAY_DISTANCE);
			CSUWall closest = null;
			double minDistance = Double.MAX_VALUE;
			for (CSUWall other : building.getAllWalls()) {
				if (other == this)
					continue;
				Point cross = intersect(start, end, other.startPoint, other.endPoint);
				if (cross != null && cross.distance(start) < minDistance) {
					minDistance = cross.distance(start);
					closest = other;
				}
			}
			if (closest == null) {
                continue;
            }
            if (closest.owner == this.owner) {
                selfHits++;
            }
            if (closest != this && closest != null && closest.owner != owner) {
                hits++;
                Hashtable<CSUBuilding, Integer> hashtable = building.getConnectedBuildingTable();
                Integer value = hashtable.get(closest.owner);
                int temp = 0;
                if (value != null) {
                    temp = value.intValue();
                }
                temp++;
//                hashtable.put(closest.owner, new Integer(temp));
                hashtable.put(closest.owner, temp);
            }
		}
	}

    public static Point getRandomPoint(Point a, Point b) {
        float[] mb = getAffineFunction((float) a.x, (float) a.y, (float) b.x, (float) b.y);
        float dx = (Math.max((float) a.x, (float) b.x) - Math.min((float) a.x, (float) b.x));
        dx *= random.nextDouble();
        dx += Math.min((float) a.x, (float) b.x);
        if (mb == null) {
            //vertical line
            int p = Math.max(a.y, b.y) - Math.min(a.y, b.y);
            p = (int) (p * Math.random());
            p = p + Math.min(a.y, b.y);
            return new Point(a.x, p);
        }
        float y = mb[0] * dx + mb[1];
        Point rtv = new Point((int) dx, (int) y);
        if (rtv != null) {
            System.currentTimeMillis();
        }
        return rtv;
    }

    public static Point getRandomPoint(Point a, double length) {
        double angel = random.nextDouble() * 2d * Math.PI;
        double x = Math.sin(angel) * length;
        double y = Math.cos(angel) * length;
        return new Point((int) x + a.x, (int) y + a.y);
    }

    public static float[] getAffineFunction(float x1, float y1, float x2, float y2) {
        if (x1 == x2) 
        	return null;
        float m = (y1 - y2) / (x1 - x2);
        float b = y1 - m * x1;
        return new float[]{m, b};
    }

    public static Point intersect(Point a, Point b, Point c, Point d) {
        float[] rv = intersect(new float[]{a.x, a.y, b.x, b.y, c.x, c.y, d.x, d.y});
        if (rv == null) return null;
        return new Point((int) rv[0], (int) rv[1]);
    }
    
    public static float[] intersect(float[] points) {
        float[] l1 = getAffineFunction(points[0], points[1], points[2], points[3]);
        float[] l2 = getAffineFunction(points[4], points[5], points[6], points[7]);
        float[] crossing;
        if (l1 == null && l2 == null) {
            return null;
        } else if (l1 == null && l2 != null) {
            crossing = intersect(l2[0], l2[1], points[0]);
        } else if (l1 != null && l2 == null) {
            crossing = intersect(l1[0], l1[1], points[4]);
        } else {
            crossing = intersect(l1[0], l1[1], l2[0], l2[1]);
        }
        if (crossing == null) {
            return null;
        }
        if (!(inBounds(points[0], points[1], points[2], points[3], crossing[0], crossing[1]) &&
                inBounds(points[4], points[5], points[6], points[7], crossing[0], crossing[1]))) return null;
        return crossing;
    }

    public static float[] intersect(float m1, float b1, float x) {
        return new float[]{x, m1 * x + b1};
    }

    public static float[] intersect(float m1, float b1, float m2, float b2) {
        if (m1 == m2) {
            return null;
        }
        float x = (b2 - b1) / (m1 - m2);
        float y = m1 * x + b1;
        return new float[]{x, y};
    }

    public static boolean inBounds(float bx1, float by1, float bx2, float by2, float x, float y) {
        if (bx1 < bx2) {
            if (x < bx1 || x > bx2) return false;
        } else {
            if (x > bx1 || x < bx2) return false;
        }
        if (by1 < by2) {
            if (y < by1 || y > by2) return false;
        } else {
            if (y > by1 || y < by2) return false;
        }
        return true;
    }

    public Line2D getLine() {
        if (startPoint == null || endPoint == null)
            return null;

        return new Line2D.Double(startPoint, endPoint);
    }
}

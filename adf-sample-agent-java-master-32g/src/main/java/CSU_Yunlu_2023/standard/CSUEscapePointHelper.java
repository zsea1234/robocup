package CSU_Yunlu_2023.standard;

import rescuecore2.misc.geometry.Line2D;

import java.awt.*;
import java.util.LinkedList;
import java.util.List;

public class CSUEscapePointHelper {
	private List<CSUBlockadeHelper> realteBlockades = new LinkedList<>();;
	
	private Point underlyingPoint;
	
	private Line2D line;
	
	public CSUEscapePointHelper(Point point, Line2D line, CSUBlockadeHelper... blockade) {
		this.setUnderlyingPoint(point);
		this.setLine(line);
		
		for (CSUBlockadeHelper next : blockade) {
			this.realteBlockades.add(next);
		}
	}

	public List<CSUBlockadeHelper> getRelateBlockade() {
		return this.realteBlockades;
	}
	
	public void addCsuBlockade(CSUBlockadeHelper blockade) {
		this.realteBlockades.add(blockade);
	}
	
	public boolean removeCsuBLockade(CSUBlockadeHelper blockade) {
		return this.realteBlockades.remove(blockade);
	}

	public Point getUnderlyingPoint() {
		return underlyingPoint;
	}

	public void setUnderlyingPoint(Point underlyingPoint) {
		this.underlyingPoint = underlyingPoint;
	}

	public Line2D getLine() {
		return line;
	}

	public void setLine(Line2D line) {
		this.line = line;
	}
}

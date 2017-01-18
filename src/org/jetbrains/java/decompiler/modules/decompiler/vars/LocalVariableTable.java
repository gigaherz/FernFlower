package org.jetbrains.java.decompiler.modules.decompiler.vars;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.java.decompiler.modules.decompiler.stats.Statement;

public class LocalVariableTable {
  private Map<StartEndPair, Set<LocalVariable>> endpoints;
  private ArrayList<LocalVariable> allLVT;
  private Map<Integer, List<LocalVariable>> mapLVT;

  public LocalVariableTable(int len) {
    endpoints = new HashMap<StartEndPair,Set<LocalVariable>>(len);
    allLVT = new ArrayList<LocalVariable>(len);
  }

  public void addVariable(LocalVariable v) {
    allLVT.add(v);
    v.addTo(endpoints);
  }

  public void mergeLVTs(LocalVariableTable otherLVT) {
   for (LocalVariable other : otherLVT.allLVT) {
      int idx = allLVT.indexOf(other);
      if (idx < 0) {
        allLVT.add(other);
      }
      else {
        LocalVariable mine = allLVT.get(idx);
        mine.merge(other);
      }
    }
    mapLVT = null; // Invalidate the cache and rebuild it.
  }

  public LocalVariable find(int index, Integer bytecodeOffset) {
    //System.out.println(indent + stat.getClass().getSimpleName() + " (" + start +", " + end + ")");

    Map<Integer, List<LocalVariable>> map = getMapVarNames();
    if (!map.containsKey(index)) {
      return null;
    }
    for (LocalVariable lvt : map.get(index)) {
      if (lvt.start_pc == bytecodeOffset) {
        return lvt;
      }
    }
    return null;
  }

  public Map<Integer, List<LocalVariable>> getMapVarNames() {
    if (mapLVT == null)
      buildNameMap();
    return mapLVT;
  }

  private void buildNameMap() {
    Map<Integer, Integer> versions = new HashMap<Integer, Integer>();
    mapLVT = new HashMap<Integer,List<LocalVariable>>();
    for (LocalVariable lvt : allLVT) {
      Integer idx = versions.get(lvt.index);
      if (idx == null)
        idx = 1;
      else
        idx++;
      versions.put(lvt.index, idx);
      List<LocalVariable> lvtList = mapLVT.get(lvt.index);
      if (lvtList == null) {
        lvtList = new ArrayList<LocalVariable>();
        mapLVT.put(lvt.index, lvtList);
      }
      lvtList.add(lvt);
    }
  }

  public List<LocalVariable> getCandidates(int index) {
    return getMapVarNames().get(index);
  }

  public Map<Integer, LocalVariable> getVars(Statement statement) {
    Map<Integer, LocalVariable> ret = new HashMap<Integer, LocalVariable>();
    if (statement == null) {
      return ret;
    }
    StartEndPair sepair = statement.getStartEndRange();
    if (endpoints.containsKey(sepair)) {
      for (LocalVariable lvt : endpoints.get(sepair)) {
        ret.put(lvt.index, lvt);
      }
    }
    return ret;
  }
}
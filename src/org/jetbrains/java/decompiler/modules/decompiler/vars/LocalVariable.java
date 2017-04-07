package org.jetbrains.java.decompiler.modules.decompiler.vars;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.jetbrains.java.decompiler.struct.gen.VarType;

public class LocalVariable implements Comparable<LocalVariable> {
  public static final Comparator<LocalVariable> INDEX_SORTER = new Comparator<LocalVariable>() {
    @Override
    public int compare(LocalVariable o1, LocalVariable o2) {
      if (o1.index != o2.index) return o1.index - o2.index;
      if (o1.start_pc != o2.start_pc) return o1.start_pc - o2.start_pc;
      return o1.end - o2.end;
    }
  };

  public final String name;
  public final int end;
  public final int index;
  private String sig;
  private boolean isLVTT;

  public final int start_pc;
  public final int length;
  final String descriptor;

  public LocalVariable(int start, int length, String name, String desc, int index, boolean isLVTT) {
    this.name = name;
    this.descriptor = desc;
    this.start_pc = start;
    this.end = start+length;
    this.length = length;
    this.index = index;
    this.isLVTT = isLVTT;
  }

  void merge(LocalVariable other) {
    if (other.isLVTT && this.sig == null) {
      this.sig = other.descriptor;
    }
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof LocalVariable))
      return false;
    return ((LocalVariable) obj).index == index && ((LocalVariable) obj).end == end;
  }

  @Override
  public int hashCode() {
    return index * 31 + end;
  }

  public void addTo(Map<StartEndPair, Set<LocalVariable>> endpoints) {
    StartEndPair sepair = new StartEndPair(this.start_pc, this.end);
    Set<LocalVariable> ends = endpoints.get(sepair);
    if (ends == null) {
      ends = new HashSet<LocalVariable>();
      endpoints.put(sepair, ends);
    }
    ends.add(this);
  }

  @Override
  public int compareTo(LocalVariable o) {
    if (o.end > end) return -1;
    if (o.end < end) return 1;
    if (o.index > index) return -1;
    if (o.index < index) return 1;
    return 0;
  }
  @Override
  public String toString() {
    return "\'("+index+","+end+")"+ descriptor +(sig!=null ? "<"+sig+"> ":" ")+name+"\'";
  }

  public String getDesc() {
    return descriptor;
  }

  public String getSig() {
    return sig;
  }

  public VarType getVarType() {
    return new VarType(descriptor);
  }

  public LocalVariable rename(String newName) {
    LocalVariable lvtVariable = new LocalVariable(start_pc, length, newName, descriptor, index, isLVTT);
    lvtVariable.sig = this.sig;
    return lvtVariable;
  }
}
/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.java.decompiler.modules.decompiler.vars;

import org.jetbrains.java.decompiler.code.CodeConstants;
import org.jetbrains.java.decompiler.main.ClassesProcessor.ClassNode;
import org.jetbrains.java.decompiler.main.DecompilerContext;
import org.jetbrains.java.decompiler.main.collectors.VarNamesCollector;
import org.jetbrains.java.decompiler.modules.decompiler.ExprProcessor;
import org.jetbrains.java.decompiler.modules.decompiler.exps.AssignmentExprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.ConstExprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.Exprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.NewExprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.VarExprent;
import org.jetbrains.java.decompiler.modules.decompiler.sforms.DirectGraph.ExprentIterator;
import org.jetbrains.java.decompiler.modules.decompiler.stats.CatchAllStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.CatchStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.DoStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.IfStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.Statement;
import org.jetbrains.java.decompiler.struct.StructClass;
import org.jetbrains.java.decompiler.struct.StructMethod;
import org.jetbrains.java.decompiler.struct.gen.MethodDescriptor;
import org.jetbrains.java.decompiler.struct.gen.VarType;
import org.jetbrains.java.decompiler.struct.gen.generics.GenericType;
import org.jetbrains.java.decompiler.util.ExprentUtil;
import org.jetbrains.java.decompiler.util.StatementIterator;

import java.util.*;
import java.util.Map.Entry;

public class VarDefinitionHelper {

  private final HashMap<Integer, Statement> mapVarDefStatements;

  // statement.id, defined vars
  private final HashMap<Integer, HashSet<Integer>> mapStatementVars;

  private final HashSet<Integer> implDefVars;

  private final VarProcessor varproc;

  private final Statement root;
  private final StructMethod mt;

  public VarDefinitionHelper(Statement root, StructMethod mt, VarProcessor varproc) {
    mapVarDefStatements = new HashMap<>();
    mapStatementVars = new HashMap<>();
    implDefVars = new HashSet<>();
    this.varproc = varproc;
    this.root = root;
    this.mt = mt;

    VarNamesCollector vc = DecompilerContext.getVarNamesCollector();

    boolean thisvar = !mt.hasModifier(CodeConstants.ACC_STATIC);

    MethodDescriptor md = MethodDescriptor.parseDescriptor(mt.getDescriptor());

    int paramcount = 0;
    if (thisvar) {
      paramcount = 1;
    }
    paramcount += md.params.length;


    // method parameters are implicitly defined
    int varindex = 0;
    for (int i = 0; i < paramcount; i++) {
      implDefVars.add(varindex);
      VarVersionPair vvp = new VarVersionPair(varindex, 0);
      varproc.setVarName(vvp, vc.getFreeName(varindex));
      if (thisvar) {
        if (i == 0) {
          varindex++;
        }
        else {
          varindex += md.params[i - 1].stackSize;
        }
      }
      else {
        varindex += md.params[i].stackSize;
      }
    }

    if (thisvar) {
      StructClass current_class = (StructClass)DecompilerContext.getProperty(DecompilerContext.CURRENT_CLASS);

      varproc.getThisVars().put(new VarVersionPair(0, 0), current_class.qualifiedName);
      varproc.setVarName(new VarVersionPair(0, 0), "this");
      vc.addName("this");
    }

    // catch variables are implicitly defined
    LinkedList<Statement> stack = new LinkedList<>();
    stack.add(root);

    while (!stack.isEmpty()) {
      Statement st = stack.removeFirst();

      List<VarExprent> lstVars = null;
      if (st.type == Statement.TYPE_CATCHALL) {
        lstVars = ((CatchAllStatement)st).getVars();
      }
      else if (st.type == Statement.TYPE_TRYCATCH) {
        lstVars = ((CatchStatement)st).getVars();
      }

      if (lstVars != null) {
        for (VarExprent var : lstVars) {
          implDefVars.add(var.getIndex());
          VarVersionPair pair = new VarVersionPair(var);
          varproc.setVarName(pair, vc.getFreeName(var.getIndex()));
          var.setDefinition(true);
        }
      }

      stack.addAll(st.getStats());
    }

    initStatement(root);
  }

  public void setVarDefinitions() {
    VarNamesCollector vc = DecompilerContext.getVarNamesCollector();

    for (Entry<Integer, Statement> en : mapVarDefStatements.entrySet()) {
      Statement stat = en.getValue();
      Integer index = en.getKey();

      if (implDefVars.contains(index)) {
        // already implicitly defined
        continue;
      }

      varproc.setVarName(new VarVersionPair(index.intValue(), 0), vc.getFreeName(index));

      // special case for
      if (stat.type == Statement.TYPE_DO) {
        DoStatement dstat = (DoStatement)stat;
        if (dstat.getLooptype() == DoStatement.LOOP_FOR) {

          if (dstat.getInitExprent() != null && setDefinition(dstat.getInitExprent(), index)) {
            continue;
          }
          else {
            List<Exprent> lstSpecial = Arrays.asList(dstat.getConditionExprent(), dstat.getIncExprent());
            for (VarExprent var : getAllVars(lstSpecial)) {
              if (var.getIndex() == index.intValue()) {
                stat = stat.getParent();
                break;
              }
            }
          }
        }
        else if (dstat.getLooptype() == DoStatement.LOOP_FOREACH) {
          if (dstat.getInitExprent() != null && dstat.getInitExprent().type == Exprent.EXPRENT_VAR) {
            VarExprent var = (VarExprent)dstat.getInitExprent();
            if (var.getIndex() == index.intValue()) {
              var.setDefinition(true);
              continue;
      }
          }
        }
      }

      Statement first = findFirstBlock(stat, index);

      List<Exprent> lst;
      if (first == null) {
        lst = stat.getVarDefinitions();
      }
      else if (first.getExprents() == null) {
        lst = first.getVarDefinitions();
      }
      else {
        lst = first.getExprents();
      }

      boolean defset = false;

      // search for the first assignement to var [index]
      int addindex = 0;
      for (Exprent expr : lst) {
        if (setDefinition(expr, index)) {
          defset = true;
          break;
        }
        else {
          boolean foundvar = false;
          for (Exprent exp : expr.getAllExprents(true)) {
            if (exp.type == Exprent.EXPRENT_VAR && ((VarExprent)exp).getIndex() == index) {
              foundvar = true;
              break;
            }
          }
          if (foundvar) {
            break;
          }
        }
        addindex++;
      }

      if (!defset) {
        VarExprent var = new VarExprent(index.intValue(), varproc.getVarType(new VarVersionPair(index.intValue(), 0)), varproc);
        var.setDefinition(true);

        if (varproc.getLVT() != null) {
          LVTVariable lvt = findLVT(index.intValue(), stat);
          if (lvt != null) {
            var.setLVT(lvt);
          }
        }

        lst.add(addindex, var);
      }
    }

    mergeVars(root);
    propogateLVTs(root);
  }


  // *****************************************************************************
  // private methods
  // *****************************************************************************

  private LVTVariable findLVT(int index, Statement stat) {
    if (stat.getExprents() == null) {
      for (Object obj : stat.getSequentialObjects()) {
        if (obj instanceof Statement) {
          LVTVariable lvt = findLVT(index, (Statement)obj);
          if (lvt != null) {
            return lvt;
          }
        }
        else if (obj instanceof Exprent) {
          LVTVariable lvt = findLVT(index, (Exprent)obj);
          if (lvt != null) {
            return lvt;
          }
        }
      }
    }
    else {
      for (Exprent exp : stat.getExprents()) {
        LVTVariable lvt = findLVT(index, exp);
        if (lvt != null) {
          return lvt;
        }
      }
    }
    return null;
  }

  private LVTVariable findLVT(int index, Exprent exp) {
    for (Exprent e : exp.getAllExprents(false)) {
      LVTVariable lvt = findLVT(index, e);
      if (lvt != null) {
        return lvt;
      }
    }

    if (exp.type != Exprent.EXPRENT_VAR) {
      return null;
    }

    VarExprent var = (VarExprent)exp;
    return var.getIndex() == index ? var.getLVT() : null;
  }

  private Statement findFirstBlock(Statement stat, Integer varindex) {

    LinkedList<Statement> stack = new LinkedList<>();
    stack.add(stat);

    while (!stack.isEmpty()) {
      Statement st = stack.remove(0);

      if (stack.isEmpty() || mapStatementVars.get(st.id).contains(varindex)) {

        if (st.isLabeled() && !stack.isEmpty()) {
          return st;
        }

        if (st.getExprents() != null) {
          return st;
        }
        else {
          stack.clear();

          switch (st.type) {
            case Statement.TYPE_SEQUENCE:
              stack.addAll(0, st.getStats());
              break;
            case Statement.TYPE_IF:
            case Statement.TYPE_ROOT:
            case Statement.TYPE_SWITCH:
            case Statement.TYPE_SYNCRONIZED:
              stack.add(st.getFirst());
              break;
            default:
              return st;
          }
        }
      }
    }

    return null;
  }

  private Set<Integer> initStatement(Statement stat) {

    HashMap<Integer, Integer> mapCount = new HashMap<>();

    List<VarExprent> condlst;

    if (stat.getExprents() == null) {

      // recurse on children statements
      List<Integer> childVars = new ArrayList<>();
      List<Exprent> currVars = new ArrayList<>();

      for (Object obj : stat.getSequentialObjects()) {
        if (obj instanceof Statement) {
          Statement st = (Statement)obj;
          childVars.addAll(initStatement(st));

          if (st.type == DoStatement.TYPE_DO) {
            DoStatement dost = (DoStatement)st;
            if (dost.getLooptype() != DoStatement.LOOP_FOR &&
                dost.getLooptype() != DoStatement.LOOP_FOREACH &&
                dost.getLooptype() != DoStatement.LOOP_DO) {
              currVars.add(dost.getConditionExprent());
            }
          }
          else if (st.type == DoStatement.TYPE_CATCHALL) {
            CatchAllStatement fin = (CatchAllStatement)st;
            if (fin.isFinally() && fin.getMonitor() != null) {
              currVars.add(fin.getMonitor());
            }
          }
        }
        else if (obj instanceof Exprent) {
          currVars.add((Exprent)obj);
        }
      }

      // children statements
      for (Integer index : childVars) {
        Integer count = mapCount.get(index);
        if (count == null) {
          count = new Integer(0);
        }
        mapCount.put(index, new Integer(count.intValue() + 1));
      }

      condlst = getAllVars(currVars);
    }
    else {
      condlst = getAllVars(stat.getExprents());
    }

    // this statement
    for (VarExprent var : condlst) {
      mapCount.put(new Integer(var.getIndex()), new Integer(2));
    }


    HashSet<Integer> set = new HashSet<>(mapCount.keySet());

    // put all variables defined in this statement into the set
    for (Entry<Integer, Integer> en : mapCount.entrySet()) {
      if (en.getValue().intValue() > 1) {
        mapVarDefStatements.put(en.getKey(), stat);
      }
    }

    mapStatementVars.put(stat.id, set);

    return set;
  }

  private static List<VarExprent> getAllVars(List<Exprent> lst) {

    List<VarExprent> res = new ArrayList<>();
    List<Exprent> listTemp = new ArrayList<>();

    for (Exprent expr : lst) {
      listTemp.addAll(expr.getAllExprents(true));
      listTemp.add(expr);
    }

    for (Exprent exprent : listTemp) {
      if (exprent.type == Exprent.EXPRENT_VAR) {
        res.add((VarExprent)exprent);
      }
    }

    return res;
  }

  private static boolean setDefinition(Exprent expr, Integer index) {
    if (expr.type == Exprent.EXPRENT_ASSIGNMENT) {
      Exprent left = ((AssignmentExprent)expr).getLeft();
      if (left.type == Exprent.EXPRENT_VAR) {
        VarExprent var = (VarExprent)left;
        if (var.getIndex() == index.intValue()) {
          var.setDefinition(true);
          return true;
        }
      }
    }
    return false;
  }

  private void propogateLVTs(Statement stat) {
    MethodDescriptor md = MethodDescriptor.parseDescriptor(mt.getDescriptor());
    Map<VarVersionPair, VarInfo> types = new LinkedHashMap<VarVersionPair, VarInfo>();

    int index = 0;
    if (!mt.hasModifier(CodeConstants.ACC_STATIC)) {
      types.put(new VarVersionPair(index++, 0), new VarInfo(null,null));
    }

    for (VarType var : md.params) {
      if (varproc.getLVT() != null) {
        List<LVTVariable> vars = varproc.getLVT().getCandidates(index);
        if (vars != null) {
          types.put(new VarVersionPair(index, 0), new VarInfo(null,null));
        }
      }
      index += var.stackSize;
    }

    findTypes(stat, types);

    Map<VarVersionPair,String> typeNames = new LinkedHashMap<VarVersionPair,String>();
    for (Entry<VarVersionPair, VarInfo> e : types.entrySet()) {
      typeNames.put(e.getKey(), e.getValue().typeName());
    }
    final StructMethod current_meth = (StructMethod)DecompilerContext.getProperty(DecompilerContext.CURRENT_METHOD);
    Map<VarVersionPair, String> renames = current_meth.getRenamer().rename(typeNames);

    // Stuff the parent context into enclosed child methods
    StatementIterator.iterate(root, new ExprentIterator(){
      @Override
      public int processExprent(Exprent exprent){
        ClassNode child = null;
        if (exprent.type == Exprent.EXPRENT_VAR) {
          VarExprent var = (VarExprent)exprent;
          if (var.isClassDef()) {
            child = DecompilerContext.getClassProcessor().getMapRootClasses().get(var.getVarType().value);
          }
        }
        else if (exprent.type == Exprent.EXPRENT_NEW) {
          NewExprent _new = (NewExprent)exprent;
          if (_new.isAnonymous()) { //TODO: Check for Lambda here?
            child = DecompilerContext.getClassProcessor().getMapRootClasses().get(_new.getNewType().value);
          }
        }

        if (child != null) {
          for (StructMethod meth : child.classStruct.getMethods()) {
            meth.getRenamer().addParentContext(current_meth.getRenamer());
          }
        }
        return 0;
      }
    });

    Map<VarVersionPair, LVTVariable> lvts = new HashMap<VarVersionPair, LVTVariable>();

    for (Entry<VarVersionPair, VarInfo> e : types.entrySet()) {
      VarVersionPair idx = e.getKey();
      // skip this. we can't rename it
      if (idx.var == 0 && !mt.hasModifier(CodeConstants.ACC_STATIC)) {
        continue;
      }
      LVTVariable lvt = e.getValue().lvt;
      if (renames!=null) {
        varproc.setVarName(idx, renames.get(idx));
      }
      if (lvt != null) {
        if (renames!=null) {
          lvt = lvt.rename(renames.get(idx));
        }
        varproc.setVarLVT(idx, lvt);
        lvts.put(idx, lvt);
      }
    }

    applyTypes(stat, lvts);
  }

  private void findTypes(Statement stat, Map<VarVersionPair, VarInfo> types) {
    if (stat == null) {
      return;
    }

    for (Exprent exp : stat.getVarDefinitions()) {
      findTypes(exp, types);
    }

    if (stat.getExprents() == null) {
      for (Object obj : stat.getSequentialObjects()) {
        if (obj instanceof Statement) {
          findTypes((Statement)obj, types);
        }
        else if (obj instanceof Exprent) {
          findTypes((Exprent)obj, types);
        }
      }
    }
    else {
      for (Exprent exp : stat.getExprents()) {
        findTypes(exp, types);
      }
    }
  }

  private void findTypes(Exprent exp, Map<VarVersionPair, VarInfo> types) {
    List<Exprent> lst = exp.getAllExprents(true);
    lst.add(exp);

    for (Exprent exprent : lst) {
      if (exprent.type == Exprent.EXPRENT_VAR) {
        VarExprent var = (VarExprent)exprent;
        VarVersionPair ver = new VarVersionPair(var);
        if (var.isDefinition()) {
          types.put(ver, new VarInfo(var.getLVT(), var.getVarType()));
        }
        else if (!types.containsKey(ver)) {
          types.put(ver, new VarInfo(var.getLVT(), var.getVarType()));
        }
      }
    }
  }

  private static class VarInfo {
    LVTVariable lvt;
    String cast;
    private VarInfo(LVTVariable lvt, VarType type) {
      if (lvt != null && lvt.getSig() != null) {
        cast = ExprProcessor.getCastTypeName(GenericType.parse(lvt.getSig()),false);
      }
      else if (lvt != null) {
        cast = ExprProcessor.getCastTypeName(lvt.getVarType(),false);
      }
      else if (type != null) {
        cast = ExprProcessor.getCastTypeName(type,false);
      }
      else {
        cast = "this";
      }
      this.lvt = lvt;
    }
    public String typeName() {
      return cast;
    }
  }

  private void applyTypes(Statement stat, Map<VarVersionPair, LVTVariable> types) {
    if (stat == null || types.size() == 0) {
      return;
    }

    for (Exprent exp : stat.getVarDefinitions()) {
        applyTypes(exp, types);
    }

    if (stat.getExprents() == null) {
      for (Object obj : stat.getSequentialObjects()) {
        if (obj instanceof Statement) {
          applyTypes((Statement)obj, types);
        }
        else if (obj instanceof Exprent) {
          applyTypes((Exprent)obj, types);
        }
      }
    }
    else {
      for (Exprent exp : stat.getExprents()) {
        applyTypes(exp, types);
      }
    }
  }

  private void applyTypes(Exprent exprent, Map<VarVersionPair, LVTVariable> types) {
    if (exprent == null) {
      return;
    }
    List<Exprent> lst = exprent.getAllExprents(true);
    lst.add(exprent);

    for (Exprent expr : lst) {
      if (expr.type == Exprent.EXPRENT_VAR) {
        VarExprent var = (VarExprent)expr;
        LVTVariable lvt = types.get(new VarVersionPair(var));
        if (lvt != null) {
          var.setLVT(lvt);
        } else {
          System.currentTimeMillis();
          //System.out.println("null " + new VarVersionPair(var));
        }
      }
    }
  }

  private VPPEntry mergeVars(Statement stat) {
    Map<Integer, VarVersionPair> parent = new HashMap<Integer, VarVersionPair>(); // Always empty dua!
    MethodDescriptor md = MethodDescriptor.parseDescriptor(mt.getDescriptor());

    int index = 0;
    if (!mt.hasModifier(CodeConstants.ACC_STATIC)) {
      parent.put(index, new VarVersionPair(index++, 0));
    }

    for (VarType var : md.params) {
      parent.put(index, new VarVersionPair(index, 0));
      index += var.stackSize;
    }

    Map<VarVersionPair, VarVersionPair> blacklist = new HashMap<VarVersionPair, VarVersionPair>();
    VPPEntry remap = mergeVars(stat, parent, new HashMap<Integer, VarVersionPair>(), blacklist);
    while (remap != null) {
      //System.out.println("Remapping: " + remap.getKey() + " -> " + remap.getValue());
      if (!remapVar(stat, remap.getKey(), remap.getValue())) {
        blacklist.put(remap.getKey(), remap.getValue());
      }
      remap = mergeVars(stat, parent, new HashMap<Integer, VarVersionPair>(), blacklist);
    }
    return null;
  }

  private VPPEntry mergeVars(Statement stat, Map<Integer, VarVersionPair> parent, Map<Integer, VarVersionPair> leaked, Map<VarVersionPair, VarVersionPair> blacklist) {
    Map<Integer, VarVersionPair> this_vars = new HashMap<Integer, VarVersionPair>();
    if (parent.size() > 0)
      this_vars.putAll(parent);

    if (stat.getVarDefinitions().size() > 0) {
      for (int x = 0; x < stat.getVarDefinitions().size(); x++) {
        Exprent exp = stat.getVarDefinitions().get(x);
        if (exp.type == Exprent.EXPRENT_VAR) {
          VarExprent var = (VarExprent)exp;
          int index = varproc.getRemapped(var.getIndex());
          if (this_vars.containsKey(index)) {
            stat.getVarDefinitions().remove(x);
            return new VPPEntry(var, this_vars.get(index));
          }
          this_vars.put(index, new VarVersionPair(var));
          leaked.put(index, new VarVersionPair(var));
        }
      }
    }

    Map<Integer, VarVersionPair> scoped = null;
    switch (stat.type) { // These are the type of statements that leak vars
      case Statement.TYPE_BASICBLOCK:
      case Statement.TYPE_GENERAL:
      case Statement.TYPE_ROOT:
      case Statement.TYPE_SEQUENCE:
        scoped = leaked;
    }

    if (stat.getExprents() == null) {
      List<Object> objs = stat.getSequentialObjects();
      for (int i = 0; i < objs.size(); i++) {
        Object obj = objs.get(i);
        if (obj instanceof Statement) {
          Statement st = (Statement)obj;

          //Map<VarVersionPair, VarVersionPair> blacklist_n = new HashMap<VarVersionPair, VarVersionPair>();
          Map<Integer, VarVersionPair> leaked_n = new HashMap<Integer, VarVersionPair>();
          VPPEntry remap = mergeVars(st, this_vars, leaked_n, blacklist);

          if (remap != null) {
            return remap;
          }
          /* TODO: See if we can optimize and only go up till needed.
          while (remap != null) {
            System.out.println("Remapping: " + remap.getKey() + " -> " + remap.getValue());
            VarVersionPair var = parent.get(varproc.getRemapped(remap.getValue().var));
            if (remap.getValue().equals(var)) { //Drill up to original declaration.
              return remap;
            }
            if (!remapVar(stat, remap.getKey(), remap.getValue())) {
              blacklist_n.put(remap.getKey(), remap.getValue());
            }
            leaked_n.clear();
            remap = mergeVars(st, this_vars, leaked_n, blacklist_n);
          }
          */

          if (leaked_n.size() > 0) {
            if (stat.type == Statement.TYPE_IF) {
              IfStatement ifst = (IfStatement)stat;
              if (obj == ifst.getIfstat() || obj == ifst.getElsestat()) {
                leaked_n.clear(); // Force no leaking at the end of if blocks
                // We may need to do this for Switches as well.. But havent run into that issue yet...
              }
              else if (obj == ifst.getFirst()) {
                leaked.putAll(leaked_n); //First is outside the scope so leak!
              }
            }
            else if (stat.type == Statement.TYPE_SWITCH ||
                     stat.type == Statement.TYPE_SYNCRONIZED) {
              if (obj == stat.getFirst()) {
                leaked.putAll(leaked_n); //First is outside the scope so leak!
              }
              else {
                leaked_n.clear();
              }
            }
            else if (stat.type == Statement.TYPE_TRYCATCH ||
                     stat.type == Statement.TYPE_CATCHALL) {
              leaked_n.clear(); // Catches can't leak anything mwhahahahah!
            }
            this_vars.putAll(leaked_n);
          }
        }
        else if (obj instanceof Exprent) {
          VPPEntry ret = processExprent((Exprent)obj, this_vars, scoped, blacklist);
          if (ret != null && !ExprentUtil.isVarReadFirst(ret.getValue(), stat, i + 1)) {
            return ret;
          }
        }
      }
    }
    else {
      List<Exprent> exps = stat.getExprents();
      for (int i = 0; i < exps.size(); i++) {
        VPPEntry ret = processExprent(exps.get(i), this_vars, scoped, blacklist);
        if (ret != null && !ExprentUtil.isVarReadFirst(ret.getValue(), stat, i + 1)) {
          return ret;
        }
      }
    }
    return null; // We made it with no remaps!!!!!!!
  }

  private VPPEntry processExprent(Exprent exp, Map<Integer, VarVersionPair> this_vars, Map<Integer, VarVersionPair> leaked, Map<VarVersionPair, VarVersionPair> blacklist) {
    VarExprent var = null;

    if (exp.type == Exprent.EXPRENT_ASSIGNMENT) {
      AssignmentExprent ass = (AssignmentExprent)exp;
      if (ass.getLeft().type != Exprent.EXPRENT_VAR) {
        return null;
      }

      var = (VarExprent)ass.getLeft();
    }
    else if (exp.type == Exprent.EXPRENT_VAR) {
      var = (VarExprent)exp;
    }

    if (var == null) {
      return null;
    }

    if (!var.isDefinition()) {
      return null;
    }

    int index = varproc.getRemapped(var.getIndex());
    VarVersionPair new_ = this_vars.get(index);
    if (new_ != null) {
      VarVersionPair old = new VarVersionPair(var);
      VarVersionPair black = blacklist.get(old);
      if (black == null || !black.equals(new_)) {
        return new VPPEntry(var, this_vars.get(index));
      }
    }
    this_vars.put(index, new VarVersionPair(var));

    if (leaked != null) {
      leaked.put(index, new VarVersionPair(var));
    }

    return null;
  }

  private boolean remapVar(Statement stat, VarVersionPair from, VarVersionPair to) {
    if (from.equals(to))
      throw new IllegalArgumentException("Shit went wrong: " + from);
    boolean success = false;
    if (stat.getExprents() == null) {
      for (Object obj : stat.getSequentialObjects()) {
        if (obj instanceof Statement) {
          success |= remapVar((Statement)obj, from, to);
        }
        else if (obj instanceof Exprent) {
          if (remapVar((Exprent)obj, from, to)) {
            success = true;
          }
        }
      }
    }
    else {
      boolean remapped = false;
      for (int x = 0; x < stat.getExprents().size(); x++) {
        Exprent exp = stat.getExprents().get(x);
        if (remapVar(exp, from, to)) {
          remapped = true;
          if (exp.type == Exprent.EXPRENT_VAR) {
            if (!((VarExprent)exp).isDefinition()) {
              stat.getExprents().remove(x);
              x--;
            }
          }
        }
      }
      success |= remapped;
    }
    if (success) {
      Iterator<Exprent> itr = stat.getVarDefinitions().iterator();
      while (itr.hasNext()) {
        Exprent exp = itr.next();
        if (exp.type == Exprent.EXPRENT_VAR) {
          VarExprent var = (VarExprent)exp;
          if (from.var == var.getIndex() && from.version == var.getVersion()) {
            itr.remove();
          }
          else if (to.var == var.getIndex() && to.version == var.getVersion()) {
            Map<VarVersionPair, VarType> mapExprentMinTypes = varproc.getVarVersions().getTypeProcessor().getMapExprentMinTypes();
            Map<VarVersionPair, VarType> mapExprentMaxTypes = varproc.getVarVersions().getTypeProcessor().getMapExprentMaxTypes();
            VarType merged = getMergedType(mapExprentMinTypes.get(from), mapExprentMinTypes.get(to),
                                           mapExprentMaxTypes.get(from), mapExprentMaxTypes.get(to));

            if (merged == null) { // Something went wrong.. This SHOULD be non-null
              continue;
            }

            var.setVarType(merged);
          }
        }
      }
    }
    return success;
  }

  private boolean remapVar(Exprent exprent, VarVersionPair from, VarVersionPair to) {
    if (exprent == null) { // Sometimes there are null exprents?
      return false;
    }
    List<Exprent> lst = exprent.getAllExprents(true);
    lst.add(exprent);
    Map<VarVersionPair, VarType> mapExprentMinTypes = varproc.getVarVersions().getTypeProcessor().getMapExprentMinTypes();
    Map<VarVersionPair, VarType> mapExprentMaxTypes = varproc.getVarVersions().getTypeProcessor().getMapExprentMaxTypes();

    boolean remapped = false;

    for (Exprent expr : lst) {
      if (expr.type == Exprent.EXPRENT_ASSIGNMENT) {
        AssignmentExprent ass = (AssignmentExprent)expr;
        if (ass.getLeft().type == Exprent.EXPRENT_VAR && ass.getRight().type == Exprent.EXPRENT_CONST) {
          VarVersionPair left = new VarVersionPair((VarExprent)ass.getLeft());
          if (!left.equals(from) && !left.equals(to)) {
            continue;
          }

          ConstExprent right = (ConstExprent)ass.getRight();
          if (right.getConstType() == VarType.VARTYPE_NULL) {
            continue;
          }
          VarType merged = getMergedType(mapExprentMinTypes.get(from), mapExprentMinTypes.get(to),
                                         mapExprentMaxTypes.get(from), mapExprentMaxTypes.get(to));

          if (merged == null) { // Types incompatible, do not merge
            continue;
          }

          right.setConstType(merged);
        }
      }
      else if (expr.type == Exprent.EXPRENT_VAR) {
        VarExprent var = (VarExprent)expr;
        VarVersionPair old = new VarVersionPair(var);
        if (!old.equals(from)) {
          continue;
        }
        VarType merged = getMergedType(mapExprentMinTypes.get(from), mapExprentMinTypes.get(to),
                                       mapExprentMaxTypes.get(from), mapExprentMaxTypes.get(to));
        if (merged == null) { // Types incompatible, do not merge
          continue;
        }

        var.setIndex(to.var);
        var.setVersion(to.version);
        var.setVarType(merged);
        if (var.isDefinition()) {
          var.setDefinition(false);
        }
        mapExprentMinTypes.put(to, merged);
        remapped = true;
      }
    }
    return remapped;
  }

  private VarType getMergedType(VarType firstMin, VarType secondMin, VarType firstMax, VarType secondMax) {
    if (firstMin != null && firstMin.equals(secondMin)) {
      return firstMin; // Short circuit this for simplicities sake
    }
    VarType type = firstMin == null ? secondMin : (secondMin == null ? firstMin : VarType.getCommonSupertype(firstMin, secondMin));
    if (type == null || firstMin == null || secondMin == null) {
      return null; // no common supertype, skip the remapping
    }
    if (type.typeFamily == CodeConstants.TYPE_FAMILY_OBJECT) {
      if (firstMax != null && secondMax != null) {
        type = VarType.getCommonMinType(firstMax, secondMax);
      } else if (firstMin.arrayDim != secondMin.arrayDim) {
        return null; // Don't merge is arrays don't match.
      } else {
        type = VarType.getCommonMinType(firstMin, secondMin);
        // couldn't find a sane common supertype, we're not gonna be able to merge
        if (type == null || type == VarType.VARTYPE_NULL) {
          return null;
        }
      }
    }
    return type;
  }

  //Helper classes because Java is dumb and doesn't have a Pair<K,V> class
  private static class SimpleEntry<K, V> implements Entry<K, V> {
    private K key;
    private V value;
    public SimpleEntry(K key, V value) {
      this.key = key;
      this.value = value;
    }
    @Override public K getKey() { return key; }
    @Override public V getValue() { return value; }
    @Override
    public V setValue(V value) {
      V tmp = this.value;
      this.value = value;
      return tmp;
    }
  }
  private static class VPPEntry extends SimpleEntry<VarVersionPair, VarVersionPair> {
    public VPPEntry(VarExprent key, VarVersionPair value) {
      super(new VarVersionPair(key), value);
    }
  }
}
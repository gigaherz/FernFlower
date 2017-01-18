package org.jetbrains.java.decompiler.util;

import java.util.List;

import org.jetbrains.java.decompiler.modules.decompiler.exps.Exprent;
import org.jetbrains.java.decompiler.modules.decompiler.sforms.DirectGraph.ExprentIterator;
import org.jetbrains.java.decompiler.modules.decompiler.stats.Statement;

public class StatementIterator {
  public static void iterate(Statement stat, ExprentIterator itr) {
    if (stat == null) {
      return;
    }

    for (Exprent exp : stat.getVarDefinitions()) {
      iterate(exp, itr);
    }

    if (stat.getExprents() == null) {
      for (Object obj : stat.getSequentialObjects()) {
        if (obj instanceof Statement) {
          iterate((Statement)obj, itr);
        }
        else if (obj instanceof Exprent) {
          iterate((Exprent)obj, itr);
        }
      }
    }
    else {
      for (Exprent exp : stat.getExprents()) {
        iterate(exp, itr);
      }
    }
  }

  private static void iterate(Exprent exp, ExprentIterator itr) {
    List<Exprent> lst = exp.getAllExprents(true);
    lst.add(exp);
    for (Exprent exprent : lst) {
      itr.processExprent(exprent);
    }
  }
}

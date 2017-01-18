/*
 * Copyright 2000-2016 JetBrains s.r.o.
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
package org.jetbrains.java.decompiler.main;

import org.jetbrains.java.decompiler.code.CodeConstants;
import org.jetbrains.java.decompiler.main.ClassesProcessor.ClassNode;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.main.rels.ClassWrapper;
import org.jetbrains.java.decompiler.main.rels.MethodWrapper;
import org.jetbrains.java.decompiler.modules.decompiler.exps.*;
import org.jetbrains.java.decompiler.modules.decompiler.stats.RootStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.Statement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.Statements;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarVersionPair;
import org.jetbrains.java.decompiler.struct.StructClass;
import org.jetbrains.java.decompiler.struct.StructContext;
import org.jetbrains.java.decompiler.struct.StructField;
import org.jetbrains.java.decompiler.struct.StructMethod;
import org.jetbrains.java.decompiler.struct.gen.MethodDescriptor;
import org.jetbrains.java.decompiler.struct.gen.VarType;
import org.jetbrains.java.decompiler.util.InterpreterUtil;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class InitializerProcessor {
  public static void extractInitializers(ClassWrapper wrapper) {
    MethodWrapper method = wrapper.getMethodWrapper(CodeConstants.CLINIT_NAME, "()V");
    if (method != null && method.root != null) {  // successfully decompiled static constructor
      extractStaticInitializers(wrapper, method);
    }

    extractDynamicInitializers(wrapper);

    // required e.g. if anonymous class is being decompiled as a standard one.
    // This can happen if InnerClasses attributes are erased
    liftConstructor(wrapper);

    if (DecompilerContext.getOption(IFernflowerPreferences.HIDE_EMPTY_SUPER)) {
      hideEmptySuper(wrapper);
    }
  }

  private static void liftConstructor(ClassWrapper wrapper) {
    for (MethodWrapper method : wrapper.getMethods()) {
      if (CodeConstants.INIT_NAME.equals(method.methodStruct.getName()) && method.root != null) {
        Statement firstData = Statements.findFirstData(method.root);
        if (firstData == null) {
          return;
        }

        int index = 0;
        List<Exprent> lstExprents = firstData.getExprents();

        for (Exprent exprent : lstExprents) {
          int action = 0;

          if (exprent.type == Exprent.EXPRENT_ASSIGNMENT) {
            AssignmentExprent assignExpr = (AssignmentExprent)exprent;
            if (assignExpr.getLeft().type == Exprent.EXPRENT_FIELD && assignExpr.getRight().type == Exprent.EXPRENT_VAR) {
              FieldExprent fExpr = (FieldExprent)assignExpr.getLeft();
              if (fExpr.getClassname().equals(wrapper.getClassStruct().qualifiedName)) {
                StructField structField = wrapper.getClassStruct().getField(fExpr.getName(), fExpr.getDescriptor().descriptorString);
                if (structField != null && structField.hasModifier(CodeConstants.ACC_FINAL)) {
                  action = 1;
                }
              }
            }
          }
          else if (index > 0 && exprent.type == Exprent.EXPRENT_INVOCATION &&
                   Statements.isInvocationInitConstructor((InvocationExprent)exprent, method, wrapper, true)) {
            // this() or super()
            lstExprents.add(0, lstExprents.remove(index));
            action = 2;
          }

          if (action != 1) {
            break;
          }

          index++;
        }
      }
    }
  }

  private static void hideEmptySuper(ClassWrapper wrapper) {
    for (MethodWrapper method : wrapper.getMethods()) {
      if (CodeConstants.INIT_NAME.equals(method.methodStruct.getName()) && method.root != null) {
        Statement firstData = Statements.findFirstData(method.root);
        if (firstData == null || firstData.getExprents().isEmpty()) {
          return;
        }

        Exprent exprent = firstData.getExprents().get(0);
        if (exprent.type == Exprent.EXPRENT_INVOCATION) {
          InvocationExprent invExpr = (InvocationExprent)exprent;
          if (Statements.isInvocationInitConstructor(invExpr, method, wrapper, false) && invExpr.getLstParameters().isEmpty()) {
            firstData.getExprents().remove(0);
          }
        }
      }
    }
  }

  private static void extractStaticInitializers(ClassWrapper wrapper, MethodWrapper method) {
    RootStatement root = method.root;
    StructClass cl = wrapper.getClassStruct();
    Set<String> whitelist = new HashSet<String>();

    Statement firstdata = findFirstData(root);
    if (firstdata != null) {
      Iterator<Exprent> itr = firstdata.getExprents().iterator();
      while (itr.hasNext()) {
        Exprent exprent = itr.next();

        if (exprent.type == Exprent.EXPRENT_ASSIGNMENT) {
          AssignmentExprent assignExpr = (AssignmentExprent)exprent;
          if (assignExpr.getLeft().type == Exprent.EXPRENT_FIELD) {
            FieldExprent fExpr = (FieldExprent)assignExpr.getLeft();
            if (fExpr.isStatic() && fExpr.getClassname().equals(cl.qualifiedName) &&
                cl.hasField(fExpr.getName(), fExpr.getDescriptor().descriptorString)) {

              String keyField = InterpreterUtil.makeUniqueKey(fExpr.getName(), fExpr.getDescriptor().descriptorString);
              if (isExprentIndependent(assignExpr.getRight(), method, cl, whitelist, cl.getFields().getIndexByKey(keyField))) {
                if (!wrapper.getStaticFieldInitializers().containsKey(keyField)) {
                  wrapper.getStaticFieldInitializers().addWithKey(assignExpr.getRight(), keyField);
                  whitelist.add(keyField);
                  itr.remove();
                }
              }
            }
          }
        }
      }
    }
  }

  private static void extractDynamicInitializers(ClassWrapper wrapper) {
    StructClass cl = wrapper.getClassStruct();

    boolean isAnonymous = DecompilerContext.getClassProcessor().getMapRootClasses().get(cl.qualifiedName).type == ClassNode.CLASS_ANONYMOUS;

    List<List<Exprent>> lstFirst = new ArrayList<>();
    List<MethodWrapper> lstMethodWrappers = new ArrayList<>();

    for (MethodWrapper method : wrapper.getMethods()) {
      if (CodeConstants.INIT_NAME.equals(method.methodStruct.getName()) && method.root != null) { // successfully decompiled constructor
        Statement firstData = Statements.findFirstData(method.root);
        if (firstData == null || firstData.getExprents().isEmpty()) {
          return;
        }
        lstFirst.add(firstData.getExprents());
        lstMethodWrappers.add(method);

        Exprent exprent = firstData.getExprents().get(0);
        if (!isAnonymous) { // FIXME: doesn't make sense
          if (exprent.type != Exprent.EXPRENT_INVOCATION ||
              !Statements.isInvocationInitConstructor((InvocationExprent)exprent, method, wrapper, false)) {
            return;
          }
        }
      }
    }

    if (lstFirst.isEmpty()) {
      return;
    }

    Set<String> whitelist = new HashSet<String>();

    while (true) {
      String fieldWithDescr = null;
      Exprent value = null;

      for (int i = 0; i < lstFirst.size(); i++) {
        List<Exprent> lst = lstFirst.get(i);

        if (lst.size() < (isAnonymous ? 1 : 2)) {
          return;
        }

        Exprent exprent = lst.get(isAnonymous ? 0 : 1);

        boolean found = false;

        if (exprent.type == Exprent.EXPRENT_ASSIGNMENT) {
          AssignmentExprent assignExpr = (AssignmentExprent)exprent;
          if (assignExpr.getLeft().type == Exprent.EXPRENT_FIELD) {
            FieldExprent fExpr = (FieldExprent)assignExpr.getLeft();
            if (!fExpr.isStatic() && fExpr.getClassname().equals(cl.qualifiedName) &&
                cl.hasField(fExpr.getName(), fExpr.getDescriptor().descriptorString)) { // check for the physical existence of the field. Could be defined in a superclass.

              String fieldKey = InterpreterUtil.makeUniqueKey(fExpr.getName(), fExpr.getDescriptor().descriptorString);
              if (isExprentIndependent(assignExpr.getRight(), lstMethodWrappers.get(i), cl, whitelist, cl.getFields().getIndexByKey(fieldKey))) {
                if (fieldWithDescr == null) {
                  fieldWithDescr = fieldKey;
                  value = assignExpr.getRight();
                }
                else {
                  if (!fieldWithDescr.equals(fieldKey) ||
                      !value.equals(assignExpr.getRight())) {
                    return;
                  }
                }
                found = true;
              }
            }
          }
        }

        if (!found) {
          return;
        }
      }

      if (!wrapper.getDynamicFieldInitializers().containsKey(fieldWithDescr)) {
        wrapper.getDynamicFieldInitializers().addWithKey(value, fieldWithDescr);

        for (List<Exprent> lst : lstFirst) {
          lst.remove(isAnonymous ? 0 : 1);
        }
      }
      else {
        return;
      }
    }
  }

  private static boolean isExprentIndependent(Exprent exprent, MethodWrapper method, StructClass cl, Set<String> whitelist, int fidx) {

    List<Exprent> lst = exprent.getAllExprents(true);
    lst.add(exprent);

    for (Exprent expr : lst) {
      switch (expr.type) {
        case Exprent.EXPRENT_VAR:
          VarVersionPair varPair = new VarVersionPair((VarExprent)expr);
          if (!method.varproc.getExternalVars().contains(varPair)) {
            String varName = method.varproc.getVarName(varPair);
            if (!varName.equals("this") && !varName.endsWith(".this")) { // FIXME: remove direct comparison with strings
              return false;
            }
          }
          break;
        case Exprent.EXPRENT_FIELD:
          FieldExprent fexpr = (FieldExprent)expr;
          if (cl.qualifiedName.equals(fexpr.getClassname())) {
            String key = InterpreterUtil.makeUniqueKey(fexpr.getName(), fexpr.getDescriptor().descriptorString);
            if (!whitelist.contains(key)) {
              return false;
            }
            return cl.getFields().getIndexByKey(key) < fidx;
          }
          else if (!fexpr.isStatic()) {
            return false;
          }
      }
    }

    return true;
  }


  private static Statement findFirstData(Statement stat) {

    if (stat.getExprents() != null) {
      return stat;
    }
    else {
      if (stat.isLabeled()) { // FIXME: Why??
        return null;
      }

      switch (stat.type) {
        case Statement.TYPE_SEQUENCE:
        case Statement.TYPE_IF:
        case Statement.TYPE_ROOT:
        case Statement.TYPE_SWITCH:
        case Statement.TYPE_SYNCRONIZED:
          return findFirstData(stat.getFirst());
        default:
          return null;
      }
    }
  }

  private static boolean isInvocationInitConstructor(InvocationExprent inv, MethodWrapper meth, ClassWrapper wrapper, boolean withThis) {

    if (inv.getFunctype() == InvocationExprent.TYP_INIT) {
      if (inv.getInstance().type == Exprent.EXPRENT_VAR) {
        VarExprent instvar = (VarExprent)inv.getInstance();
        VarVersionPair varpaar = new VarVersionPair(instvar);

        String classname = meth.varproc.getThisVars().get(varpaar);

        if (classname != null) { // any this instance. TODO: Restrict to current class?
          if (withThis || !wrapper.getClassStruct().qualifiedName.equals(inv.getClassname())) {
            return true;
          }
        }
      }
    }

    return false;
  }


  public static void hideInitalizers(ClassWrapper wrapper) {
    // hide initializers with anon class arguments
    for (MethodWrapper method : wrapper.getMethods()) {
      StructMethod mt = method.methodStruct;
      String name = mt.getName();
      String desc = mt.getDescriptor();

      if (mt.isSynthetic() && CodeConstants.INIT_NAME.equals(name)) {
        MethodDescriptor md = MethodDescriptor.parseDescriptor(desc);
        if (md.params.length > 0) {
          VarType type = md.params[md.params.length - 1];
          if (type.type == CodeConstants.TYPE_OBJECT) {
            ClassNode node = DecompilerContext.getClassProcessor().getMapRootClasses().get(type.value);
            if (node != null && (node.namelessConstructorStub || node.type == ClassNode.CLASS_ANONYMOUS || (node.access & CodeConstants.ACC_SYNTHETIC) != 0)) {
              //TODO: Verify that the body is JUST a this([args]) call?
              wrapper.getHiddenMembers().add(InterpreterUtil.makeUniqueKey(name, desc));
            }
          }
        }
      }
    }
  }
}

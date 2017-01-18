/*
 * Copyright 2000-2017 JetBrains s.r.o.
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
package org.jetbrains.java.decompiler.modules.decompiler.exps;

import org.jetbrains.java.decompiler.code.CodeConstants;
import org.jetbrains.java.decompiler.main.ClassWriter;
import org.jetbrains.java.decompiler.main.ClassesProcessor.ClassNode;
import org.jetbrains.java.decompiler.main.DecompilerContext;
import org.jetbrains.java.decompiler.main.TextBuffer;
import org.jetbrains.java.decompiler.main.collectors.BytecodeMappingTracer;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.main.rels.MethodWrapper;
import org.jetbrains.java.decompiler.modules.decompiler.ExprProcessor;
import org.jetbrains.java.decompiler.modules.decompiler.vars.CheckTypesResult;
import org.jetbrains.java.decompiler.modules.decompiler.vars.LocalVariable;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarProcessor;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarTypeProcessor;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarVersionPair;
import org.jetbrains.java.decompiler.struct.StructMethod;
import org.jetbrains.java.decompiler.struct.attr.StructGeneralAttribute;
import org.jetbrains.java.decompiler.struct.attr.StructLocalVariableTableAttribute;
import org.jetbrains.java.decompiler.struct.attr.StructLocalVariableTypeTableAttribute;
import org.jetbrains.java.decompiler.struct.gen.VarType;
import org.jetbrains.java.decompiler.struct.gen.generics.GenericFieldDescriptor;
import org.jetbrains.java.decompiler.struct.gen.generics.GenericMain;
import org.jetbrains.java.decompiler.struct.gen.generics.GenericType;
import org.jetbrains.java.decompiler.struct.match.MatchEngine;
import org.jetbrains.java.decompiler.struct.match.MatchNode;
import org.jetbrains.java.decompiler.struct.match.MatchNode.RuleValue;
import org.jetbrains.java.decompiler.util.InterpreterUtil;
import org.jetbrains.java.decompiler.util.TextUtil;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

public class VarExprent extends Exprent {

  public static final int STACK_BASE = 10000;
  public static final String VAR_NAMELESS_ENCLOSURE = "<VAR_NAMELESS_ENCLOSURE>";

  private int index;
  private VarType varType;
  private boolean definition = false;
  private VarProcessor processor;
  private final int visibleOffset;
  private int version = 0;
  private boolean classDef = false;
  private boolean stack = false;
  private LocalVariable lvt = null;

  public VarExprent(int index, VarType varType, VarProcessor processor) {
    this(index, varType, processor, -1);
  }

  public VarExprent(int index, VarType varType, VarProcessor processor, int visibleOffset) {
    super(EXPRENT_VAR);
    this.index = index;
    this.varType = varType;
    this.processor = processor;
    this.visibleOffset = visibleOffset;
  }

  @Override
  public VarType getExprType() {
    return getVarType();
  }

  @Override
  public VarType getInferredExprType(VarType upperBound) {
    if (lvt != null && lvt.getSig() != null) {
      // TODO; figure out why it's crashing, ugly fix for now
      try {
        return GenericType.parse(lvt.getSig());
      } catch (StringIndexOutOfBoundsException ex) {
      }
    }
    else if (lvt != null) {
      return lvt.getVarType();
    }
    return getVarType();
  }

  @Override
  public int getExprentUse() {
    return Exprent.MULTIPLE_USES | Exprent.SIDE_EFFECTS_FREE;
  }

  @Override
  public List<Exprent> getAllExprents() {
    return new ArrayList<>();
  }

  @Override
  public Exprent copy() {
    VarExprent var = new VarExprent(index, getVarType(), processor, visibleOffset);
    var.setDefinition(definition);
    var.setVersion(version);
    var.setClassDef(classDef);
    var.setStack(stack);
    var.setLVT(lvt);
    return var;
  }

  @Override
  public TextBuffer toJava(int indent, BytecodeMappingTracer tracer) {
    TextBuffer buffer = new TextBuffer();

    tracer.addMapping(bytecode);

    if (classDef) {
      ClassNode child = DecompilerContext.getClassProcessor().getMapRootClasses().get(varType.value);
      new ClassWriter().classToJava(child, buffer, indent, tracer);
      tracer.incrementCurrentSourceLine(buffer.countLines());
    }
    else {
      VarVersionPair varVersion = getVarVersionPair();
      String name = null;
      if (lvt != null) {
        name = lvt.name;
      }
      else if (processor != null) {
        name = processor.getVarName(varVersion);
      }

      if (definition) {
        if (processor != null && processor.getVarFinal(varVersion) == VarTypeProcessor.VAR_EXPLICIT_FINAL) {
          buffer.append("final ");
        }
        if (lvt != null && lvt.getSig() != null) {
          buffer.append(ExprProcessor.getCastTypeName(GenericType.parse(lvt.getSig()))).append(" ");
        }
        else if (lvt != null) {
          buffer.append(ExprProcessor.getCastTypeName(lvt.getVarType())).append(" ");
        }
        else {
          buffer.append(ExprProcessor.getCastTypeName(getVarType())).append(" ");
      }
      }
      buffer.append(name == null ? ("var" + index + (this.version == 0 ? "" : "_" + this.version)) : name);
    }

    return buffer;
  }

  public VarVersionPair getVarVersionPair() {
    return new VarVersionPair(index, version);
  }

  public String getDebugName(StructMethod method) {
    StructLocalVariableTableAttribute attr = method.getLocalVariableAttr();
    if (attr != null && processor != null) {
      Integer origIndex = processor.getVarOriginalIndex(index);
      if (origIndex != null) {
        String name = attr.getName(origIndex, visibleOffset);
        if (name != null && TextUtil.isValidIdentifier(name, method.getClassStruct().getBytecodeVersion())) {
          return name;
        }
      }
    }
    return null;
  }

  private void appendDefinitionType(TextBuffer buffer) {
    if (DecompilerContext.getOption(IFernflowerPreferences.USE_DEBUG_VAR_NAMES)) {
      MethodWrapper method = (MethodWrapper)DecompilerContext.getProperty(DecompilerContext.CURRENT_METHOD_WRAPPER);
      if (method != null) {
        Integer originalIndex = null;
        if (processor != null) {
          originalIndex = processor.getVarOriginalIndex(index);
        }
        if (originalIndex != null) {
          // first try from signature
          if (DecompilerContext.getOption(IFernflowerPreferences.DECOMPILE_GENERIC_SIGNATURES)) {
            StructLocalVariableTypeTableAttribute attr = (StructLocalVariableTypeTableAttribute)method.methodStruct.getAttributes()
              .getWithKey(StructGeneralAttribute.ATTRIBUTE_LOCAL_VARIABLE_TYPE_TABLE);
            if (attr != null) {
              String signature = attr.getSignature(originalIndex, visibleOffset);
              if (signature != null) {
                GenericFieldDescriptor descriptor = GenericMain.parseFieldSignature(signature);
                if (descriptor != null) {
                  buffer.append(GenericMain.getGenericCastTypeName((GenericType)descriptor.type));
                  return;
                }
              }
            }
          }

          // then try from descriptor
          StructLocalVariableTableAttribute attr = method.methodStruct.getLocalVariableAttr();
          if (attr != null) {
            String descriptor = attr.getDescriptor(originalIndex, visibleOffset);
            if (descriptor != null) {
              buffer.append(ExprProcessor.getCastTypeName(new VarType(descriptor)));
              return;
            }
          }
        }
      }
    }

    buffer.append(ExprProcessor.getCastTypeName(getVarType()));
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) return true;
    if (o == null || !(o instanceof VarExprent)) return false;

    VarExprent ve = (VarExprent)o;
    return index == ve.getIndex() &&
           version == ve.getVersion() &&
           InterpreterUtil.equalObjects(getVarType(), ve.getVarType()); // FIXME: varType comparison redundant?
  }

  @Override
  public void getBytecodeRange(BitSet values) {
    measureBytecode(values);
  }

  public int getIndex() {
    return index;
  }

  public void setIndex(int index) {
    this.index = index;
  }

  public VarType getVarType() {
    VarType vt = null;

    if (processor != null) {
      vt = processor.getVarType(getVarVersionPair());
    }

    if (vt == null || (varType != null && varType.type != CodeConstants.TYPE_UNKNOWN)) {
      vt = varType;
    }

    return vt == null ? VarType.VARTYPE_UNKNOWN : vt;
  }

  public void setVarType(VarType varType) {
    this.varType = varType;
  }

  public boolean isDefinition() {
    return definition;
  }

  public void setDefinition(boolean definition) {
    this.definition = definition;
  }

  public VarProcessor getProcessor() {
    return processor;
  }

  public void setProcessor(VarProcessor processor) {
    this.processor = processor;
  }

  public int getVersion() {
    return version;
  }

  public void setVersion(int version) {
    this.version = version;
  }

  public boolean isClassDef() {
    return classDef;
  }

  public void setClassDef(boolean classDef) {
    this.classDef = classDef;
  }

  public boolean isStack() {
    return stack;
  }

  public void setStack(boolean stack) {
    this.stack = stack;
  }

  // *****************************************************************************
  // IMatchable implementation
  // *****************************************************************************

  public boolean match(MatchNode matchNode, MatchEngine engine) {

    if(!super.match(matchNode, engine)) {
      return false;
    }

    RuleValue rule = matchNode.getRules().get(MatchProperties.EXPRENT_VAR_INDEX);
    if(rule != null) {
      if(rule.isVariable()) {
        if(!engine.checkAndSetVariableValue((String)rule.value, this.index)) {
          return false;
        }
      } else {
        if(this.index != Integer.valueOf((String)rule.value).intValue()) {
          return false;
        }
      }
    }

    return true;
  }

  public void setLVT(LocalVariable lvt) {
    this.lvt = lvt;
    if (processor != null && lvt != null) {
      processor.setVarType(new VarVersionPair(this), lvt.getVarType());
    }
  }

  public LocalVariable getLVT() {
    return this.lvt;
  }

  @Override
  public String toString() {
    return lvt != null ? lvt.name :  "var_" + index + "_" + version;
  }

  @Override
  public CheckTypesResult checkExprTypeBounds() {
    CheckTypesResult checkExprTypeBounds = super.checkExprTypeBounds();
    if (lvt != null) {
      checkExprTypeBounds.addMinTypeExprent(this, lvt.getVarType());
    }
    return checkExprTypeBounds;
  }

}

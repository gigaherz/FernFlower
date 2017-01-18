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
package org.jetbrains.java.decompiler.struct.attr;

import org.jetbrains.java.decompiler.modules.decompiler.vars.LVTVariable;
import org.jetbrains.java.decompiler.modules.decompiler.vars.LocalVariableTable;
import org.jetbrains.java.decompiler.struct.consts.ConstantPool;
import org.jetbrains.java.decompiler.util.DataInputFullStream;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/*
  u2 local_variable_table_length;
  local_variable {
    u2 start_pc;
    u2 length;
    u2 name_index;
    u2 descriptor_index;
    u2 index;
  }
*/
public class StructLocalVariableTableAttribute extends StructGeneralAttribute {
  private List<LocalVariable> localVariables = Collections.emptyList();

  private Map<Integer, List<LVTVariable>> EMPTY_LVT = Collections.emptyMap();
  private LocalVariableTable lvt;

  @Override
  public void initContent(DataInputFullStream data, ConstantPool pool) throws IOException {
    int len = data.readUnsignedShort();
    boolean isLVTT = this.getName().equals(ATTRIBUTE_LOCAL_VARIABLE_TYPE_TABLE);
    if (len > 0) {
      lvt = new LocalVariableTable(len);
      for (int i = 0; i < len; i++) {
        int start = data.readUnsignedShort();
        int vlen = data.readUnsignedShort();
        int nameIndex = data.readUnsignedShort();
        int descIndex = data.readUnsignedShort(); // either descriptor or signature
        int varIndex = data.readUnsignedShort();
        LVTVariable v = new LVTVariable(pool.getPrimitiveConstant(nameIndex).getString(), pool.getPrimitiveConstant(descIndex).getString(),start,start+vlen,varIndex,isLVTT);
        lvt.addVariable(v);
      }
    }
  }

  public void addLocalVariableTable(StructLocalVariableTableAttribute attr) {
    if (lvt == null) {
      lvt = attr.lvt;
    }
    else {
      lvt.mergeLVTs(attr.lvt);
      attr.lvt = lvt;
    }
  }

  public Map<Integer, List<LVTVariable>> getMapVarNames() {
    return lvt == null ? EMPTY_LVT : lvt.getMapVarNames();
  }

  public LocalVariableTable getLVT() {
    return lvt;
  }

  public String getDescriptor(int index, int visibleOffset) {
    return matchingVars(index, visibleOffset).map(v -> v.descriptor).findFirst().orElse(null);
  }

  private Stream<LocalVariable> matchingVars(int index, int visibleOffset) {
    return localVariables.stream()
      .filter(v -> v.index == index && (visibleOffset >= v.start_pc && visibleOffset < v.start_pc + v.length));
  }

  public boolean containsName(String name) {
    return localVariables.stream().anyMatch(v -> v.name == name);
  }

  public Map<Integer, String> getMapParamNames() {
    return localVariables.stream().filter(v -> v.start_pc == 0).collect(Collectors.toMap(v -> v.index, v -> v.name, (n1, n2) -> n2));
  }

  private static class LocalVariable {
    final int start_pc;
    final int length;
    final String name;
    final String descriptor;
    final int index;

    private LocalVariable(int start_pc, int length, String name, String descriptor, int index) {
      this.start_pc = start_pc;
      this.length = length;
      this.name = name;
      this.descriptor = descriptor;
      this.index = index;
    }
  }
}

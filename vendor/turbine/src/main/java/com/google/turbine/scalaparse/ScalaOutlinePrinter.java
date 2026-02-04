/*
 * Copyright 2026 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.turbine.scalaparse;

import com.google.common.collect.ImmutableList;
import com.google.turbine.scalaparse.ScalaTree.ClassDef;
import com.google.turbine.scalaparse.ScalaTree.DefDef;
import com.google.turbine.scalaparse.ScalaTree.Defn;
import com.google.turbine.scalaparse.ScalaTree.ImportStat;
import com.google.turbine.scalaparse.ScalaTree.Param;
import com.google.turbine.scalaparse.ScalaTree.ParamList;
import com.google.turbine.scalaparse.ScalaTree.TypeDef;
import com.google.turbine.scalaparse.ScalaTree.TypeParam;
import com.google.turbine.scalaparse.ScalaTree.ValDef;
import java.util.ArrayList;
import java.util.List;

/** Renders a stable line-based outline of a Scala tree. */
public final class ScalaOutlinePrinter {

  public static List<String> print(ScalaTree.CompUnit unit) {
    List<String> lines = new ArrayList<>();
    for (ScalaTree.Stat stat : unit.stats()) {
      renderStat(lines, stat, "");
    }
    return lines;
  }

  private static void renderStat(List<String> lines, ScalaTree.Stat stat, String indent) {
    if (stat instanceof ImportStat imp) {
      lines.add(indent + prefixPkg(imp.packageName()) + "import " + imp.text());
      return;
    }
    if (stat instanceof ClassDef cls) {
      lines.add(indent + renderClassHeader(cls));
      for (Defn member : cls.members()) {
        renderStat(lines, member, indent + "  ");
      }
      return;
    }
    if (stat instanceof DefDef def) {
      lines.add(indent + renderDef(def));
      return;
    }
    if (stat instanceof ValDef val) {
      lines.add(indent + renderVal(val));
      return;
    }
    if (stat instanceof TypeDef type) {
      lines.add(indent + renderType(type));
    }
  }

  private static String renderClassHeader(ClassDef cls) {
    StringBuilder sb = new StringBuilder();
    sb.append(prefixPkg(cls.packageName()));
    if (cls.isPackageObject()) {
      sb.append("package object ");
    } else {
      sb.append(cls.kind().name().toLowerCase()).append(' ');
    }
    sb.append(cls.name());
    if (!cls.modifiers().isEmpty()) {
      sb.append(" mods=").append(cls.modifiers());
    }
    if (cls.isCase()) {
      sb.append(" case");
    }
    if (!cls.typeParams().isEmpty()) {
      sb.append(" tparams=").append(renderTypeParams(cls.typeParams()));
    }
    if (!cls.ctorParams().isEmpty()) {
      sb.append(" ctor=").append(renderParamLists(cls.ctorParams()));
    }
    if (!cls.parents().isEmpty()) {
      sb.append(" parents=").append(cls.parents());
    }
    return sb.toString();
  }

  private static String renderDef(DefDef def) {
    StringBuilder sb = new StringBuilder();
    sb.append(prefixPkg(def.packageName())).append("def ").append(def.name());
    if (!def.modifiers().isEmpty()) {
      sb.append(" mods=").append(def.modifiers());
    }
    if (!def.typeParams().isEmpty()) {
      sb.append(" tparams=").append(renderTypeParams(def.typeParams()));
    }
    if (!def.paramLists().isEmpty()) {
      sb.append(" params=").append(renderParamLists(def.paramLists()));
    }
    if (def.returnType() != null && !def.returnType().isEmpty()) {
      sb.append(" : ").append(def.returnType());
    }
    return sb.toString();
  }

  private static String renderVal(ValDef val) {
    StringBuilder sb = new StringBuilder();
    sb.append(prefixPkg(val.packageName()));
    sb.append(val.isVar() ? "var " : "val ");
    sb.append(val.name());
    if (!val.modifiers().isEmpty()) {
      sb.append(" mods=").append(val.modifiers());
    }
    if (val.type() != null && !val.type().isEmpty()) {
      sb.append(": ").append(val.type());
    }
    if (val.hasDefault()) {
      sb.append(" = <expr>");
    }
    return sb.toString();
  }

  private static String renderType(TypeDef type) {
    StringBuilder sb = new StringBuilder();
    sb.append(prefixPkg(type.packageName())).append("type ").append(type.name());
    if (!type.modifiers().isEmpty()) {
      sb.append(" mods=").append(type.modifiers());
    }
    if (!type.typeParams().isEmpty()) {
      sb.append(" tparams=").append(renderTypeParams(type.typeParams()));
    }
    if (type.lowerBound() != null) {
      sb.append(" >: ").append(type.lowerBound());
    }
    if (type.upperBound() != null) {
      sb.append(" <: ").append(type.upperBound());
    }
    if (!type.viewBounds().isEmpty()) {
      sb.append(" <% ").append(type.viewBounds());
    }
    if (!type.contextBounds().isEmpty()) {
      sb.append(" : ").append(type.contextBounds());
    }
    if (type.rhs() != null) {
      sb.append(" = ").append(type.rhs());
    }
    return sb.toString();
  }

  private static String renderParamLists(ImmutableList<ParamList> lists) {
    StringBuilder sb = new StringBuilder();
    for (ParamList list : lists) {
      if (sb.length() > 0) {
        sb.append(' ');
      }
      sb.append('(');
      boolean first = true;
      for (Param param : list.params()) {
        if (!first) {
          sb.append(", ");
        }
        first = false;
        sb.append(param.name());
        if (!param.modifiers().isEmpty()) {
          sb.append(" mods=").append(param.modifiers());
        }
        if (param.type() != null && !param.type().isEmpty()) {
          sb.append(": ").append(param.type());
        }
        if (param.hasDefault()) {
          sb.append(" = <expr>");
        }
      }
      sb.append(')');
    }
    return sb.toString();
  }

  private static String renderTypeParams(ImmutableList<TypeParam> tparams) {
    List<String> parts = new ArrayList<>();
    for (TypeParam param : tparams) {
      StringBuilder sb = new StringBuilder();
      if (param.variance() != null) {
        sb.append(param.variance());
      }
      sb.append(param.name());
      if (param.lowerBound() != null) {
        sb.append(" >: ").append(param.lowerBound());
      }
      if (param.upperBound() != null) {
        sb.append(" <: ").append(param.upperBound());
      }
      if (!param.viewBounds().isEmpty()) {
        sb.append(" <% ").append(param.viewBounds());
      }
      if (!param.contextBounds().isEmpty()) {
        sb.append(" : ").append(param.contextBounds());
      }
      parts.add(sb.toString());
    }
    return parts.toString();
  }

  private static String prefixPkg(String pkg) {
    if (pkg == null || pkg.isEmpty()) {
      return "";
    }
    return "pkg=" + pkg + " ";
  }

  private ScalaOutlinePrinter() {}
}

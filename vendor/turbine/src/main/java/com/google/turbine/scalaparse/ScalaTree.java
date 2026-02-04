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
import com.google.turbine.diag.SourceFile;
import org.jspecify.annotations.Nullable;

/** Minimal Scala parse tree used for outline parsing. */
public final class ScalaTree {

  private ScalaTree() {}

  public interface Stat {
    int position();

    String packageName();
  }

  public record CompUnit(ImmutableList<Stat> stats, SourceFile source) {}

  public record ImportStat(String packageName, String text, int position) implements Stat {}

  public sealed interface Defn extends Stat permits ClassDef, DefDef, ValDef, TypeDef {}

  public record ClassDef(
      String packageName,
      String name,
      Kind kind,
      boolean isCase,
      boolean isPackageObject,
      ImmutableList<String> modifiers,
      ImmutableList<TypeParam> typeParams,
      ImmutableList<ParamList> ctorParams,
      ImmutableList<String> parents,
      ImmutableList<String> imports,
      ImmutableList<Defn> members,
      int position)
      implements Defn {
    public enum Kind {
      CLASS,
      TRAIT,
      OBJECT
    }
  }

  public record DefDef(
      String packageName,
      String name,
      ImmutableList<String> modifiers,
      ImmutableList<TypeParam> typeParams,
      ImmutableList<ParamList> paramLists,
      @Nullable String returnType,
      int position)
      implements Defn {}

  public record ValDef(
      String packageName,
      String name,
      boolean isVar,
      ImmutableList<String> modifiers,
      @Nullable String type,
      boolean hasExplicitType,
      boolean hasDefault,
      int position)
      implements Defn {}

  public record TypeDef(
      String packageName,
      String name,
      ImmutableList<String> modifiers,
      ImmutableList<TypeParam> typeParams,
      @Nullable String lowerBound,
      @Nullable String upperBound,
      ImmutableList<String> viewBounds,
      ImmutableList<String> contextBounds,
      @Nullable String rhs,
      int position)
      implements Defn {}

  public record TypeParam(
      String name,
      @Nullable String variance,
      @Nullable String lowerBound,
      @Nullable String upperBound,
      ImmutableList<String> viewBounds,
      ImmutableList<String> contextBounds) {}

  public record ParamList(ImmutableList<Param> params) {}

  public record Param(
      String name,
      ImmutableList<String> modifiers,
      @Nullable String type,
      boolean hasDefault,
      boolean defaultUsesParam) {}
}

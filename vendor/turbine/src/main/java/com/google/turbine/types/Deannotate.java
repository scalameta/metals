/*
 * Copyright 2021 Google Inc. All Rights Reserved.
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

package com.google.turbine.types;

import com.google.common.collect.ImmutableList;
import com.google.turbine.type.Type;

/** Removes type annotation metadata. */
public class Deannotate {
  public static Type deannotate(Type ty) {
    switch (ty.tyKind()) {
      case CLASS_TY:
        return deannotateClassTy((Type.ClassTy) ty);
      case ARRAY_TY:
        return Type.ArrayTy.create(
            deannotate(((Type.ArrayTy) ty).elementType()), ImmutableList.of());
      case INTERSECTION_TY:
        return Type.IntersectionTy.create(deannotate(((Type.IntersectionTy) ty).bounds()));
      case WILD_TY:
        return deannotateWildTy((Type.WildTy) ty);
      case METHOD_TY:
        return deannotateMethodTy((Type.MethodTy) ty);
      case PRIM_TY:
        return Type.PrimTy.create(((Type.PrimTy) ty).primkind(), ImmutableList.of());
      case TY_VAR:
        return Type.TyVar.create(((Type.TyVar) ty).sym(), ImmutableList.of());
      case VOID_TY:
      case ERROR_TY:
      case NONE_TY:
        return ty;
    }
    throw new AssertionError(ty.tyKind());
  }

  public static ImmutableList<Type> deannotate(ImmutableList<Type> types) {
    ImmutableList.Builder<Type> result = ImmutableList.builder();
    for (Type type : types) {
      result.add(deannotate(type));
    }
    return result.build();
  }

  public static Type.ClassTy deannotateClassTy(Type.ClassTy ty) {
    ImmutableList.Builder<Type.ClassTy.SimpleClassTy> classes = ImmutableList.builder();
    for (Type.ClassTy.SimpleClassTy c : ty.classes()) {
      classes.add(
          Type.ClassTy.SimpleClassTy.create(c.sym(), deannotate(c.targs()), ImmutableList.of()));
    }
    return Type.ClassTy.create(classes.build());
  }

  private static Type deannotateWildTy(Type.WildTy ty) {
    switch (ty.boundKind()) {
      case NONE:
        return Type.WildUnboundedTy.create(ImmutableList.of());
      case LOWER:
        return Type.WildLowerBoundedTy.create(deannotate(ty.bound()), ImmutableList.of());
      case UPPER:
        return Type.WildUpperBoundedTy.create(deannotate(ty.bound()), ImmutableList.of());
    }
    throw new AssertionError(ty.boundKind());
  }

  private static Type deannotateMethodTy(Type.MethodTy ty) {
    return Type.MethodTy.create(
        ty.tyParams(),
        deannotate(ty.returnType()),
        ty.receiverType() != null ? deannotate(ty.receiverType()) : null,
        deannotate(ty.parameters()),
        deannotate(ty.thrown()));
  }

  private Deannotate() {}
}

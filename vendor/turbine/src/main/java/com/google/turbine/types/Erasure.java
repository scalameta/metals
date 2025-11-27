/*
 * Copyright 2016 Google Inc. All Rights Reserved.
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
import com.google.common.collect.ImmutableSet;
import com.google.turbine.binder.bound.TypeBoundClass.TyVarInfo;
import com.google.turbine.binder.sym.TyVarSymbol;
import com.google.turbine.type.Type;
import com.google.turbine.type.Type.ArrayTy;
import com.google.turbine.type.Type.ClassTy;
import com.google.turbine.type.Type.ClassTy.SimpleClassTy;
import com.google.turbine.type.Type.IntersectionTy;
import com.google.turbine.type.Type.MethodTy;
import com.google.turbine.type.Type.TyVar;
import com.google.turbine.type.Type.WildTy;
import java.util.function.Function;

/** Generic type erasure. */
public final class Erasure {
  public static Type erase(Type ty, Function<TyVarSymbol, TyVarInfo> tenv) {
    switch (ty.tyKind()) {
      case CLASS_TY:
        return eraseClassTy((Type.ClassTy) ty);
      case ARRAY_TY:
        return eraseArrayTy((Type.ArrayTy) ty, tenv);
      case TY_VAR:
        return eraseTyVar((TyVar) ty, tenv);
      case INTERSECTION_TY:
        return eraseIntersectionTy((Type.IntersectionTy) ty, tenv);
      case WILD_TY:
        return eraseWildTy((Type.WildTy) ty, tenv);
      case METHOD_TY:
        return erasureMethodTy((Type.MethodTy) ty, tenv);
      case PRIM_TY:
      case VOID_TY:
      case ERROR_TY:
      case NONE_TY:
        return ty;
    }
    throw new AssertionError(ty.tyKind());
  }

  private static ImmutableList<Type> erase(
      ImmutableList<Type> types, Function<TyVarSymbol, TyVarInfo> tenv) {
    ImmutableList.Builder<Type> result = ImmutableList.builder();
    for (Type type : types) {
      result.add(erase(type, tenv));
    }
    return result.build();
  }

  private static Type eraseIntersectionTy(
      IntersectionTy ty, Function<TyVarSymbol, TyVarInfo> tenv) {
    return ty.bounds().isEmpty() ? ClassTy.OBJECT : erase(ty.bounds().get(0), tenv);
  }

  private static Type eraseTyVar(TyVar ty, Function<TyVarSymbol, TyVarInfo> tenv) {
    TyVarInfo info = tenv.apply(ty.sym());
    return erase(info.upperBound(), tenv);
  }

  private static Type.ArrayTy eraseArrayTy(Type.ArrayTy ty, Function<TyVarSymbol, TyVarInfo> tenv) {
    return ArrayTy.create(erase(ty.elementType(), tenv), ty.annos());
  }

  public static Type.ClassTy eraseClassTy(Type.ClassTy ty) {
    ImmutableList.Builder<Type.ClassTy.SimpleClassTy> classes = ImmutableList.builder();
    for (Type.ClassTy.SimpleClassTy c : ty.classes()) {
      if (c.targs().isEmpty()) {
        classes.add(c);
      } else {
        classes.add(SimpleClassTy.create(c.sym(), ImmutableList.of(), c.annos()));
      }
    }
    return ClassTy.create(classes.build());
  }

  private static Type eraseWildTy(WildTy ty, Function<TyVarSymbol, TyVarInfo> tenv) {
    switch (ty.boundKind()) {
      case NONE:
      case LOWER:
        return ClassTy.OBJECT;
      case UPPER:
        return erase(ty.bound(), tenv);
    }
    throw new AssertionError(ty.boundKind());
  }

  private static Type erasureMethodTy(MethodTy ty, Function<TyVarSymbol, TyVarInfo> tenv) {
    return MethodTy.create(
        /* tyParams= */ ImmutableSet.of(),
        erase(ty.returnType(), tenv),
        ty.receiverType() != null ? erase(ty.receiverType(), tenv) : null,
        erase(ty.parameters(), tenv),
        erase(ty.thrown(), tenv));
  }

  private Erasure() {}
}

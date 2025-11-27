/*
 * Copyright 2024 Google Inc. All Rights Reserved.
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

package com.google.turbine.binder;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.MultimapBuilder;
import com.google.turbine.binder.bound.SourceTypeBoundClass;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.env.SimpleEnv;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.model.TurbineFlag;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class PermitsBinder {

  /**
   * Given the classes in the current compilation, finds implicit permitted subtypes of sealed
   * classes.
   *
   * <p>See JLS §8.1.1.2 for details of implicit permits.
   *
   * @param syms the set of classes being compiled in this compilation unit
   * @param tenv the environment of the current compilation unit only. Dependencies from the
   *     classpath or bootclasspath are not required by this pass, because any implicitly permitted
   *     subtypes are required to be in the same compilation unit as their supertype.
   */
  static Env<ClassSymbol, SourceTypeBoundClass> bindPermits(
      ImmutableSet<ClassSymbol> syms, Env<ClassSymbol, SourceTypeBoundClass> tenv) {
    Set<ClassSymbol> sealedClassesWithoutExplicitPermits = new HashSet<>();
    for (ClassSymbol sym : syms) {
      SourceTypeBoundClass info = tenv.getNonNull(sym);
      if (((info.access() & TurbineFlag.ACC_SEALED) == TurbineFlag.ACC_SEALED)
          && info.permits().isEmpty()) {
        sealedClassesWithoutExplicitPermits.add(sym);
      }
    }
    if (sealedClassesWithoutExplicitPermits.isEmpty()) {
      // fast path if there were no sealed types with an empty 'permits' clause
      return tenv;
    }
    ListMultimap<ClassSymbol, ClassSymbol> permits =
        MultimapBuilder.hashKeys().arrayListValues().build();
    for (ClassSymbol sym : syms) {
      SourceTypeBoundClass info = tenv.getNonNull(sym);
      // Check if the current class has a direct supertype that is a sealed class with an empty
      // 'permits' clause.
      ClassSymbol superclass = info.superclass();
      if (superclass != null && sealedClassesWithoutExplicitPermits.contains(superclass)) {
        permits.put(superclass, sym);
      }
      for (ClassSymbol i : info.interfaces()) {
        if (sealedClassesWithoutExplicitPermits.contains(i)) {
          permits.put(i, sym);
        }
      }
    }
    SimpleEnv.Builder<ClassSymbol, SourceTypeBoundClass> builder = SimpleEnv.builder();
    for (ClassSymbol sym : syms) {
      List<ClassSymbol> thisPermits = permits.get(sym);
      SourceTypeBoundClass base = tenv.getNonNull(sym);
      if (thisPermits.isEmpty()) {
        builder.put(sym, base);
      } else {
        builder.put(
            sym,
            new SourceTypeBoundClass(
                /* interfaceTypes= */ base.interfaceTypes(),
                /* permits= */ ImmutableList.copyOf(thisPermits),
                /* superClassType= */ base.superClassType(),
                /* typeParameterTypes= */ base.typeParameterTypes(),
                /* access= */ base.access(),
                /* components= */ base.components(),
                /* methods= */ base.methods(),
                /* fields= */ base.fields(),
                /* owner= */ base.owner(),
                /* kind= */ base.kind(),
                /* children= */ base.children(),
                /* typeParameters= */ base.typeParameters(),
                /* enclosingScope= */ base.enclosingScope(),
                /* scope= */ base.scope(),
                /* memberImports= */ base.memberImports(),
                /* annotationMetadata= */ base.annotationMetadata(),
                /* annotations= */ base.annotations(),
                /* source= */ base.source(),
                /* decl= */ base.decl()));
      }
    }
    return builder.build();
  }

  private PermitsBinder() {}
}

/*
 * Copyright 2019 Google Inc. All Rights Reserved.
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

package com.google.turbine.processing;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.Symbol;
import com.google.turbine.processing.TurbineElement.TurbineTypeElement;
import java.lang.annotation.Annotation;
import java.util.Set;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;

/** A {@link RoundEnvironment}. */
public class TurbineRoundEnvironment implements RoundEnvironment {

  private final ModelFactory factory;
  private final ImmutableSet<ClassSymbol> syms;
  private final boolean processingOver;
  private final boolean errorRaised;
  private final ImmutableSetMultimap<ClassSymbol, Symbol> allAnnotations;

  // the round environment doesn't outlive the round, so don't worry about resetting this cache
  private final Supplier<ImmutableSet<TurbineTypeElement>> rootElements =
      Suppliers.memoize(
          new Supplier<ImmutableSet<TurbineTypeElement>>() {
            @Override
            public ImmutableSet<TurbineTypeElement> get() {
              ImmutableSet.Builder<TurbineTypeElement> result = ImmutableSet.builder();
              for (ClassSymbol sym : syms) {
                if (sym.simpleName().contains("$") && factory.getSymbol(sym).owner() != null) {
                  continue;
                }
                if (sym.simpleName().equals("package-info")) {
                  continue;
                }
                result.add(factory.typeElement(sym));
              }
              return result.build();
            }
          });

  public TurbineRoundEnvironment(
      ModelFactory factory,
      ImmutableSet<ClassSymbol> syms,
      boolean processingOver,
      boolean errorRaised,
      ImmutableSetMultimap<ClassSymbol, Symbol> allAnnotations) {
    this.factory = factory;
    this.syms = syms;
    this.processingOver = processingOver;
    this.errorRaised = errorRaised;
    this.allAnnotations = allAnnotations;
  }

  @Override
  public boolean processingOver() {
    return processingOver;
  }

  @Override
  public boolean errorRaised() {
    return errorRaised;
  }

  @Override
  public ImmutableSet<TurbineTypeElement> getRootElements() {
    return rootElements.get();
  }

  @Override
  public Set<? extends Element> getElementsAnnotatedWith(TypeElement a) {
    return factory.elements(allAnnotations.get(((TurbineTypeElement) a).sym()));
  }

  @Override
  public Set<? extends Element> getElementsAnnotatedWith(Class<? extends Annotation> a) {
    return getElementsAnnotatedWith(
        factory.typeElement(new ClassSymbol(a.getName().replace('.', '/'))));
  }
}

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

import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.CharMatcher;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.FieldSymbol;
import com.google.turbine.binder.sym.PackageSymbol;
import com.google.turbine.binder.sym.Symbol;
import com.google.turbine.model.Const;
import com.google.turbine.model.TurbineFlag;
import com.google.turbine.model.TurbineVisibility;
import com.google.turbine.processing.TurbineElement.TurbineExecutableElement;
import com.google.turbine.processing.TurbineElement.TurbineFieldElement;
import com.google.turbine.processing.TurbineElement.TurbineTypeElement;
import com.google.turbine.processing.TurbineTypeMirror.TurbineExecutableType;
import com.google.turbine.type.AnnoInfo;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import org.jspecify.annotations.Nullable;

/** An implementation of {@link Elements} backed by turbine's {@link Element}. */
@SuppressWarnings("nullness") // TODO(cushon): Address nullness diagnostics.
public class TurbineElements implements Elements {

  private final ModelFactory factory;
  private final TurbineTypes types;

  public TurbineElements(ModelFactory factory, TurbineTypes types) {
    this.factory = factory;
    this.types = types;
  }

  private static Symbol asSymbol(Element element) {
    if (!(element instanceof TurbineElement)) {
      throw new IllegalArgumentException(element.toString());
    }
    return ((TurbineElement) element).sym();
  }

  @Override
  public PackageElement getPackageElement(CharSequence name) {
    ImmutableList<String> packageName = ImmutableList.copyOf(Splitter.on('.').split(name));
    if (factory.tli().lookupPackage(packageName) == null) {
      return null;
    }
    return factory.packageElement(new PackageSymbol(Joiner.on('/').join(packageName)));
  }

  @Override
  public TypeElement getTypeElement(CharSequence name) {
    ClassSymbol sym = factory.inferSymbol(name);
    if (sym == null) {
      return null;
    }
    if (factory.getSymbol(sym) == null) {
      return null;
    }
    return factory.typeElement(sym);
  }

  @Override
  public Map<? extends ExecutableElement, ? extends AnnotationValue> getElementValuesWithDefaults(
      AnnotationMirror a) {
    return ((TurbineAnnotationMirror) a).getElementValuesWithDefaults();
  }

  @Override
  public String getDocComment(Element e) {
    if (!(e instanceof TurbineElement)) {
      throw new IllegalArgumentException(e.toString());
    }
    String comment = ((TurbineElement) e).javadoc();
    if (comment == null) {
      return null;
    }
    StringBuilder sb = new StringBuilder();
    boolean first = true;
    for (String line : Splitter.on('\n').split(comment)) {
      int start = 0;
      if (!first) {
        sb.append('\n');
        while (start < line.length() && CharMatcher.whitespace().matches(line.charAt(start))) {
          start++;
        }
        while (start < line.length() && line.charAt(start) == '*') {
          start++;
        }
      }
      sb.append(line, start, line.length());
      first = false;
    }
    return sb.toString();
  }

  @Override
  public boolean isDeprecated(Element element) {
    if (!(element instanceof TurbineElement)) {
      throw new IllegalArgumentException(element.toString());
    }
    for (AnnoInfo a : ((TurbineElement) element).annos()) {
      if (a.sym().equals(ClassSymbol.DEPRECATED)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public Name getBinaryName(TypeElement element) {
    if (!(element instanceof TurbineTypeElement)) {
      throw new IllegalArgumentException(element.toString());
    }
    return getName(((TurbineTypeElement) element).sym().binaryName().replace('/', '.'));
  }

  /**
   * {@inheritDoc}
   *
   * @throws IllegalArgumentException for module elements
   */
  @Override
  public PackageElement getPackageOf(Element element) {
    Symbol sym = asSymbol(element);
    return factory.packageElement(packageSymbol(sym));
  }

  private static PackageSymbol packageSymbol(Symbol sym) {
    if (sym.symKind().equals(Symbol.Kind.PACKAGE)) {
      return (PackageSymbol) sym;
    }
    return ModelFactory.enclosingClass(sym).owner();
  }

  @Override
  public List<? extends Element> getAllMembers(TypeElement type) {
    ClassSymbol s = (ClassSymbol) asSymbol(type);
    PackageSymbol from = packageSymbol(s);

    // keep track of processed methods grouped by their names, to handle overrides more efficiently
    Multimap<String, TurbineExecutableElement> methods =
        MultimapBuilder.linkedHashKeys().linkedHashSetValues().build();

    // collect all members of each transitive supertype of the input
    ImmutableList.Builder<Element> results = ImmutableList.builder();
    for (ClassSymbol superType : factory.cha().transitiveSupertypes(s)) {
      // Most of JSR-269 is implemented on top of turbine's model, instead of the Element and
      // TypeMirror wrappers. We don't do that here because we need most of the Elements returned
      // by getEnclosedElements anyways, and the work below benefits from some of the caching done
      // by TurbineElement.
      for (Element el : factory.typeElement(superType).getEnclosedElements()) {
        Symbol sym = asSymbol(el);
        switch (sym.symKind()) {
          case METHOD:
            TurbineExecutableElement m = (TurbineExecutableElement) el;
            if (shouldAdd(s, from, methods, m)) {
              methods.put(m.info().name(), m);
              results.add(el);
            }
            break;
          case FIELD:
            if (shouldAdd(s, from, (TurbineFieldElement) el)) {
              results.add(el);
            }
            break;
          default:
            results.add(el);
        }
      }
    }
    return results.build();
  }

  private boolean shouldAdd(
      ClassSymbol s,
      PackageSymbol from,
      Multimap<String, TurbineExecutableElement> methods,
      TurbineExecutableElement m) {
    if (m.sym().owner().equals(s)) {
      // always include methods (and constructors) declared in the given type
      return true;
    }
    if (m.getKind() == ElementKind.CONSTRUCTOR) {
      // skip constructors from super-types, because the spec says so
      return false;
    }
    if (!isVisible(from, packageSymbol(m.sym()), TurbineVisibility.fromAccess(m.info().access()))) {
      // skip invisible methods in supers
      return false;
    }
    // otherwise check if we've seen methods that override, or are overridden by, the
    // current method
    Set<TurbineExecutableElement> overrides = new HashSet<>();
    Set<TurbineExecutableElement> overridden = new HashSet<>();
    String name = m.info().name();
    for (TurbineExecutableElement other : methods.get(name)) {
      if (overrides(m, other, (TypeElement) m.getEnclosingElement())) {
        overrides.add(other);
        continue;
      }
      if (overrides(other, m, (TypeElement) other.getEnclosingElement())) {
        overridden.add(other);
        continue;
      }
    }
    if (!overridden.isEmpty()) {
      // We've already processed method(s) that override this one; nothing to do here.
      // If that's true, and we've *also* processed a methods that this one overrides,
      // something has gone terribly wrong: since overriding is transitive the results
      // contain a pair of methods that override each other.
      checkState(overrides.isEmpty());
      return false;
    }
    // Add this method, and remove any methods we've already processed that it overrides.
    for (TurbineExecutableElement override : overrides) {
      methods.remove(name, override);
    }
    return true;
  }

  private static boolean shouldAdd(ClassSymbol s, PackageSymbol from, TurbineFieldElement f) {
    FieldSymbol sym = f.sym();
    if (sym.owner().equals(s)) {
      // always include fields declared in the given type
      return true;
    }
    if (!isVisible(from, packageSymbol(sym), TurbineVisibility.fromAccess(f.info().access()))) {
      // skip invisible fields in supers
      return false;
    }
    return true;
  }

  /**
   * Returns true if an element with the given {@code visibility} and located in package {@code
   * from} is visible to elements in package {@code to}.
   */
  private static boolean isVisible(
      PackageSymbol from, PackageSymbol to, TurbineVisibility visibility) {
    switch (visibility) {
      case PUBLIC:
      case PROTECTED:
        break;
      case PACKAGE:
        return from.equals(to);
      case PRIVATE:
        return false;
    }
    return true;
  }

  @Override
  public List<? extends AnnotationMirror> getAllAnnotationMirrors(Element element) {
    return ((TurbineElement) element).getAllAnnotationMirrors();
  }

  @Override
  public boolean hides(Element hider, Element hidden) {
    if (!(hider instanceof TurbineElement)) {
      throw new IllegalArgumentException(hider.toString());
    }
    if (!(hidden instanceof TurbineElement)) {
      throw new IllegalArgumentException(hidden.toString());
    }
    return hides((TurbineElement) hider, (TurbineElement) hidden);
  }

  private boolean hides(TurbineElement hider, TurbineElement hidden) {
    if (!hider.sym().symKind().equals(hidden.sym().symKind())) {
      return false;
    }
    if (!hider.getSimpleName().equals(hidden.getSimpleName())) {
      return false;
    }
    if (hider.sym().equals(hidden.sym())) {
      return false;
    }
    if (!isVisibleForHiding(hider, hidden)) {
      return false;
    }
    if (hider.sym().symKind().equals(Symbol.Kind.METHOD)) {
      int access = ((TurbineExecutableElement) hider).info().access();
      if ((access & TurbineFlag.ACC_STATIC) != TurbineFlag.ACC_STATIC) {
        return false;
      }
      // Static interface methods shouldn't be able to hide static methods in super-interfaces,
      // but include them anyways for bug-compatibility with javac, see:
      // https://bugs.openjdk.java.net/browse/JDK-8275746
      if (!types.isSubsignature(
          (TurbineExecutableType) hider.asType(), (TurbineExecutableType) hidden.asType())) {
        return false;
      }
    }
    Element containingHider = containingClass(hider);
    Element containingHidden = containingClass(hidden);
    if (containingHider == null || containingHidden == null) {
      return false;
    }
    if (!types.isSubtype(containingHider.asType(), containingHidden.asType())) {
      return false;
    }
    return true;
  }

  private static @Nullable Element containingClass(TurbineElement element) {
    Element enclosing = element.getEnclosingElement();
    if (enclosing == null) {
      return null;
    }
    if (!isClassOrInterface(enclosing.getKind())) {
      // The immediately enclosing element of a field or method is a class. For classes, annotation
      // processing only deals with top-level and nested (but not local or anonymous) classes,
      // so the immediately enclosing element is either an enclosing class or a package symbol.
      return null;
    }
    return enclosing;
  }

  private static boolean isClassOrInterface(ElementKind kind) {
    return kind.isClass() || kind.isInterface();
  }

  private static boolean isVisibleForHiding(TurbineElement hider, TurbineElement hidden) {
    int access;
    switch (hidden.sym().symKind()) {
      case CLASS:
        access = ((TurbineTypeElement) hidden).info().access();
        break;
      case FIELD:
        access = ((TurbineFieldElement) hidden).info().access();
        break;
      case METHOD:
        access = ((TurbineExecutableElement) hidden).info().access();
        break;
      default:
        return false;
    }
    return isVisible(
        packageSymbol(asSymbol(hider)),
        packageSymbol(asSymbol(hidden)),
        TurbineVisibility.fromAccess(access));
  }

  @Override
  public boolean overrides(
      ExecutableElement overrider, ExecutableElement overridden, TypeElement type) {
    if (!overrider.getSimpleName().contentEquals(overridden.getSimpleName())) {
      return false;
    }
    TypeMirror a = overrider.asType();
    TypeMirror b = types.asMemberOfInternal((DeclaredType) type.asType(), overridden);
    if (b == null) {
      return false;
    }
    if (!types.isSubsignature((TurbineExecutableType) a, (TurbineExecutableType) b)) {
      return false;
    }
    return isVisible(
        packageSymbol(asSymbol(overrider)),
        packageSymbol(asSymbol(overridden)),
        TurbineVisibility.fromAccess(((TurbineExecutableElement) overridden).info().access()));
  }

  @Override
  public String getConstantExpression(Object value) {
    if (value instanceof Byte) {
      return new Const.ByteValue((Byte) value).toString();
    }
    if (value instanceof Long) {
      return new Const.LongValue((Long) value).toString();
    }
    if (value instanceof Float) {
      return new Const.FloatValue((Float) value).toString();
    }
    if (value instanceof Double) {
      return new Const.DoubleValue((Double) value).toString();
    }
    if (value instanceof Short) {
      // Special-case short for consistency with javac, see:
      // https://bugs.openjdk.java.net/browse/JDK-8227617
      return String.format("(short)%d", (Short) value);
    }
    if (value instanceof String) {
      return new Const.StringValue((String) value).toString();
    }
    if (value instanceof Character) {
      return new Const.CharValue((Character) value).toString();
    }
    return String.valueOf(value);
  }

  @Override
  public void printElements(Writer w, Element... elements) {
    PrintWriter pw = new PrintWriter(w, true);
    for (Element element : elements) {
      pw.println(element.toString());
    }
  }

  @Override
  public Name getName(CharSequence cs) {
    return new TurbineName(cs.toString());
  }

  @Override
  public boolean isFunctionalInterface(TypeElement type) {
    throw new UnsupportedOperationException();
  }
}

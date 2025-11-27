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

import static java.util.Objects.requireNonNull;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.turbine.binder.bound.SourceTypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass.FieldInfo;
import com.google.turbine.binder.bound.TypeBoundClass.MethodInfo;
import com.google.turbine.binder.bound.TypeBoundClass.ParamInfo;
import com.google.turbine.binder.bound.TypeBoundClass.RecordComponentInfo;
import com.google.turbine.binder.bound.TypeBoundClass.TyVarInfo;
import com.google.turbine.binder.lookup.PackageScope;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.FieldSymbol;
import com.google.turbine.binder.sym.MethodSymbol;
import com.google.turbine.binder.sym.PackageSymbol;
import com.google.turbine.binder.sym.ParamSymbol;
import com.google.turbine.binder.sym.RecordComponentSymbol;
import com.google.turbine.binder.sym.Symbol;
import com.google.turbine.binder.sym.TyVarSymbol;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.diag.TurbineError.ErrorKind;
import com.google.turbine.model.TurbineFlag;
import com.google.turbine.tree.Tree.MethDecl;
import com.google.turbine.tree.Tree.VarDecl;
import com.google.turbine.type.AnnoInfo;
import com.google.turbine.type.Type;
import com.google.turbine.type.Type.ClassTy;
import com.google.turbine.type.Type.ClassTy.SimpleClassTy;
import com.google.turbine.type.Type.ErrorTy;
import java.lang.annotation.Annotation;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ElementVisitor;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import org.jspecify.annotations.Nullable;

/** An {@link Element} implementation backed by a {@link Symbol}. */
@SuppressWarnings("nullness") // TODO(cushon): Address nullness diagnostics.
public abstract class TurbineElement implements Element {

  public abstract Symbol sym();

  public abstract String javadoc();

  @Override
  public abstract int hashCode();

  @Override
  public abstract boolean equals(@Nullable Object obj);

  protected final ModelFactory factory;
  private final Supplier<ImmutableList<AnnotationMirror>> annotationMirrors;

  protected <T> Supplier<T> memoize(Supplier<T> supplier) {
    return factory.memoize(supplier);
  }

  protected TurbineElement(ModelFactory factory) {
    this.factory = requireNonNull(factory);
    this.annotationMirrors =
        factory.memoize(
            new Supplier<ImmutableList<AnnotationMirror>>() {
              @Override
              public ImmutableList<AnnotationMirror> get() {
                ImmutableList.Builder<AnnotationMirror> result = ImmutableList.builder();
                for (AnnoInfo anno : annos()) {
                  result.add(TurbineAnnotationMirror.create(factory, anno));
                }
                return result.build();
              }
            });
  }

  static AnnoInfo getAnnotation(Iterable<AnnoInfo> annos, ClassSymbol sym) {
    for (AnnoInfo anno : annos) {
      if (Objects.equals(anno.sym(), sym)) {
        return anno;
      }
    }
    return null;
  }

  @Override
  public <A extends Annotation> A getAnnotation(Class<A> annotationType) {
    return TurbineAnnotationProxy.getAnnotation(factory, annos(), annotationType);
  }

  @Override
  public final <A extends Annotation> A[] getAnnotationsByType(Class<A> annotationType) {
    return TurbineAnnotationProxy.getAnnotationsByType(factory, annos(), annotationType);
  }

  @Override
  public final List<? extends AnnotationMirror> getAnnotationMirrors() {
    return annotationMirrors.get();
  }

  List<? extends AnnotationMirror> getAllAnnotationMirrors() {
    return getAnnotationMirrors();
  }

  protected abstract ImmutableList<AnnoInfo> annos();

  /** A {@link TypeElement} implementation backed by a {@link ClassSymbol}. */
  static class TurbineTypeElement extends TurbineElement implements TypeElement {

    @Override
    public int hashCode() {
      return sym.hashCode();
    }

    private final ClassSymbol sym;
    private final Supplier<TypeBoundClass> info;

    TurbineTypeElement(ModelFactory factory, ClassSymbol sym) {
      super(factory);
      this.sym = requireNonNull(sym);
      this.info =
          memoize(
              new Supplier<TypeBoundClass>() {
                @Override
                public TypeBoundClass get() {
                  return factory.getSymbol(sym);
                }
              });
    }

    @Nullable TypeBoundClass info() {
      return info.get();
    }

    TypeBoundClass infoNonNull() {
      TypeBoundClass info = info();
      if (info == null) {
        throw TurbineError.format(/* source= */ null, ErrorKind.SYMBOL_NOT_FOUND, sym);
      }
      return info;
    }

    @Override
    public NestingKind getNestingKind() {
      TypeBoundClass info = info();
      return (info != null && info.owner() != null) ? NestingKind.MEMBER : NestingKind.TOP_LEVEL;
    }

    private final Supplier<TurbineName> qualifiedName =
        memoize(
            new Supplier<TurbineName>() {
              @Override
              public TurbineName get() {
                TypeBoundClass info = info();
                if (info == null || info.owner() == null) {
                  return new TurbineName(sym.toString());
                }
                ClassSymbol sym = sym();
                Deque<String> flat = new ArrayDeque<>();
                while (info.owner() != null) {
                  flat.addFirst(sym.binaryName().substring(info.owner().binaryName().length() + 1));
                  sym = info.owner();
                  info = factory.getSymbol(sym);
                }
                flat.addFirst(sym.toString());
                return new TurbineName(Joiner.on('.').join(flat));
              }
            });

    @Override
    public Name getQualifiedName() {
      return qualifiedName.get();
    }

    private final Supplier<TypeMirror> superclass =
        memoize(
            new Supplier<TypeMirror>() {
              @Override
              public TypeMirror get() {
                TypeBoundClass info = infoNonNull();
                switch (info.kind()) {
                  case CLASS:
                  case ENUM:
                  case RECORD:
                    if (info.superClassType() != null) {
                      return factory.asTypeMirror(info.superClassType());
                    }
                    return factory.noType();
                  case INTERFACE:
                  case ANNOTATION:
                    return factory.noType();
                }
                throw new AssertionError(info.kind());
              }
            });

    @Override
    public TypeMirror getSuperclass() {
      return superclass.get();
    }

    @Override
    public String toString() {
      return getQualifiedName().toString();
    }

    private final Supplier<List<TypeMirror>> interfaces =
        memoize(
            new Supplier<List<TypeMirror>>() {
              @Override
              public List<TypeMirror> get() {
                return factory.asTypeMirrors(infoNonNull().interfaceTypes());
              }
            });

    @Override
    public List<? extends TypeMirror> getInterfaces() {
      return interfaces.get();
    }

    private final Supplier<ImmutableList<TypeParameterElement>> typeParameters =
        memoize(
            new Supplier<ImmutableList<TypeParameterElement>>() {
              @Override
              public ImmutableList<TypeParameterElement> get() {
                ImmutableList.Builder<TypeParameterElement> result = ImmutableList.builder();
                for (TyVarSymbol p : infoNonNull().typeParameters().values()) {
                  result.add(factory.typeParameterElement(p));
                }
                return result.build();
              }
            });

    @Override
    public List<? extends TypeParameterElement> getTypeParameters() {
      return typeParameters.get();
    }

    private final Supplier<TypeMirror> type =
        memoize(
            new Supplier<TypeMirror>() {
              @Override
              public TypeMirror get() {
                return factory.asTypeMirror(asGenericType(sym));
              }

              Type asGenericType(ClassSymbol symbol) {
                TypeBoundClass info = info();
                if (info == null) {
                  return ErrorTy.create(getQualifiedName().toString(), ImmutableList.of());
                }
                Deque<Type.ClassTy.SimpleClassTy> simples = new ArrayDeque<>();
                simples.addFirst(simple(symbol, info));
                while (info.owner() != null && (info.access() & TurbineFlag.ACC_STATIC) == 0) {
                  symbol = info.owner();
                  info = factory.getSymbol(symbol);
                  simples.addFirst(simple(symbol, info));
                }
                return ClassTy.create(ImmutableList.copyOf(simples));
              }

              private SimpleClassTy simple(ClassSymbol sym, TypeBoundClass info) {
                ImmutableList.Builder<Type> args = ImmutableList.builder();
                for (TyVarSymbol t : info.typeParameters().values()) {
                  args.add(Type.TyVar.create(t, ImmutableList.of()));
                }
                return SimpleClassTy.create(sym, args.build(), ImmutableList.of());
              }
            });

    @Override
    public TypeMirror asType() {
      return type.get();
    }

    @Override
    public ElementKind getKind() {
      TypeBoundClass info = infoNonNull();
      switch (info.kind()) {
        case CLASS:
          return ElementKind.CLASS;
        case INTERFACE:
          return ElementKind.INTERFACE;
        case ENUM:
          return ElementKind.ENUM;
        case ANNOTATION:
          return ElementKind.ANNOTATION_TYPE;
        case RECORD:
          return RECORD.get();
      }
      throw new AssertionError(info.kind());
    }

    private static final Supplier<ElementKind> RECORD =
        Suppliers.memoize(
            new Supplier<ElementKind>() {
              @Override
              public ElementKind get() {
                return ElementKind.valueOf("RECORD");
              }
            });

    @Override
    public Set<Modifier> getModifiers() {
      return asModifierSet(ModifierOwner.TYPE, infoNonNull().access() & ~TurbineFlag.ACC_SUPER);
    }

    private final Supplier<TurbineName> simpleName =
        memoize(
            new Supplier<TurbineName>() {
              @Override
              public TurbineName get() {
                TypeBoundClass info = info();
                if (info == null || info.owner() == null) {
                  return new TurbineName(sym.simpleName());
                }
                return new TurbineName(
                    sym.binaryName().substring(info.owner().binaryName().length() + 1));
              }
            });

    @Override
    public Name getSimpleName() {
      return simpleName.get();
    }

    private final Supplier<Element> enclosing =
        memoize(
            new Supplier<Element>() {
              @Override
              public Element get() {
                return getNestingKind().equals(NestingKind.TOP_LEVEL)
                    ? factory.packageElement(sym.owner())
                    : factory.typeElement(info().owner());
              }
            });

    @Override
    public Element getEnclosingElement() {
      return enclosing.get();
    }

    private final Supplier<ImmutableList<TypeMirror>> permits =
        memoize(
            new Supplier<>() {
              @Override
              public ImmutableList<TypeMirror> get() {
                ImmutableList.Builder<TypeMirror> result = ImmutableList.builder();
                for (ClassSymbol p : infoNonNull().permits()) {
                  result.add(factory.asTypeMirror(ClassTy.asNonParametricClassTy(p)));
                }
                return result.build();
              }
            });

    @Override
    public List<? extends TypeMirror> getPermittedSubclasses() {
      return permits.get();
    }

    private final Supplier<ImmutableList<Element>> enclosed =
        memoize(
            new Supplier<ImmutableList<Element>>() {
              @Override
              public ImmutableList<Element> get() {
                TypeBoundClass info = infoNonNull();
                ImmutableList.Builder<Element> result = ImmutableList.builder();
                for (RecordComponentInfo component : info.components()) {
                  result.add(factory.recordComponentElement(component.sym()));
                }
                for (FieldInfo field : info.fields()) {
                  result.add(factory.fieldElement(field.sym()));
                }
                for (MethodInfo method : info.methods()) {
                  result.add(factory.executableElement(method.sym()));
                }
                for (ClassSymbol child : info.children().values()) {
                  result.add(factory.typeElement(child));
                }
                return result.build();
              }
            });

    @Override
    public List<? extends Element> getEnclosedElements() {
      return enclosed.get();
    }

    @Override
    public <R, P> R accept(ElementVisitor<R, P> v, P p) {
      return v.visitType(this, p);
    }

    @Override
    public ClassSymbol sym() {
      return sym;
    }

    @Override
    public String javadoc() {
      TypeBoundClass info = info();
      if (!(info instanceof SourceTypeBoundClass)) {
        return null;
      }
      return ((SourceTypeBoundClass) info).decl().javadoc();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
      return obj instanceof TurbineTypeElement && sym.equals(((TurbineTypeElement) obj).sym);
    }

    @Override
    protected ImmutableList<AnnoInfo> annos() {
      return infoNonNull().annotations();
    }

    @Override
    public final <A extends Annotation> A getAnnotation(Class<A> annotationType) {
      ClassSymbol sym = new ClassSymbol(annotationType.getName().replace('.', '/'));
      AnnoInfo anno = getAnnotation(annos(), sym);
      if (anno != null) {
        return TurbineAnnotationProxy.create(factory, annotationType, anno);
      }
      if (!isAnnotationInherited(sym)) {
        return null;
      }
      ClassSymbol superclass = infoNonNull().superclass();
      while (superclass != null) {
        TypeBoundClass info = factory.getSymbol(superclass);
        if (info == null) {
          break;
        }
        anno = getAnnotation(info.annotations(), sym);
        if (anno != null) {
          return TurbineAnnotationProxy.create(factory, annotationType, anno);
        }
        superclass = info.superclass();
      }
      return null;
    }

    @Override
    List<? extends AnnotationMirror> getAllAnnotationMirrors() {
      Map<ClassSymbol, AnnotationMirror> result = new LinkedHashMap<>();
      for (AnnoInfo anno : annos()) {
        result.put(anno.sym(), TurbineAnnotationMirror.create(factory, anno));
      }
      ClassSymbol superclass = infoNonNull().superclass();
      while (superclass != null) {
        TypeBoundClass i = factory.getSymbol(superclass);
        if (i == null) {
          break;
        }
        for (AnnoInfo anno : i.annotations()) {
          addAnnotationFromSuper(result, anno);
        }
        superclass = i.superclass();
      }
      return ImmutableList.copyOf(result.values());
    }

    private void addAnnotationFromSuper(Map<ClassSymbol, AnnotationMirror> result, AnnoInfo anno) {
      if (!isAnnotationInherited(anno.sym())) {
        return;
      }
      if (result.containsKey(anno.sym())) {
        // if the same inherited annotation is present on multiple supertypes, only return one
        return;
      }
      result.put(anno.sym(), TurbineAnnotationMirror.create(factory, anno));
    }

    private boolean isAnnotationInherited(ClassSymbol sym) {
      TypeBoundClass annoInfo = factory.getSymbol(sym);
      if (annoInfo == null) {
        return false;
      }
      for (AnnoInfo anno : annoInfo.annotations()) {
        if (anno.sym().equals(ClassSymbol.INHERITED)) {
          return true;
        }
      }
      return false;
    }

    private final Supplier<ImmutableMap<RecordComponentSymbol, MethodSymbol>> recordAccessors =
        memoize(
            new Supplier<ImmutableMap<RecordComponentSymbol, MethodSymbol>>() {
              @Override
              public ImmutableMap<RecordComponentSymbol, MethodSymbol> get() {
                Map<String, MethodSymbol> methods = new HashMap<>();
                for (MethodInfo method : info().methods()) {
                  if (method.parameters().isEmpty()) {
                    methods.put(method.name(), method.sym());
                  }
                }
                ImmutableMap.Builder<RecordComponentSymbol, MethodSymbol> result =
                    ImmutableMap.builder();
                for (RecordComponentInfo component : info().components()) {
                  result.put(component.sym(), methods.get(component.name()));
                }
                return result.buildOrThrow();
              }
            });

    ExecutableElement recordAccessor(RecordComponentSymbol component) {
      return factory.executableElement(recordAccessors.get().get(component));
    }

    private final Supplier<ImmutableList<RecordComponentElement>> recordComponents =
        memoize(
            new Supplier<ImmutableList<RecordComponentElement>>() {
              @Override
              public ImmutableList<RecordComponentElement> get() {
                ImmutableList.Builder<RecordComponentElement> result = ImmutableList.builder();
                for (RecordComponentInfo component : info().components()) {
                  result.add(factory.recordComponentElement(component.sym()));
                }
                return result.build();
              }
            });

    @Override
    public List<? extends RecordComponentElement> getRecordComponents() {
      return recordComponents.get();
    }
  }

  /** A {@link TypeParameterElement} implementation backed by a {@link TyVarSymbol}. */
  static class TurbineTypeParameterElement extends TurbineElement implements TypeParameterElement {

    @Override
    public int hashCode() {
      return sym.hashCode();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
      return obj instanceof TurbineTypeParameterElement
          && sym.equals(((TurbineTypeParameterElement) obj).sym);
    }

    private final TyVarSymbol sym;

    public TurbineTypeParameterElement(ModelFactory factory, TyVarSymbol sym) {
      super(factory);
      this.sym = sym;
    }

    private final Supplier<TyVarInfo> info =
        memoize(
            new Supplier<TyVarInfo>() {
              @Override
              public TyVarInfo get() {
                return factory.getTyVarInfo(sym);
              }
            });

    private @Nullable TyVarInfo info() {
      return info.get();
    }

    @Override
    public String toString() {
      return sym.name();
    }

    @Override
    public Element getGenericElement() {
      return factory.element(sym.owner());
    }

    @Override
    public List<? extends TypeMirror> getBounds() {
      ImmutableList<Type> bounds = info().upperBound().bounds();
      return factory.asTypeMirrors(bounds.isEmpty() ? ImmutableList.of(ClassTy.OBJECT) : bounds);
    }

    @Override
    public TypeMirror asType() {
      return factory.asTypeMirror(Type.TyVar.create(sym, ImmutableList.of()));
    }

    @Override
    public ElementKind getKind() {
      return ElementKind.TYPE_PARAMETER;
    }

    @Override
    public Set<Modifier> getModifiers() {
      return ImmutableSet.of();
    }

    @Override
    public Name getSimpleName() {
      return new TurbineName(sym.name());
    }

    @Override
    public Element getEnclosingElement() {
      return getGenericElement();
    }

    @Override
    public List<? extends Element> getEnclosedElements() {
      return ImmutableList.of();
    }

    @Override
    public <R, P> R accept(ElementVisitor<R, P> v, P p) {
      return v.visitTypeParameter(this, p);
    }

    @Override
    public TyVarSymbol sym() {
      return sym;
    }

    @Override
    public String javadoc() {
      return null;
    }

    @Override
    protected ImmutableList<AnnoInfo> annos() {
      return info().annotations();
    }
  }

  /** An {@link ExecutableElement} implementation backed by a {@link MethodSymbol}. */
  static class TurbineExecutableElement extends TurbineElement implements ExecutableElement {

    private final MethodSymbol sym;

    private final Supplier<MethodInfo> info =
        memoize(
            new Supplier<MethodInfo>() {
              @Override
              public MethodInfo get() {
                return factory.getMethodInfo(sym);
              }
            });

    @Nullable MethodInfo info() {
      return info.get();
    }

    TurbineExecutableElement(ModelFactory factory, MethodSymbol sym) {
      super(factory);
      this.sym = sym;
    }

    @Override
    public MethodSymbol sym() {
      return sym;
    }

    @Override
    public String javadoc() {
      MethDecl decl = info().decl();
      return decl != null ? decl.javadoc() : null;
    }

    @Override
    public int hashCode() {
      return sym.hashCode();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
      return obj instanceof TurbineExecutableElement
          && sym.equals(((TurbineExecutableElement) obj).sym);
    }

    @Override
    public List<? extends TypeParameterElement> getTypeParameters() {
      ImmutableList.Builder<TurbineTypeParameterElement> result = ImmutableList.builder();
      for (Map.Entry<TyVarSymbol, TyVarInfo> p : info().tyParams().entrySet()) {
        result.add(factory.typeParameterElement(p.getKey()));
      }
      return result.build();
    }

    @Override
    public TypeMirror getReturnType() {
      return factory.asTypeMirror(info().returnType());
    }

    private final Supplier<ImmutableList<VariableElement>> parameters =
        memoize(
            new Supplier<ImmutableList<VariableElement>>() {
              @Override
              public ImmutableList<VariableElement> get() {
                ImmutableList.Builder<VariableElement> result = ImmutableList.builder();
                for (ParamInfo param : info().parameters()) {
                  if (param.synthetic()) {
                    // ExecutableElement#getParameters doesn't expect synthetic or mandated
                    // parameters
                    continue;
                  }
                  result.add(factory.parameterElement(param.sym()));
                }
                return result.build();
              }
            });

    @Override
    public List<? extends VariableElement> getParameters() {
      return parameters.get();
    }

    @Override
    public String toString() {
      MethodInfo info = info();
      StringBuilder sb = new StringBuilder();
      if (!info.tyParams().isEmpty()) {
        sb.append('<');
        Joiner.on(',').appendTo(sb, info.tyParams().keySet());
        sb.append('>');
      }
      if (getKind() == ElementKind.CONSTRUCTOR) {
        sb.append(info.sym().owner().simpleName());
      } else {
        sb.append(info.sym().name());
      }
      sb.append('(');
      boolean first = true;
      for (ParamInfo p : info.parameters()) {
        if (!first) {
          sb.append(',');
        }
        sb.append(p.type());
        first = false;
      }
      sb.append(')');
      return sb.toString();
    }

    @Override
    public TypeMirror getReceiverType() {
      return info().receiver() != null
          ? factory.asTypeMirror(info().receiver().type())
          : factory.noType();
    }

    @Override
    public boolean isVarArgs() {
      return (info().access() & TurbineFlag.ACC_VARARGS) == TurbineFlag.ACC_VARARGS;
    }

    @Override
    public boolean isDefault() {
      return (info().access() & TurbineFlag.ACC_DEFAULT) == TurbineFlag.ACC_DEFAULT;
    }

    @Override
    public List<? extends TypeMirror> getThrownTypes() {
      return factory.asTypeMirrors(info().exceptions());
    }

    @Override
    public AnnotationValue getDefaultValue() {
      return info().defaultValue() != null
          ? TurbineAnnotationMirror.annotationValue(factory, info().defaultValue())
          : null;
    }

    @Override
    public TypeMirror asType() {
      return factory.asTypeMirror(info().asType());
    }

    @Override
    public ElementKind getKind() {
      return sym.name().equals("<init>") ? ElementKind.CONSTRUCTOR : ElementKind.METHOD;
    }

    @Override
    public Set<Modifier> getModifiers() {
      return asModifierSet(ModifierOwner.METHOD, info().access());
    }

    @Override
    public Name getSimpleName() {
      return new TurbineName(info().sym().name());
    }

    @Override
    public Element getEnclosingElement() {
      return factory.typeElement(info().sym().owner());
    }

    @Override
    public List<? extends Element> getEnclosedElements() {
      return ImmutableList.of();
    }

    @Override
    public <R, P> R accept(ElementVisitor<R, P> v, P p) {
      return v.visitExecutable(this, p);
    }

    @Override
    protected ImmutableList<AnnoInfo> annos() {
      return info().annotations();
    }
  }

  /** An {@link VariableElement} implementation backed by a {@link FieldSymbol}. */
  static class TurbineFieldElement extends TurbineElement implements VariableElement {

    @Override
    public String toString() {
      return sym.name();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
      return obj instanceof TurbineFieldElement && sym.equals(((TurbineFieldElement) obj).sym);
    }

    @Override
    public int hashCode() {
      return sym.hashCode();
    }

    private final FieldSymbol sym;

    @Override
    public FieldSymbol sym() {
      return sym;
    }

    @Override
    public String javadoc() {
      VarDecl decl = info().decl();
      return decl != null ? decl.javadoc() : null;
    }

    private final Supplier<FieldInfo> info =
        memoize(
            new Supplier<FieldInfo>() {
              @Override
              public FieldInfo get() {
                return factory.getFieldInfo(sym);
              }
            });

    @Nullable FieldInfo info() {
      return info.get();
    }

    TurbineFieldElement(ModelFactory factory, FieldSymbol sym) {
      super(factory);
      this.sym = sym;
    }

    @Override
    public Object getConstantValue() {
      if (info().value() == null) {
        return null;
      }
      return info().value().getValue();
    }

    @Override
    public TypeMirror asType() {
      return factory.asTypeMirror(info().type());
    }

    @Override
    public ElementKind getKind() {
      return ((info().access() & TurbineFlag.ACC_ENUM) == TurbineFlag.ACC_ENUM)
          ? ElementKind.ENUM_CONSTANT
          : ElementKind.FIELD;
    }

    @Override
    public Set<Modifier> getModifiers() {
      return asModifierSet(ModifierOwner.FIELD, info().access());
    }

    @Override
    public Name getSimpleName() {
      return new TurbineName(sym.name());
    }

    @Override
    public Element getEnclosingElement() {
      return factory.typeElement(sym.owner());
    }

    @Override
    public List<? extends Element> getEnclosedElements() {
      return ImmutableList.of();
    }

    @Override
    public <R, P> R accept(ElementVisitor<R, P> v, P p) {
      return v.visitVariable(this, p);
    }

    @Override
    protected ImmutableList<AnnoInfo> annos() {
      return info().annotations();
    }
  }

  private enum ModifierOwner {
    TYPE,
    PARAMETER,
    FIELD,
    METHOD
  }

  private static ImmutableSet<Modifier> asModifierSet(ModifierOwner modifierOwner, int access) {
    EnumSet<Modifier> modifiers = EnumSet.noneOf(Modifier.class);
    if ((access & TurbineFlag.ACC_PUBLIC) == TurbineFlag.ACC_PUBLIC) {
      modifiers.add(Modifier.PUBLIC);
    }
    if ((access & TurbineFlag.ACC_PROTECTED) == TurbineFlag.ACC_PROTECTED) {
      modifiers.add(Modifier.PROTECTED);
    }
    if ((access & TurbineFlag.ACC_PRIVATE) == TurbineFlag.ACC_PRIVATE) {
      modifiers.add(Modifier.PRIVATE);
    }
    if ((access & TurbineFlag.ACC_ABSTRACT) == TurbineFlag.ACC_ABSTRACT) {
      modifiers.add(Modifier.ABSTRACT);
    }
    if ((access & TurbineFlag.ACC_FINAL) == TurbineFlag.ACC_FINAL) {
      modifiers.add(Modifier.FINAL);
    }
    if ((access & TurbineFlag.ACC_DEFAULT) == TurbineFlag.ACC_DEFAULT) {
      modifiers.add(Modifier.DEFAULT);
    }
    if ((access & TurbineFlag.ACC_STATIC) == TurbineFlag.ACC_STATIC) {
      modifiers.add(Modifier.STATIC);
    }
    if ((access & TurbineFlag.ACC_TRANSIENT) == TurbineFlag.ACC_TRANSIENT) {
      switch (modifierOwner) {
        case METHOD:
        case PARAMETER:
          // varargs and transient use the same bits
          break;
        default:
          modifiers.add(Modifier.TRANSIENT);
      }
    }
    if ((access & TurbineFlag.ACC_VOLATILE) == TurbineFlag.ACC_VOLATILE) {
      modifiers.add(Modifier.VOLATILE);
    }
    if ((access & TurbineFlag.ACC_SYNCHRONIZED) == TurbineFlag.ACC_SYNCHRONIZED) {
      modifiers.add(Modifier.SYNCHRONIZED);
    }
    if ((access & TurbineFlag.ACC_NATIVE) == TurbineFlag.ACC_NATIVE) {
      modifiers.add(Modifier.NATIVE);
    }
    if ((access & TurbineFlag.ACC_STRICT) == TurbineFlag.ACC_STRICT) {
      modifiers.add(Modifier.STRICTFP);
    }
    if ((access & TurbineFlag.ACC_SEALED) == TurbineFlag.ACC_SEALED) {
      modifiers.add(Modifier.SEALED);
    }
    if ((access & TurbineFlag.ACC_NON_SEALED) == TurbineFlag.ACC_NON_SEALED) {
      modifiers.add(Modifier.NON_SEALED);
    }
    return Sets.immutableEnumSet(modifiers);
  }

  /** A {@link PackageElement} implementation backed by a {@link PackageSymbol}. */
  static class TurbinePackageElement extends TurbineElement implements PackageElement {

    private final PackageSymbol sym;

    public TurbinePackageElement(ModelFactory factory, PackageSymbol sym) {
      super(factory);
      this.sym = sym;
    }

    @Override
    public Name getQualifiedName() {
      return new TurbineName(sym.toString());
    }

    @Override
    public boolean isUnnamed() {
      return sym.binaryName().isEmpty();
    }

    @Override
    public TypeMirror asType() {
      return factory.packageType(sym);
    }

    @Override
    public ElementKind getKind() {
      return ElementKind.PACKAGE;
    }

    @Override
    public Set<Modifier> getModifiers() {
      return ImmutableSet.of();
    }

    @Override
    public Name getSimpleName() {
      return new TurbineName(sym.binaryName().substring(sym.binaryName().lastIndexOf('/') + 1));
    }

    @Override
    public Element getEnclosingElement() {
      // a package is not enclosed by another element
      return null;
    }

    @Override
    public List<TurbineTypeElement> getEnclosedElements() {
      ImmutableSet.Builder<TurbineTypeElement> result = ImmutableSet.builder();
      PackageScope scope = factory.tli().lookupPackage(Splitter.on('/').split(sym.binaryName()));
      requireNonNull(scope); // the current package exists
      for (ClassSymbol key : scope.classes()) {
        if (key.binaryName().contains("$") && factory.getSymbol(key).owner() != null) {
          // Skip member classes: only top-level classes are enclosed by the package.
          // The initial check for '$' is an optimization.
          continue;
        }
        if (key.simpleName().equals("package-info")) {
          continue;
        }
        result.add(factory.typeElement(key));
      }
      return result.build().asList();
    }

    @Override
    public <R, P> R accept(ElementVisitor<R, P> v, P p) {
      return v.visitPackage(this, p);
    }

    @Override
    public PackageSymbol sym() {
      return sym;
    }

    @Override
    public String javadoc() {
      return null;
    }

    @Override
    public int hashCode() {
      return sym.hashCode();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
      return obj instanceof TurbinePackageElement && sym.equals(((TurbinePackageElement) obj).sym);
    }

    private final Supplier<ImmutableList<AnnoInfo>> annos =
        memoize(
            new Supplier<ImmutableList<AnnoInfo>>() {
              @Override
              public ImmutableList<AnnoInfo> get() {
                TypeBoundClass info =
                    factory.getSymbol(new ClassSymbol(sym.binaryName() + "/package-info"));
                return info != null ? info.annotations() : ImmutableList.of();
              }
            });

    @Override
    protected ImmutableList<AnnoInfo> annos() {
      return annos.get();
    }

    @Override
    public String toString() {
      return sym.toString();
    }
  }

  /** A {@link VariableElement} implementation backed by a {@link ParamSymbol}. */
  static class TurbineParameterElement extends TurbineElement implements VariableElement {

    @Override
    public ParamSymbol sym() {
      return sym;
    }

    @Override
    public String javadoc() {
      return null;
    }

    @Override
    public int hashCode() {
      return sym.hashCode();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
      return obj instanceof TurbineParameterElement
          && sym.equals(((TurbineParameterElement) obj).sym);
    }

    private final ParamSymbol sym;

    private final Supplier<ParamInfo> info =
        memoize(
            new Supplier<ParamInfo>() {
              @Override
              public ParamInfo get() {
                return factory.getParamInfo(sym);
              }
            });

    @Nullable ParamInfo info() {
      return info.get();
    }

    public TurbineParameterElement(ModelFactory factory, ParamSymbol sym) {
      super(factory);
      this.sym = sym;
    }

    @Override
    public Object getConstantValue() {
      return null;
    }

    private final Supplier<TypeMirror> type =
        memoize(
            new Supplier<TypeMirror>() {
              @Override
              public TypeMirror get() {
                return factory.asTypeMirror(info().type());
              }
            });

    @Override
    public TypeMirror asType() {
      return type.get();
    }

    @Override
    public ElementKind getKind() {
      return ElementKind.PARAMETER;
    }

    @Override
    public Set<Modifier> getModifiers() {
      return asModifierSet(ModifierOwner.PARAMETER, info().access());
    }

    @Override
    public Name getSimpleName() {
      return new TurbineName(sym.name());
    }

    @Override
    public Element getEnclosingElement() {
      return factory.executableElement(sym.owner());
    }

    @Override
    public List<? extends Element> getEnclosedElements() {
      return ImmutableList.of();
    }

    @Override
    public <R, P> R accept(ElementVisitor<R, P> v, P p) {
      return v.visitVariable(this, p);
    }

    @Override
    public String toString() {
      return String.valueOf(sym.name());
    }

    @Override
    protected ImmutableList<AnnoInfo> annos() {
      return info().annotations();
    }
  }

  /** A {@link VariableElement} implementation for a record info. */
  static class TurbineRecordComponentElement extends TurbineElement
      implements RecordComponentElement {

    @Override
    public RecordComponentSymbol sym() {
      return sym;
    }

    @Override
    public String javadoc() {
      return null;
    }

    @Override
    public int hashCode() {
      return sym.hashCode();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
      return obj instanceof TurbineRecordComponentElement
          && sym.equals(((TurbineRecordComponentElement) obj).sym);
    }

    private final RecordComponentSymbol sym;

    private final Supplier<RecordComponentInfo> info =
        memoize(
            new Supplier<RecordComponentInfo>() {
              @Override
              public RecordComponentInfo get() {
                return factory.getRecordComponentInfo(sym);
              }
            });

    @Nullable RecordComponentInfo info() {
      return info.get();
    }

    public TurbineRecordComponentElement(ModelFactory factory, RecordComponentSymbol sym) {
      super(factory);
      this.sym = sym;
    }

    private final Supplier<TypeMirror> type =
        memoize(
            new Supplier<TypeMirror>() {
              @Override
              public TypeMirror get() {
                return factory.asTypeMirror(info().type());
              }
            });

    @Override
    public TypeMirror asType() {
      return type.get();
    }

    @Override
    public ElementKind getKind() {
      return ElementKind.RECORD_COMPONENT;
    }

    @Override
    public Set<Modifier> getModifiers() {
      return asModifierSet(ModifierOwner.PARAMETER, info().access());
    }

    @Override
    public Name getSimpleName() {
      return new TurbineName(sym.name());
    }

    private final Supplier<ExecutableElement> accessor =
        Suppliers.memoize(
            new Supplier<ExecutableElement>() {
              @Override
              public ExecutableElement get() {
                return factory.typeElement(sym.owner()).recordAccessor(sym);
              }
            });

    @Override
    public ExecutableElement getAccessor() {
      return accessor.get();
    }

    @Override
    public Element getEnclosingElement() {
      return factory.typeElement(sym.owner());
    }

    @Override
    public List<? extends Element> getEnclosedElements() {
      return ImmutableList.of();
    }

    @Override
    public <R, P> R accept(ElementVisitor<R, P> v, P p) {
      return v.visitRecordComponent(this, p);
    }

    @Override
    public String toString() {
      return String.valueOf(sym.name());
    }

    @Override
    protected ImmutableList<AnnoInfo> annos() {
      return info().annotations();
    }
  }

  static class TurbineNoTypeElement implements TypeElement {

    private final ModelFactory factory;
    private final String name;

    public TurbineNoTypeElement(ModelFactory factory, String name) {
      this.factory = factory;
      this.name = requireNonNull(name);
    }

    @Override
    public TypeMirror asType() {
      return factory.noType();
    }

    @Override
    public ElementKind getKind() {
      return ElementKind.CLASS;
    }

    @Override
    public Set<Modifier> getModifiers() {
      return ImmutableSet.of();
    }

    @Override
    public Name getSimpleName() {
      return new TurbineName(name.substring(name.lastIndexOf('.') + 1));
    }

    @Override
    public TypeMirror getSuperclass() {
      return factory.noType();
    }

    @Override
    public List<? extends TypeMirror> getInterfaces() {
      return ImmutableList.of();
    }

    @Override
    public List<? extends TypeParameterElement> getTypeParameters() {
      return ImmutableList.of();
    }

    @Override
    public Element getEnclosingElement() {
      int idx = name.lastIndexOf('.');
      String packageName;
      if (idx == -1) {
        packageName = "";
      } else {
        packageName = name.substring(0, idx).replace('.', '/');
      }
      return factory.packageElement(new PackageSymbol(packageName));
    }

    @Override
    public List<? extends Element> getEnclosedElements() {
      return ImmutableList.of();
    }

    @Override
    public NestingKind getNestingKind() {
      return NestingKind.TOP_LEVEL;
    }

    @Override
    public Name getQualifiedName() {
      return new TurbineName(name);
    }

    @Override
    public List<? extends AnnotationMirror> getAnnotationMirrors() {
      return ImmutableList.of();
    }

    @Override
    public <A extends Annotation> A getAnnotation(Class<A> aClass) {
      return null;
    }

    @Override
    public <A extends Annotation> A[] getAnnotationsByType(Class<A> aClass) {
      return null;
    }

    @Override
    public <R, P> R accept(ElementVisitor<R, P> elementVisitor, P p) {
      return elementVisitor.visitType(this, p);
    }

    @Override
    public String toString() {
      return getSimpleName().toString();
    }
  }
}

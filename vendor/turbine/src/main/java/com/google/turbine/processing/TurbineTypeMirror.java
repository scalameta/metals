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

import static com.google.common.collect.Iterables.getLast;
import static java.util.Objects.requireNonNull;

import com.google.common.base.Joiner;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass.TyVarInfo;
import com.google.turbine.binder.sym.PackageSymbol;
import com.google.turbine.binder.sym.TyVarSymbol;
import com.google.turbine.model.TurbineConstantTypeKind;
import com.google.turbine.model.TurbineFlag;
import com.google.turbine.model.TurbineTyKind;
import com.google.turbine.type.AnnoInfo;
import com.google.turbine.type.Type;
import com.google.turbine.type.Type.ArrayTy;
import com.google.turbine.type.Type.ClassTy;
import com.google.turbine.type.Type.ErrorTy;
import com.google.turbine.type.Type.IntersectionTy;
import com.google.turbine.type.Type.MethodTy;
import com.google.turbine.type.Type.PrimTy;
import com.google.turbine.type.Type.TyVar;
import com.google.turbine.type.Type.WildTy;
import com.google.turbine.type.Type.WildTy.BoundKind;
import java.lang.annotation.Annotation;
import java.util.List;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ErrorType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.NoType;
import javax.lang.model.type.NullType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.TypeVisitor;
import javax.lang.model.type.WildcardType;
import org.jspecify.annotations.Nullable;

/** A {@link TypeMirror} implementation backed by a {@link Type}. */
public abstract class TurbineTypeMirror implements TypeMirror {

  protected final ModelFactory factory;

  protected TurbineTypeMirror(ModelFactory factory) {
    this.factory = requireNonNull(factory);
  }

  protected abstract ImmutableList<AnnoInfo> annos();

  @Override
  public final List<? extends AnnotationMirror> getAnnotationMirrors() {
    ImmutableList.Builder<AnnotationMirror> result = ImmutableList.builder();
    for (AnnoInfo anno : annos()) {
      result.add(TurbineAnnotationMirror.create(factory, anno));
    }
    return result.build();
  }

  @Override
  public final <A extends Annotation> A getAnnotation(Class<A> annotationType) {
    return TurbineAnnotationProxy.getAnnotation(factory, annos(), annotationType);
  }

  @Override
  public final <A extends Annotation> A[] getAnnotationsByType(Class<A> annotationType) {
    return TurbineAnnotationProxy.getAnnotationsByType(factory, annos(), annotationType);
  }

  public abstract Type asTurbineType();

  @Override
  public String toString() {
    return asTurbineType().toString();
  }

  /** A {@link PrimitiveType} implementation backed by a {@link PrimTy}. */
  static class TurbinePrimitiveType extends TurbineTypeMirror implements PrimitiveType {

    @Override
    public Type asTurbineType() {
      return type;
    }

    public final PrimTy type;

    TurbinePrimitiveType(ModelFactory factory, PrimTy type) {
      super(factory);
      if (type.primkind() == TurbineConstantTypeKind.STRING) {
        throw new AssertionError(type);
      }
      this.type = type;
    }

    @Override
    public TypeKind getKind() {
      switch (type.primkind()) {
        case CHAR:
          return TypeKind.CHAR;
        case SHORT:
          return TypeKind.SHORT;
        case INT:
          return TypeKind.INT;
        case LONG:
          return TypeKind.LONG;
        case FLOAT:
          return TypeKind.FLOAT;
        case DOUBLE:
          return TypeKind.DOUBLE;
        case BOOLEAN:
          return TypeKind.BOOLEAN;
        case BYTE:
          return TypeKind.BYTE;
        case NULL:
          return TypeKind.NULL;
        case STRING:
      }
      throw new AssertionError(type.primkind());
    }

    @Override
    public <R, P> R accept(TypeVisitor<R, P> v, P p) {
      return v.visitPrimitive(this, p);
    }

    @Override
    protected ImmutableList<AnnoInfo> annos() {
      return type.annos();
    }
  }

  /** A {@link DeclaredType} implementation backed by a {@link ClassTy}. */
  static class TurbineDeclaredType extends TurbineTypeMirror implements DeclaredType {

    @Override
    public int hashCode() {
      return type.sym().hashCode();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
      return obj instanceof TurbineDeclaredType && type.equals(((TurbineDeclaredType) obj).type);
    }

    @Override
    public ClassTy asTurbineType() {
      return type;
    }

    private final ClassTy type;

    TurbineDeclaredType(ModelFactory factory, ClassTy type) {
      super(factory);
      this.type = type;
    }

    final Supplier<Element> element =
        factory.memoize(
            new Supplier<Element>() {
              @Override
              public Element get() {
                return factory.typeElement(type.sym());
              }
            });

    @Override
    public Element asElement() {
      return element.get();
    }

    final Supplier<TypeMirror> enclosing =
        factory.memoize(
            new Supplier<TypeMirror>() {
              @Override
              public TypeMirror get() {
                TypeBoundClass info = factory.getSymbol(type.sym());
                if (info != null
                    && info.owner() != null
                    && ((info.access() & TurbineFlag.ACC_STATIC) == 0)
                    && info.kind() == TurbineTyKind.CLASS) {
                  if (type.classes().size() > 1) {
                    return factory.asTypeMirror(
                        ClassTy.create(type.classes().subList(0, type.classes().size() - 1)));
                  }
                  return factory.asTypeMirror(ClassTy.asNonParametricClassTy(info.owner()));
                }
                return factory.noType();
              }
            });

    @Override
    public TypeMirror getEnclosingType() {
      return enclosing.get();
    }

    final Supplier<ImmutableList<TypeMirror>> typeArguments =
        factory.memoize(
            new Supplier<ImmutableList<TypeMirror>>() {
              @Override
              public ImmutableList<TypeMirror> get() {
                return factory.asTypeMirrors(getLast(type.classes()).targs());
              }
            });

    @Override
    public List<? extends TypeMirror> getTypeArguments() {
      return typeArguments.get();
    }

    @Override
    public TypeKind getKind() {
      return TypeKind.DECLARED;
    }

    @Override
    public <R, P> R accept(TypeVisitor<R, P> v, P p) {
      return v.visitDeclared(this, p);
    }

    public ClassTy type() {
      return type;
    }

    @Override
    protected ImmutableList<AnnoInfo> annos() {
      return getLast(type.classes()).annos();
    }
  }

  /** An {@link ArrayType} implementation backed by a {@link ArrayTy}. */
  static class TurbineArrayType extends TurbineTypeMirror implements ArrayType {

    @Override
    public Type asTurbineType() {
      return type;
    }

    private final ArrayTy type;

    TurbineArrayType(ModelFactory factory, ArrayTy type) {
      super(factory);
      this.type = type;
    }

    @Override
    public TypeMirror getComponentType() {
      return factory.asTypeMirror(type.elementType());
    }

    @Override
    public TypeKind getKind() {
      return TypeKind.ARRAY;
    }

    @Override
    public <R, P> R accept(TypeVisitor<R, P> v, P p) {
      return v.visitArray(this, p);
    }

    @Override
    protected ImmutableList<AnnoInfo> annos() {
      return type.annos();
    }
  }

  /** An {@link ErrorType} implementation backed by a {@link ErrorTy}. */
  static class TurbineErrorType extends TurbineTypeMirror implements ErrorType {

    private final ErrorTy type;

    public TurbineErrorType(ModelFactory factory, ErrorTy type) {
      super(factory);
      this.type = type;
    }

    @Override
    public TypeKind getKind() {
      return TypeKind.ERROR;
    }

    @Override
    public <R, P> R accept(TypeVisitor<R, P> v, P p) {
      return v.visitError(this, p);
    }

    @Override
    protected ImmutableList<AnnoInfo> annos() {
      return ImmutableList.of();
    }

    @Override
    public Type asTurbineType() {
      return type;
    }

    @Override
    public Element asElement() {
      return factory.noElement(type.name());
    }

    @Override
    public TypeMirror getEnclosingType() {
      return factory.noType();
    }

    @Override
    public List<? extends TypeMirror> getTypeArguments() {
      return factory.asTypeMirrors(type.targs());
    }
  }

  /** A 'package type' implementation backed by a {@link PackageSymbol}. */
  static class TurbinePackageType extends TurbineTypeMirror implements NoType {

    @Override
    public Type asTurbineType() {
      throw new UnsupportedOperationException();
    }

    final PackageSymbol symbol;

    TurbinePackageType(ModelFactory factory, PackageSymbol symbol) {
      super(factory);
      this.symbol = symbol;
    }

    @Override
    public TypeKind getKind() {
      return TypeKind.PACKAGE;
    }

    @Override
    public <R, P> R accept(TypeVisitor<R, P> v, P p) {
      return v.visitNoType(this, p);
    }

    @Override
    public String toString() {
      return symbol.toString();
    }

    @Override
    public boolean equals(@Nullable Object other) {
      return other instanceof TurbinePackageType
          && symbol.equals(((TurbinePackageType) other).symbol);
    }

    @Override
    public int hashCode() {
      return symbol.hashCode();
    }

    @Override
    protected ImmutableList<AnnoInfo> annos() {
      return ImmutableList.of();
    }
  }

  /** The absence of a type, {@see javax.lang.model.util.Types#getNoType}. */
  static class TurbineNoType extends TurbineTypeMirror implements NoType {

    @Override
    public Type asTurbineType() {
      return Type.NONE;
    }

    TurbineNoType(ModelFactory factory) {
      super(factory);
    }

    @Override
    public TypeKind getKind() {
      return TypeKind.NONE;
    }

    @Override
    public <R, P> R accept(TypeVisitor<R, P> v, P p) {
      return v.visitNoType(this, p);
    }

    @Override
    public boolean equals(@Nullable Object other) {
      return other instanceof TurbineNoType;
    }

    @Override
    public int hashCode() {
      return getKind().hashCode();
    }

    @Override
    protected ImmutableList<AnnoInfo> annos() {
      return ImmutableList.of();
    }
  }

  /** A void type, {@see javax.lang.model.util.Types#getNoType}. */
  static class TurbineVoidType extends TurbineTypeMirror implements NoType {

    @Override
    public Type asTurbineType() {
      return Type.VOID;
    }

    TurbineVoidType(ModelFactory factory) {
      super(factory);
    }

    @Override
    public TypeKind getKind() {
      return TypeKind.VOID;
    }

    @Override
    public <R, P> R accept(TypeVisitor<R, P> v, P p) {
      return v.visitNoType(this, p);
    }

    @Override
    protected ImmutableList<AnnoInfo> annos() {
      return ImmutableList.of();
    }
  }

  /** A {@link TypeVariable} implementation backed by a {@link TyVar}. */
  static class TurbineTypeVariable extends TurbineTypeMirror implements TypeVariable {

    @Override
    public int hashCode() {
      return type.hashCode();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
      return obj instanceof TurbineTypeVariable && type.equals(((TurbineTypeVariable) obj).type);
    }

    @Override
    public Type asTurbineType() {
      return type;
    }

    private final TyVar type;

    private final Supplier<TyVarInfo> info =
        factory.memoize(
            new Supplier<TyVarInfo>() {
              @Override
              public TyVarInfo get() {
                return factory.getTyVarInfo(type.sym());
              }
            });

    private TyVarInfo info() {
      return info.get();
    }

    TurbineTypeVariable(ModelFactory factory, Type.TyVar type) {
      super(factory);
      this.type = type;
    }

    @Override
    public TypeKind getKind() {
      return TypeKind.TYPEVAR;
    }

    @Override
    public <R, P> R accept(TypeVisitor<R, P> v, P p) {
      return v.visitTypeVariable(this, p);
    }

    @Override
    public Element asElement() {
      return factory.typeParameterElement(type.sym());
    }

    @Override
    public TypeMirror getUpperBound() {
      return factory.asTypeMirror(info().upperBound());
    }

    @Override
    public TypeMirror getLowerBound() {
      return info().lowerBound() != null
          ? factory.asTypeMirror(info().lowerBound())
          : factory.nullType();
    }

    @Override
    protected ImmutableList<AnnoInfo> annos() {
      return type.annos();
    }
  }

  /** A {@link WildcardType} implementation backed by a {@link WildTy}. */
  static class TurbineWildcardType extends TurbineTypeMirror implements WildcardType {

    @Override
    public Type asTurbineType() {
      return type;
    }

    private final WildTy type;

    public TurbineWildcardType(ModelFactory factory, WildTy type) {
      super(factory);
      this.type = type;
    }

    @Override
    public TypeKind getKind() {
      return TypeKind.WILDCARD;
    }

    @Override
    public <R, P> R accept(TypeVisitor<R, P> v, P p) {
      return v.visitWildcard(this, p);
    }

    @Override
    public boolean equals(@Nullable Object obj) {
      return obj instanceof TurbineWildcardType && type.equals(((TurbineWildcardType) obj).type);
    }

    @Override
    public int hashCode() {
      return type.hashCode();
    }

    @Override
    public TypeMirror getExtendsBound() {
      return type.boundKind() == BoundKind.UPPER ? factory.asTypeMirror(type.bound()) : null;
    }

    @Override
    public TypeMirror getSuperBound() {
      return type.boundKind() == BoundKind.LOWER ? factory.asTypeMirror(type.bound()) : null;
    }

    @Override
    protected ImmutableList<AnnoInfo> annos() {
      return type.annotations();
    }
  }

  /** A {@link IntersectionType} implementation backed by a {@link IntersectionTy}. */
  static class TurbineIntersectionType extends TurbineTypeMirror implements IntersectionType {

    @Override
    public Type asTurbineType() {
      return type;
    }

    private final IntersectionTy type;

    TurbineIntersectionType(ModelFactory factory, IntersectionTy type) {
      super(factory);
      this.type = type;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
      return obj instanceof TurbineIntersectionType
          && type.equals(((TurbineIntersectionType) obj).type);
    }

    @Override
    public int hashCode() {
      return type.hashCode();
    }

    @Override
    public TypeKind getKind() {
      return TypeKind.INTERSECTION;
    }

    @Override
    public <R, P> R accept(TypeVisitor<R, P> v, P p) {
      return v.visitIntersection(this, p);
    }

    final Supplier<ImmutableList<TypeMirror>> bounds =
        factory.memoize(
            new Supplier<ImmutableList<TypeMirror>>() {
              @Override
              public ImmutableList<TypeMirror> get() {
                return factory.asTypeMirrors(TurbineTypes.getBounds(factory, type));
              }
            });

    @Override
    public List<? extends TypeMirror> getBounds() {
      return bounds.get();
    }

    @Override
    public String toString() {
      return Joiner.on('&').join(getBounds());
    }

    @Override
    protected ImmutableList<AnnoInfo> annos() {
      return ImmutableList.of();
    }
  }

  /** A {@link NullType} implementation. */
  public static class TurbineNullType extends TurbineTypeMirror implements NullType {

    @Override
    public Type asTurbineType() {
      return Type.PrimTy.create(TurbineConstantTypeKind.NULL, ImmutableList.of());
    }

    public TurbineNullType(ModelFactory factory) {
      super(factory);
    }

    @Override
    protected ImmutableList<AnnoInfo> annos() {
      return ImmutableList.of();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
      return obj instanceof NullType;
    }

    @Override
    public int hashCode() {
      return super.hashCode();
    }

    @Override
    public TypeKind getKind() {
      return TypeKind.NULL;
    }

    @Override
    public <R, P> R accept(TypeVisitor<R, P> v, P p) {
      return v.visitNull(this, p);
    }
  }

  /** An {@link ExecutableType} implementation backed by a {@link MethodTy}. */
  public static class TurbineExecutableType extends TurbineTypeMirror implements ExecutableType {

    @Override
    public MethodTy asTurbineType() {
      return type;
    }

    public final MethodTy type;

    TurbineExecutableType(ModelFactory factory, MethodTy type) {
      super(factory);
      this.type = type;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
      return obj instanceof TurbineExecutableType
          && type.equals(((TurbineExecutableType) obj).type);
    }

    @Override
    public int hashCode() {
      return type.hashCode();
    }

    @Override
    public List<? extends TypeVariable> getTypeVariables() {
      ImmutableList.Builder<TypeVariable> result = ImmutableList.builder();
      for (TyVarSymbol tyVar : type.tyParams()) {
        result.add((TypeVariable) factory.asTypeMirror(TyVar.create(tyVar, ImmutableList.of())));
      }
      return result.build();
    }

    @Override
    public TypeMirror getReturnType() {
      return factory.asTypeMirror(type.returnType());
    }

    @Override
    public List<? extends TypeMirror> getParameterTypes() {
      return factory.asTypeMirrors(type.parameters());
    }

    @Override
    public TypeMirror getReceiverType() {
      return type.receiverType() != null
          ? factory.asTypeMirror(type.receiverType())
          : factory.noType();
    }

    @Override
    public List<? extends TypeMirror> getThrownTypes() {
      return factory.asTypeMirrors(type.thrown());
    }

    @Override
    public TypeKind getKind() {
      return TypeKind.EXECUTABLE;
    }

    @Override
    public <R, P> R accept(TypeVisitor<R, P> v, P p) {
      return v.visitExecutable(this, p);
    }

    @Override
    protected ImmutableList<AnnoInfo> annos() {
      return ImmutableList.of();
    }
  }
}

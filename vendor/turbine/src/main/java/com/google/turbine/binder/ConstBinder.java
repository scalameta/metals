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

package com.google.turbine.binder;

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.turbine.binder.bound.AnnotationMetadata;
import com.google.turbine.binder.bound.EnumConstantValue;
import com.google.turbine.binder.bound.SourceTypeBoundClass;
import com.google.turbine.binder.bound.TurbineClassValue;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass.FieldInfo;
import com.google.turbine.binder.bound.TypeBoundClass.MethodInfo;
import com.google.turbine.binder.bound.TypeBoundClass.ParamInfo;
import com.google.turbine.binder.bound.TypeBoundClass.RecordComponentInfo;
import com.google.turbine.binder.bound.TypeBoundClass.TyVarInfo;
import com.google.turbine.binder.env.CompoundEnv;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.FieldSymbol;
import com.google.turbine.binder.sym.TyVarSymbol;
import com.google.turbine.diag.TurbineLog.TurbineLogWithSource;
import com.google.turbine.model.Const;
import com.google.turbine.model.Const.ArrayInitValue;
import com.google.turbine.model.Const.Kind;
import com.google.turbine.model.Const.Value;
import com.google.turbine.model.TurbineElementType;
import com.google.turbine.model.TurbineFlag;
import com.google.turbine.model.TurbineTyKind;
import com.google.turbine.type.AnnoInfo;
import com.google.turbine.type.Type;
import com.google.turbine.type.Type.ArrayTy;
import com.google.turbine.type.Type.ClassTy;
import com.google.turbine.type.Type.ClassTy.SimpleClassTy;
import com.google.turbine.type.Type.IntersectionTy;
import com.google.turbine.type.Type.TyKind;
import com.google.turbine.type.Type.TyVar;
import com.google.turbine.type.Type.WildLowerBoundedTy;
import com.google.turbine.type.Type.WildTy;
import com.google.turbine.type.Type.WildUnboundedTy;
import com.google.turbine.type.Type.WildUpperBoundedTy;
import java.lang.annotation.RetentionPolicy;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Binding pass to evaluate constant expressions. */
public class ConstBinder {

  private final Env<FieldSymbol, Value> constantEnv;
  private final ClassSymbol origin;
  private final SourceTypeBoundClass base;
  private final CompoundEnv<ClassSymbol, TypeBoundClass> env;
  private final ConstEvaluator constEvaluator;
  private final TurbineLogWithSource log;

  public ConstBinder(
      Env<FieldSymbol, Value> constantEnv,
      ClassSymbol origin,
      CompoundEnv<ClassSymbol, TypeBoundClass> env,
      SourceTypeBoundClass base,
      TurbineLogWithSource log) {
    this.constantEnv = constantEnv;
    this.origin = origin;
    this.base = base;
    this.env = env;
    this.log = log;
    this.constEvaluator =
        new ConstEvaluator(
            origin,
            origin,
            base.memberImports(),
            base.source(),
            base.scope(),
            constantEnv,
            env,
            log);
  }

  public SourceTypeBoundClass bind() {
    ImmutableList<AnnoInfo> annos =
        new ConstEvaluator(
                origin,
                base.owner(),
                base.memberImports(),
                base.source(),
                base.enclosingScope(),
                constantEnv,
                env,
                log)
            .evaluateAnnotations(base.annotations());
    ImmutableList<RecordComponentInfo> components = bindRecordComponents(base.components());
    ImmutableList<TypeBoundClass.FieldInfo> fields = fields(base.fields());
    ImmutableList<MethodInfo> methods = bindMethods(base.methods());
    return new SourceTypeBoundClass(
        bindTypes(base.interfaceTypes()),
        base.permits(),
        base.superClassType() != null ? bindType(base.superClassType()) : null,
        bindTypeParameters(base.typeParameterTypes()),
        base.access(),
        components,
        methods,
        fields,
        base.owner(),
        base.kind(),
        base.children(),
        base.typeParameters(),
        base.enclosingScope(),
        base.scope(),
        base.memberImports(),
        bindAnnotationMetadata(base.kind(), annos),
        annos,
        base.source(),
        base.decl());
  }

  private ImmutableList<MethodInfo> bindMethods(ImmutableList<MethodInfo> methods) {
    ImmutableList.Builder<MethodInfo> result = ImmutableList.builder();
    for (MethodInfo f : methods) {
      result.add(bindMethod(f));
    }
    return result.build();
  }

  private MethodInfo bindMethod(MethodInfo base) {
    Const value = null;
    if (base.decl() != null && base.decl().defaultValue().isPresent()) {
      value =
          constEvaluator.evalAnnotationValue(base.decl().defaultValue().get(), base.returnType());
    }

    return new MethodInfo(
        base.sym(),
        bindTypeParameters(base.tyParams()),
        bindType(base.returnType()),
        bindParameters(base.parameters()),
        bindTypes(base.exceptions()),
        base.access(),
        value,
        base.decl(),
        constEvaluator.evaluateAnnotations(base.annotations()),
        base.receiver() != null ? bindParameter(base.receiver()) : null);
  }

  private ImmutableList<ParamInfo> bindParameters(ImmutableList<ParamInfo> formals) {
    ImmutableList.Builder<ParamInfo> result = ImmutableList.builder();
    for (ParamInfo base : formals) {
      result.add(bindParameter(base));
    }
    return result.build();
  }

  private ParamInfo bindParameter(ParamInfo base) {
    ImmutableList<AnnoInfo> annos = constEvaluator.evaluateAnnotations(base.annotations());
    return new ParamInfo(base.sym(), bindType(base.type()), annos, base.access());
  }

  private ImmutableList<RecordComponentInfo> bindRecordComponents(
      ImmutableList<RecordComponentInfo> components) {
    ImmutableList.Builder<RecordComponentInfo> result = ImmutableList.builder();
    for (RecordComponentInfo base : components) {
      ImmutableList<AnnoInfo> annos = constEvaluator.evaluateAnnotations(base.annotations());
      result.add(new RecordComponentInfo(base.sym(), bindType(base.type()), annos, base.access()));
    }
    return result.build();
  }

  static @Nullable AnnotationMetadata bindAnnotationMetadata(
      TurbineTyKind kind, Iterable<AnnoInfo> annotations) {
    if (kind != TurbineTyKind.ANNOTATION) {
      return null;
    }
    RetentionPolicy retention = null;
    ImmutableSet<TurbineElementType> target = null;
    ClassSymbol repeatable = null;
    for (AnnoInfo annotation : annotations) {
      ClassSymbol sym = annotation.sym();
      if (sym == null) {
        continue;
      }
      switch (sym.binaryName()) {
        case "java/lang/annotation/Retention":
          retention = bindRetention(annotation);
          break;
        case "java/lang/annotation/Target":
          target = bindTarget(annotation);
          break;
        case "java/lang/annotation/Repeatable":
          repeatable = bindRepeatable(annotation);
          break;
        default:
          break;
      }
    }
    return new AnnotationMetadata(retention, target, repeatable);
  }

  private static @Nullable RetentionPolicy bindRetention(AnnoInfo annotation) {
    Const value = annotation.values().get("value");
    if (value == null) {
      return null;
    }
    if (value.kind() != Kind.ENUM_CONSTANT) {
      return null;
    }
    EnumConstantValue enumValue = (EnumConstantValue) value;
    if (!enumValue.sym().owner().binaryName().equals("java/lang/annotation/RetentionPolicy")) {
      return null;
    }
    return RetentionPolicy.valueOf(enumValue.sym().name());
  }

  private static ImmutableSet<TurbineElementType> bindTarget(AnnoInfo annotation) {
    ImmutableSet.Builder<TurbineElementType> result = ImmutableSet.builder();
    // requireNonNull is safe because java.lang.annotation.Target declares `value`.
    Const val = requireNonNull(annotation.values().get("value"));
    switch (val.kind()) {
      case ARRAY:
        for (Const element : ((ArrayInitValue) val).elements()) {
          if (element.kind() == Kind.ENUM_CONSTANT) {
            bindTargetElement(result, (EnumConstantValue) element);
          }
        }
        break;
      case ENUM_CONSTANT:
        bindTargetElement(result, (EnumConstantValue) val);
        break;
      default:
        break;
    }
    return result.build();
  }

  private static @Nullable ClassSymbol bindRepeatable(AnnoInfo annotation) {
    // requireNonNull is safe because java.lang.annotation.Repeatable declares `value`.
    Const value = requireNonNull(annotation.values().get("value"));
    if (value.kind() != Kind.CLASS_LITERAL) {
      return null;
    }
    Type type = ((TurbineClassValue) value).type();
    if (type.tyKind() != TyKind.CLASS_TY) {
      return null;
    }
    return ((ClassTy) type).sym();
  }

  private static void bindTargetElement(
      ImmutableSet.Builder<TurbineElementType> target, EnumConstantValue enumVal) {
    if (enumVal.sym().owner().binaryName().equals("java/lang/annotation/ElementType")) {
      target.add(TurbineElementType.valueOf(enumVal.sym().name()));
    }
  }

  private ImmutableList<TypeBoundClass.FieldInfo> fields(ImmutableList<FieldInfo> fields) {
    ImmutableList.Builder<TypeBoundClass.FieldInfo> result = ImmutableList.builder();
    for (TypeBoundClass.FieldInfo base : fields) {
      Value value = fieldValue(base);
      result.add(
          new TypeBoundClass.FieldInfo(
              base.sym(),
              bindType(base.type()),
              base.access(),
              constEvaluator.evaluateAnnotations(base.annotations()),
              base.decl(),
              value));
    }
    return result.build();
  }

  private @Nullable Value fieldValue(TypeBoundClass.FieldInfo base) {
    if (base.decl() == null || !base.decl().init().isPresent()) {
      return null;
    }
    if ((base.access() & TurbineFlag.ACC_FINAL) == 0) {
      return null;
    }
    Type type = base.type();
    switch (type.tyKind()) {
      case PRIM_TY:
        break;
      case CLASS_TY:
        if (((Type.ClassTy) type).sym().equals(ClassSymbol.STRING)) {
          break;
        }
      // falls through
      default:
        return null;
    }
    Value value = constantEnv.get(base.sym());
    if (value == null) {
      return null;
    }
    if (type.tyKind().equals(TyKind.PRIM_TY)) {
      value =
          constEvaluator.coerce(
              base.decl().init().get().position(), value, ((Type.PrimTy) type).primkind());
    }
    return value;
  }

  private ImmutableList<Type> bindTypes(ImmutableList<Type> types) {
    ImmutableList.Builder<Type> result = ImmutableList.builder();
    for (Type t : types) {
      result.add(bindType(t));
    }
    return result.build();
  }

  private ImmutableMap<TyVarSymbol, TyVarInfo> bindTypeParameters(
      ImmutableMap<TyVarSymbol, TyVarInfo> typarams) {
    ImmutableMap.Builder<TyVarSymbol, TyVarInfo> result = ImmutableMap.builder();
    for (Map.Entry<TyVarSymbol, TyVarInfo> entry : typarams.entrySet()) {
      TyVarInfo info = entry.getValue();
      result.put(
          entry.getKey(),
          new TyVarInfo(
              (IntersectionTy) bindType(info.upperBound()),
              /* lowerBound= */ null,
              constEvaluator.evaluateAnnotations(info.annotations())));
    }
    return result.buildOrThrow();
  }

  private Type bindType(Type type) {
    switch (type.tyKind()) {
      case TY_VAR:
        TyVar tyVar = (TyVar) type;
        return TyVar.create(tyVar.sym(), constEvaluator.evaluateAnnotations(tyVar.annos()));
      case CLASS_TY:
        return bindClassType((ClassTy) type);
      case ARRAY_TY:
        ArrayTy arrayTy = (ArrayTy) type;
        return ArrayTy.create(
            bindType(arrayTy.elementType()), constEvaluator.evaluateAnnotations(arrayTy.annos()));
      case WILD_TY:
        {
          WildTy wildTy = (WildTy) type;
          switch (wildTy.boundKind()) {
            case NONE:
              return WildUnboundedTy.create(
                  constEvaluator.evaluateAnnotations(wildTy.annotations()));
            case UPPER:
              return WildUpperBoundedTy.create(
                  bindType(wildTy.bound()),
                  constEvaluator.evaluateAnnotations(wildTy.annotations()));
            case LOWER:
              return WildLowerBoundedTy.create(
                  bindType(wildTy.bound()),
                  constEvaluator.evaluateAnnotations(wildTy.annotations()));
          }
          throw new AssertionError(wildTy.boundKind());
        }
      case PRIM_TY:
      case VOID_TY:
      case ERROR_TY:
        return type;
      case INTERSECTION_TY:
        return IntersectionTy.create(bindTypes(((IntersectionTy) type).bounds()));
      default:
        throw new AssertionError(type.tyKind());
    }
  }

  private ClassTy bindClassType(ClassTy type) {
    ClassTy classTy = type;
    ImmutableList.Builder<SimpleClassTy> classes = ImmutableList.builder();
    for (SimpleClassTy c : classTy.classes()) {
      classes.add(
          SimpleClassTy.create(
              c.sym(), bindTypes(c.targs()), constEvaluator.evaluateAnnotations(c.annos())));
    }
    return ClassTy.create(classes.build());
  }
}

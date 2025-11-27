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

package com.google.turbine.binder.bytecode;

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.turbine.binder.bound.EnumConstantValue;
import com.google.turbine.binder.bound.ModuleInfo;
import com.google.turbine.binder.bound.TurbineAnnotationValue;
import com.google.turbine.binder.bound.TurbineClassValue;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.FieldSymbol;
import com.google.turbine.binder.sym.TyVarSymbol;
import com.google.turbine.bytecode.ClassFile;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo.ElementValue;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo.ElementValue.ArrayValue;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo.ElementValue.ConstTurbineClassValue;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo.ElementValue.ConstValue;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo.ElementValue.EnumConstValue;
import com.google.turbine.bytecode.ClassFile.TypeAnnotationInfo;
import com.google.turbine.bytecode.ClassReader;
import com.google.turbine.bytecode.sig.Sig;
import com.google.turbine.bytecode.sig.Sig.LowerBoundTySig;
import com.google.turbine.bytecode.sig.Sig.UpperBoundTySig;
import com.google.turbine.bytecode.sig.Sig.WildTySig;
import com.google.turbine.bytecode.sig.SigParser;
import com.google.turbine.model.Const;
import com.google.turbine.model.Const.ArrayInitValue;
import com.google.turbine.model.Const.Value;
import com.google.turbine.type.AnnoInfo;
import com.google.turbine.type.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** Bind {@link Type}s from bytecode. */
public final class BytecodeBinder {

  /** Context that is required to create types from type signatures in bytecode. */
  interface Scope {
    /** Look up a type variable by name on an enclosing method or class. */
    TyVarSymbol apply(String input);

    /**
     * Returns the enclosing class for a nested class, or {@code null}.
     *
     * <p>Locating type annotations on nested classes requires knowledge of their enclosing types.
     */
    @Nullable ClassSymbol outer(ClassSymbol sym);
  }

  public static Type.ClassTy bindClassTy(
      Sig.ClassTySig sig, Scope scope, ImmutableList<TypeAnnotationInfo> annotations) {
    return bindClassTy(
        sig, scope, typeAnnotationsByPath(annotations, scope), TypeAnnotationInfo.TypePath.root());
  }

  private static Type.ClassTy bindClassTy(
      Sig.ClassTySig sig,
      Scope scope,
      ImmutableListMultimap<TypeAnnotationInfo.TypePath, AnnoInfo> annotations,
      TypeAnnotationInfo.TypePath typePath) {
    StringBuilder sb = new StringBuilder();
    if (!sig.pkg().isEmpty()) {
      sb.append(sig.pkg()).append('/');
    }
    boolean first = true;
    Map<ClassSymbol, Sig.SimpleClassTySig> syms = new LinkedHashMap<>();
    for (Sig.SimpleClassTySig s : sig.classes()) {
      if (!first) {
        sb.append('$');
      }
      sb.append(s.simpleName());
      ClassSymbol sym = new ClassSymbol(sb.toString());
      syms.put(sym, s);
      first = false;
    }
    ArrayDeque<ClassSymbol> outers = new ArrayDeque<>();
    for (ClassSymbol curr = Iterables.getLast(syms.keySet());
        curr != null;
        curr = scope.outer(curr)) {
      outers.addFirst(curr);
    }
    List<Type.ClassTy.SimpleClassTy> classes = new ArrayList<>();
    for (ClassSymbol curr : outers) {
      ImmutableList.Builder<Type> tyArgs = ImmutableList.builder();
      Sig.SimpleClassTySig s = syms.get(curr);
      if (s != null) {
        for (int i = 0; i < s.tyArgs().size(); i++) {
          tyArgs.add(bindTy(s.tyArgs().get(i), scope, annotations, typePath.typeArgument(i)));
        }
      }
      classes.add(
          Type.ClassTy.SimpleClassTy.create(curr, tyArgs.build(), annotations.get(typePath)));
      typePath = typePath.nested();
    }
    return Type.ClassTy.create(classes);
  }

  private static Type wildTy(
      WildTySig sig,
      Scope scope,
      ImmutableListMultimap<TypeAnnotationInfo.TypePath, AnnoInfo> annotations,
      TypeAnnotationInfo.TypePath typePath) {
    switch (sig.boundKind()) {
      case NONE:
        return Type.WildUnboundedTy.create(annotations.get(typePath));
      case LOWER:
        return Type.WildLowerBoundedTy.create(
            bindTy(((LowerBoundTySig) sig).bound(), scope, annotations, typePath.wild()),
            annotations.get(typePath));
      case UPPER:
        return Type.WildUpperBoundedTy.create(
            bindTy(((UpperBoundTySig) sig).bound(), scope, annotations, typePath.wild()),
            annotations.get(typePath));
    }
    throw new AssertionError(sig.boundKind());
  }

  public static Type bindTy(
      Sig.TySig sig, Scope scope, ImmutableList<TypeAnnotationInfo> annotations) {
    return bindTy(
        sig, scope, typeAnnotationsByPath(annotations, scope), TypeAnnotationInfo.TypePath.root());
  }

  static Type bindTy(
      Sig.TySig sig,
      Scope scope,
      ImmutableListMultimap<TypeAnnotationInfo.TypePath, AnnoInfo> annotations,
      TypeAnnotationInfo.TypePath typePath) {
    switch (sig.kind()) {
      case BASE_TY_SIG:
        return Type.PrimTy.create(((Sig.BaseTySig) sig).type(), annotations.get(typePath));
      case CLASS_TY_SIG:
        return bindClassTy((Sig.ClassTySig) sig, scope, annotations, typePath);
      case TY_VAR_SIG:
        return Type.TyVar.create(
            scope.apply(((Sig.TyVarSig) sig).name()), annotations.get(typePath));
      case ARRAY_TY_SIG:
        return bindArrayTy((Sig.ArrayTySig) sig, scope, annotations, typePath);
      case WILD_TY_SIG:
        return wildTy((WildTySig) sig, scope, annotations, typePath);
      case VOID_TY_SIG:
        return Type.VOID;
    }
    throw new AssertionError(sig.kind());
  }

  private static Type bindArrayTy(
      Sig.ArrayTySig arrayTySig,
      Scope scope,
      ImmutableListMultimap<TypeAnnotationInfo.TypePath, AnnoInfo> annotations,
      TypeAnnotationInfo.TypePath typePath) {
    return Type.ArrayTy.create(
        bindTy(arrayTySig.elementType(), scope, annotations, typePath.array()),
        annotations.get(typePath));
  }

  private static ImmutableListMultimap<TypeAnnotationInfo.TypePath, AnnoInfo> typeAnnotationsByPath(
      ImmutableList<TypeAnnotationInfo> typeAnnotations, Scope scope) {
    if (typeAnnotations.isEmpty()) {
      return ImmutableListMultimap.of();
    }
    ImmutableListMultimap.Builder<TypeAnnotationInfo.TypePath, AnnoInfo> result =
        ImmutableListMultimap.builder();
    for (TypeAnnotationInfo typeAnnotation : typeAnnotations) {
      result.put(typeAnnotation.path(), bindAnnotationValue(typeAnnotation.anno(), scope).info());
    }
    return result.build();
  }

  /**
   * Similar to {@link Type.ClassTy#asNonParametricClassTy}, but handles any provided type
   * annotations and attaches them to the corresponding {@link Type.ClassTy.SimpleClassTy}.
   */
  public static Type.ClassTy asNonParametricClassTy(
      ClassSymbol sym, ImmutableList<TypeAnnotationInfo> annotations, Scope scope) {
    return asNonParametricClassTy(sym, scope, typeAnnotationsByPath(annotations, scope));
  }

  private static Type.ClassTy asNonParametricClassTy(
      ClassSymbol sym,
      Scope scope,
      ImmutableListMultimap<TypeAnnotationInfo.TypePath, AnnoInfo> annotations) {
    if (annotations.isEmpty()) {
      // fast path if there are no type annotations
      return Type.ClassTy.asNonParametricClassTy(sym);
    }
    ArrayDeque<ClassSymbol> outers = new ArrayDeque<>();
    for (ClassSymbol curr = sym; curr != null; curr = scope.outer(curr)) {
      outers.addFirst(curr);
    }
    List<Type.ClassTy.SimpleClassTy> classes = new ArrayList<>();
    TypeAnnotationInfo.TypePath typePath = TypeAnnotationInfo.TypePath.root();
    for (ClassSymbol curr : outers) {
      classes.add(
          Type.ClassTy.SimpleClassTy.create(curr, ImmutableList.of(), annotations.get(typePath)));
      typePath = typePath.nested();
    }
    return Type.ClassTy.create(classes);
  }

  public static Const bindValue(ElementValue value, Scope scope) {
    switch (value.kind()) {
      case ENUM:
        return bindEnumValue((EnumConstValue) value);
      case CONST:
        return ((ConstValue) value).value();
      case ARRAY:
        return bindArrayValue((ArrayValue) value, scope);
      case CLASS:
        return new TurbineClassValue(
            bindTy(
                new SigParser(((ConstTurbineClassValue) value).className()).parseType(),
                new Scope() {
                  @Override
                  public TyVarSymbol apply(String x) {
                    throw new IllegalStateException(x);
                  }

                  @Override
                  public @Nullable ClassSymbol outer(ClassSymbol sym) {
                    return scope.outer(sym);
                  }
                },
                /* annotations= */ ImmutableList.of()));
      case ANNOTATION:
        return bindAnnotationValue(
            ((ElementValue.ConstTurbineAnnotationValue) value).annotation(), scope);
    }
    throw new AssertionError(value.kind());
  }

  static TurbineAnnotationValue bindAnnotationValue(AnnotationInfo value, Scope scope) {
    ClassSymbol sym = asClassSymbol(value.typeName());
    ImmutableMap.Builder<String, Const> values = ImmutableMap.builder();
    for (Map.Entry<String, ElementValue> e : value.elementValuePairs().entrySet()) {
      values.put(e.getKey(), bindValue(e.getValue(), scope));
    }
    return new TurbineAnnotationValue(new AnnoInfo(null, sym, null, values.buildOrThrow()));
  }

  static ImmutableList<AnnoInfo> bindAnnotations(List<AnnotationInfo> input, Scope scope) {
    ImmutableList.Builder<AnnoInfo> result = ImmutableList.builder();
    for (AnnotationInfo annotation : input) {
      TurbineAnnotationValue anno = bindAnnotationValue(annotation, scope);
      if (!shouldSkip(anno)) {
        result.add(anno.info());
      }
    }
    return result.build();
  }

  private static boolean shouldSkip(TurbineAnnotationValue anno) {
    // ct.sym contains fake annotations without corresponding class files.
    return anno.sym().equals(ClassSymbol.PROFILE_ANNOTATION)
        || anno.sym().equals(ClassSymbol.PROPRIETARY_ANNOTATION);
  }

  private static ClassSymbol asClassSymbol(String s) {
    return new ClassSymbol(s.substring(1, s.length() - 1));
  }

  private static Const bindArrayValue(ArrayValue value, Scope scope) {
    ImmutableList.Builder<Const> elements = ImmutableList.builder();
    for (ElementValue element : value.elements()) {
      elements.add(bindValue(element, scope));
    }
    return new ArrayInitValue(elements.build());
  }

  public static Const.Value bindConstValue(Type type, Const.Value value) {
    if (type.tyKind() != Type.TyKind.PRIM_TY) {
      return value;
    }
    // Deficient numeric types and booleans are all stored as ints in the class file,
    // coerce them to the target type.
    switch (((Type.PrimTy) type).primkind()) {
      case CHAR:
        return new Const.CharValue((char) asInt(value));
      case SHORT:
        return new Const.ShortValue((short) asInt(value));
      case BOOLEAN:
        // boolean constants are encoded as integers, see also JDK-8171132
        return new Const.BooleanValue(asInt(value) != 0);
      case BYTE:
        return new Const.ByteValue((byte) asInt(value));
      default:
        return value;
    }
  }

  private static int asInt(Value value) {
    return ((Const.IntValue) value).value();
  }

  private static Const bindEnumValue(EnumConstValue value) {
    return new EnumConstantValue(
        new FieldSymbol(asClassSymbol(value.typeName()), value.constName()));
  }

  /**
   * Returns a {@link ModuleInfo} given a module-info class file. Currently only the module's name,
   * version, and flags are populated, since the directives are not needed by turbine at compile
   * time.
   */
  public static ModuleInfo bindModuleInfo(String path, Supplier<byte[]> bytes) {
    ClassFile classFile = ClassReader.read(path, bytes.get());
    ClassFile.ModuleInfo module = classFile.module();
    requireNonNull(module, path);
    return new ModuleInfo(
        module.name(),
        module.version(),
        module.flags(),
        /* annos= */ ImmutableList.of(),
        /* requires= */ ImmutableList.of(),
        /* exports= */ ImmutableList.of(),
        /* opens= */ ImmutableList.of(),
        /* uses= */ ImmutableList.of(),
        /* provides= */ ImmutableList.of());
  }

  private BytecodeBinder() {}
}

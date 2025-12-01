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

package com.google.turbine.lower;

import com.google.common.collect.ImmutableList;
import com.google.turbine.binder.bound.SourceTypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass.TyVarInfo;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.TyVarSymbol;
import com.google.turbine.bytecode.sig.Sig;
import com.google.turbine.bytecode.sig.Sig.ClassSig;
import com.google.turbine.bytecode.sig.Sig.ClassTySig;
import com.google.turbine.bytecode.sig.Sig.LowerBoundTySig;
import com.google.turbine.bytecode.sig.Sig.MethodSig;
import com.google.turbine.bytecode.sig.Sig.SimpleClassTySig;
import com.google.turbine.bytecode.sig.Sig.TySig;
import com.google.turbine.bytecode.sig.Sig.UpperBoundTySig;
import com.google.turbine.bytecode.sig.SigWriter;
import com.google.turbine.model.TurbineFlag;
import com.google.turbine.model.TurbineTyKind;
import com.google.turbine.type.Type;
import com.google.turbine.type.Type.ArrayTy;
import com.google.turbine.type.Type.ClassTy;
import com.google.turbine.type.Type.ClassTy.SimpleClassTy;
import com.google.turbine.type.Type.PrimTy;
import com.google.turbine.type.Type.TyKind;
import com.google.turbine.type.Type.TyVar;
import com.google.turbine.type.Type.WildTy;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Translator from {@link Type}s to {@link Sig}natures. */
public class LowerSignature {

  final Set<ClassSymbol> classes = new LinkedHashSet<>();

  /** Translates types to signatures. */
  public Sig.TySig signature(Type ty) {
    switch (ty.tyKind()) {
      case CLASS_TY:
        return classTySig((Type.ClassTy) ty);
      case TY_VAR:
        return tyVarSig((TyVar) ty);
      case ARRAY_TY:
        return arrayTySig((ArrayTy) ty);
      case PRIM_TY:
        return refBaseTy((PrimTy) ty);
      case VOID_TY:
        return Sig.VOID;
      case WILD_TY:
        return wildTy((WildTy) ty);
      // TURBINE-DIFF START
      case ERROR_TY:
        return classTySig(ClassTy.OBJECT);
      // TURBINE-DIFF END
      default:
        throw new AssertionError(ty.tyKind());
    }
  }

  private Sig.BaseTySig refBaseTy(PrimTy t) {
    return new Sig.BaseTySig(t.primkind());
  }

  private Sig.ArrayTySig arrayTySig(ArrayTy t) {
    return new Sig.ArrayTySig(signature(t.elementType()));
  }

  private Sig.TyVarSig tyVarSig(TyVar t) {
    return new Sig.TyVarSig(t.sym().name());
  }

  private ClassTySig classTySig(ClassTy t) {
    classes.add(t.sym());
    ImmutableList.Builder<SimpleClassTySig> classes = ImmutableList.builder();
    Iterator<SimpleClassTy> it = t.classes().iterator();
    SimpleClassTy curr = it.next();
    while (curr.targs().isEmpty() && it.hasNext()) {
      curr = it.next();
    }
    String pkg = curr.sym().packageName();
    classes.add(new Sig.SimpleClassTySig(curr.sym().simpleName(), tyArgSigs(curr)));
    while (it.hasNext()) {
      SimpleClassTy outer = curr;
      curr = it.next();
      String shortname = curr.sym().binaryName().substring(outer.sym().binaryName().length() + 1);
      classes.add(new Sig.SimpleClassTySig(shortname, tyArgSigs(curr)));
    }
    return new ClassTySig(pkg, classes.build());
  }

  private ImmutableList<TySig> tyArgSigs(SimpleClassTy part) {
    ImmutableList.Builder<TySig> tyargs = ImmutableList.builder();
    for (Type targ : part.targs()) {
      tyargs.add(signature(targ));
    }
    return tyargs.build();
  }

  private TySig wildTy(WildTy ty) {
    switch (ty.boundKind()) {
      case NONE:
        return new Sig.WildTyArgSig();
      case UPPER:
        return new UpperBoundTySig(signature(((Type.WildUpperBoundedTy) ty).bound()));
      case LOWER:
        return new LowerBoundTySig(signature(((Type.WildLowerBoundedTy) ty).bound()));
    }
    throw new AssertionError(ty.boundKind());
  }

  /**
   * Produces a method signature attribute for a generic method, or {@code null} if the signature is
   * unnecessary.
   */
  public @Nullable String methodSignature(
      Env<ClassSymbol, TypeBoundClass> env, TypeBoundClass.MethodInfo method, ClassSymbol sym) {
    if (!needsMethodSig(sym, env, method)) {
      return null;
    }
    ImmutableList<Sig.TyParamSig> typarams = tyParamSig(method.tyParams(), env);
    ImmutableList.Builder<Sig.TySig> fparams = ImmutableList.builder();
    for (TypeBoundClass.ParamInfo t : method.parameters()) {
      if (t.synthetic()) {
        continue;
      }
      fparams.add(signature(t.type()));
    }
    Sig.TySig ret = signature(method.returnType());
    ImmutableList.Builder<Sig.TySig> excn = ImmutableList.builder();
    boolean needsExnSig = false;
    for (Type e : method.exceptions()) {
      if (needsSig(e)) {
        needsExnSig = true;
        break;
      }
    }
    if (needsExnSig) {
      for (Type e : method.exceptions()) {
        excn.add(signature(e));
      }
    }
    MethodSig sig = new MethodSig(typarams, fparams.build(), ret, excn.build());
    return SigWriter.method(sig);
  }

  private boolean needsMethodSig(
      ClassSymbol sym, Env<ClassSymbol, TypeBoundClass> env, TypeBoundClass.MethodInfo m) {
    if ((env.getNonNull(sym).access() & TurbineFlag.ACC_ENUM) == TurbineFlag.ACC_ENUM
        && m.name().equals("<init>")) {
      // JDK-8024694: javac always expects signature attribute for enum constructors
      return true;
    }
    if (!m.tyParams().isEmpty()) {
      return true;
    }
    if (m.returnType() != null && needsSig(m.returnType())) {
      return true;
    }
    for (TypeBoundClass.ParamInfo t : m.parameters()) {
      if (t.synthetic()) {
        continue;
      }
      if (needsSig(t.type())) {
        return true;
      }
    }
    for (Type t : m.exceptions()) {
      if (needsSig(t)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Produces a class signature attribute for a generic class, or {@code null} if the signature is
   * unnecessary.
   */
  public @Nullable String classSignature(
      SourceTypeBoundClass info, Env<ClassSymbol, TypeBoundClass> env) {
    if (!classNeedsSig(info)) {
      return null;
    }
    // TURBINE-DIFF START
    if (!(info.superClassType() instanceof Type.ErrorTy)) {
      return null;
    }
    ImmutableList<Sig.TyParamSig> typarams = tyParamSig(info.typeParameterTypes(), env);
    Type superClassType = info.superClassType();
    if (superClassType == null || !(superClassType instanceof ClassTy)) {
      return null;
    }
    // TURBINE-DIFF END
    ClassTySig xtnd = classTySig((ClassTy) superClassType);
    ImmutableList.Builder<ClassTySig> impl = ImmutableList.builder();
    for (Type i : info.interfaceTypes()) {
      // TURBINE-DIFF START
      if (i.tyKind() == TyKind.CLASS_TY) {
        impl.add(classTySig((ClassTy) i));
      }
      // TURBINE-DIFF END
    }
    ClassSig sig = new ClassSig(typarams, xtnd, impl.build());
    return SigWriter.classSig(sig);
  }

  /**
   * A field signature, or {@code null} if the descriptor provides all necessary type information.
   */
  public @Nullable String fieldSignature(Type type) {
    return needsSig(type) ? SigWriter.type(signature(type)) : null;
  }

  private boolean classNeedsSig(SourceTypeBoundClass ci) {
    if (!ci.typeParameters().isEmpty()) {
      return true;
    }
    if (ci.superClassType() != null && needsSig(ci.superClassType())) {
      return true;
    }
    for (Type i : ci.interfaceTypes()) {
      if (needsSig(i)) {
        return true;
      }
    }
    return false;
  }

  private boolean needsSig(Type ty) {
    switch (ty.tyKind()) {
      case PRIM_TY:
      case VOID_TY:
        return false;
      case CLASS_TY:
        {
          for (SimpleClassTy s : ((ClassTy) ty).classes()) {
            if (!s.targs().isEmpty()) {
              return true;
            }
          }
          return false;
        }
      case ARRAY_TY:
        return needsSig(((ArrayTy) ty).elementType());
      case TY_VAR:
        return true;
      // TURBINE-DIFF START
      case ERROR_TY:
        return needsSig(ClassTy.OBJECT);
      // TURBINE-DIFF END
      default:
        throw new AssertionError(ty.tyKind());
    }
  }

  private ImmutableList<Sig.TyParamSig> tyParamSig(
      Map<TyVarSymbol, TyVarInfo> px, Env<ClassSymbol, TypeBoundClass> env) {
    ImmutableList.Builder<Sig.TyParamSig> result = ImmutableList.builder();
    for (Map.Entry<TyVarSymbol, TyVarInfo> entry : px.entrySet()) {
      result.add(tyParamSig(entry.getKey(), entry.getValue(), env));
    }
    return result.build();
  }

  private Sig.TyParamSig tyParamSig(
      TyVarSymbol sym, TyVarInfo info, Env<ClassSymbol, TypeBoundClass> env) {

    String identifier = sym.name();
    Sig.TySig cbound = null;
    ImmutableList.Builder<Sig.TySig> ibounds = ImmutableList.builder();
    if (info.upperBound().bounds().isEmpty()) {
      cbound =
          new ClassTySig(
              "java/lang", ImmutableList.of(new SimpleClassTySig("Object", ImmutableList.of())));
    } else {
      boolean first = true;
      for (Type bound : info.upperBound().bounds()) {
        TySig sig = signature(bound);
        if (first) {
          if (!isInterface(bound, env)) {
            cbound = sig;
            continue;
          }
        }
        ibounds.add(sig);
        first = false;
      }
    }
    return new Sig.TyParamSig(identifier, cbound, ibounds.build());
  }

  private boolean isInterface(Type type, Env<ClassSymbol, TypeBoundClass> env) {
    return type.tyKind() == TyKind.CLASS_TY
        && env.getNonNull(((ClassTy) type).sym()).kind() == TurbineTyKind.INTERFACE;
  }

  public String descriptor(ClassSymbol sym) {
    classes.add(sym);
    return sym.binaryName();
  }

  String objectType(ClassSymbol sym) {
    // TURBINE-DIFF START
    if (sym == null) {
      return "L" + ClassSymbol.OBJECT.binaryName() + ";";
    }
    // TURBINE-DIFF END
    return "L" + descriptor(sym) + ";";
  }
}

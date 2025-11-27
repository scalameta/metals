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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.bound.SourceTypeBoundClass;
import com.google.turbine.binder.bound.TurbineAnnotationValue;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass.MethodInfo;
import com.google.turbine.binder.bound.TypeBoundClass.TyVarInfo;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.FieldSymbol;
import com.google.turbine.binder.sym.MethodSymbol;
import com.google.turbine.binder.sym.ParamSymbol;
import com.google.turbine.binder.sym.Symbol;
import com.google.turbine.binder.sym.TyVarSymbol;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.diag.TurbineLog;
import com.google.turbine.model.Const;
import com.google.turbine.processing.TurbineElement.TurbineNoTypeElement;
import com.google.turbine.tree.Tree;
import com.google.turbine.type.AnnoInfo;
import java.util.Iterator;
import javax.annotation.processing.Messager;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;
import org.jspecify.annotations.Nullable;

/** Turbine's implementation of {@link Messager}. */
public class TurbineMessager implements Messager {
  private final ModelFactory factory;
  private final TurbineLog log;

  public TurbineMessager(ModelFactory factory, TurbineLog log) {
    this.factory = factory;
    this.log = log;
  }

  @Override
  public void printMessage(Diagnostic.Kind kind, CharSequence msg) {
    // TODO(cushon): null-check `msg` after fixing affected processors
    log.diagnostic(kind, String.valueOf(msg));
  }

  @Override
  public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e) {
    if (e == null || e instanceof TurbineNoTypeElement) {
      printMessage(kind, msg);
      return;
    }
    Symbol sym = ((TurbineElement) e).sym();
    SourceFile source = getSource(sym);
    int position = getPosition(sym);
    log.withSource(source).diagnostic(kind, position, TurbineError.ErrorKind.PROC, msg);
  }

  @Override
  public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e, AnnotationMirror a) {
    if (a == null || e == null || e instanceof TurbineNoTypeElement) {
      printMessage(kind, msg, e);
      return;
    }
    SourceFile source = getSource(((TurbineElement) e).sym());
    int position = ((TurbineAnnotationMirror) a).anno().tree().position();
    log.withSource(source).diagnostic(kind, position, TurbineError.ErrorKind.PROC, msg);
  }

  @Override
  public void printMessage(
      Diagnostic.Kind kind, CharSequence msg, Element e, AnnotationMirror a, AnnotationValue v) {
    if (a == null || e == null || e instanceof TurbineNoTypeElement || v == null) {
      printMessage(kind, msg, e, a);
      return;
    }
    SourceFile source = getSource(((TurbineElement) e).sym());
    AnnoInfo anno = ((TurbineAnnotationMirror) a).anno();
    int position = locateInAnnotation(((TurbineAnnotationValueMirror) v).value(), anno);
    if (position == -1) {
      position = anno.tree().position();
    }
    log.withSource(source).diagnostic(kind, position, TurbineError.ErrorKind.PROC, msg);
  }

  /**
   * Returns the {@link SourceFile} that contains the declaration of the given {@link Symbol}, or
   * {@code null} if the symbol was not compiled from source.
   */
  private @Nullable SourceFile getSource(Symbol sym) {
    ClassSymbol encl = ModelFactory.enclosingClass(sym);
    TypeBoundClass info = factory.getSymbol(encl);
    if (!(info instanceof SourceTypeBoundClass)) {
      return null;
    }
    return ((SourceTypeBoundClass) info).source();
  }

  /**
   * Returns the position of the given {@link Symbol}'s declaration, or {@code null} if it was not
   * compiled from source.
   */
  private int getPosition(Symbol sym) {
    switch (sym.symKind()) {
      case CLASS:
        return classPosition((ClassSymbol) sym);
      case TY_PARAM:
        return tyParamPosition((TyVarSymbol) sym);
      case METHOD:
        return methodPosition((MethodSymbol) sym);
      case FIELD:
        return fieldPosition((FieldSymbol) sym);
      case PARAMETER:
        return paramPosition((ParamSymbol) sym);
      case RECORD_COMPONENT:
        // javac doesn't seem to provide diagnostic positions for record components, so we don't
        // either
        return -1;
      case MODULE:
      case PACKAGE:
        break;
    }
    throw new AssertionError(sym.symKind());
  }

  private int fieldPosition(FieldSymbol sym) {
    Tree.VarDecl decl = factory.getFieldInfo(sym).decl();
    return decl != null ? decl.position() : -1;
  }

  private int paramPosition(ParamSymbol sym) {
    MethodInfo minfo = factory.getMethodInfo(sym.owner());
    if (minfo.decl() == null) {
      return -1;
    }
    int idx = minfo.parameters().indexOf(factory.getParamInfo(sym));
    return minfo.decl().params().get(idx).position();
  }

  private int methodPosition(MethodSymbol sym) {
    MethodInfo methodInfo = factory.getMethodInfo(sym);
    Tree.MethDecl decl = methodInfo.decl();
    if (decl != null) {
      return decl.position();
    }
    // use the enclosing class position for synthetic methods
    int position = classPosition(sym.owner());
    if (position == -1) {
      return -1;
    }
    // TODO(b/139079081): change diagnostic position of declarations instead of the -= 6 fixup
    position -= 6; // adjust to start of `class ` for parity with javac
    return position;
  }

  private int classPosition(ClassSymbol owner) {
    TypeBoundClass symbol = factory.getSymbol(owner);
    if (!(symbol instanceof SourceTypeBoundClass)) {
      return -1;
    }
    return ((SourceTypeBoundClass) symbol).decl().position();
  }

  private int tyParamPosition(TyVarSymbol sym) {
    TyVarSymbol tyVarSymbol = sym;
    Symbol owner = tyVarSymbol.owner();
    ImmutableMap<TyVarSymbol, TyVarInfo> tyVars;
    ImmutableList<Tree.TyParam> trees;
    switch (owner.symKind()) {
      case CLASS:
        TypeBoundClass cinfo = factory.getSymbol((ClassSymbol) owner);
        if (!(cinfo instanceof SourceTypeBoundClass)) {
          return -1;
        }
        tyVars = cinfo.typeParameterTypes();
        trees = ((SourceTypeBoundClass) cinfo).decl().typarams();
        break;
      case METHOD:
        MethodInfo minfo = factory.getMethodInfo((MethodSymbol) owner);
        if (minfo.decl() == null) {
          return -1;
        }
        tyVars = minfo.tyParams();
        trees = minfo.decl().typarams();
        break;
      default:
        throw new AssertionError(owner.symKind());
    }
    return trees.get(tyVars.keySet().asList().indexOf(tyVarSymbol)).position();
  }

  /** Returns the position of the given annotation value {@code v} in the given annotation. */
  private static int locateInAnnotation(Const v, AnnoInfo anno) {
    return locate(v, anno.values().values().asList(), anno.tree().args());
  }

  /**
   * Returns the position of the given annotation value {@code toFind} within the given constant
   * {@code v} (which may be a compound value, i.e. a nested annotation or array value), and given
   * the corresponding expression tree.
   */
  private static int locate(Const toFind, Const v, Tree.Expression t) {
    // the element name can be omitted for `value`, e.g. in `@A({1, 2, 3})`
    t = t.kind().equals(Tree.Kind.ASSIGN) ? ((Tree.Assign) t).expr() : t;
    if (toFind.equals(v)) {
      return t.position();
    }
    switch (v.kind()) {
      case ARRAY:
        ImmutableList<Tree.Expression> elements =
            t.kind().equals(Tree.Kind.ARRAY_INIT)
                ? ((Tree.ArrayInit) t).exprs()
                : ImmutableList.of(t);
        return locate(toFind, ((Const.ArrayInitValue) v).elements(), elements);
      case ANNOTATION:
        return locateInAnnotation(toFind, ((TurbineAnnotationValue) v).info());
      default:
        return -1;
    }
  }

  /**
   * Returns the position of the given annotation value {@code toFind}, given a list of annotation
   * values corresponding to the element values of a (possibly nested) annotation or an array value,
   * and the corresponding expression trees.
   */
  private static int locate(
      Const toFind, ImmutableList<Const> vx, ImmutableList<Tree.Expression> tx) {
    Iterator<Const> vi = vx.iterator();
    Iterator<Tree.Expression> ti = tx.iterator();
    while (vi.hasNext()) {
      int result = locate(toFind, vi.next(), ti.next());
      if (result != -1) {
        return result;
      }
    }
    return -1;
  }
}

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

package com.google.turbine.bytecode.sig;

import com.google.turbine.bytecode.sig.Sig.ArrayTySig;
import com.google.turbine.bytecode.sig.Sig.BaseTySig;
import com.google.turbine.bytecode.sig.Sig.ClassSig;
import com.google.turbine.bytecode.sig.Sig.ClassTySig;
import com.google.turbine.bytecode.sig.Sig.LowerBoundTySig;
import com.google.turbine.bytecode.sig.Sig.MethodSig;
import com.google.turbine.bytecode.sig.Sig.SimpleClassTySig;
import com.google.turbine.bytecode.sig.Sig.TyParamSig;
import com.google.turbine.bytecode.sig.Sig.TySig;
import com.google.turbine.bytecode.sig.Sig.TyVarSig;
import com.google.turbine.bytecode.sig.Sig.UpperBoundTySig;
import com.google.turbine.bytecode.sig.Sig.WildTySig;

/** Writes {@link Sig}s to their serialized string equivalents. */
public class SigWriter {

  /** Writes a {@link ClassSig} to a string. */
  public static String classSig(ClassSig classSig) {
    SigWriter writer = new SigWriter();
    writer.printClassSig(classSig);
    return writer.toString();
  }

  /** Writes a {@link TySig} to a string. */
  public static String type(TySig tySig) {
    SigWriter writer = new SigWriter();
    writer.writeTySig(tySig);
    return writer.toString();
  }

  /** Writes a {@link MethodSig} to a string. */
  public static String method(MethodSig methodSig) {
    SigWriter writer = new SigWriter();
    writer.writeMethodSig(methodSig);
    return writer.toString();
  }

  private final StringBuilder sb = new StringBuilder();

  @Override
  public String toString() {
    return sb.toString();
  }

  private void writeFormalTyParamSig(TyParamSig tyParamSig) {
    sb.append(tyParamSig.name());
    sb.append(':');
    if (tyParamSig.classBound() != null) {
      writeTySig(tyParamSig.classBound());
    }
    for (Sig.TySig f : tyParamSig.interfaceBounds()) {
      sb.append(':');
      writeTySig(f);
    }
  }

  private void writeClassTySig(ClassTySig classTySig) {
    sb.append('L');
    if (!classTySig.pkg().isEmpty()) {
      sb.append(classTySig.pkg()).append('/');
    }
    boolean first = true;
    for (SimpleClassTySig c : classTySig.classes()) {
      if (first) {
        first = false;
      } else {
        sb.append('.');
      }
      writeSimpleClassTySig(c);
    }
    sb.append(';');
  }

  public void writeSimpleClassTySig(SimpleClassTySig simpleClassTySig) {
    sb.append(simpleClassTySig.simpleName());
    if (!simpleClassTySig.tyArgs().isEmpty()) {
      sb.append('<');
      for (Sig.TySig x : simpleClassTySig.tyArgs()) {
        writeTySig(x);
      }
      sb.append('>');
    }
  }

  private void wildTyArgSig(WildTySig sig) {
    switch (sig.boundKind()) {
      case NONE:
        sb.append('*');
        break;
      case LOWER:
        sb.append('-');
        writeTySig(((LowerBoundTySig) sig).bound());
        break;
      case UPPER:
        sb.append('+');
        writeTySig(((UpperBoundTySig) sig).bound());
        break;
    }
  }

  public void writeArrayTySig(ArrayTySig arrayTySig) {
    sb.append('[');
    writeTySig(arrayTySig.elementType());
  }

  public void writeTyVarSig(TyVarSig tyVarSig) {
    sb.append('T').append(tyVarSig.name()).append(';');
  }

  public void writePrimitiveTySig(BaseTySig ty) {
    switch (ty.type()) {
      case BYTE:
        sb.append('B');
        break;
      case CHAR:
        sb.append('C');
        break;
      case DOUBLE:
        sb.append('D');
        break;
      case FLOAT:
        sb.append('F');
        break;
      case INT:
        sb.append('I');
        break;
      case LONG:
        sb.append('J');
        break;
      case SHORT:
        sb.append('S');
        break;
      case BOOLEAN:
        sb.append('Z');
        break;
      default:
        throw new AssertionError(ty.type());
    }
  }

  private void writeMethodSig(MethodSig methodSig) {
    if (!methodSig.tyParams().isEmpty()) {
      sb.append('<');
      for (TyParamSig x : methodSig.tyParams()) {
        writeFormalTyParamSig(x);
      }
      sb.append('>');
    }
    sb.append('(');
    for (TySig p : methodSig.params()) {
      writeTySig(p);
    }
    sb.append(')');
    writeTySig(methodSig.returnType());
    for (TySig e : methodSig.exceptions()) {
      sb.append('^');
      writeTySig(e);
    }
  }

  private void writeTySig(TySig p) {
    switch (p.kind()) {
      case VOID_TY_SIG:
        sb.append('V');
        break;
      case BASE_TY_SIG:
        writePrimitiveTySig((BaseTySig) p);
        break;
      case CLASS_TY_SIG:
        writeClassTySig((ClassTySig) p);
        break;
      case ARRAY_TY_SIG:
        writeArrayTySig((ArrayTySig) p);
        break;
      case TY_VAR_SIG:
        writeTyVarSig((TyVarSig) p);
        break;
      case WILD_TY_SIG:
        wildTyArgSig((WildTySig) p);
        break;
    }
  }

  private void printClassSig(ClassSig classSig) {
    if (!classSig.tyParams().isEmpty()) {
      sb.append('<');
      for (TyParamSig x : classSig.tyParams()) {
        writeFormalTyParamSig(x);
      }
      sb.append('>');
    }
    writeClassTySig(classSig.superClass());
    for (ClassTySig i : classSig.interfaces()) {
      writeClassTySig(i);
    }
  }
}

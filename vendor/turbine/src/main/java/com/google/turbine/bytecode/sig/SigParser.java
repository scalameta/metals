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

import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
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
import com.google.turbine.bytecode.sig.Sig.WildTyArgSig;
import com.google.turbine.model.TurbineConstantTypeKind;

/** Parser for JVMS 4.3.4 signatures. */
public class SigParser {

  /** The string to parse. */
  private final String sig;

  /** The current position. */
  private int idx = 0;

  /** Returns the next character to process, without advancing. */
  char peek() {
    return sig.charAt(idx);
  }

  /** Returns the next character and advances. */
  @CanIgnoreReturnValue
  char eat() {
    return sig.charAt(idx++);
  }

  /** Returns true if there is more input to process. */
  boolean hasNext() {
    return idx < sig.length();
  }

  public SigParser(String sig) {
    this.sig = sig;
  }

  public TySig parseFieldSig() {
    switch (peek()) {
      case '[':
        return parseArraySig();
      case 'T':
        return parseTyVar();
      case 'L':
        return parseClassTySig();
      case '+':
        eat();
        return new UpperBoundTySig(parseFieldSig());
      case '-':
        eat();
        return new LowerBoundTySig(parseFieldSig());
      case '*':
        eat();
        return new WildTyArgSig();
      default:
        throw new AssertionError(peek());
    }
  }

  /** Parses a MethodTypeSignature into a {@link MethodSig}. */
  public MethodSig parseMethodSig() {
    ImmutableList<TyParamSig> tyParams = parseTyParams();
    if (peek() != '(') {
      throw new AssertionError();
    }
    eat();
    ImmutableList.Builder<TySig> params = ImmutableList.builder();
    while (peek() != ')') {
      params.add(parseType());
    }
    eat();
    ImmutableList.Builder<TySig> exceptions = ImmutableList.builder();
    TySig result = parseType();
    while (hasNext() && eat() == '^') {
      exceptions.add(parseFieldSig());
    }
    return new MethodSig(tyParams, params.build(), result, exceptions.build());
  }

  /** Parses a ClassTypeSignature into a {@link ClassSig}. */
  public ClassSig parseClassSig() {
    ClassTySig superClass;
    ImmutableList<TyParamSig> tyParams = parseTyParams();
    superClass = parseClassTySig();
    ImmutableList.Builder<ClassTySig> interfaces = ImmutableList.builder();
    while (hasNext()) {
      interfaces.add(parseClassTySig());
    }
    return new ClassSig(tyParams, superClass, interfaces.build());
  }

  private ImmutableList<TyParamSig> parseTyParams() {
    ImmutableList.Builder<TyParamSig> tyParams = ImmutableList.builder();
    if (peek() == '<') {
      eat();
      do {
        StringBuilder identifier = new StringBuilder();
        char ch;
        while ((ch = eat()) != ':') {
          identifier.append(ch);
        }
        TySig classBound = null;
        switch (peek()) {
          case 'L':
          case '[':
          case 'T':
            classBound = parseFieldSig();
            break;
          default:
            break;
        }
        ImmutableList.Builder<TySig> interfaceBounds = ImmutableList.builder();
        while (peek() == ':') {
          eat();
          interfaceBounds.add(parseFieldSig());
        }
        tyParams.add(new TyParamSig(identifier.toString(), classBound, interfaceBounds.build()));
      } while (peek() != '>');
      eat();
    }
    return tyParams.build();
  }

  /** Parses a type signature. */
  public TySig parseType() {
    switch (peek()) {
      case 'Z':
        eat();
        return new BaseTySig(TurbineConstantTypeKind.BOOLEAN);
      case 'C':
        eat();
        return new BaseTySig(TurbineConstantTypeKind.CHAR);
      case 'B':
        eat();
        return new BaseTySig(TurbineConstantTypeKind.BYTE);
      case 'S':
        eat();
        return new BaseTySig(TurbineConstantTypeKind.SHORT);
      case 'I':
        eat();
        return new BaseTySig(TurbineConstantTypeKind.INT);
      case 'F':
        eat();
        return new BaseTySig(TurbineConstantTypeKind.FLOAT);
      case 'J':
        eat();
        return new BaseTySig(TurbineConstantTypeKind.LONG);
      case 'D':
        eat();
        return new BaseTySig(TurbineConstantTypeKind.DOUBLE);
      case 'V':
        eat();
        return Sig.VOID;
      default:
        return parseFieldSig();
    }
  }

  private ArrayTySig parseArraySig() {
    eat();
    TySig elementType = parseType();
    return new ArrayTySig(elementType);
  }

  private TyVarSig parseTyVar() {
    eat();
    StringBuilder name = new StringBuilder();
    char ch;
    while ((ch = eat()) != ';') {
      name.append(ch);
    }
    return new TyVarSig(name.toString());
  }

  private ClassTySig parseClassTySig() {
    eat();
    ImmutableList.Builder<SimpleClassTySig> simples = ImmutableList.builder();
    StringBuilder name = new StringBuilder();
    StringBuilder pkg = new StringBuilder();
    ImmutableList.Builder<TySig> tyArgs = ImmutableList.builder();
    OUTER:
    while (true) {
      switch (peek()) {
        case '/':
          eat();
          if (pkg.length() > 0) {
            pkg.append('/');
          }
          pkg.append(name);
          name = new StringBuilder();
          break;
        case '<':
          {
            eat();
            do {
              tyArgs.add(parseFieldSig());
            } while (peek() != '>');
            eat();
            break;
          }
        case '.':
          {
            eat();
            simples.add(new SimpleClassTySig(name.toString(), tyArgs.build()));
            tyArgs = ImmutableList.builder();
            name = new StringBuilder();
            break;
          }
        case ';':
          break OUTER;
        default:
          name.append(eat());
          break;
      }
    }
    simples.add(new SimpleClassTySig(name.toString(), tyArgs.build()));
    eat();
    return new ClassTySig(pkg.toString(), simples.build());
  }
}

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

package com.google.turbine.tree;

/**
 * An operator kind, with precedence. Only operators that can appear in compile-time constant
 * expressions are included.
 */
public enum TurbineOperatorKind {
  POST_INCR("++", Precedence.POSTFIX),
  POST_DECR("--", Precedence.POSTFIX),
  PRE_INCR("++", Precedence.UNARY),
  PRE_DECR("--", Precedence.UNARY),
  UNARY_PLUS("+", Precedence.UNARY),
  NEG("-", Precedence.UNARY),
  BITWISE_COMP("~", Precedence.UNARY),
  NOT("!", Precedence.UNARY),
  MULT("*", Precedence.MULTIPLICATIVE),
  DIVIDE("/", Precedence.MULTIPLICATIVE),
  MODULO("%", Precedence.MULTIPLICATIVE),
  PLUS("+", Precedence.ADDITIVE),
  MINUS("-", Precedence.ADDITIVE),
  SHIFT_LEFT("<<", Precedence.SHIFT),
  SHIFT_RIGHT(">>", Precedence.SHIFT),
  UNSIGNED_SHIFT_RIGHT(">>>", Precedence.SHIFT),
  LESS_THAN("<", Precedence.RELATIONAL),
  GREATER_THAN(">", Precedence.RELATIONAL),
  GREATER_THAN_EQ(">=", Precedence.RELATIONAL),
  LESS_THAN_EQ("<=", Precedence.RELATIONAL),
  INSTANCE_OF("instanceof", Precedence.RELATIONAL),
  EQUAL("==", Precedence.EQUALITY),
  NOT_EQUAL("!=", Precedence.EQUALITY),
  BITWISE_AND("&", Precedence.BIT_AND),
  BITWISE_XOR("^", Precedence.BIT_XOR),
  BITWISE_OR("|", Precedence.BIT_IOR),
  AND("&&", Precedence.AND),
  OR("||", Precedence.OR),
  TERNARY("?", Precedence.TERNARY),
  ASSIGN("=", Precedence.ASSIGNMENT);

  private final String name;
  private final Precedence prec;

  TurbineOperatorKind(String name, Precedence prec) {
    this.name = name;
    this.prec = prec;
  }

  @Override
  public String toString() {
    return name;
  }

  public Precedence prec() {
    return prec;
  }

  /** Operator precedence groups. */
  public enum Precedence {
    CAST(14),
    POSTFIX(13),
    UNARY(12),
    MULTIPLICATIVE(11),
    ADDITIVE(10),
    SHIFT(9),
    RELATIONAL(8),
    EQUALITY(7),
    BIT_AND(6),
    BIT_XOR(5),
    BIT_IOR(4),
    AND(3),
    OR(2),
    TERNARY(1),
    ASSIGNMENT(0);

    private final int rank;

    public int rank() {
      return rank;
    }

    Precedence(int rank) {
      this.rank = rank;
    }
  }
}

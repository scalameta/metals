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

package com.google.turbine.model;

/** The visibility of a declaration. */
public enum TurbineVisibility {
  PUBLIC(3, TurbineFlag.ACC_PUBLIC),
  PROTECTED(2, TurbineFlag.ACC_PROTECTED),
  PACKAGE(1, 0),
  PRIVATE(0, TurbineFlag.ACC_PRIVATE);

  private final int level;
  private final int flag;

  TurbineVisibility(int level, int flag) {
    this.level = level;
    this.flag = flag;
  }

  public boolean moreVisible(TurbineVisibility other) {
    return level > other.level;
  }

  public int flag() {
    return flag;
  }

  public static final int VISIBILITY_MASK =
      TurbineFlag.ACC_PUBLIC | TurbineFlag.ACC_PRIVATE | TurbineFlag.ACC_PROTECTED;

  /**
   * Returns the {@link TurbineVisibility} corresponding to the given access bits.
   *
   * <p>If the input is ill-formed and corresponds to multiple visibilities, {@code PUBLIC}, {@code
   * PROTECTED}, {@code PRIVATE}, and {@code PACKAGE}, are returned in that order. This means that
   * turbine will occasionally produce valid output for invalid input. In general turbine performs
   * the minimum possible error-checking, and the expectation is that it is run in parallel with
   * javac or another non-header compiler as part of a build, and it defers well-formedness checking
   * to the other tool.
   */
  public static TurbineVisibility fromAccess(int access) {
    if ((access & TurbineFlag.ACC_PUBLIC) == TurbineFlag.ACC_PUBLIC) {
      return PUBLIC;
    }
    if ((access & TurbineFlag.ACC_PROTECTED) == TurbineFlag.ACC_PROTECTED) {
      return PROTECTED;
    }
    if ((access & TurbineFlag.ACC_PRIVATE) == TurbineFlag.ACC_PRIVATE) {
      return PRIVATE;
    }
    return PACKAGE;
  }

  public int setAccess(int access) {
    access &= ~(TurbineFlag.ACC_PUBLIC | TurbineFlag.ACC_PRIVATE | TurbineFlag.ACC_PROTECTED);
    access |= flag();
    return access;
  }
}

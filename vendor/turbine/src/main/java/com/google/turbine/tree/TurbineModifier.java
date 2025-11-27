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

import static java.util.Locale.ENGLISH;

import com.google.turbine.model.TurbineFlag;

/**
 * Modifiers.
 *
 * <p>See JLS 8.1.1, 8.3.1, 8.4.3, 8.8.3, and 9.1.1.
 */
public enum TurbineModifier {
  PRIVATE(TurbineFlag.ACC_PRIVATE),
  PROTECTED(TurbineFlag.ACC_PROTECTED),
  PUBLIC(TurbineFlag.ACC_PUBLIC),
  ACC_SUPER(TurbineFlag.ACC_SUPER),
  ABSTRACT(TurbineFlag.ACC_ABSTRACT),
  STATIC(TurbineFlag.ACC_STATIC),
  FINAL(TurbineFlag.ACC_FINAL),
  VOLATILE(TurbineFlag.ACC_VOLATILE),
  SYNCHRONIZED(TurbineFlag.ACC_SYNCHRONIZED),
  STRICTFP(TurbineFlag.ACC_STRICT),
  NATIVE(TurbineFlag.ACC_NATIVE),
  VARARGS(TurbineFlag.ACC_VARARGS),
  TRANSIENT(TurbineFlag.ACC_TRANSIENT),
  INTERFACE(TurbineFlag.ACC_INTERFACE),
  ACC_ENUM(TurbineFlag.ACC_ENUM),
  ACC_ANNOTATION(TurbineFlag.ACC_ANNOTATION),
  ACC_SYNTHETIC(TurbineFlag.ACC_SYNTHETIC),
  ACC_BRIDGE(TurbineFlag.ACC_BRIDGE),
  DEFAULT(TurbineFlag.ACC_DEFAULT),
  TRANSITIVE(TurbineFlag.ACC_TRANSITIVE),
  SEALED(TurbineFlag.ACC_SEALED),
  NON_SEALED(TurbineFlag.ACC_NON_SEALED),
  COMPACT_CTOR(TurbineFlag.ACC_COMPACT_CTOR),
  ENUM_IMPL(TurbineFlag.ACC_ENUM_IMPL);

  private final int flag;

  TurbineModifier(int flag) {
    this.flag = flag;
  }

  public int flag() {
    return flag;
  }

  @Override
  public String toString() {
    return name().replace('_', '-').toLowerCase(ENGLISH);
  }
}

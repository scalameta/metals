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

/**
 * Access bits.
 *
 * <p>See tables 4.1-A, 4.5-A, 4.6-A, and 4.7.6-A in JVMS 4:
 * https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-4.html
 */
public final class TurbineFlag {
  public static final int ACC_PUBLIC = 0x0001;
  public static final int ACC_PRIVATE = 0x0002;
  public static final int ACC_PROTECTED = 0x0004;
  public static final int ACC_STATIC = 0x0008;
  public static final int ACC_FINAL = 0x0010;
  public static final int ACC_SYNCHRONIZED = 0x0020;
  public static final int ACC_OPEN = 0x0020;
  public static final int ACC_SUPER = 0x0020;
  public static final int ACC_TRANSITIVE = 0x0020;
  public static final int ACC_STATIC_PHASE = 0x0040;
  public static final int ACC_BRIDGE = 0x0040;
  public static final int ACC_VOLATILE = 0x0040;
  public static final int ACC_VARARGS = 0x0080;
  public static final int ACC_TRANSIENT = 0x0080;
  public static final int ACC_INTERFACE = 0x0200;
  public static final int ACC_NATIVE = 0x0100;
  public static final int ACC_ABSTRACT = 0x0400;
  public static final int ACC_STRICT = 0x0800;
  public static final int ACC_SYNTHETIC = 0x1000;
  public static final int ACC_ANNOTATION = 0x2000;
  public static final int ACC_ENUM = 0x4000;
  public static final int ACC_MODULE = 0x8000;
  public static final int ACC_MANDATED = 0x8000;

  // TODO(cushon): the rest of these aren't spec'd access bits, put them somewhere else?

  /** Default methods. */
  public static final int ACC_DEFAULT = 1 << 16;

  /** Enum constants with class bodies. */
  public static final int ACC_ENUM_IMPL = 1 << 17;

  /** Synthetic constructors (e.g. of inner classes and enums). */
  public static final int ACC_SYNTH_CTOR = 1 << 18;

  public static final int ACC_SEALED = 1 << 19;
  public static final int ACC_NON_SEALED = 1 << 20;

  /** Compact record constructor. */
  public static final int ACC_COMPACT_CTOR = 1 << 21;

  private TurbineFlag() {}
}

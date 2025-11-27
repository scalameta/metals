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

package com.google.turbine.binder.sym;

import com.google.errorprone.annotations.Immutable;
import org.jspecify.annotations.Nullable;

/**
 * A class symbol.
 *
 * <p>Turbine identifies classes by their binary string name. Symbols are immutable and do not hold
 * any semantic information: the information that has been determined at the current phase (e.g.
 * about super-types and members) is held externally.
 */
// TODO(cushon): investigate performance impact of interning names/symbols
@Immutable
public class ClassSymbol implements Symbol {

  public static final ClassSymbol OBJECT = new ClassSymbol("java/lang/Object");
  public static final ClassSymbol STRING = new ClassSymbol("java/lang/String");
  public static final ClassSymbol ENUM = new ClassSymbol("java/lang/Enum");
  public static final ClassSymbol RECORD = new ClassSymbol("java/lang/Record");
  public static final ClassSymbol ANNOTATION = new ClassSymbol("java/lang/annotation/Annotation");
  public static final ClassSymbol INHERITED = new ClassSymbol("java/lang/annotation/Inherited");
  public static final ClassSymbol CLONEABLE = new ClassSymbol("java/lang/Cloneable");
  public static final ClassSymbol SERIALIZABLE = new ClassSymbol("java/io/Serializable");
  public static final ClassSymbol DEPRECATED = new ClassSymbol("java/lang/Deprecated");
  public static final ClassSymbol PROFILE_ANNOTATION = new ClassSymbol("jdk/Profile+Annotation");
  public static final ClassSymbol PROPRIETARY_ANNOTATION =
      new ClassSymbol("sun/Proprietary+Annotation");
  public static final ClassSymbol ERROR = new ClassSymbol("<error>");

  public static final ClassSymbol CHARACTER = new ClassSymbol("java/lang/Character");
  public static final ClassSymbol SHORT = new ClassSymbol("java/lang/Short");
  public static final ClassSymbol INTEGER = new ClassSymbol("java/lang/Integer");
  public static final ClassSymbol LONG = new ClassSymbol("java/lang/Long");
  public static final ClassSymbol FLOAT = new ClassSymbol("java/lang/Float");
  public static final ClassSymbol DOUBLE = new ClassSymbol("java/lang/Double");
  public static final ClassSymbol BOOLEAN = new ClassSymbol("java/lang/Boolean");
  public static final ClassSymbol BYTE = new ClassSymbol("java/lang/Byte");

  private final String className;

  public ClassSymbol(String className) {
    this.className = className;
  }

  @Override
  public int hashCode() {
    return className.hashCode();
  }

  @Override
  public String toString() {
    return className.replace('/', '.');
  }

  @Override
  public boolean equals(@Nullable Object o) {
    return o instanceof ClassSymbol && className.equals(((ClassSymbol) o).className);
  }

  /** The JVMS 4.2.1 binary name of the class. */
  public String binaryName() {
    return className;
  }

  @Override
  public Kind symKind() {
    return Kind.CLASS;
  }

  public String simpleName() {
    return binaryName().substring(binaryName().lastIndexOf('/') + 1);
  }

  public String packageName() {
    int idx = binaryName().lastIndexOf('/');
    return idx != -1 ? binaryName().substring(0, idx) : "";
  }

  public PackageSymbol owner() {
    return new PackageSymbol(packageName());
  }
}

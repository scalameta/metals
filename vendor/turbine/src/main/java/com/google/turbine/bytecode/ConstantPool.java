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

package com.google.turbine.bytecode;

import com.google.common.collect.ImmutableList;
import com.google.turbine.model.Const;
import com.google.turbine.model.Const.IntValue;
import com.google.turbine.model.Const.StringValue;
import com.google.turbine.model.Const.Value;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A constant pool builder, used when writing class files. */
public class ConstantPool {

  /** The next available constant pool entry. */
  int nextEntry = 1;

  private final Map<String, Integer> utf8Pool = new HashMap<>();
  private final Map<Integer, Integer> classInfoPool = new HashMap<>();
  private final Map<Integer, Integer> stringPool = new HashMap<>();
  private final Map<Integer, Integer> integerPool = new HashMap<>();
  private final Map<Double, Integer> doublePool = new HashMap<>();
  private final Map<Float, Integer> floatPool = new HashMap<>();
  private final Map<Long, Integer> longPool = new HashMap<>();
  private final Map<Integer, Integer> modulePool = new HashMap<>();
  private final Map<Integer, Integer> packagePool = new HashMap<>();

  private final List<Entry> constants = new ArrayList<>();

  /** The ordered list of constant pool entries. */
  public ImmutableList<Entry> constants() {
    return ImmutableList.copyOf(constants);
  }

  /** The number of constant pool entries the given kind takes up. */
  private static short width(Kind kind) {
    switch (kind) {
      case CLASS_INFO:
      case STRING:
      case INTEGER:
      case UTF8:
      case FLOAT:
      case MODULE:
      case PACKAGE:
        return 1;
      case LONG:
      case DOUBLE:
        // "In retrospect, making 8-byte constants take two constant pool entries
        // was a poor choice." -- JVMS 4.4.5
        return 2;
    }
    throw new AssertionError(kind);
  }

  /** A constant pool entry. */
  static class Entry {
    private final Kind kind;
    private final Value value;

    Entry(Kind kind, Value value) {
      this.kind = kind;
      this.value = value;
    }

    /** The entry kind. */
    public Kind kind() {
      return kind;
    }

    /** The entry's value. */
    public Value value() {
      return value;
    }
  }

  /** Adds a CONSTANT_Class_info entry to the pool. */
  int classInfo(String value) {
    Objects.requireNonNull(value);
    int utf8 = utf8(value);
    if (classInfoPool.containsKey(utf8)) {
      return classInfoPool.get(utf8);
    }
    int index = insert(new Entry(Kind.CLASS_INFO, new IntValue(utf8)));
    classInfoPool.put(utf8, index);
    return index;
  }

  /** Adds a CONSTANT_Utf8_info entry to the pool. */
  int utf8(String value) {
    Objects.requireNonNull(value);
    if (utf8Pool.containsKey(value)) {
      return utf8Pool.get(value);
    }
    int index = insert(new Entry(Kind.UTF8, new StringValue(value)));
    utf8Pool.put(value, index);
    return index;
  }

  int integer(int value) {
    if (integerPool.containsKey(value)) {
      return integerPool.get(value);
    }
    int index = insert(new Entry(Kind.INTEGER, new Const.IntValue(value)));
    integerPool.put(value, index);
    return index;
  }

  int longInfo(long value) {
    if (longPool.containsKey(value)) {
      return longPool.get(value);
    }
    int index = insert(new Entry(Kind.LONG, new Const.LongValue(value)));
    longPool.put(value, index);
    return index;
  }

  int doubleInfo(double value) {
    if (doublePool.containsKey(value)) {
      return doublePool.get(value);
    }
    int index = insert(new Entry(Kind.DOUBLE, new Const.DoubleValue(value)));
    doublePool.put(value, index);
    return index;
  }

  int floatInfo(float value) {
    if (floatPool.containsKey(value)) {
      return floatPool.get(value);
    }
    int index = insert(new Entry(Kind.FLOAT, new Const.FloatValue(value)));
    floatPool.put(value, index);
    return index;
  }

  int string(String value) {
    Objects.requireNonNull(value);
    int utf8 = utf8(value);
    if (stringPool.containsKey(utf8)) {
      return stringPool.get(utf8);
    }
    int index = insert(new Entry(Kind.STRING, new IntValue(utf8)));
    stringPool.put(utf8, index);
    return index;
  }

  /** Adds a CONSTANT_Module_info entry to the pool. */
  int moduleInfo(String value) {
    Objects.requireNonNull(value);
    int utf8 = utf8(value);
    if (modulePool.containsKey(utf8)) {
      return modulePool.get(utf8);
    }
    int index = insert(new Entry(Kind.MODULE, new IntValue(utf8)));
    modulePool.put(utf8, index);
    return index;
  }

  /** Adds a CONSTANT_Package_info entry to the pool. */
  int packageInfo(String value) {
    Objects.requireNonNull(value);
    int utf8 = utf8(value);
    if (packagePool.containsKey(utf8)) {
      return packagePool.get(utf8);
    }
    int index = insert(new Entry(Kind.PACKAGE, new IntValue(utf8)));
    packagePool.put(utf8, index);
    return index;
  }

  private int insert(Entry key) {
    int entry = nextEntry;
    constants.add(key);
    nextEntry += width(key.kind());
    if ((nextEntry & 0xffff) != nextEntry) {
      throw new AssertionError("constant pool has more than 2^16 entries");
    }
    return entry;
  }

  /** Constant pool entry kinds. */
  enum Kind {
    CLASS_INFO(7),
    STRING(8),
    INTEGER(3),
    DOUBLE(6),
    FLOAT(4),
    LONG(5),
    UTF8(1),
    MODULE(19),
    PACKAGE(20);

    private final short tag;

    Kind(int tag) {
      this.tag = (short) tag;
    }

    /** The JVMS Table 4.4-A tag. */
    public short tag() {
      return tag;
    }
  }
}

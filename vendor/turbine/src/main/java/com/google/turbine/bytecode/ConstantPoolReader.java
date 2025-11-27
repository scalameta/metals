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

import com.google.common.io.ByteArrayDataInput;
import com.google.turbine.model.Const;

/** A JVMS §4.4 constant pool reader. */
public class ConstantPoolReader {

  // JVMS table 4.3
  static final int CONSTANT_CLASS = 7;
  static final int CONSTANT_FIELDREF = 9;
  static final int CONSTANT_METHODREF = 10;
  static final int CONSTANT_INTERFACE_METHODREF = 11;
  static final int CONSTANT_STRING = 8;
  static final int CONSTANT_INTEGER = 3;
  static final int CONSTANT_FLOAT = 4;
  static final int CONSTANT_LONG = 5;
  static final int CONSTANT_DOUBLE = 6;
  static final int CONSTANT_NAME_AND_TYPE = 12;
  static final int CONSTANT_UTF8 = 1;
  static final int CONSTANT_METHOD_HANDLE = 15;
  static final int CONSTANT_METHOD_TYPE = 16;
  static final int CONSTANT_DYNAMIC = 17;
  static final int CONSTANT_INVOKE_DYNAMIC = 18;
  static final int CONSTANT_MODULE = 19;
  static final int CONSTANT_PACKAGE = 20;

  /** A table that maps constant pool entries to byte offsets in {@link #byteReader}. */
  private final int[] constantPool;

  /** The constant pool data. */
  private final ByteReader byteReader;

  private ConstantPoolReader(int[] constantPool, ByteReader byteReader) {
    this.constantPool = constantPool;
    this.byteReader = byteReader;
  }

  /**
   * Skips over all constant pool entries, saving the byte offset of each index so it can be read
   * later if it's needed.
   */
  public static ConstantPoolReader readConstantPool(ByteReader reader) {
    int constantPoolCount = reader.u2();
    int[] constantPool = new int[constantPoolCount - 1];
    for (int i = 0; i < constantPoolCount - 1; ) {
      constantPool[i] = reader.pos();
      i += skipConstantPool(reader);
    }
    return new ConstantPoolReader(constantPool, reader);
  }

  /** Skips over the data for a single constant pool entry and returns the size of the entry. */
  private static int skipConstantPool(ByteReader reader) {
    int tag = reader.u1();
    switch (tag) {
      case CONSTANT_CLASS:
      case CONSTANT_METHOD_TYPE:
      case CONSTANT_STRING:
      case CONSTANT_MODULE:
      case CONSTANT_PACKAGE:
        reader.skip(2);
        return 1;
      case CONSTANT_DOUBLE:
      case CONSTANT_LONG:
        reader.skip(8);
        // "In retrospect, making 8-byte constants take two constant pool entries
        // was a poor choice." -- JVMS 4.4.5
        return 2;
      case CONSTANT_FIELDREF:
      case CONSTANT_METHODREF:
      case CONSTANT_INTERFACE_METHODREF:
      case CONSTANT_INTEGER:
      case CONSTANT_FLOAT:
      case CONSTANT_NAME_AND_TYPE:
      case CONSTANT_DYNAMIC:
      case CONSTANT_INVOKE_DYNAMIC:
        reader.skip(4);
        return 1;
      case CONSTANT_UTF8:
        reader.skip(reader.u2());
        return 1;
      case CONSTANT_METHOD_HANDLE:
        reader.skip(3);
        return 1;
      default:
        throw new AssertionError(String.format("bad constant pool tag: 0x%x", tag));
    }
  }

  /** Reads the CONSTANT_Class_info at the given index. */
  public String classInfo(int index) {
    ByteArrayDataInput reader = byteReader.seek(constantPool[index - 1]);
    byte tag = reader.readByte();
    if (tag != CONSTANT_CLASS) {
      throw new AssertionError(String.format("bad tag: %x", tag));
    }
    int nameIndex = reader.readUnsignedShort();
    return utf8(nameIndex);
  }

  /** Reads the CONSTANT_Utf8_info at the given index. */
  public String utf8(int index) {
    ByteArrayDataInput reader = byteReader.seek(constantPool[index - 1]);
    byte tag = reader.readByte();
    if (tag != CONSTANT_UTF8) {
      throw new AssertionError(String.format("bad tag: %x", tag));
    }
    return reader.readUTF();
  }

  /** Reads the CONSTANT_Module_info at the given index. */
  public String moduleInfo(int index) {
    ByteArrayDataInput reader = byteReader.seek(constantPool[index - 1]);
    byte tag = reader.readByte();
    if (tag != CONSTANT_MODULE) {
      throw new AssertionError(String.format("bad tag: %x", tag));
    }
    int nameIndex = reader.readUnsignedShort();
    return utf8(nameIndex);
  }

  /** Reads the CONSTANT_Package_info at the given index. */
  public String packageInfo(int index) {
    ByteArrayDataInput reader = byteReader.seek(constantPool[index - 1]);
    byte tag = reader.readByte();
    if (tag != CONSTANT_PACKAGE) {
      throw new AssertionError(String.format("bad tag: %x", tag));
    }
    int nameIndex = reader.readUnsignedShort();
    return utf8(nameIndex);
  }

  /**
   * Reads a constant value at the given index, which must be one of CONSTANT_String_info,
   * CONSTANT_Integer_info, CONSTANT_Float_info, CONSTANT_Long_info, or CONSTANT_Double_info.
   */
  Const.Value constant(int index) {
    ByteArrayDataInput reader = byteReader.seek(constantPool[index - 1]);
    byte tag = reader.readByte();
    switch (tag) {
      case CONSTANT_LONG:
        return new Const.LongValue(reader.readLong());
      case CONSTANT_FLOAT:
        return new Const.FloatValue(reader.readFloat());
      case CONSTANT_DOUBLE:
        return new Const.DoubleValue(reader.readDouble());
      case CONSTANT_INTEGER:
        return new Const.IntValue(reader.readInt());
      case CONSTANT_STRING:
        return new Const.StringValue(utf8(reader.readUnsignedShort()));
      case CONSTANT_UTF8:
        return new Const.StringValue(reader.readUTF());
      default:
        throw new AssertionError(String.format("bad tag: %x", tag));
    }
  }
}

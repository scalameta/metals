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
import com.google.common.io.ByteArrayDataOutput;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo.ElementValue;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo.ElementValue.ArrayValue;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo.ElementValue.ConstTurbineAnnotationValue;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo.ElementValue.ConstTurbineClassValue;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo.ElementValue.ConstValue;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo.ElementValue.EnumConstValue;
import com.google.turbine.bytecode.ClassFile.TypeAnnotationInfo;
import com.google.turbine.bytecode.ClassFile.TypeAnnotationInfo.FormalParameterTarget;
import com.google.turbine.bytecode.ClassFile.TypeAnnotationInfo.SuperTypeTarget;
import com.google.turbine.bytecode.ClassFile.TypeAnnotationInfo.Target;
import com.google.turbine.bytecode.ClassFile.TypeAnnotationInfo.ThrowsTarget;
import com.google.turbine.bytecode.ClassFile.TypeAnnotationInfo.TypeParameterBoundTarget;
import com.google.turbine.bytecode.ClassFile.TypeAnnotationInfo.TypeParameterTarget;
import com.google.turbine.bytecode.ClassFile.TypeAnnotationInfo.TypePath;
import com.google.turbine.model.Const;
import com.google.turbine.model.Const.Value;
import java.util.Map;

/** Writes an {@link AnnotationInfo} to a class file. */
public class AnnotationWriter {

  final ConstantPool pool;
  final ByteArrayDataOutput output;

  public AnnotationWriter(ConstantPool pool, ByteArrayDataOutput output) {
    this.pool = pool;
    this.output = output;
  }

  public void writeAnnotation(AnnotationInfo annotation) {
    output.writeShort(pool.utf8(annotation.typeName()));
    output.writeShort(annotation.elementValuePairs().size());
    for (Map.Entry<String, ElementValue> entry : annotation.elementValuePairs().entrySet()) {
      output.writeShort(pool.utf8(entry.getKey()));
      writeElementValue(entry.getValue());
    }
  }

  void writeElementValue(ElementValue value) {
    switch (value.kind()) {
      case CONST -> writeConstElementValue(((ConstValue) value).value());
      case ENUM -> writeEnumElementValue((EnumConstValue) value);
      case CLASS -> writeClassElementValue((ConstTurbineClassValue) value);
      case ARRAY -> writeArrayElementValue((ArrayValue) value);
      case ANNOTATION -> writeAnnotationElementValue((ConstTurbineAnnotationValue) value);
    }
  }

  private void writeConstElementValue(Value value) {
    switch (value.constantTypeKind()) {
      case BYTE -> writeConst('B', pool.integer(((Const.ByteValue) value).value()));
      case CHAR -> writeConst('C', pool.integer(((Const.CharValue) value).value()));
      case SHORT -> writeConst('S', pool.integer(((Const.ShortValue) value).value()));
      case DOUBLE -> writeConst('D', pool.doubleInfo(((Const.DoubleValue) value).value()));
      case FLOAT -> writeConst('F', pool.floatInfo(((Const.FloatValue) value).value()));
      case INT -> writeConst('I', pool.integer(((Const.IntValue) value).value()));
      case LONG -> writeConst('J', pool.longInfo(((Const.LongValue) value).value()));
      case STRING -> writeConst('s', pool.utf8(((Const.StringValue) value).value()));
      case BOOLEAN -> writeConst('Z', pool.integer(((Const.BooleanValue) value).value() ? 1 : 0));
      default -> throw new AssertionError(value.constantTypeKind());
    }
  }

  private void writeConst(char tag, int index) {
    output.writeByte(tag);
    output.writeShort(index);
  }

  private void writeEnumElementValue(EnumConstValue value) {
    output.writeByte('e');
    output.writeShort(pool.utf8(value.typeName()));
    output.writeShort(pool.utf8(value.constName()));
  }

  private void writeClassElementValue(ConstTurbineClassValue value) {
    output.writeByte('c');
    output.writeShort(pool.utf8(value.className()));
  }

  private void writeArrayElementValue(ArrayValue value) {
    output.writeByte('[');
    output.writeShort(value.elements().size());
    for (ElementValue elementValue : value.elements()) {
      writeElementValue(elementValue);
    }
  }

  private void writeAnnotationElementValue(ConstTurbineAnnotationValue value) {
    output.writeByte('@');
    writeAnnotation(value.annotation());
  }

  public void writeTypeAnnotation(TypeAnnotationInfo annotation) {
    output.writeByte(annotation.targetType().tag());
    writeTypeAnnotationTarget(annotation.target());
    writePath(annotation.path());
    writeAnnotation(annotation.anno());
  }

  private void writePath(TypePath path) {
    ImmutableList<TypePath> flat = path.flatten();
    output.writeByte(flat.size());
    for (TypePath curr : flat) {
      output.writeByte(curr.tag());
      output.writeByte(curr.typeArgumentIndex());
    }
  }

  private void writeTypeAnnotationTarget(Target target) {
    switch (target.kind()) {
      case EMPTY -> {}
      case TYPE_PARAMETER -> output.writeByte(((TypeParameterTarget) target).index());
      case FORMAL_PARAMETER -> output.writeByte(((FormalParameterTarget) target).index());
      case THROWS -> output.writeShort(((ThrowsTarget) target).index());
      case SUPERTYPE -> output.writeShort(((SuperTypeTarget) target).index());
      case TYPE_PARAMETER_BOUND -> {
        TypeParameterBoundTarget typeParameterBoundTarget = (TypeParameterBoundTarget) target;
        output.writeByte(typeParameterBoundTarget.typeParameterIndex());
        output.writeByte(typeParameterBoundTarget.boundIndex());
      }
    }
  }
}

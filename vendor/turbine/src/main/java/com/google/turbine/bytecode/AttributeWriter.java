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

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.google.turbine.bytecode.Attribute.Annotations;
import com.google.turbine.bytecode.Attribute.ConstantValue;
import com.google.turbine.bytecode.Attribute.ExceptionsAttribute;
import com.google.turbine.bytecode.Attribute.InnerClasses;
import com.google.turbine.bytecode.Attribute.MethodParameters;
import com.google.turbine.bytecode.Attribute.Signature;
import com.google.turbine.bytecode.Attribute.TurbineTransitiveJar;
import com.google.turbine.bytecode.Attribute.TypeAnnotations;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo;
import com.google.turbine.bytecode.ClassFile.MethodInfo.ParameterInfo;
import com.google.turbine.bytecode.ClassFile.ModuleInfo;
import com.google.turbine.bytecode.ClassFile.ModuleInfo.ExportInfo;
import com.google.turbine.bytecode.ClassFile.ModuleInfo.OpenInfo;
import com.google.turbine.bytecode.ClassFile.ModuleInfo.ProvideInfo;
import com.google.turbine.bytecode.ClassFile.ModuleInfo.RequireInfo;
import com.google.turbine.bytecode.ClassFile.ModuleInfo.UseInfo;
import com.google.turbine.bytecode.ClassFile.TypeAnnotationInfo;
import com.google.turbine.model.Const;
import java.util.List;

/** Writer {@link Attribute}s to bytecode. */
public class AttributeWriter {

  private final ConstantPool pool;

  public AttributeWriter(ConstantPool pool) {
    this.pool = pool;
  }

  /** Writes a single attribute. */
  public void write(ByteArrayDataOutput output, Attribute attribute) {
    switch (attribute.kind()) {
      case SIGNATURE -> writeSignatureAttribute(output, (Signature) attribute);
      case EXCEPTIONS -> writeExceptionsAttribute(output, (ExceptionsAttribute) attribute);
      case INNER_CLASSES -> writeInnerClasses(output, (InnerClasses) attribute);
      case CONSTANT_VALUE -> writeConstantValue(output, (ConstantValue) attribute);
      case RUNTIME_VISIBLE_ANNOTATIONS, RUNTIME_INVISIBLE_ANNOTATIONS ->
          writeAnnotation(output, (Attribute.Annotations) attribute);
      case ANNOTATION_DEFAULT ->
          writeAnnotationDefault(output, (Attribute.AnnotationDefault) attribute);
      case RUNTIME_VISIBLE_PARAMETER_ANNOTATIONS, RUNTIME_INVISIBLE_PARAMETER_ANNOTATIONS ->
          writeParameterAnnotations(output, (Attribute.ParameterAnnotations) attribute);
      case DEPRECATED -> writeDeprecated(output, attribute);
      case RUNTIME_INVISIBLE_TYPE_ANNOTATIONS, RUNTIME_VISIBLE_TYPE_ANNOTATIONS ->
          writeTypeAnnotation(output, (Attribute.TypeAnnotations) attribute);
      case METHOD_PARAMETERS ->
          writeMethodParameters(output, (Attribute.MethodParameters) attribute);
      case MODULE -> writeModule(output, (Attribute.Module) attribute);
      case NEST_HOST -> writeNestHost(output, (Attribute.NestHost) attribute);
      case NEST_MEMBERS -> writeNestMembers(output, (Attribute.NestMembers) attribute);
      case RECORD -> writeRecord(output, (Attribute.Record) attribute);
      case PERMITTED_SUBCLASSES ->
          writePermittedSubclasses(output, (Attribute.PermittedSubclasses) attribute);
      case TURBINE_TRANSITIVE_JAR ->
          writeTurbineTransitiveJar(output, (Attribute.TurbineTransitiveJar) attribute);
    }
  }

  private void writeInnerClasses(ByteArrayDataOutput output, InnerClasses attribute) {
    output.writeShort(pool.utf8(attribute.kind().signature()));
    output.writeInt(attribute.inners.size() * 8 + 2);
    output.writeShort(attribute.inners.size());
    for (ClassFile.InnerClass inner : attribute.inners) {
      output.writeShort(pool.classInfo(inner.innerClass()));
      output.writeShort(pool.classInfo(inner.outerClass()));
      output.writeShort(pool.utf8(inner.innerName()));
      output.writeShort(inner.access());
    }
  }

  private void writeExceptionsAttribute(ByteArrayDataOutput output, ExceptionsAttribute attribute) {
    output.writeShort(pool.utf8(attribute.kind().signature()));
    output.writeInt(2 + attribute.exceptions.size() * 2);
    output.writeShort(attribute.exceptions.size());
    for (String exception : attribute.exceptions) {
      output.writeShort(pool.classInfo(exception));
    }
  }

  private void writeSignatureAttribute(ByteArrayDataOutput output, Signature attribute) {
    output.writeShort(pool.utf8(attribute.kind().signature()));
    output.writeInt(2);
    output.writeShort(pool.utf8(attribute.signature));
  }

  public void writeConstantValue(ByteArrayDataOutput output, ConstantValue attribute) {
    output.writeShort(pool.utf8(attribute.kind().signature()));
    output.writeInt(2);
    Const.Value value = attribute.value;
    switch (value.constantTypeKind()) {
      case INT -> output.writeShort(pool.integer(((Const.IntValue) value).value()));
      case CHAR -> output.writeShort(pool.integer(((Const.CharValue) value).value()));
      case SHORT -> output.writeShort(pool.integer(((Const.ShortValue) value).value()));
      case BYTE -> output.writeShort(pool.integer(((Const.ByteValue) value).value()));
      case LONG -> output.writeShort(pool.longInfo(((Const.LongValue) value).value()));
      case DOUBLE -> output.writeShort(pool.doubleInfo(((Const.DoubleValue) value).value()));
      case FLOAT -> output.writeShort(pool.floatInfo(((Const.FloatValue) value).value()));
      case BOOLEAN -> output.writeShort(pool.integer(((Const.BooleanValue) value).value() ? 1 : 0));
      case STRING -> output.writeShort(pool.string(((Const.StringValue) value).value()));
      case NULL -> throw new AssertionError(value.constantTypeKind());
    }
  }

  public void writeAnnotation(ByteArrayDataOutput output, Annotations attribute) {
    output.writeShort(pool.utf8(attribute.kind().signature()));
    ByteArrayDataOutput tmp = ByteStreams.newDataOutput();
    tmp.writeShort(attribute.annotations().size());
    for (AnnotationInfo annotation : attribute.annotations()) {
      new AnnotationWriter(pool, tmp).writeAnnotation(annotation);
    }
    byte[] data = tmp.toByteArray();
    output.writeInt(data.length);
    output.write(data);
  }

  public void writeAnnotationDefault(
      ByteArrayDataOutput output, Attribute.AnnotationDefault attribute) {
    output.writeShort(pool.utf8(attribute.kind().signature()));
    ByteArrayDataOutput tmp = ByteStreams.newDataOutput();
    new AnnotationWriter(pool, tmp).writeElementValue(attribute.value());
    byte[] data = tmp.toByteArray();
    output.writeInt(data.length);
    output.write(data);
  }

  public void writeParameterAnnotations(
      ByteArrayDataOutput output, Attribute.ParameterAnnotations attribute) {
    output.writeShort(pool.utf8(attribute.kind().signature()));
    ByteArrayDataOutput tmp = ByteStreams.newDataOutput();
    tmp.writeByte(attribute.annotations().size());
    for (List<AnnotationInfo> parameterAnnotations : attribute.annotations()) {
      tmp.writeShort(parameterAnnotations.size());
      for (AnnotationInfo annotation : parameterAnnotations) {
        new AnnotationWriter(pool, tmp).writeAnnotation(annotation);
      }
    }
    byte[] data = tmp.toByteArray();
    output.writeInt(data.length);
    output.write(data);
  }

  private void writeDeprecated(ByteArrayDataOutput output, Attribute attribute) {
    output.writeShort(pool.utf8(attribute.kind().signature()));
    output.writeInt(0);
  }

  private void writeTypeAnnotation(ByteArrayDataOutput output, TypeAnnotations attribute) {
    output.writeShort(pool.utf8(attribute.kind().signature()));
    ByteArrayDataOutput tmp = ByteStreams.newDataOutput();
    tmp.writeShort(attribute.annotations().size());
    for (TypeAnnotationInfo annotation : attribute.annotations()) {
      new AnnotationWriter(pool, tmp).writeTypeAnnotation(annotation);
    }
    byte[] data = tmp.toByteArray();
    output.writeInt(data.length);
    output.write(data);
  }

  private void writeMethodParameters(ByteArrayDataOutput output, MethodParameters attribute) {
    output.writeShort(pool.utf8(attribute.kind().signature()));
    output.writeInt(attribute.parameters().size() * 4 + 1);
    output.writeByte(attribute.parameters().size());
    for (ParameterInfo parameter : attribute.parameters()) {
      output.writeShort(parameter.name() != null ? pool.utf8(parameter.name()) : 0);
      output.writeShort(parameter.access());
    }
  }

  private void writeModule(ByteArrayDataOutput output, Attribute.Module attribute) {
    ModuleInfo module = attribute.module();

    ByteArrayDataOutput tmp = ByteStreams.newDataOutput();

    tmp.writeShort(pool.moduleInfo(module.name()));
    tmp.writeShort(module.flags());
    tmp.writeShort(module.version() != null ? pool.utf8(module.version()) : 0);

    tmp.writeShort(module.requires().size());
    for (RequireInfo require : module.requires()) {
      tmp.writeShort(pool.moduleInfo(require.moduleName()));
      tmp.writeShort(require.flags());
      tmp.writeShort(require.version() != null ? pool.utf8(require.version()) : 0);
    }

    tmp.writeShort(module.exports().size());
    for (ExportInfo export : module.exports()) {
      tmp.writeShort(pool.packageInfo(export.moduleName()));
      tmp.writeShort(export.flags());
      tmp.writeShort(export.modules().size());
      for (String exportedModule : export.modules()) {
        tmp.writeShort(pool.moduleInfo(exportedModule));
      }
    }

    tmp.writeShort(module.opens().size());
    for (OpenInfo opens : module.opens()) {
      tmp.writeShort(pool.packageInfo(opens.moduleName()));
      tmp.writeShort(opens.flags());
      tmp.writeShort(opens.modules().size());
      for (String openModule : opens.modules()) {
        tmp.writeShort(pool.moduleInfo(openModule));
      }
    }

    tmp.writeShort(module.uses().size());
    for (UseInfo use : module.uses()) {
      tmp.writeShort(pool.classInfo(use.descriptor()));
    }

    tmp.writeShort(module.provides().size());
    for (ProvideInfo provide : module.provides()) {
      tmp.writeShort(pool.classInfo(provide.descriptor()));
      tmp.writeShort(provide.implDescriptors().size());
      for (String impl : provide.implDescriptors()) {
        tmp.writeShort(pool.classInfo(impl));
      }
    }

    byte[] data = tmp.toByteArray();
    output.writeShort(pool.utf8(attribute.kind().signature()));
    output.writeInt(data.length);
    output.write(data);
  }

  private void writeNestHost(ByteArrayDataOutput output, Attribute.NestHost attribute) {
    output.writeShort(pool.utf8(attribute.kind().signature()));
    output.writeInt(2);
    output.writeShort(pool.classInfo(attribute.hostClass()));
  }

  private void writeNestMembers(ByteArrayDataOutput output, Attribute.NestMembers attribute) {
    output.writeShort(pool.utf8(attribute.kind().signature()));
    output.writeInt(2 + attribute.classes().size() * 2);
    output.writeShort(attribute.classes().size());
    for (String classes : attribute.classes()) {
      output.writeShort(pool.classInfo(classes));
    }
  }

  private void writeRecord(ByteArrayDataOutput output, Attribute.Record attribute) {
    output.writeShort(pool.utf8(attribute.kind().signature()));
    ByteArrayDataOutput tmp = ByteStreams.newDataOutput();
    tmp.writeShort(attribute.components().size());
    for (Attribute.Record.Component c : attribute.components()) {
      tmp.writeShort(pool.utf8(c.name()));
      tmp.writeShort(pool.utf8(c.descriptor()));
      tmp.writeShort(c.attributes().size());
      for (Attribute a : c.attributes()) {
        write(tmp, a);
      }
    }
    byte[] data = tmp.toByteArray();
    output.writeInt(data.length);
    output.write(data);
  }

  private void writePermittedSubclasses(
      ByteArrayDataOutput output, Attribute.PermittedSubclasses attribute) {
    output.writeShort(pool.utf8(attribute.kind().signature()));
    output.writeInt(2 + attribute.permits.size() * 2);
    output.writeShort(attribute.permits.size());
    for (String permits : attribute.permits) {
      output.writeShort(pool.classInfo(permits));
    }
  }

  private void writeTurbineTransitiveJar(
      ByteArrayDataOutput output, TurbineTransitiveJar attribute) {
    output.writeShort(pool.utf8(attribute.kind().signature()));
    output.writeInt(2);
    output.writeShort(pool.utf8(attribute.transitiveJar));
  }
}

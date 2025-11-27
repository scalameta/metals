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
import com.google.turbine.bytecode.Attribute.AnnotationDefault;
import com.google.turbine.bytecode.Attribute.ConstantValue;
import com.google.turbine.bytecode.Attribute.ExceptionsAttribute;
import com.google.turbine.bytecode.Attribute.InnerClasses;
import com.google.turbine.bytecode.Attribute.MethodParameters;
import com.google.turbine.bytecode.Attribute.Signature;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo;
import com.google.turbine.bytecode.ClassFile.TypeAnnotationInfo;
import java.util.ArrayList;
import java.util.List;

/** Lower information in {@link ClassFile} structures to attributes. */
public final class LowerAttributes {

  /** Collects the {@link Attribute}s for a {@link ClassFile}. */
  static List<Attribute> classAttributes(ClassFile classfile) {
    List<Attribute> attributes = new ArrayList<>();
    if (!classfile.innerClasses().isEmpty()) {
      attributes.add(new InnerClasses(classfile.innerClasses()));
    }
    addAllAnnotations(attributes, classfile.annotations());
    addAllTypeAnnotations(attributes, classfile.typeAnnotations());
    if (classfile.signature() != null) {
      attributes.add(new Signature(classfile.signature()));
    }
    if (classfile.module() != null) {
      attributes.add(new Attribute.Module(classfile.module()));
    }
    if (classfile.nestHost() != null) {
      attributes.add(new Attribute.NestHost(classfile.nestHost()));
    }
    if (!classfile.nestMembers().isEmpty()) {
      attributes.add(new Attribute.NestMembers(classfile.nestMembers()));
    }
    if (classfile.record() != null) {
      attributes.add(recordAttribute(classfile.record()));
    }
    if (!classfile.permits().isEmpty()) {
      attributes.add(new Attribute.PermittedSubclasses(classfile.permits()));
    }
    if (classfile.transitiveJar() != null) {
      attributes.add(new Attribute.TurbineTransitiveJar(classfile.transitiveJar()));
    }
    return attributes;
  }

  private static Attribute recordAttribute(ClassFile.RecordInfo record) {
    ImmutableList.Builder<Attribute.Record.Component> components = ImmutableList.builder();
    for (ClassFile.RecordInfo.RecordComponentInfo component : record.recordComponents()) {
      List<Attribute> attributes = new ArrayList<>();
      if (component.signature() != null) {
        attributes.add(new Attribute.Signature(component.signature()));
      }
      addAllAnnotations(attributes, component.annotations());
      addAllTypeAnnotations(attributes, component.typeAnnotations());
      components.add(
          new Attribute.Record.Component(component.name(), component.descriptor(), attributes));
    }
    return new Attribute.Record(components.build());
  }

  /** Collects the {@link Attribute}s for a {@link MethodInfo}. */
  static List<Attribute> methodAttributes(ClassFile.MethodInfo method) {
    List<Attribute> attributes = new ArrayList<>();
    addAllAnnotations(attributes, method.annotations());
    addAllTypeAnnotations(attributes, method.typeAnnotations());
    if (method.signature() != null) {
      attributes.add(new Signature(method.signature()));
    }
    addParameterAnnotations(attributes, method.parameterAnnotations());
    if (!method.exceptions().isEmpty()) {
      attributes.add(new ExceptionsAttribute(method.exceptions()));
    }
    if (method.defaultValue() != null) {
      attributes.add(new AnnotationDefault(method.defaultValue()));
    }
    if (!method.parameters().isEmpty()) {
      attributes.add(new MethodParameters(method.parameters()));
    }
    return attributes;
  }

  /** Collects the {@link Attribute}s for a {@link FieldInfo}. */
  static List<Attribute> fieldAttributes(ClassFile.FieldInfo field) {
    List<Attribute> attributes = new ArrayList<>();
    if (field.signature() != null) {
      attributes.add(new Signature(field.signature()));
    }
    if (field.value() != null) {
      attributes.add(new ConstantValue(field.value()));
    }
    addAllAnnotations(attributes, field.annotations());
    addAllTypeAnnotations(attributes, field.typeAnnotations());
    return attributes;
  }

  static void addAllAnnotations(List<Attribute> attributes, List<AnnotationInfo> annotations) {
    List<AnnotationInfo> visible = new ArrayList<>();
    List<AnnotationInfo> invisible = new ArrayList<>();
    for (AnnotationInfo annotation : annotations) {
      if (annotation.typeName().equals("Ljava/lang/Deprecated;")) {
        attributes.add(Attribute.DEPRECATED);
      }
      (annotation.isRuntimeVisible() ? visible : invisible).add(annotation);
    }
    if (!visible.isEmpty()) {
      attributes.add(new Attribute.RuntimeVisibleAnnotations(visible));
    }
    if (!invisible.isEmpty()) {
      attributes.add(new Attribute.RuntimeInvisibleAnnotations(invisible));
    }
  }

  private static void addAllTypeAnnotations(
      List<Attribute> attributes, ImmutableList<TypeAnnotationInfo> annotations) {
    List<TypeAnnotationInfo> visible = new ArrayList<>();
    List<TypeAnnotationInfo> invisible = new ArrayList<>();
    for (TypeAnnotationInfo annotation : annotations) {
      (annotation.anno().isRuntimeVisible() ? visible : invisible).add(annotation);
    }
    if (!visible.isEmpty()) {
      attributes.add(new Attribute.RuntimeVisibleTypeAnnotations(ImmutableList.copyOf(visible)));
    }
    if (!invisible.isEmpty()) {
      attributes.add(
          new Attribute.RuntimeInvisibleTypeAnnotations(ImmutableList.copyOf(invisible)));
    }
  }

  static void addParameterAnnotations(
      List<Attribute> attributes, ImmutableList<ImmutableList<AnnotationInfo>> annotations) {
    List<List<AnnotationInfo>> visibles = new ArrayList<>();
    List<List<AnnotationInfo>> invisibles = new ArrayList<>();
    boolean hasVisible = false;
    boolean hasInvisible = false;
    for (List<AnnotationInfo> parameterAnnotations : annotations) {
      List<AnnotationInfo> visible = new ArrayList<>();
      List<AnnotationInfo> invisible = new ArrayList<>();
      for (AnnotationInfo annotation : parameterAnnotations) {
        if (annotation.isRuntimeVisible()) {
          hasVisible = true;
          visible.add(annotation);
        } else {
          hasInvisible = true;
          invisible.add(annotation);
        }
      }
      visibles.add(visible);
      invisibles.add(invisible);
    }
    // only add the attributes if one of the nested lists is non-empty,
    // i.e. at least one parameter was annotated
    if (hasVisible) {
      attributes.add(new Attribute.RuntimeVisibleParameterAnnotations(visibles));
    }
    if (hasInvisible) {
      attributes.add(new Attribute.RuntimeInvisibleParameterAnnotations(invisibles));
    }
  }

  private LowerAttributes() {}
}

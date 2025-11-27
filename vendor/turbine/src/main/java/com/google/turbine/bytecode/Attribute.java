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
import com.google.turbine.bytecode.ClassFile.AnnotationInfo;
import com.google.turbine.bytecode.ClassFile.MethodInfo.ParameterInfo;
import com.google.turbine.bytecode.ClassFile.ModuleInfo;
import com.google.turbine.bytecode.ClassFile.TypeAnnotationInfo;
import com.google.turbine.model.Const.Value;
import java.util.List;

/** Well-known JVMS §4.1 attributes. */
interface Attribute {

  enum Kind {
    SIGNATURE("Signature"),
    EXCEPTIONS("Exceptions"),
    INNER_CLASSES("InnerClasses"),
    CONSTANT_VALUE("ConstantValue"),
    RUNTIME_VISIBLE_ANNOTATIONS("RuntimeVisibleAnnotations"),
    RUNTIME_INVISIBLE_ANNOTATIONS("RuntimeInvisibleAnnotations"),
    ANNOTATION_DEFAULT("AnnotationDefault"),
    RUNTIME_VISIBLE_PARAMETER_ANNOTATIONS("RuntimeVisibleParameterAnnotations"),
    RUNTIME_INVISIBLE_PARAMETER_ANNOTATIONS("RuntimeInvisibleParameterAnnotations"),
    DEPRECATED("Deprecated"),
    RUNTIME_VISIBLE_TYPE_ANNOTATIONS("RuntimeVisibleTypeAnnotations"),
    RUNTIME_INVISIBLE_TYPE_ANNOTATIONS("RuntimeInvisibleTypeAnnotations"),
    METHOD_PARAMETERS("MethodParameters"),
    MODULE("Module"),
    NEST_HOST("NestHost"),
    NEST_MEMBERS("NestMembers"),
    RECORD("Record"),
    TURBINE_TRANSITIVE_JAR("TurbineTransitiveJar"),
    PERMITTED_SUBCLASSES("PermittedSubclasses");

    private final String signature;

    Kind(String signature) {
      this.signature = signature;
    }

    public String signature() {
      return signature;
    }
  }

  Kind kind();

  /** A JVMS §4.7.6 InnerClasses attribute. */
  class InnerClasses implements Attribute {

    final List<ClassFile.InnerClass> inners;

    public InnerClasses(List<ClassFile.InnerClass> inners) {
      this.inners = inners;
    }

    @Override
    public Kind kind() {
      return Kind.INNER_CLASSES;
    }
  }

  /** A JVMS §4.7.9 Signature attribute. */
  class Signature implements Attribute {

    final String signature;

    public Signature(String signature) {
      this.signature = signature;
    }

    @Override
    public Kind kind() {
      return Kind.SIGNATURE;
    }
  }

  /** A JVMS §4.7.5 Exceptions attribute. */
  class ExceptionsAttribute implements Attribute {

    final List<String> exceptions;

    public ExceptionsAttribute(List<String> exceptions) {
      this.exceptions = exceptions;
    }

    @Override
    public Kind kind() {
      return Kind.EXCEPTIONS;
    }
  }

  interface Annotations extends Attribute {
    List<AnnotationInfo> annotations();
  }

  /** A JVMS §4.7.16 RuntimeVisibleAnnotations attribute. */
  class RuntimeVisibleAnnotations implements Annotations {
    List<AnnotationInfo> annotations;

    public RuntimeVisibleAnnotations(List<AnnotationInfo> annotations) {
      this.annotations = annotations;
    }

    @Override
    public List<AnnotationInfo> annotations() {
      return annotations;
    }

    @Override
    public Kind kind() {
      return Kind.RUNTIME_VISIBLE_ANNOTATIONS;
    }
  }

  /** A JVMS §4.7.17 RuntimeInvisibleAnnotations attribute. */
  class RuntimeInvisibleAnnotations implements Annotations {
    List<AnnotationInfo> annotations;

    public RuntimeInvisibleAnnotations(List<AnnotationInfo> annotations) {
      this.annotations = annotations;
    }

    @Override
    public List<AnnotationInfo> annotations() {
      return annotations;
    }

    @Override
    public Kind kind() {
      return Kind.RUNTIME_INVISIBLE_ANNOTATIONS;
    }
  }

  /** A JVMS §4.7.2 ConstantValue attribute. */
  class ConstantValue implements Attribute {

    final Value value;

    public ConstantValue(Value value) {
      this.value = value;
    }

    @Override
    public Kind kind() {
      return Kind.CONSTANT_VALUE;
    }
  }

  /** A JVMS §4.7.22 AnnotationDefault attribute. */
  class AnnotationDefault implements Attribute {

    private final AnnotationInfo.ElementValue value;

    public AnnotationDefault(AnnotationInfo.ElementValue value) {
      this.value = value;
    }

    @Override
    public Kind kind() {
      return Kind.ANNOTATION_DEFAULT;
    }

    public AnnotationInfo.ElementValue value() {
      return value;
    }
  }

  interface ParameterAnnotations extends Attribute {
    List<List<AnnotationInfo>> annotations();
  }

  /** A JVMS §4.7.18 RuntimeVisibleParameterAnnotations attribute. */
  class RuntimeVisibleParameterAnnotations implements ParameterAnnotations {

    @Override
    public List<List<AnnotationInfo>> annotations() {
      return annotations;
    }

    final List<List<AnnotationInfo>> annotations;

    public RuntimeVisibleParameterAnnotations(List<List<AnnotationInfo>> annotations) {
      this.annotations = annotations;
    }

    @Override
    public Kind kind() {
      return Kind.RUNTIME_VISIBLE_PARAMETER_ANNOTATIONS;
    }
  }

  /** A JVMS §4.7.19 RuntimeInvisibleParameterAnnotations attribute. */
  class RuntimeInvisibleParameterAnnotations implements ParameterAnnotations {

    @Override
    public List<List<AnnotationInfo>> annotations() {
      return annotations;
    }

    final List<List<AnnotationInfo>> annotations;

    public RuntimeInvisibleParameterAnnotations(List<List<AnnotationInfo>> annotations) {
      this.annotations = annotations;
    }

    @Override
    public Kind kind() {
      return Kind.RUNTIME_INVISIBLE_PARAMETER_ANNOTATIONS;
    }
  }

  /** A JVMS §4.7.15 Deprecated attribute. */
  Attribute DEPRECATED =
      new Attribute() {
        @Override
        public Kind kind() {
          return Kind.DEPRECATED;
        }
      };

  interface TypeAnnotations extends Attribute {
    ImmutableList<TypeAnnotationInfo> annotations();
  }

  /** A JVMS §4.7.20 RuntimeInvisibleTypeAnnotations attribute. */
  class RuntimeInvisibleTypeAnnotations implements TypeAnnotations {
    final ImmutableList<TypeAnnotationInfo> annotations;

    public RuntimeInvisibleTypeAnnotations(ImmutableList<TypeAnnotationInfo> annotations) {
      this.annotations = annotations;
    }

    @Override
    public Kind kind() {
      return Kind.RUNTIME_INVISIBLE_TYPE_ANNOTATIONS;
    }

    @Override
    public ImmutableList<TypeAnnotationInfo> annotations() {
      return annotations;
    }
  }

  /** A JVMS §4.7.20 RuntimeVisibleTypeAnnotations attribute. */
  class RuntimeVisibleTypeAnnotations implements TypeAnnotations {
    final ImmutableList<TypeAnnotationInfo> annotations;

    public RuntimeVisibleTypeAnnotations(ImmutableList<TypeAnnotationInfo> annotations) {
      this.annotations = annotations;
    }

    @Override
    public Kind kind() {
      return Kind.RUNTIME_VISIBLE_TYPE_ANNOTATIONS;
    }

    @Override
    public ImmutableList<TypeAnnotationInfo> annotations() {
      return annotations;
    }
  }

  /** A JVMS §4.7.24 MethodParameters attribute. */
  class MethodParameters implements Attribute {
    private final ImmutableList<ParameterInfo> parameters;

    public MethodParameters(ImmutableList<ParameterInfo> parameters) {
      this.parameters = parameters;
    }

    /** The parameters. */
    public ImmutableList<ParameterInfo> parameters() {
      return parameters;
    }

    @Override
    public Kind kind() {
      return Kind.METHOD_PARAMETERS;
    }
  }

  /** A JVMS §4.7.25 Module attribute. */
  class Module implements Attribute {

    private final ModuleInfo module;

    public Module(ModuleInfo module) {
      this.module = module;
    }

    @Override
    public Kind kind() {
      return Kind.MODULE;
    }

    public ModuleInfo module() {
      return module;
    }
  }

  /** A JVMS §4.7.28 NestHost attribute. */
  class NestHost implements Attribute {

    private final String hostClass;

    public NestHost(String hostClass) {
      this.hostClass = hostClass;
    }

    String hostClass() {
      return hostClass;
    }

    @Override
    public Kind kind() {
      return Kind.NEST_HOST;
    }
  }

  /** A JVMS §4.7.29 NestHost attribute. */
  class NestMembers implements Attribute {

    private final ImmutableList<String> classes;

    public NestMembers(ImmutableList<String> classes) {
      this.classes = classes;
    }

    ImmutableList<String> classes() {
      return classes;
    }

    @Override
    public Kind kind() {
      return Kind.NEST_MEMBERS;
    }
  }

  /** A JVMS §4.7.30 Record attribute. */
  class Record implements Attribute {

    private final ImmutableList<Component> components;

    public Record(ImmutableList<Component> components) {
      this.components = components;
    }

    @Override
    public Kind kind() {
      return Kind.RECORD;
    }

    ImmutableList<Component> components() {
      return components;
    }

    /** A JVMS §4.7.30 Record component info. */
    static class Component {
      private final String name;
      private final String descriptor;
      private final List<Attribute> attributes;

      Component(String name, String descriptor, List<Attribute> attributes) {
        this.name = name;
        this.descriptor = descriptor;
        this.attributes = attributes;
      }

      String name() {
        return name;
      }

      String descriptor() {
        return descriptor;
      }

      List<Attribute> attributes() {
        return attributes;
      }
    }
  }

  /** A JVMS §4.7.31 PermittedSubclasses attribute. */
  class PermittedSubclasses implements Attribute {
    final List<String> permits;

    public PermittedSubclasses(List<String> permits) {
      this.permits = permits;
    }

    @Override
    public Kind kind() {
      return Kind.PERMITTED_SUBCLASSES;
    }
  }

  /** A custom attribute for recording the original jar of repackaged transitive classes. */
  class TurbineTransitiveJar implements Attribute {

    final String transitiveJar;

    public TurbineTransitiveJar(String transitiveJar) {
      this.transitiveJar = transitiveJar;
    }

    @Override
    public Kind kind() {
      return Kind.TURBINE_TRANSITIVE_JAR;
    }
  }
}

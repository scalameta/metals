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

import static java.util.Objects.requireNonNull;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo.ElementValue;
import com.google.turbine.model.Const;
import com.google.turbine.model.Const.Value;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** A JVMS §4.1 ClassFile. */
public class ClassFile {

  public byte[] bytes;
  private final int access;
  private final int majorVersion;
  private final String name;
  private final @Nullable String signature;
  private final @Nullable String superClass;
  private final List<String> interfaces;
  private final List<String> permits;
  private final List<MethodInfo> methods;
  private final List<FieldInfo> fields;
  private final List<AnnotationInfo> annotations;
  private final List<InnerClass> innerClasses;
  private final ImmutableList<TypeAnnotationInfo> typeAnnotations;
  private final @Nullable ModuleInfo module;
  private final @Nullable String nestHost;
  private final ImmutableList<String> nestMembers;
  private final @Nullable RecordInfo record;
  private final @Nullable String transitiveJar;

  public ClassFile(
      int access,
      int majorVersion,
      String name,
      @Nullable String signature,
      @Nullable String superClass,
      List<String> interfaces,
      List<String> permits,
      List<MethodInfo> methods,
      List<FieldInfo> fields,
      List<AnnotationInfo> annotations,
      List<InnerClass> innerClasses,
      ImmutableList<TypeAnnotationInfo> typeAnnotations,
      @Nullable ModuleInfo module,
      @Nullable String nestHost,
      ImmutableList<String> nestMembers,
      @Nullable RecordInfo record,
      @Nullable String transitiveJar) {
    this.access = access;
    this.majorVersion = majorVersion;
    this.name = name;
    this.signature = signature;
    this.superClass = superClass;
    this.interfaces = interfaces;
    this.permits = permits;
    this.methods = methods;
    this.fields = fields;
    this.annotations = annotations;
    this.innerClasses = innerClasses;
    this.typeAnnotations = typeAnnotations;
    this.module = module;
    this.nestHost = nestHost;
    this.nestMembers = nestMembers;
    this.record = record;
    this.transitiveJar = transitiveJar;
  }

  /** Class access and property flags. */
  public int access() {
    return access;
  }

  /** Class file major version. */
  public int majorVersion() {
    return majorVersion;
  }

  /** The name of the class or interface. */
  public String name() {
    return name;
  }

  /** The value of the Signature attribute. */
  public @Nullable String signature() {
    return signature;
  }

  /** The super class. */
  public @Nullable String superName() {
    return superClass;
  }

  /** The direct superinterfaces. */
  public List<String> interfaces() {
    return interfaces;
  }

  /** The permitted direct subclasses. */
  public List<String> permits() {
    return permits;
  }

  /** Methods declared by this class or interfaces type. */
  public List<MethodInfo> methods() {
    return methods;
  }

  /** Fields declared by this class or interfaces type. */
  public List<FieldInfo> fields() {
    return fields;
  }

  /** Declaration annotations of the class. */
  public List<AnnotationInfo> annotations() {
    return annotations;
  }

  /** Inner class information. */
  public List<InnerClass> innerClasses() {
    return innerClasses;
  }

  /** Type annotations. */
  public ImmutableList<TypeAnnotationInfo> typeAnnotations() {
    return typeAnnotations;
  }

  /** A module attribute. */
  public @Nullable ModuleInfo module() {
    return module;
  }

  public @Nullable String nestHost() {
    return nestHost;
  }

  public ImmutableList<String> nestMembers() {
    return nestMembers;
  }

  public @Nullable RecordInfo record() {
    return record;
  }

  /** The original jar of a repackaged transitive class. */
  public @Nullable String transitiveJar() {
    return transitiveJar;
  }

  /** The contents of a JVMS §4.5 field_info structure. */
  public static class FieldInfo {

    private final int access;
    private final String name;
    private final String descriptor;
    private final @Nullable String signature;
    private final @Nullable Value value;
    private final List<AnnotationInfo> annotations;
    private final ImmutableList<TypeAnnotationInfo> typeAnnotations;

    public FieldInfo(
        int access,
        String name,
        String descriptor,
        @Nullable String signature,
        @Nullable Value value,
        List<AnnotationInfo> annotations,
        ImmutableList<TypeAnnotationInfo> typeAnnotations) {
      this.access = access;
      this.name = name;
      this.descriptor = descriptor;
      this.signature = signature;
      this.value = value;
      this.annotations = annotations;
      this.typeAnnotations = typeAnnotations;
    }

    /** Field access and property flags. */
    public int access() {
      return access;
    }

    /** The name of the field. */
    public String name() {
      return name;
    }

    /** The descriptor. */
    public String descriptor() {
      return descriptor;
    }

    /** The value of Signature attribute. */
    public @Nullable String signature() {
      return signature;
    }

    /** The compile-time constant value. */
    public Const.@Nullable Value value() {
      return value;
    }

    /** Declaration annotations of the field. */
    public List<AnnotationInfo> annotations() {
      return annotations;
    }

    /** Type annotations. */
    public ImmutableList<TypeAnnotationInfo> typeAnnotations() {
      return typeAnnotations;
    }
  }

  /** A JVMS §4.7.6 InnerClasses attribute. */
  public static class InnerClass {

    private final String innerClass;
    private final String outerClass;
    private final String innerName;
    private final int access;

    public InnerClass(String innerClass, String outerClass, String innerName, int access) {
      this.innerClass = requireNonNull(innerClass);
      this.outerClass = requireNonNull(outerClass);
      this.innerName = requireNonNull(innerName);
      this.access = access;
    }

    /** The binary name of the inner class. */
    public String innerClass() {
      return innerClass;
    }

    /** The binary name of the enclosing class. */
    public String outerClass() {
      return outerClass;
    }

    /** The simple name of the inner class. */
    public String innerName() {
      return innerName;
    }

    /** Access and property flags of the inner class; see JVMS table 4.8. */
    public int access() {
      return access;
    }
  }

  /** The contents of a JVMS §4.6 method_info structure. */
  public static class MethodInfo {

    private final int access;
    private final String name;
    private final String descriptor;
    private final @Nullable String signature;
    private final List<String> exceptions;
    private final AnnotationInfo.@Nullable ElementValue defaultValue;
    private final List<AnnotationInfo> annotations;
    private final ImmutableList<ImmutableList<AnnotationInfo>> parameterAnnotations;
    private final ImmutableList<TypeAnnotationInfo> typeAnnotations;
    private final ImmutableList<ParameterInfo> parameters;

    public MethodInfo(
        int access,
        String name,
        String descriptor,
        @Nullable String signature,
        List<String> exceptions,
        @Nullable ElementValue defaultValue,
        List<AnnotationInfo> annotations,
        ImmutableList<ImmutableList<AnnotationInfo>> parameterAnnotations,
        ImmutableList<TypeAnnotationInfo> typeAnnotations,
        ImmutableList<ParameterInfo> parameters) {
      this.access = access;
      this.name = name;
      this.descriptor = descriptor;
      this.signature = signature;
      this.exceptions = exceptions;
      this.defaultValue = defaultValue;
      this.annotations = annotations;
      this.parameterAnnotations = parameterAnnotations;
      this.typeAnnotations = typeAnnotations;
      this.parameters = parameters;
    }

    /** Method access and property flags. */
    public int access() {
      return access;
    }

    /** The name of the method. */
    public String name() {
      return name;
    }

    /** The descriptor. */
    public String descriptor() {
      return descriptor;
    }

    /** The value of Signature attribute. */
    public @Nullable String signature() {
      return signature;
    }

    /** The value of Exceptions attribute. */
    public List<String> exceptions() {
      return exceptions;
    }

    /** The value of the AnnotationDefault attribute. */
    public AnnotationInfo.@Nullable ElementValue defaultValue() {
      return defaultValue;
    }

    /** Declaration annotations of the method. */
    public List<AnnotationInfo> annotations() {
      return annotations;
    }

    /** Declaration annotations of the formal parameters. */
    public ImmutableList<ImmutableList<AnnotationInfo>> parameterAnnotations() {
      return parameterAnnotations;
    }

    /** Type annotations. */
    public ImmutableList<TypeAnnotationInfo> typeAnnotations() {
      return typeAnnotations;
    }

    /** Formal parameters. */
    public ImmutableList<ParameterInfo> parameters() {
      return parameters;
    }

    /** A formal parameter. */
    public static class ParameterInfo {
      private final String name;
      private final int access;

      public ParameterInfo(String name, int access) {
        this.name = name;
        this.access = access;
      }

      /** Returns the parameter's name. */
      public String name() {
        return name;
      }

      /** Returns the parameter's modifiers. */
      public int access() {
        return access;
      }
    }
  }

  /** The contents of a JVMS §4.7.16 annotation structure. */
  public static class AnnotationInfo {

    /** Whether the annotation is visible at runtime. */
    public enum RuntimeVisibility {
      VISIBLE,
      INVISIBLE
    }

    private final String typeName;
    private final RuntimeVisibility runtimeVisibility;
    private final Map<String, ElementValue> elementValuePairs;

    public AnnotationInfo(
        String typeName,
        RuntimeVisibility runtimeVisibility,
        Map<String, ElementValue> elementValuePairs) {
      this.typeName = typeName;
      this.runtimeVisibility = runtimeVisibility;
      this.elementValuePairs = elementValuePairs;
    }

    /** The JVMS §4.3.2 field descriptor for the type of the annotation. */
    public String typeName() {
      return typeName;
    }

    /** Returns true if the annotation is visible at runtime. */
    public boolean isRuntimeVisible() {
      return runtimeVisibility == RuntimeVisibility.VISIBLE;
    }

    /** The element-value pairs of the annotation. */
    public Map<String, ElementValue> elementValuePairs() {
      return elementValuePairs;
    }

    /** A value of a JVMS §4.7.16.1 element-value pair. */
    public interface ElementValue {

      /** The value kind. */
      ElementValue.Kind kind();

      /** Element value kinds. */
      enum Kind {
        ENUM,
        CONST,
        ARRAY,
        CLASS,
        ANNOTATION
      }

      /** An enum constant value. */
      class EnumConstValue implements ElementValue {

        private final String typeName;
        private final String constName;

        public EnumConstValue(String typeName, String constName) {
          this.typeName = typeName;
          this.constName = constName;
        }

        @Override
        public ElementValue.Kind kind() {
          return ElementValue.Kind.ENUM;
        }

        /** The type of the enum. */
        public String typeName() {
          return typeName;
        }

        /** The name of the enum constant. */
        public String constName() {
          return constName;
        }
      }

      /** A primitive or string constant value. */
      class ConstValue implements ElementValue {

        private final Const.Value value;

        public ConstValue(Const.Value value) {

          this.value = value;
        }

        @Override
        public ElementValue.Kind kind() {
          return ElementValue.Kind.CONST;
        }

        /** The constant value. */
        public Const.Value value() {
          return value;
        }
      }

      /** A constant array value. */
      class ArrayValue implements ElementValue {

        private final List<ElementValue> elements;

        public ArrayValue(List<ElementValue> elements) {
          this.elements = elements;
        }

        @Override
        public ElementValue.Kind kind() {
          return ElementValue.Kind.ARRAY;
        }

        /** The elements of the array. */
        public List<ElementValue> elements() {
          return elements;
        }
      }

      /** A constant class literal value. */
      class ConstTurbineClassValue implements ElementValue {

        private final String className;

        public ConstTurbineClassValue(String className) {
          this.className = className;
        }

        @Override
        public ElementValue.Kind kind() {
          return ElementValue.Kind.CLASS;
        }

        /** The class name. */
        public String className() {
          return className;
        }
      }

      /** A nested annotation value. */
      class ConstTurbineAnnotationValue implements ElementValue {

        private final AnnotationInfo annotation;

        public ConstTurbineAnnotationValue(AnnotationInfo annotation) {
          this.annotation = annotation;
        }

        @Override
        public ElementValue.Kind kind() {
          return ElementValue.Kind.ANNOTATION;
        }

        /** The annotation. */
        public AnnotationInfo annotation() {
          return annotation;
        }
      }
    }
  }

  /** The contents of a JVMS §4.7.20 type annotation structure. */
  public static class TypeAnnotationInfo {
    private final TargetType targetType;
    private final Target target;
    private final TypePath path;
    private final AnnotationInfo anno;

    public TypeAnnotationInfo(
        TargetType targetType, Target target, TypePath path, AnnotationInfo anno) {
      this.targetType = targetType;
      this.target = target;
      this.path = path;
      this.anno = anno;
    }

    /**
     * The underlying annotation info (type, visibility, element-value pairs); shared with
     * declaration annotations.
     */
    public AnnotationInfo anno() {
      return anno;
    }

    /** A JVMS 4.7.20 target_type kind, denotes the type context where the annotation appears. */
    public TargetType targetType() {
      return targetType;
    }

    /** A JVMS 4.7.20 target_info structure. */
    public Target target() {
      return target;
    }

    /**
     * A JVMS 4.7.20 type_path structure, denotes which part of the type the annotation applies to.
     */
    public TypePath path() {
      return path;
    }

    /** A JVMS 4.7.20-A target_type kind. */
    public enum TargetType {
      CLASS_TYPE_PARAMETER(0x00),
      METHOD_TYPE_PARAMETER(0x01),
      SUPERTYPE(0x10),
      CLASS_TYPE_PARAMETER_BOUND(0x11),
      METHOD_TYPE_PARAMETER_BOUND(0x12),
      FIELD(0x13),
      METHOD_RETURN(0x14),
      METHOD_RECEIVER_PARAMETER(0x15),
      METHOD_FORMAL_PARAMETER(0x16),
      METHOD_THROWS(0x17);

      private final int tag;

      TargetType(int tag) {
        this.tag = tag;
      }

      public int tag() {
        return tag;
      }

      /**
       * Returns the {@link TargetType} for the given JVMS 4.7.20-A target_type value, and {@code
       * null} for target_type values that do not correspond to API elements (see JVMS 4.7.20-B).
       */
      static @Nullable TargetType forTag(int targetType) {
        switch (targetType) {
          case 0x00:
            return CLASS_TYPE_PARAMETER;
          case 0x01:
            return METHOD_TYPE_PARAMETER;
          case 0x10:
            return SUPERTYPE;
          case 0x11:
            return CLASS_TYPE_PARAMETER_BOUND;
          case 0x12:
            return METHOD_TYPE_PARAMETER_BOUND;
          case 0x13:
            return FIELD;
          case 0x14:
            return METHOD_RETURN;
          case 0x15:
            return METHOD_RECEIVER_PARAMETER;
          case 0x16:
            return METHOD_FORMAL_PARAMETER;
          case 0x17:
            return METHOD_THROWS;
          default:
            return null;
        }
      }
    }

    /** A JVMS 4.7.20 target_info. */
    public abstract static class Target {
      /** Target info kind. */
      public enum Kind {
        TYPE_PARAMETER,
        SUPERTYPE,
        TYPE_PARAMETER_BOUND,
        EMPTY,
        FORMAL_PARAMETER,
        THROWS;
      }

      /** Returns the target info kind. */
      public abstract Target.Kind kind();
    }

    /** A JVMS 4.7.20.1 type_parameter_target. */
    @AutoValue
    public abstract static class TypeParameterTarget extends Target {
      public static TypeParameterTarget create(int index) {
        return new AutoValue_ClassFile_TypeAnnotationInfo_TypeParameterTarget(index);
      }

      public abstract int index();

      @Override
      public Target.Kind kind() {
        return Target.Kind.TYPE_PARAMETER;
      }
    }

    /** A JVMS 4.7.20.1 supertype_target. */
    @AutoValue
    public abstract static class SuperTypeTarget extends Target {
      public static SuperTypeTarget create(int index) {
        return new AutoValue_ClassFile_TypeAnnotationInfo_SuperTypeTarget(index);
      }

      @Override
      public Target.Kind kind() {
        return Target.Kind.SUPERTYPE;
      }

      public abstract int index();
    }

    /** A JVMS 4.7.20.1 type_parameter_bound_target. */
    @AutoValue
    public abstract static class TypeParameterBoundTarget extends Target {
      public static TypeParameterBoundTarget create(int typeParameterIndex, int boundIndex) {
        return new AutoValue_ClassFile_TypeAnnotationInfo_TypeParameterBoundTarget(
            typeParameterIndex, boundIndex);
      }

      @Override
      public Target.Kind kind() {
        return Target.Kind.TYPE_PARAMETER_BOUND;
      }

      public abstract int typeParameterIndex();

      public abstract int boundIndex();
    }

    /** A JVMS 4.7.20.1 empty_target. */
    public static final Target EMPTY_TARGET =
        new Target() {
          @Override
          public Target.Kind kind() {
            return Target.Kind.EMPTY;
          }
        };

    /** A JVMS 4.7.20.1 formal_parameter_target. */
    @AutoValue
    public abstract static class FormalParameterTarget extends Target {

      public static FormalParameterTarget create(int index) {
        return new AutoValue_ClassFile_TypeAnnotationInfo_FormalParameterTarget(index);
      }

      @Override
      public Target.Kind kind() {
        return Target.Kind.FORMAL_PARAMETER;
      }

      public abstract int index();
    }

    /** A JVMS 4.7.20.1 throws_target. */
    @AutoValue
    public abstract static class ThrowsTarget extends Target {

      public static ThrowsTarget create(int index) {
        return new AutoValue_ClassFile_TypeAnnotationInfo_ThrowsTarget(index);
      }

      @Override
      public Target.Kind kind() {
        return Target.Kind.THROWS;
      }

      public abstract int index();
    }

    /**
     * A JVMS 4.7.20.2 type_path.
     *
     * <p>Represented as an immutable linked-list of nodes, which is built out by {@code Lower}
     * while recursively searching for type annotations to process.
     */
    @AutoValue
    public abstract static class TypePath {

      /** The root type_path_kind, used for initialization. */
      public static TypePath root() {
        return create(null, null);
      }

      /** Adds an array type_path_kind entry. */
      public TypePath array() {
        return create(TypePath.Kind.ARRAY, this);
      }

      /** Adds a nested type type_path_kind entry. */
      public TypePath nested() {
        return create(TypePath.Kind.NESTED, this);
      }

      /** Adds a wildcard bound type_path_kind entry. */
      public TypePath wild() {
        return create(TypePath.Kind.WILDCARD_BOUND, this);
      }

      /** Adds a type argument type_path_kind entry. */
      public TypePath typeArgument(int idx) {
        return create(idx, TypePath.Kind.TYPE_ARGUMENT, this);
      }

      /** A type_path_kind. */
      public enum Kind {
        ARRAY(0),
        NESTED(1),
        WILDCARD_BOUND(2),
        TYPE_ARGUMENT(3);

        final int tag;

        Kind(int tag) {
          this.tag = tag;
        }
      }

      /** The type argument index; set only if the kind is {@code TYPE_ARGUMENT}. */
      public abstract int typeArgumentIndex();

      public abstract @Nullable Kind kind();

      public abstract @Nullable TypePath parent();

      private static TypePath create(TypePath.@Nullable Kind kind, @Nullable TypePath parent) {
        // JVMS 4.7.20.2: type_argument_index is 0 if the bound kind is not TYPE_ARGUMENT
        return create(0, kind, parent);
      }

      private static TypePath create(
          int index, TypePath.@Nullable Kind kind, @Nullable TypePath parent) {
        return new AutoValue_ClassFile_TypeAnnotationInfo_TypePath(index, kind, parent);
      }

      /** The JVMS 4.7.20.2-A serialized value of the type_path_kind. */
      public byte tag() {
        return (byte) requireNonNull(kind()).tag;
      }

      /** Returns a flattened view of the type path. */
      public ImmutableList<TypePath> flatten() {
        Deque<TypePath> flat = new ArrayDeque<>();
        for (TypePath curr = this; requireNonNull(curr).kind() != null; curr = curr.parent()) {
          flat.addFirst(curr);
        }
        return ImmutableList.copyOf(flat);
      }
    }
  }

  /** A JVMS 4.7.25 module attribute. */
  public static class ModuleInfo {

    private final String name;
    private final @Nullable String version;
    private final int flags;
    private final ImmutableList<RequireInfo> requires;
    private final ImmutableList<ExportInfo> exports;
    private final ImmutableList<OpenInfo> opens;
    private final ImmutableList<UseInfo> uses;
    private final ImmutableList<ProvideInfo> provides;

    public ModuleInfo(
        String name,
        int flags,
        @Nullable String version,
        ImmutableList<RequireInfo> requires,
        ImmutableList<ExportInfo> exports,
        ImmutableList<OpenInfo> opens,
        ImmutableList<UseInfo> uses,
        ImmutableList<ProvideInfo> provides) {
      this.name = name;
      this.flags = flags;
      this.version = version;
      this.requires = requires;
      this.exports = exports;
      this.opens = opens;
      this.uses = uses;
      this.provides = provides;
    }

    public String name() {
      return name;
    }

    public int flags() {
      return flags;
    }

    public @Nullable String version() {
      return version;
    }

    public ImmutableList<RequireInfo> requires() {
      return requires;
    }

    public ImmutableList<ExportInfo> exports() {
      return exports;
    }

    public ImmutableList<OpenInfo> opens() {
      return opens;
    }

    public ImmutableList<UseInfo> uses() {
      return uses;
    }

    public ImmutableList<ProvideInfo> provides() {
      return provides;
    }

    /** A JVMS 4.7.25 module requires directive. */
    public static class RequireInfo {

      private final String moduleName;
      private final int flags;
      private final @Nullable String version;

      public RequireInfo(String moduleName, int flags, @Nullable String version) {
        this.moduleName = moduleName;
        this.flags = flags;
        this.version = version;
      }

      public String moduleName() {
        return moduleName;
      }

      public int flags() {
        return flags;
      }

      public @Nullable String version() {
        return version;
      }
    }

    /** A JVMS 4.7.25 module exports directive. */
    public static class ExportInfo {

      private final String moduleName;
      private final int flags;
      private final ImmutableList<String> modules;

      public ExportInfo(String moduleName, int flags, ImmutableList<String> modules) {
        this.moduleName = moduleName;
        this.flags = flags;
        this.modules = modules;
      }

      public String moduleName() {
        return moduleName;
      }

      public int flags() {
        return flags;
      }

      public ImmutableList<String> modules() {
        return modules;
      }
    }

    /** A JVMS 4.7.25 module opens directive. */
    public static class OpenInfo {

      private final String moduleName;
      private final int flags;
      private final ImmutableList<String> modules;

      public OpenInfo(String moduleName, int flags, ImmutableList<String> modules) {
        this.moduleName = moduleName;
        this.flags = flags;
        this.modules = modules;
      }

      public String moduleName() {
        return moduleName;
      }

      public int flags() {
        return flags;
      }

      public ImmutableList<String> modules() {
        return modules;
      }
    }

    /** A JVMS 4.7.25 module uses directive. */
    public static class UseInfo {

      private final String descriptor;

      public UseInfo(String descriptor) {
        this.descriptor = descriptor;
      }

      public String descriptor() {
        return descriptor;
      }
    }

    /** A JVMS 4.7.25 module provides directive. */
    public static class ProvideInfo {

      private final String descriptor;
      private final ImmutableList<String> implDescriptors;

      public ProvideInfo(String descriptor, ImmutableList<String> implDescriptors) {
        this.descriptor = descriptor;
        this.implDescriptors = implDescriptors;
      }

      public String descriptor() {
        return descriptor;
      }

      public ImmutableList<String> implDescriptors() {
        return implDescriptors;
      }
    }
  }

  /** A JVMS §4.7.30 Record attribute. */
  public static class RecordInfo {

    /** A JVMS §4.7.30 Record component attribute. */
    public static class RecordComponentInfo {

      private final String name;
      private final String descriptor;
      private final @Nullable String signature;
      private final ImmutableList<AnnotationInfo> annotations;
      private final ImmutableList<TypeAnnotationInfo> typeAnnotations;

      public RecordComponentInfo(
          String name,
          String descriptor,
          @Nullable String signature,
          ImmutableList<AnnotationInfo> annotations,
          ImmutableList<TypeAnnotationInfo> typeAnnotations) {
        this.name = name;
        this.descriptor = descriptor;
        this.signature = signature;
        this.annotations = annotations;
        this.typeAnnotations = typeAnnotations;
      }

      public String name() {
        return name;
      }

      public String descriptor() {
        return descriptor;
      }

      public @Nullable String signature() {
        return signature;
      }

      public ImmutableList<AnnotationInfo> annotations() {
        return annotations;
      }

      public ImmutableList<TypeAnnotationInfo> typeAnnotations() {
        return typeAnnotations;
      }
    }

    public RecordInfo(ImmutableList<RecordComponentInfo> recordComponents) {
      this.recordComponents = recordComponents;
    }

    private final ImmutableList<RecordComponentInfo> recordComponents;

    public ImmutableList<RecordComponentInfo> recordComponents() {
      return recordComponents;
    }
  }
}

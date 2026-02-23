package scala.meta.internal.proto.codegen.java;

import java.util.ArrayList;
import java.util.List;
import scala.meta.internal.proto.codegen.CodeGenerator;
import scala.meta.internal.proto.tree.Proto;
import scala.meta.internal.proto.tree.Proto.*;

/**
 * Generates Java outline code compatible with protobuf-java.
 *
 * <p>The generated code has the same public API as protoc-generated code, but method bodies are
 * stubs that throw UnsupportedOperationException. This allows the code to compile and be used for
 * IDE features.
 */
public final class JavaOutlineGenerator implements CodeGenerator {

  private static final String STUB = "throw new UnsupportedOperationException(\"outline stub\")";
  private final String javaPackagePrefix;

  public JavaOutlineGenerator() {
    this("");
  }

  public JavaOutlineGenerator(String javaPackagePrefix) {
    this.javaPackagePrefix = normalizePrefix(javaPackagePrefix);
  }

  @Override
  public List<OutputFile> generate(ProtoFile file) {
    List<OutputFile> outputs = new ArrayList<>();

    // Get java package from options or proto package
    String javaPackage = getJavaPackage(file);
    String outerClassName = getOuterClassName(file);
    boolean multipleFiles = getJavaMultipleFiles(file);
    String packagePath = javaPackage.isEmpty() ? "" : javaPackage.replace('.', '/') + "/";

    if (multipleFiles) {
      // Generate each top-level message, enum, and service in its own file
      for (Proto decl : file.declarations()) {
        if (decl instanceof MessageDecl) {
          MessageDecl msg = (MessageDecl) decl;
          outputs.add(generateMessageFile(msg, javaPackage, packagePath));
        } else if (decl instanceof EnumDecl) {
          EnumDecl e = (EnumDecl) decl;
          outputs.add(generateEnumFile(e, javaPackage, packagePath));
        } else if (decl instanceof ServiceDecl) {
          ServiceDecl svc = (ServiceDecl) decl;
          outputs.add(generateServiceFile(svc, javaPackage, packagePath));
        }
      }

      // Generate outer class with only static methods
      StringBuilder sb = new StringBuilder();
      if (!javaPackage.isEmpty()) {
        sb.append("package ").append(javaPackage).append(";\n\n");
      }
      sb.append("public final class ").append(outerClassName).append(" {\n");
      sb.append("  private ").append(outerClassName).append("() {}\n\n");
      sb.append(
          "  public static void registerAllExtensions(com.google.protobuf.ExtensionRegistryLite"
              + " registry) {}\n\n");
      sb.append(
          "  public static void registerAllExtensions(com.google.protobuf.ExtensionRegistry"
              + " registry) {\n");
      sb.append(
          "    registerAllExtensions((com.google.protobuf.ExtensionRegistryLite) registry);\n");
      sb.append("  }\n\n");
      sb.append(
          "  public static com.google.protobuf.Descriptors.FileDescriptor getDescriptor() {\n");
      sb.append("    ").append(STUB).append(";\n");
      sb.append("  }\n");
      sb.append("}\n");
      outputs.add(outputFile(packagePath + outerClassName + ".java", sb.toString()));
    } else {
      // Generate all in a single outer class file
      StringBuilder sb = new StringBuilder();

      // Package declaration
      if (!javaPackage.isEmpty()) {
        sb.append("package ").append(javaPackage).append(";\n\n");
      }

      // Outer class
      sb.append("public final class ").append(outerClassName).append(" {\n");
      sb.append("  private ").append(outerClassName).append("() {}\n\n");

      // Static registerAllExtensions methods
      sb.append(
          "  public static void registerAllExtensions(com.google.protobuf.ExtensionRegistryLite"
              + " registry) {}\n\n");
      sb.append(
          "  public static void registerAllExtensions(com.google.protobuf.ExtensionRegistry"
              + " registry) {\n");
      sb.append(
          "    registerAllExtensions((com.google.protobuf.ExtensionRegistryLite) registry);\n");
      sb.append("  }\n\n");

      // Static getDescriptor
      sb.append(
          "  public static com.google.protobuf.Descriptors.FileDescriptor getDescriptor() {\n");
      sb.append("    ").append(STUB).append(";\n");
      sb.append("  }\n\n");

      // Generate all top-level messages, enums, and services
      for (Proto decl : file.declarations()) {
        if (decl instanceof MessageDecl) {
          generateMessageOrBuilder(sb, (MessageDecl) decl, "  ", "");
          generateMessage(sb, (MessageDecl) decl, "  ", "");
        } else if (decl instanceof EnumDecl) {
          generateEnum(sb, (EnumDecl) decl, "  ");
        } else if (decl instanceof ServiceDecl) {
          generateService(sb, (ServiceDecl) decl, "  ");
        }
      }

      sb.append("}\n");
      outputs.add(outputFile(packagePath + outerClassName + ".java", sb.toString()));
    }

    return outputs;
  }

  /** Generate a separate file for a top-level message. */
  private OutputFile generateMessageFile(MessageDecl msg, String javaPackage, String packagePath) {
    StringBuilder sb = new StringBuilder();
    String className = msg.name().value();

    if (!javaPackage.isEmpty()) {
      sb.append("package ").append(javaPackage).append(";\n\n");
    }

    // Generate OrBuilder interface as separate top-level interface
    generateTopLevelOrBuilder(sb, msg);

    // Generate message class as top-level class
    sb.append("public final class ").append(className);
    sb.append(" extends com.google.protobuf.GeneratedMessageV3 implements ")
        .append(className)
        .append("OrBuilder {\n");

    String indent = "  ";
    generateMessageBody(sb, msg, indent, className);

    sb.append("}\n");

    return outputFile(packagePath + className + ".java", sb.toString());
  }

  /** Generate a separate file for a top-level enum. */
  private OutputFile generateEnumFile(EnumDecl e, String javaPackage, String packagePath) {
    StringBuilder sb = new StringBuilder();
    String enumName = e.name().value();

    if (!javaPackage.isEmpty()) {
      sb.append("package ").append(javaPackage).append(";\n\n");
    }

    // Generate enum as top-level enum
    sb.append("public enum ")
        .append(enumName)
        .append(" implements com.google.protobuf.ProtocolMessageEnum {\n");
    generateEnumBody(sb, e, "  ");
    sb.append("}\n");

    return outputFile(packagePath + enumName + ".java", sb.toString());
  }

  /** Generate a separate file for a top-level service. */
  private OutputFile generateServiceFile(ServiceDecl svc, String javaPackage, String packagePath) {
    StringBuilder sb = new StringBuilder();
    String serviceName = svc.name().value() + "Grpc";

    if (!javaPackage.isEmpty()) {
      sb.append("package ").append(javaPackage).append(";\n\n");
    }

    generateServiceClass(sb, svc, "");

    return outputFile(packagePath + serviceName + ".java", sb.toString());
  }

  /** Generate top-level OrBuilder interface for multiple_files mode. */
  private void generateTopLevelOrBuilder(StringBuilder sb, MessageDecl msg) {
    String className = msg.name().value();
    String interfaceName = className + "OrBuilder";

    sb.append("interface ").append(interfaceName);
    sb.append(" extends com.google.protobuf.MessageOrBuilder {\n");

    String indent = "  ";

    // Generate getter signatures for fields
    for (FieldDecl field : msg.fields()) {
      generateOrBuilderFieldMethods(sb, field, indent, className, msg);
    }

    // Generate getter signatures for map fields
    for (MapFieldDecl mapField : msg.mapFields()) {
      generateOrBuilderMapFieldMethods(sb, mapField, indent);
    }

    sb.append("}\n\n");
  }

  /** Generate the OrBuilder interface for a message. */
  private void generateMessageOrBuilder(
      StringBuilder sb, MessageDecl msg, String indent, String parentClass) {
    String className = msg.name().value();
    String interfaceName = className + "OrBuilder";
    String fullClassName = parentClass.isEmpty() ? className : parentClass + "." + className;

    sb.append(indent).append("public interface ").append(interfaceName);
    sb.append(" extends com.google.protobuf.MessageOrBuilder {\n");

    String innerIndent = indent + "  ";

    // Generate getter signatures for fields
    for (FieldDecl field : msg.fields()) {
      generateOrBuilderFieldMethods(sb, field, innerIndent, fullClassName, msg);
    }

    // Generate getter signatures for map fields
    for (MapFieldDecl mapField : msg.mapFields()) {
      generateOrBuilderMapFieldMethods(sb, mapField, innerIndent);
    }

    sb.append(indent).append("}\n\n");
  }

  /** Generate field method signatures for OrBuilder interface. */
  private void generateOrBuilderFieldMethods(
      StringBuilder sb,
      FieldDecl field,
      String indent,
      String enclosingClass,
      MessageDecl parentMsg) {
    String fieldName = field.name().value();
    String typeName = field.type().fullName();
    String javaType = resolveFieldType(typeName, enclosingClass, parentMsg);
    String boxedType = resolveBoxedFieldType(typeName, enclosingClass, parentMsg);
    String getterName = JavaTypeMapper.toGetterName(fieldName);
    String hasName = JavaTypeMapper.toHasMethodName(fieldName);
    boolean isRepeated = field.modifier() == FieldModifier.REPEATED;
    boolean isMessageType =
        !JavaTypeMapper.isScalarType(typeName) && !isEnumType(typeName, parentMsg);

    if (isRepeated) {
      sb.append(indent).append("java.util.List<").append(boxedType).append("> ");
      sb.append(getterName).append("List();\n");
      sb.append(indent).append("int ").append(getterName).append("Count();\n");
      sb.append(indent).append(javaType).append(" ").append(getterName).append("(int index);\n");
      // OrBuilder getter for repeated message fields
      if (isMessageType) {
        sb.append(indent)
            .append("java.util.List<? extends ")
            .append(javaType)
            .append("OrBuilder> ");
        sb.append(getterName).append("OrBuilderList();\n");
        sb.append(indent)
            .append(javaType)
            .append("OrBuilder ")
            .append(getterName)
            .append("OrBuilder(int index);\n");
      }
    } else {
      if (field.modifier() == FieldModifier.OPTIONAL || !JavaTypeMapper.isScalarType(typeName)) {
        sb.append(indent).append("boolean ").append(hasName).append("();\n");
      }
      sb.append(indent).append(javaType).append(" ").append(getterName).append("();\n");
      // ByteString getter for string fields
      if ("string".equals(typeName)) {
        sb.append(indent)
            .append("com.google.protobuf.ByteString ")
            .append(getterName)
            .append("Bytes();\n");
      }
      // OrBuilder getter for message fields
      if (isMessageType) {
        sb.append(indent)
            .append(javaType)
            .append("OrBuilder ")
            .append(getterName)
            .append("OrBuilder();\n");
      }
    }
  }

  /** Check if a type is an enum type (nested in parent or built-in). */
  private boolean isEnumType(String typeName, MessageDecl parentMsg) {
    for (EnumDecl nested : parentMsg.nestedEnums()) {
      if (nested.name().value().equals(typeName)) {
        return true;
      }
    }
    return false;
  }

  /** Resolve a field type, qualifying nested message types with enclosing class. */
  private String resolveFieldType(String typeName, String enclosingClass, MessageDecl parentMsg) {
    // Check if this is a nested message type within the enclosing class
    if (isNestedMessageType(typeName, parentMsg)) {
      return enclosingClass + "." + typeName;
    }
    return JavaTypeMapper.toJavaType(typeName);
  }

  /** Resolve a boxed field type, qualifying nested message types with enclosing class. */
  private String resolveBoxedFieldType(
      String typeName, String enclosingClass, MessageDecl parentMsg) {
    // Check if this is a nested message type within the enclosing class
    if (isNestedMessageType(typeName, parentMsg)) {
      return enclosingClass + "." + typeName;
    }
    return JavaTypeMapper.toBoxedJavaType(typeName);
  }

  /** Check if a type name refers to a nested message within the parent message. */
  private boolean isNestedMessageType(String typeName, MessageDecl parentMsg) {
    for (MessageDecl nested : parentMsg.nestedMessages()) {
      if (nested.name().value().equals(typeName)) {
        return true;
      }
    }
    for (EnumDecl nested : parentMsg.nestedEnums()) {
      if (nested.name().value().equals(typeName)) {
        return true;
      }
    }
    return false;
  }

  /** Generate map field method signatures for OrBuilder interface. */
  private void generateOrBuilderMapFieldMethods(
      StringBuilder sb, MapFieldDecl mapField, String indent) {
    String fieldName = mapField.name().value();
    String keyType = JavaTypeMapper.toBoxedJavaType(mapField.keyType().fullName());
    String valueType = JavaTypeMapper.toBoxedJavaType(mapField.valueType().fullName());
    String getterName = JavaTypeMapper.toGetterName(fieldName);
    String capitalName = JavaTypeMapper.capitalize(JavaTypeMapper.toCamelCase(fieldName));

    sb.append(indent).append("int ").append(getterName).append("Count();\n");
    sb.append(indent)
        .append("boolean contains")
        .append(capitalName)
        .append("(")
        .append(keyType)
        .append(" key);\n");
    sb.append(indent)
        .append("java.util.Map<")
        .append(keyType)
        .append(", ")
        .append(valueType)
        .append("> ")
        .append(getterName)
        .append("Map();\n");
    sb.append(indent)
        .append(valueType)
        .append(" ")
        .append(getterName)
        .append("OrDefault(")
        .append(keyType)
        .append(" key, ")
        .append(valueType)
        .append(" defaultValue);\n");
    sb.append(indent)
        .append(valueType)
        .append(" ")
        .append(getterName)
        .append("OrThrow(")
        .append(keyType)
        .append(" key);\n");
  }

  private void generateMessage(
      StringBuilder sb, MessageDecl msg, String indent, String parentClass) {
    String className = msg.name().value();
    String fullClassName = parentClass.isEmpty() ? className : parentClass + "." + className;

    // Message class (as static nested class extending GeneratedMessageV3)
    sb.append(indent).append("public static final class ").append(className);
    sb.append(" extends com.google.protobuf.GeneratedMessageV3 implements ")
        .append(className)
        .append("OrBuilder {\n");

    String innerIndent = indent + "  ";

    // Private constructor
    sb.append(innerIndent).append("private ").append(className).append("() {}\n\n");

    // Field number constants
    int fieldNum = 1;
    for (FieldDecl field : msg.fields()) {
      String constName = toFieldNumberConstant(field.name().value());
      sb.append(innerIndent)
          .append("public static final int ")
          .append(constName)
          .append(" = ")
          .append(field.number())
          .append(";\n");
    }
    for (MapFieldDecl mapField : msg.mapFields()) {
      String constName = toFieldNumberConstant(mapField.name().value());
      sb.append(innerIndent)
          .append("public static final int ")
          .append(constName)
          .append(" = ")
          .append(mapField.number())
          .append(";\n");
    }
    if (!msg.fields().isEmpty() || !msg.mapFields().isEmpty()) {
      sb.append("\n");
    }

    // Static getDescriptor
    sb.append(innerIndent)
        .append(
            "public static final com.google.protobuf.Descriptors.Descriptor getDescriptor() {\n");
    sb.append(innerIndent).append("  ").append(STUB).append(";\n");
    sb.append(innerIndent).append("}\n\n");

    // getDefaultInstance()
    sb.append(innerIndent)
        .append("public static ")
        .append(className)
        .append(" getDefaultInstance() {\n");
    sb.append(innerIndent).append("  ").append(STUB).append(";\n");
    sb.append(innerIndent).append("}\n\n");

    // parser()
    sb.append(innerIndent)
        .append("public static com.google.protobuf.Parser<")
        .append(className)
        .append("> parser() {\n");
    sb.append(innerIndent).append("  ").append(STUB).append(";\n");
    sb.append(innerIndent).append("}\n\n");

    // getParserForType()
    sb.append(innerIndent).append("@Override\n");
    sb.append(innerIndent)
        .append("public com.google.protobuf.Parser<")
        .append(className)
        .append("> getParserForType() {\n");
    sb.append(innerIndent).append("  ").append(STUB).append(";\n");
    sb.append(innerIndent).append("}\n\n");

    // newBuilder()
    sb.append(innerIndent).append("public static Builder newBuilder() {\n");
    sb.append(innerIndent).append("  ").append(STUB).append(";\n");
    sb.append(innerIndent).append("}\n\n");

    // newBuilder(Message)
    sb.append(innerIndent)
        .append("public static Builder newBuilder(")
        .append(className)
        .append(" prototype) {\n");
    sb.append(innerIndent).append("  ").append(STUB).append(";\n");
    sb.append(innerIndent).append("}\n\n");

    // parseFrom methods - all variants
    generateParseFromMethods(sb, className, innerIndent);

    // parseDelimitedFrom methods
    sb.append(innerIndent)
        .append("public static ")
        .append(className)
        .append(" parseDelimitedFrom(java.io.InputStream input) throws java.io.IOException {\n");
    sb.append(innerIndent).append("  ").append(STUB).append(";\n");
    sb.append(innerIndent).append("}\n\n");

    sb.append(innerIndent)
        .append("public static ")
        .append(className)
        .append(
            " parseDelimitedFrom(java.io.InputStream input,"
                + " com.google.protobuf.ExtensionRegistryLite extensionRegistry) throws"
                + " java.io.IOException {\n");
    sb.append(innerIndent).append("  ").append(STUB).append(";\n");
    sb.append(innerIndent).append("}\n\n");

    // toBuilder()
    sb.append(innerIndent).append("@Override\n");
    sb.append(innerIndent).append("public Builder toBuilder() {\n");
    sb.append(innerIndent).append("  ").append(STUB).append(";\n");
    sb.append(innerIndent).append("}\n\n");

    // Required abstract method overrides from GeneratedMessageV3 and Message
    sb.append(innerIndent).append("@Override\n");
    sb.append(innerIndent).append("public Builder newBuilderForType() {\n");
    sb.append(innerIndent).append("  ").append(STUB).append(";\n");
    sb.append(innerIndent).append("}\n\n");

    sb.append(innerIndent).append("@Override\n");
    sb.append(innerIndent)
        .append(
            "protected Builder"
                + " newBuilderForType(com.google.protobuf.GeneratedMessageV3.BuilderParent parent)"
                + " {\n");
    sb.append(innerIndent).append("  ").append(STUB).append(";\n");
    sb.append(innerIndent).append("}\n\n");

    sb.append(innerIndent).append("@Override\n");
    sb.append(innerIndent)
        .append("public ")
        .append(className)
        .append(" getDefaultInstanceForType() {\n");
    sb.append(innerIndent).append("  ").append(STUB).append(";\n");
    sb.append(innerIndent).append("}\n\n");

    sb.append(innerIndent).append("@Override\n");
    sb.append(innerIndent)
        .append(
            "protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable"
                + " internalGetFieldAccessorTable() {\n");
    sb.append(innerIndent).append("  ").append(STUB).append(";\n");
    sb.append(innerIndent).append("}\n\n");

    // isInitialized
    sb.append(innerIndent).append("@Override\n");
    sb.append(innerIndent).append("public final boolean isInitialized() {\n");
    sb.append(innerIndent).append("  ").append(STUB).append(";\n");
    sb.append(innerIndent).append("}\n\n");

    // writeTo
    sb.append(innerIndent).append("@Override\n");
    sb.append(innerIndent)
        .append(
            "public void writeTo(com.google.protobuf.CodedOutputStream output) throws"
                + " java.io.IOException {\n");
    sb.append(innerIndent).append("  ").append(STUB).append(";\n");
    sb.append(innerIndent).append("}\n\n");

    // getSerializedSize
    sb.append(innerIndent).append("@Override\n");
    sb.append(innerIndent).append("public int getSerializedSize() {\n");
    sb.append(innerIndent).append("  ").append(STUB).append(";\n");
    sb.append(innerIndent).append("}\n\n");

    // equals
    sb.append(innerIndent).append("@Override\n");
    sb.append(innerIndent).append("public boolean equals(java.lang.Object obj) {\n");
    sb.append(innerIndent).append("  ").append(STUB).append(";\n");
    sb.append(innerIndent).append("}\n\n");

    // hashCode
    sb.append(innerIndent).append("@Override\n");
    sb.append(innerIndent).append("public int hashCode() {\n");
    sb.append(innerIndent).append("  ").append(STUB).append(";\n");
    sb.append(innerIndent).append("}\n\n");

    // Generate field accessors
    for (FieldDecl field : msg.fields()) {
      generateFieldAccessors(sb, field, innerIndent, msg);
    }

    // Generate map field accessors
    for (MapFieldDecl mapField : msg.mapFields()) {
      generateMapFieldAccessors(sb, mapField, innerIndent);
    }

    // Generate oneof accessors
    for (OneofDecl oneof : msg.oneofs()) {
      generateOneofAccessors(sb, oneof, innerIndent);
    }

    // Generate nested messages (OrBuilder first, then message class)
    for (MessageDecl nested : msg.nestedMessages()) {
      generateMessageOrBuilder(sb, nested, innerIndent, fullClassName);
      generateMessage(sb, nested, innerIndent, fullClassName);
    }

    // Generate nested enums
    for (EnumDecl nested : msg.nestedEnums()) {
      generateEnum(sb, nested, innerIndent);
    }

    // Builder class
    generateBuilder(sb, msg, innerIndent);

    sb.append(indent).append("}\n\n");
  }

  /** Generate parseFrom method variants. */
  private void generateParseFromMethods(StringBuilder sb, String className, String indent) {
    // parseFrom(byte[])
    sb.append(indent)
        .append("public static ")
        .append(className)
        .append(
            " parseFrom(byte[] data) throws com.google.protobuf.InvalidProtocolBufferException"
                + " {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");

    // parseFrom(byte[], ExtensionRegistryLite)
    sb.append(indent)
        .append("public static ")
        .append(className)
        .append(
            " parseFrom(byte[] data, com.google.protobuf.ExtensionRegistryLite extensionRegistry)"
                + " throws com.google.protobuf.InvalidProtocolBufferException {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");

    // parseFrom(ByteString)
    sb.append(indent)
        .append("public static ")
        .append(className)
        .append(
            " parseFrom(com.google.protobuf.ByteString data) throws"
                + " com.google.protobuf.InvalidProtocolBufferException {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");

    // parseFrom(ByteString, ExtensionRegistryLite)
    sb.append(indent)
        .append("public static ")
        .append(className)
        .append(
            " parseFrom(com.google.protobuf.ByteString data,"
                + " com.google.protobuf.ExtensionRegistryLite extensionRegistry) throws"
                + " com.google.protobuf.InvalidProtocolBufferException {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");

    // parseFrom(InputStream)
    sb.append(indent)
        .append("public static ")
        .append(className)
        .append(" parseFrom(java.io.InputStream input) throws java.io.IOException {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");

    // parseFrom(InputStream, ExtensionRegistryLite)
    sb.append(indent)
        .append("public static ")
        .append(className)
        .append(
            " parseFrom(java.io.InputStream input, com.google.protobuf.ExtensionRegistryLite"
                + " extensionRegistry) throws java.io.IOException {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");

    // parseFrom(CodedInputStream)
    sb.append(indent)
        .append("public static ")
        .append(className)
        .append(
            " parseFrom(com.google.protobuf.CodedInputStream input) throws java.io.IOException"
                + " {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");

    // parseFrom(CodedInputStream, ExtensionRegistryLite)
    sb.append(indent)
        .append("public static ")
        .append(className)
        .append(
            " parseFrom(com.google.protobuf.CodedInputStream input,"
                + " com.google.protobuf.ExtensionRegistryLite extensionRegistry) throws"
                + " java.io.IOException {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");

    // parseFrom(ByteBuffer)
    sb.append(indent)
        .append("public static ")
        .append(className)
        .append(
            " parseFrom(java.nio.ByteBuffer data) throws"
                + " com.google.protobuf.InvalidProtocolBufferException {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");

    // parseFrom(ByteBuffer, ExtensionRegistryLite)
    sb.append(indent)
        .append("public static ")
        .append(className)
        .append(
            " parseFrom(java.nio.ByteBuffer data, com.google.protobuf.ExtensionRegistryLite"
                + " extensionRegistry) throws com.google.protobuf.InvalidProtocolBufferException"
                + " {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");
  }

  /** Convert field name to UPPER_SNAKE_CASE field number constant. */
  private String toFieldNumberConstant(String fieldName) {
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < fieldName.length(); i++) {
      char c = fieldName.charAt(i);
      if (Character.isUpperCase(c) && i > 0) {
        result.append('_');
      }
      result.append(Character.toUpperCase(c));
    }
    result.append("_FIELD_NUMBER");
    return result.toString();
  }

  private void generateFieldAccessors(
      StringBuilder sb, FieldDecl field, String indent, MessageDecl parentMsg) {
    String fieldName = field.name().value();
    String typeName = field.type().fullName();
    String javaType = JavaTypeMapper.toJavaType(typeName);
    String getterName = JavaTypeMapper.toGetterName(fieldName);
    String hasName = JavaTypeMapper.toHasMethodName(fieldName);
    boolean isRepeated = field.modifier() == FieldModifier.REPEATED;
    boolean isMessageType =
        !JavaTypeMapper.isScalarType(typeName) && !isEnumType(typeName, parentMsg);

    if (isRepeated) {
      // List getter
      sb.append(indent)
          .append("public java.util.List<")
          .append(JavaTypeMapper.toBoxedJavaType(typeName))
          .append("> ");
      sb.append(getterName).append("List() {\n");
      sb.append(indent).append("  ").append(STUB).append(";\n");
      sb.append(indent).append("}\n\n");

      // Count getter
      sb.append(indent).append("public int ").append(getterName).append("Count() {\n");
      sb.append(indent).append("  ").append(STUB).append(";\n");
      sb.append(indent).append("}\n\n");

      // Index getter
      sb.append(indent)
          .append("public ")
          .append(javaType)
          .append(" ")
          .append(getterName)
          .append("(int index) {\n");
      sb.append(indent).append("  ").append(STUB).append(";\n");
      sb.append(indent).append("}\n\n");

      // OrBuilder getters for repeated message fields
      if (isMessageType) {
        sb.append(indent)
            .append("public java.util.List<? extends ")
            .append(javaType)
            .append("OrBuilder> ");
        sb.append(getterName).append("OrBuilderList() {\n");
        sb.append(indent).append("  ").append(STUB).append(";\n");
        sb.append(indent).append("}\n\n");

        sb.append(indent)
            .append("public ")
            .append(javaType)
            .append("OrBuilder ")
            .append(getterName)
            .append("OrBuilder(int index) {\n");
        sb.append(indent).append("  ").append(STUB).append(";\n");
        sb.append(indent).append("}\n\n");
      }
    } else {
      // Has method (for optional fields and message types)
      if (field.modifier() == FieldModifier.OPTIONAL || !JavaTypeMapper.isScalarType(typeName)) {
        sb.append(indent).append("public boolean ").append(hasName).append("() {\n");
        sb.append(indent).append("  ").append(STUB).append(";\n");
        sb.append(indent).append("}\n\n");
      }

      // Getter
      sb.append(indent)
          .append("public ")
          .append(javaType)
          .append(" ")
          .append(getterName)
          .append("() {\n");
      sb.append(indent).append("  ").append(STUB).append(";\n");
      sb.append(indent).append("}\n\n");

      // ByteString getter for string fields
      if ("string".equals(typeName)) {
        sb.append(indent)
            .append("public com.google.protobuf.ByteString ")
            .append(getterName)
            .append("Bytes() {\n");
        sb.append(indent).append("  ").append(STUB).append(";\n");
        sb.append(indent).append("}\n\n");
      }

      // OrBuilder getter for message fields
      if (isMessageType) {
        sb.append(indent)
            .append("public ")
            .append(javaType)
            .append("OrBuilder ")
            .append(getterName)
            .append("OrBuilder() {\n");
        sb.append(indent).append("  ").append(STUB).append(";\n");
        sb.append(indent).append("}\n\n");
      }
    }
  }

  private void generateMapFieldAccessors(StringBuilder sb, MapFieldDecl mapField, String indent) {
    String fieldName = mapField.name().value();
    String keyType = JavaTypeMapper.toBoxedJavaType(mapField.keyType().fullName());
    String valueType = JavaTypeMapper.toBoxedJavaType(mapField.valueType().fullName());
    String getterName = JavaTypeMapper.toGetterName(fieldName);
    String capitalName = JavaTypeMapper.capitalize(JavaTypeMapper.toCamelCase(fieldName));

    // Map getter
    sb.append(indent)
        .append("public java.util.Map<")
        .append(keyType)
        .append(", ")
        .append(valueType)
        .append("> ");
    sb.append(getterName).append("Map() {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");

    // Count getter
    sb.append(indent).append("public int ").append(getterName).append("Count() {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");

    // Contains key
    sb.append(indent)
        .append("public boolean contains")
        .append(capitalName)
        .append("(")
        .append(keyType)
        .append(" key) {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");

    // Get or default
    sb.append(indent)
        .append("public ")
        .append(valueType)
        .append(" ")
        .append(getterName)
        .append("OrDefault(")
        .append(keyType)
        .append(" key, ")
        .append(valueType)
        .append(" defaultValue) {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");

    // Get or throw
    sb.append(indent)
        .append("public ")
        .append(valueType)
        .append(" ")
        .append(getterName)
        .append("OrThrow(")
        .append(keyType)
        .append(" key) {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");
  }

  private void generateOneofAccessors(StringBuilder sb, OneofDecl oneof, String indent) {
    String oneofName = oneof.name().value();
    String enumName = JavaTypeMapper.capitalize(JavaTypeMapper.toCamelCase(oneofName)) + "Case";

    // Oneof case enum
    sb.append(indent).append("public enum ").append(enumName).append(" {\n");
    for (FieldDecl field : oneof.fields()) {
      String enumValue = field.name().value().toUpperCase();
      sb.append(indent)
          .append("  ")
          .append(enumValue)
          .append("(")
          .append(field.number())
          .append("),\n");
    }
    sb.append(indent).append("  ").append(oneofName.toUpperCase()).append("_NOT_SET(0);\n");
    sb.append(indent).append("  private final int value;\n");
    sb.append(indent).append("  ").append(enumName).append("(int value) { this.value = value; }\n");
    sb.append(indent).append("}\n\n");

    // getXxxCase()
    sb.append(indent)
        .append("public ")
        .append(enumName)
        .append(" get")
        .append(enumName)
        .append("() {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");
  }

  private void generateBuilder(StringBuilder sb, MessageDecl msg, String indent) {
    String className = msg.name().value();

    sb.append(indent)
        .append(
            "public static final class Builder extends"
                + " com.google.protobuf.GeneratedMessageV3.Builder<Builder> {\n");

    String innerIndent = indent + "  ";

    // Private constructor
    sb.append(innerIndent).append("private Builder() {}\n\n");

    // build()
    sb.append(innerIndent).append("public ").append(className).append(" build() {\n");
    sb.append(innerIndent).append("  ").append(STUB).append(";\n");
    sb.append(innerIndent).append("}\n\n");

    // buildPartial()
    sb.append(innerIndent).append("public ").append(className).append(" buildPartial() {\n");
    sb.append(innerIndent).append("  ").append(STUB).append(";\n");
    sb.append(innerIndent).append("}\n\n");

    // clear()
    sb.append(innerIndent).append("public Builder clear() {\n");
    sb.append(innerIndent).append("  ").append(STUB).append(";\n");
    sb.append(innerIndent).append("}\n\n");

    // Required abstract method overrides from Builder
    sb.append(innerIndent).append("@Override\n");
    sb.append(innerIndent)
        .append("public ")
        .append(className)
        .append(" getDefaultInstanceForType() {\n");
    sb.append(innerIndent).append("  ").append(STUB).append(";\n");
    sb.append(innerIndent).append("}\n\n");

    sb.append(innerIndent).append("@Override\n");
    sb.append(innerIndent)
        .append(
            "protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable"
                + " internalGetFieldAccessorTable() {\n");
    sb.append(innerIndent).append("  ").append(STUB).append(";\n");
    sb.append(innerIndent).append("}\n\n");

    // clone() with covariant return type
    sb.append(innerIndent).append("@Override\n");
    sb.append(innerIndent).append("public Builder clone() {\n");
    sb.append(innerIndent).append("  ").append(STUB).append(";\n");
    sb.append(innerIndent).append("}\n\n");

    // getDescriptor() and getDescriptorForType()
    sb.append(innerIndent)
        .append(
            "public static final com.google.protobuf.Descriptors.Descriptor getDescriptor() {\n");
    sb.append(innerIndent).append("  ").append(STUB).append(";\n");
    sb.append(innerIndent).append("}\n\n");

    sb.append(innerIndent).append("@Override\n");
    sb.append(innerIndent)
        .append("public com.google.protobuf.Descriptors.Descriptor getDescriptorForType() {\n");
    sb.append(innerIndent).append("  ").append(STUB).append(";\n");
    sb.append(innerIndent).append("}\n\n");

    // isInitialized()
    sb.append(innerIndent).append("@Override\n");
    sb.append(innerIndent).append("public final boolean isInitialized() {\n");
    sb.append(innerIndent).append("  ").append(STUB).append(";\n");
    sb.append(innerIndent).append("}\n\n");

    // mergeFrom variants with covariant return types
    sb.append(innerIndent)
        .append("public Builder mergeFrom(")
        .append(className)
        .append(" other) {\n");
    sb.append(innerIndent).append("  ").append(STUB).append(";\n");
    sb.append(innerIndent).append("}\n\n");

    sb.append(innerIndent).append("@Override\n");
    sb.append(innerIndent)
        .append("public Builder mergeFrom(com.google.protobuf.Message other) {\n");
    sb.append(innerIndent).append("  ").append(STUB).append(";\n");
    sb.append(innerIndent).append("}\n\n");

    sb.append(innerIndent).append("@Override\n");
    sb.append(innerIndent)
        .append(
            "public Builder mergeFrom(com.google.protobuf.CodedInputStream input,"
                + " com.google.protobuf.ExtensionRegistryLite extensionRegistry) throws"
                + " java.io.IOException {\n");
    sb.append(innerIndent).append("  ").append(STUB).append(";\n");
    sb.append(innerIndent).append("}\n\n");

    // setUnknownFields / mergeUnknownFields with covariant return types
    sb.append(innerIndent).append("@Override\n");
    sb.append(innerIndent)
        .append(
            "public final Builder setUnknownFields(com.google.protobuf.UnknownFieldSet"
                + " unknownFields) {\n");
    sb.append(innerIndent).append("  ").append(STUB).append(";\n");
    sb.append(innerIndent).append("}\n\n");

    sb.append(innerIndent).append("@Override\n");
    sb.append(innerIndent)
        .append(
            "public final Builder mergeUnknownFields(com.google.protobuf.UnknownFieldSet"
                + " unknownFields) {\n");
    sb.append(innerIndent).append("  ").append(STUB).append(";\n");
    sb.append(innerIndent).append("}\n\n");

    // Field descriptor methods with covariant return types
    sb.append(innerIndent).append("@Override\n");
    sb.append(innerIndent)
        .append(
            "public Builder setField(com.google.protobuf.Descriptors.FieldDescriptor field,"
                + " java.lang.Object value) {\n");
    sb.append(innerIndent).append("  ").append(STUB).append(";\n");
    sb.append(innerIndent).append("}\n\n");

    sb.append(innerIndent).append("@Override\n");
    sb.append(innerIndent)
        .append(
            "public Builder clearField(com.google.protobuf.Descriptors.FieldDescriptor field) {\n");
    sb.append(innerIndent).append("  ").append(STUB).append(";\n");
    sb.append(innerIndent).append("}\n\n");

    sb.append(innerIndent).append("@Override\n");
    sb.append(innerIndent)
        .append(
            "public Builder clearOneof(com.google.protobuf.Descriptors.OneofDescriptor oneof) {\n");
    sb.append(innerIndent).append("  ").append(STUB).append(";\n");
    sb.append(innerIndent).append("}\n\n");

    sb.append(innerIndent).append("@Override\n");
    sb.append(innerIndent)
        .append(
            "public Builder setRepeatedField(com.google.protobuf.Descriptors.FieldDescriptor field,"
                + " int index, java.lang.Object value) {\n");
    sb.append(innerIndent).append("  ").append(STUB).append(";\n");
    sb.append(innerIndent).append("}\n\n");

    sb.append(innerIndent).append("@Override\n");
    sb.append(innerIndent)
        .append(
            "public Builder addRepeatedField(com.google.protobuf.Descriptors.FieldDescriptor field,"
                + " java.lang.Object value) {\n");
    sb.append(innerIndent).append("  ").append(STUB).append(";\n");
    sb.append(innerIndent).append("}\n\n");

    // Generate field setters/clearers
    for (FieldDecl field : msg.fields()) {
      generateBuilderFieldMethods(sb, field, innerIndent, msg);
    }

    // Generate oneof field setters/clearers
    for (OneofDecl oneof : msg.oneofs()) {
      for (FieldDecl field : oneof.fields()) {
        generateBuilderFieldMethods(sb, field, innerIndent, msg);
      }
    }

    // Generate map field methods
    for (MapFieldDecl mapField : msg.mapFields()) {
      generateBuilderMapFieldMethods(sb, mapField, innerIndent);
    }

    sb.append(indent).append("}\n\n");
  }

  private void generateBuilderFieldMethods(
      StringBuilder sb, FieldDecl field, String indent, MessageDecl parentMsg) {
    String fieldName = field.name().value();
    String typeName = field.type().fullName();
    String javaType = JavaTypeMapper.toJavaType(typeName);
    String setterName = JavaTypeMapper.toSetterName(fieldName);
    String getterName = JavaTypeMapper.toGetterName(fieldName);
    String capitalName = JavaTypeMapper.capitalize(JavaTypeMapper.toCamelCase(fieldName));
    String clearName = "clear" + capitalName;
    boolean isRepeated = field.modifier() == FieldModifier.REPEATED;
    boolean isMessageType =
        !JavaTypeMapper.isScalarType(typeName) && !isEnumType(typeName, parentMsg);

    if (isRepeated) {
      // addXxx
      sb.append(indent)
          .append("public Builder add")
          .append(capitalName)
          .append("(")
          .append(javaType)
          .append(" value) {\n");
      sb.append(indent).append("  ").append(STUB).append(";\n");
      sb.append(indent).append("}\n\n");

      // addAllXxx
      sb.append(indent)
          .append("public Builder addAll")
          .append(capitalName)
          .append("(java.lang.Iterable<? extends ")
          .append(JavaTypeMapper.toBoxedJavaType(typeName))
          .append("> values) {\n");
      sb.append(indent).append("  ").append(STUB).append(";\n");
      sb.append(indent).append("}\n\n");

      // clearXxx
      sb.append(indent).append("public Builder ").append(clearName).append("() {\n");
      sb.append(indent).append("  ").append(STUB).append(";\n");
      sb.append(indent).append("}\n\n");

      // Message-specific builder methods for repeated message fields
      if (isMessageType) {
        // addXxx(Builder)
        sb.append(indent)
            .append("public Builder add")
            .append(capitalName)
            .append("(")
            .append(javaType)
            .append(".Builder builderForValue) {\n");
        sb.append(indent).append("  ").append(STUB).append(";\n");
        sb.append(indent).append("}\n\n");

        // setXxx(int, Builder)
        sb.append(indent)
            .append("public Builder set")
            .append(capitalName)
            .append("(int index, ")
            .append(javaType)
            .append(".Builder builderForValue) {\n");
        sb.append(indent).append("  ").append(STUB).append(";\n");
        sb.append(indent).append("}\n\n");

        // addXxxBuilder()
        sb.append(indent)
            .append("public ")
            .append(javaType)
            .append(".Builder add")
            .append(capitalName)
            .append("Builder() {\n");
        sb.append(indent).append("  ").append(STUB).append(";\n");
        sb.append(indent).append("}\n\n");

        // addXxxBuilder(int)
        sb.append(indent)
            .append("public ")
            .append(javaType)
            .append(".Builder add")
            .append(capitalName)
            .append("Builder(int index) {\n");
        sb.append(indent).append("  ").append(STUB).append(";\n");
        sb.append(indent).append("}\n\n");

        // getXxxBuilderList()
        sb.append(indent)
            .append("public java.util.List<")
            .append(javaType)
            .append(".Builder> get")
            .append(capitalName)
            .append("BuilderList() {\n");
        sb.append(indent).append("  ").append(STUB).append(";\n");
        sb.append(indent).append("}\n\n");
      }
    } else {
      // setXxx
      sb.append(indent)
          .append("public Builder ")
          .append(setterName)
          .append("(")
          .append(javaType)
          .append(" value) {\n");
      sb.append(indent).append("  ").append(STUB).append(";\n");
      sb.append(indent).append("}\n\n");

      // clearXxx
      sb.append(indent).append("public Builder ").append(clearName).append("() {\n");
      sb.append(indent).append("  ").append(STUB).append(";\n");
      sb.append(indent).append("}\n\n");

      // Message-specific builder methods
      if (isMessageType) {
        // setXxx(Builder)
        sb.append(indent)
            .append("public Builder ")
            .append(setterName)
            .append("(")
            .append(javaType)
            .append(".Builder builderForValue) {\n");
        sb.append(indent).append("  ").append(STUB).append(";\n");
        sb.append(indent).append("}\n\n");

        // mergeXxx
        sb.append(indent)
            .append("public Builder merge")
            .append(capitalName)
            .append("(")
            .append(javaType)
            .append(" value) {\n");
        sb.append(indent).append("  ").append(STUB).append(";\n");
        sb.append(indent).append("}\n\n");

        // getXxxBuilder
        sb.append(indent)
            .append("public ")
            .append(javaType)
            .append(".Builder get")
            .append(capitalName)
            .append("Builder() {\n");
        sb.append(indent).append("  ").append(STUB).append(";\n");
        sb.append(indent).append("}\n\n");
      }
    }

    // Getter (builder has same getters as message)
    if (isRepeated) {
      sb.append(indent)
          .append("public java.util.List<")
          .append(JavaTypeMapper.toBoxedJavaType(typeName))
          .append("> ");
      sb.append(getterName).append("List() {\n");
      sb.append(indent).append("  ").append(STUB).append(";\n");
      sb.append(indent).append("}\n\n");

      // OrBuilder getters for repeated message fields
      if (isMessageType) {
        sb.append(indent)
            .append("public java.util.List<? extends ")
            .append(javaType)
            .append("OrBuilder> ");
        sb.append(getterName).append("OrBuilderList() {\n");
        sb.append(indent).append("  ").append(STUB).append(";\n");
        sb.append(indent).append("}\n\n");

        sb.append(indent)
            .append("public ")
            .append(javaType)
            .append("OrBuilder ")
            .append(getterName)
            .append("OrBuilder(int index) {\n");
        sb.append(indent).append("  ").append(STUB).append(";\n");
        sb.append(indent).append("}\n\n");
      }
    } else {
      sb.append(indent)
          .append("public ")
          .append(javaType)
          .append(" ")
          .append(getterName)
          .append("() {\n");
      sb.append(indent).append("  ").append(STUB).append(";\n");
      sb.append(indent).append("}\n\n");

      // ByteString getter and setter for string fields
      if ("string".equals(typeName)) {
        sb.append(indent)
            .append("public com.google.protobuf.ByteString ")
            .append(getterName)
            .append("Bytes() {\n");
        sb.append(indent).append("  ").append(STUB).append(";\n");
        sb.append(indent).append("}\n\n");

        sb.append(indent)
            .append("public Builder set")
            .append(capitalName)
            .append("Bytes(com.google.protobuf.ByteString value) {\n");
        sb.append(indent).append("  ").append(STUB).append(";\n");
        sb.append(indent).append("}\n\n");
      }

      // hasXxx and OrBuilder getter for message fields in builder
      if (isMessageType) {
        sb.append(indent).append("public boolean has").append(capitalName).append("() {\n");
        sb.append(indent).append("  ").append(STUB).append(";\n");
        sb.append(indent).append("}\n\n");

        sb.append(indent)
            .append("public ")
            .append(javaType)
            .append("OrBuilder get")
            .append(capitalName)
            .append("OrBuilder() {\n");
        sb.append(indent).append("  ").append(STUB).append(";\n");
        sb.append(indent).append("}\n\n");
      }
    }
  }

  private void generateBuilderMapFieldMethods(
      StringBuilder sb, MapFieldDecl mapField, String indent) {
    String fieldName = mapField.name().value();
    String keyType = JavaTypeMapper.toBoxedJavaType(mapField.keyType().fullName());
    String valueType = JavaTypeMapper.toBoxedJavaType(mapField.valueType().fullName());
    String capitalName = JavaTypeMapper.capitalize(JavaTypeMapper.toCamelCase(fieldName));

    // putXxx
    sb.append(indent)
        .append("public Builder put")
        .append(capitalName)
        .append("(")
        .append(keyType)
        .append(" key, ")
        .append(valueType)
        .append(" value) {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");

    // putAllXxx
    sb.append(indent)
        .append("public Builder putAll")
        .append(capitalName)
        .append("(java.util.Map<")
        .append(keyType)
        .append(", ")
        .append(valueType)
        .append("> values) {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");

    // clearXxx
    sb.append(indent).append("public Builder clear").append(capitalName).append("() {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");

    // removeXxx
    sb.append(indent)
        .append("public Builder remove")
        .append(capitalName)
        .append("(")
        .append(keyType)
        .append(" key) {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");
  }

  private void generateEnum(StringBuilder sb, EnumDecl e, String indent) {
    String enumName = e.name().value();

    sb.append(indent)
        .append("public enum ")
        .append(enumName)
        .append(" implements com.google.protobuf.ProtocolMessageEnum {\n");

    String innerIndent = indent + "  ";

    // Enum values
    for (EnumValueDecl value : e.values()) {
      sb.append(innerIndent)
          .append(value.name().value())
          .append("(")
          .append(value.number())
          .append("),\n");
    }
    sb.append(innerIndent).append("UNRECOGNIZED(-1);\n\n");

    // Value field
    sb.append(innerIndent).append("private final int value;\n\n");

    // Constructor
    sb.append(innerIndent).append(enumName).append("(int value) {\n");
    sb.append(innerIndent).append("  this.value = value;\n");
    sb.append(innerIndent).append("}\n\n");

    // getNumber()
    sb.append(innerIndent).append("public int getNumber() {\n");
    sb.append(innerIndent).append("  return value;\n");
    sb.append(innerIndent).append("}\n\n");

    // forNumber(int)
    sb.append(innerIndent)
        .append("public static ")
        .append(enumName)
        .append(" forNumber(int value) {\n");
    sb.append(innerIndent).append("  ").append(STUB).append(";\n");
    sb.append(innerIndent).append("}\n\n");

    // getValueDescriptor()
    sb.append(innerIndent)
        .append(
            "public com.google.protobuf.Descriptors.EnumValueDescriptor getValueDescriptor() {\n");
    sb.append(innerIndent).append("  ").append(STUB).append(";\n");
    sb.append(innerIndent).append("}\n\n");

    // getDescriptorForType()
    sb.append(innerIndent)
        .append("public com.google.protobuf.Descriptors.EnumDescriptor getDescriptorForType() {\n");
    sb.append(innerIndent).append("  ").append(STUB).append(";\n");
    sb.append(innerIndent).append("}\n\n");

    sb.append(indent).append("}\n\n");
  }

  private String getJavaPackage(ProtoFile file) {
    // First check for java_package option
    for (OptionDecl option : file.options()) {
      String optionName =
          option.name().stream()
              .map(ident -> ident.value())
              .reduce((a, b) -> a + "." + b)
              .orElse("");
      if ("java_package".equals(optionName)) {
        Proto value = option.value();
        if (value instanceof Ident) {
          String v = ((Ident) value).value();
          // Remove quotes if present
          if (v.startsWith("\"") && v.endsWith("\"")) {
            return v.substring(1, v.length() - 1);
          }
          return v;
        }
      }
    }

    // Fall back to proto package
    return file.pkg().map(PackageDecl::fullName).orElse("");
  }

  private String getOuterClassName(ProtoFile file) {
    // First check for java_outer_classname option
    for (OptionDecl option : file.options()) {
      String optionName =
          option.name().stream()
              .map(ident -> ident.value())
              .reduce((a, b) -> a + "." + b)
              .orElse("");
      if ("java_outer_classname".equals(optionName)) {
        Proto value = option.value();
        if (value instanceof Ident) {
          String v = ((Ident) value).value();
          // Remove quotes if present
          if (v.startsWith("\"") && v.endsWith("\"")) {
            return v.substring(1, v.length() - 1);
          }
          return v;
        }
      }
    }

    // Generate from filename (not available here, use default)
    return "OuterClass";
  }

  private boolean getJavaMultipleFiles(ProtoFile file) {
    for (OptionDecl option : file.options()) {
      String optionName =
          option.name().stream()
              .map(ident -> ident.value())
              .reduce((a, b) -> a + "." + b)
              .orElse("");
      if ("java_multiple_files".equals(optionName)) {
        Proto value = option.value();
        if (value instanceof Ident) {
          String v = ((Ident) value).value();
          return "true".equals(v);
        }
      }
    }
    return false;
  }

  /** Generate the body of a message class (everything between the class declaration braces). */
  private void generateMessageBody(
      StringBuilder sb, MessageDecl msg, String indent, String fullClassName) {
    String className = msg.name().value();

    // Private constructor
    sb.append(indent).append("private ").append(className).append("() {}\n\n");

    // Field number constants
    for (FieldDecl field : msg.fields()) {
      String constName = toFieldNumberConstant(field.name().value());
      sb.append(indent)
          .append("public static final int ")
          .append(constName)
          .append(" = ")
          .append(field.number())
          .append(";\n");
    }
    for (MapFieldDecl mapField : msg.mapFields()) {
      String constName = toFieldNumberConstant(mapField.name().value());
      sb.append(indent)
          .append("public static final int ")
          .append(constName)
          .append(" = ")
          .append(mapField.number())
          .append(";\n");
    }
    if (!msg.fields().isEmpty() || !msg.mapFields().isEmpty()) {
      sb.append("\n");
    }

    // Static getDescriptor
    sb.append(indent)
        .append(
            "public static final com.google.protobuf.Descriptors.Descriptor getDescriptor() {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");

    // getDefaultInstance()
    sb.append(indent)
        .append("public static ")
        .append(className)
        .append(" getDefaultInstance() {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");

    // parser()
    sb.append(indent)
        .append("public static com.google.protobuf.Parser<")
        .append(className)
        .append("> parser() {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");

    // getParserForType()
    sb.append(indent).append("@Override\n");
    sb.append(indent)
        .append("public com.google.protobuf.Parser<")
        .append(className)
        .append("> getParserForType() {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");

    // newBuilder()
    sb.append(indent).append("public static Builder newBuilder() {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");

    // newBuilder(Message)
    sb.append(indent)
        .append("public static Builder newBuilder(")
        .append(className)
        .append(" prototype) {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");

    // parseFrom methods - all variants
    generateParseFromMethods(sb, className, indent);

    // parseDelimitedFrom methods
    sb.append(indent)
        .append("public static ")
        .append(className)
        .append(" parseDelimitedFrom(java.io.InputStream input) throws java.io.IOException {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");

    sb.append(indent)
        .append("public static ")
        .append(className)
        .append(
            " parseDelimitedFrom(java.io.InputStream input,"
                + " com.google.protobuf.ExtensionRegistryLite extensionRegistry) throws"
                + " java.io.IOException {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");

    // toBuilder()
    sb.append(indent).append("@Override\n");
    sb.append(indent).append("public Builder toBuilder() {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");

    // Required abstract method overrides from GeneratedMessageV3 and Message
    sb.append(indent).append("@Override\n");
    sb.append(indent).append("public Builder newBuilderForType() {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");

    sb.append(indent).append("@Override\n");
    sb.append(indent)
        .append(
            "protected Builder"
                + " newBuilderForType(com.google.protobuf.GeneratedMessageV3.BuilderParent parent)"
                + " {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");

    sb.append(indent).append("@Override\n");
    sb.append(indent)
        .append("public ")
        .append(className)
        .append(" getDefaultInstanceForType() {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");

    sb.append(indent).append("@Override\n");
    sb.append(indent)
        .append(
            "protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable"
                + " internalGetFieldAccessorTable() {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");

    // isInitialized
    sb.append(indent).append("@Override\n");
    sb.append(indent).append("public final boolean isInitialized() {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");

    // writeTo
    sb.append(indent).append("@Override\n");
    sb.append(indent)
        .append(
            "public void writeTo(com.google.protobuf.CodedOutputStream output) throws"
                + " java.io.IOException {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");

    // getSerializedSize
    sb.append(indent).append("@Override\n");
    sb.append(indent).append("public int getSerializedSize() {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");

    // equals
    sb.append(indent).append("@Override\n");
    sb.append(indent).append("public boolean equals(java.lang.Object obj) {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");

    // hashCode
    sb.append(indent).append("@Override\n");
    sb.append(indent).append("public int hashCode() {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");

    // Generate field accessors
    for (FieldDecl field : msg.fields()) {
      generateFieldAccessors(sb, field, indent, msg);
    }

    // Generate map field accessors
    for (MapFieldDecl mapField : msg.mapFields()) {
      generateMapFieldAccessors(sb, mapField, indent);
    }

    // Generate oneof accessors
    for (OneofDecl oneof : msg.oneofs()) {
      generateOneofAccessors(sb, oneof, indent);
    }

    // Generate nested messages (as static nested classes)
    for (MessageDecl nested : msg.nestedMessages()) {
      generateMessageOrBuilder(sb, nested, indent, fullClassName);
      generateMessage(sb, nested, indent, fullClassName);
    }

    // Generate nested enums
    for (EnumDecl nested : msg.nestedEnums()) {
      generateEnum(sb, nested, indent);
    }

    // Builder class
    generateBuilder(sb, msg, indent);
  }

  /** Generate the body of an enum (everything between the enum declaration braces). */
  private void generateEnumBody(StringBuilder sb, EnumDecl e, String indent) {
    String enumName = e.name().value();

    // Enum values
    for (EnumValueDecl value : e.values()) {
      sb.append(indent)
          .append(value.name().value())
          .append("(")
          .append(value.number())
          .append("),\n");
    }
    sb.append(indent).append("UNRECOGNIZED(-1);\n\n");

    // *_VALUE static constants
    for (EnumValueDecl value : e.values()) {
      sb.append(indent)
          .append("public static final int ")
          .append(value.name().value())
          .append("_VALUE = ")
          .append(value.number())
          .append(";\n");
    }
    sb.append("\n");

    // Value field
    sb.append(indent).append("private final int value;\n\n");

    // Constructor
    sb.append(indent).append(enumName).append("(int value) {\n");
    sb.append(indent).append("  this.value = value;\n");
    sb.append(indent).append("}\n\n");

    // getNumber()
    sb.append(indent).append("public final int getNumber() {\n");
    sb.append(indent).append("  return value;\n");
    sb.append(indent).append("}\n\n");

    // valueOf(int) - deprecated
    sb.append(indent).append("@java.lang.Deprecated\n");
    sb.append(indent).append("public static ").append(enumName).append(" valueOf(int value) {\n");
    sb.append(indent).append("  return forNumber(value);\n");
    sb.append(indent).append("}\n\n");

    // forNumber(int)
    sb.append(indent).append("public static ").append(enumName).append(" forNumber(int value) {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");

    // internalGetValueMap()
    sb.append(indent)
        .append("public static com.google.protobuf.Internal.EnumLiteMap<")
        .append(enumName)
        .append("> internalGetValueMap() {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");

    // getValueDescriptor()
    sb.append(indent)
        .append(
            "public final com.google.protobuf.Descriptors.EnumValueDescriptor getValueDescriptor()"
                + " {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");

    // getDescriptorForType()
    sb.append(indent)
        .append(
            "public final com.google.protobuf.Descriptors.EnumDescriptor getDescriptorForType()"
                + " {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");

    // static getDescriptor()
    sb.append(indent)
        .append(
            "public static final com.google.protobuf.Descriptors.EnumDescriptor getDescriptor()"
                + " {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");

    // valueOf(EnumValueDescriptor)
    sb.append(indent)
        .append("public static ")
        .append(enumName)
        .append(" valueOf(com.google.protobuf.Descriptors.EnumValueDescriptor desc) {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");
  }

  /** Generate service as nested class (for single file mode). */
  private void generateService(StringBuilder sb, ServiceDecl svc, String indent) {
    sb.append(indent)
        .append("public static final class ")
        .append(svc.name().value())
        .append("Grpc {\n");
    generateServiceBody(sb, svc, indent + "  ");
    sb.append(indent).append("}\n\n");
  }

  /** Generate service as top-level class (for multiple files mode). */
  private void generateServiceClass(StringBuilder sb, ServiceDecl svc, String indent) {
    sb.append(indent).append("public final class ").append(svc.name().value()).append("Grpc {\n");
    generateServiceBody(sb, svc, indent + "  ");
    sb.append(indent).append("}\n");
  }

  /** Generate service body (shared between nested and top-level). */
  private void generateServiceBody(StringBuilder sb, ServiceDecl svc, String indent) {
    String serviceName = svc.name().value();

    // Private constructor
    sb.append(indent).append("private ").append(serviceName).append("Grpc() {}\n\n");

    // Service name constant
    sb.append(indent)
        .append("public static final String SERVICE_NAME = \"")
        .append(serviceName)
        .append("\";\n\n");

    // Method descriptors for each RPC
    for (RpcDecl rpc : svc.rpcs()) {
      String methodName = rpc.name().value();
      String inputType = rpc.inputType().fullName();
      String outputType = rpc.outputType().fullName();

      // getXxxMethod()
      sb.append(indent)
          .append("public static io.grpc.MethodDescriptor<")
          .append(inputType)
          .append(", ")
          .append(outputType)
          .append("> get");
      sb.append(methodName).append("Method() {\n");
      sb.append(indent).append("  ").append(STUB).append(";\n");
      sb.append(indent).append("}\n\n");
    }

    // Stub classes

    // ImplBase (abstract service implementation)
    sb.append(indent)
        .append("public static abstract class ")
        .append(serviceName)
        .append("ImplBase implements io.grpc.BindableService {\n");
    for (RpcDecl rpc : svc.rpcs()) {
      String methodName = decapitalize(rpc.name().value());
      String inputType = rpc.inputType().fullName();
      String outputType = rpc.outputType().fullName();

      if (rpc.clientStreaming() && rpc.serverStreaming()) {
        // Bidi streaming
        sb.append(indent)
            .append("  public io.grpc.stub.StreamObserver<")
            .append(inputType)
            .append("> ");
        sb.append(methodName)
            .append("(io.grpc.stub.StreamObserver<")
            .append(outputType)
            .append("> responseObserver) {\n");
        sb.append(indent).append("    ").append(STUB).append(";\n");
        sb.append(indent).append("  }\n\n");
      } else if (rpc.serverStreaming()) {
        // Server streaming
        sb.append(indent).append("  public void ").append(methodName).append("(");
        sb.append(inputType)
            .append(" request, io.grpc.stub.StreamObserver<")
            .append(outputType)
            .append("> responseObserver) {\n");
        sb.append(indent).append("    ").append(STUB).append(";\n");
        sb.append(indent).append("  }\n\n");
      } else if (rpc.clientStreaming()) {
        // Client streaming
        sb.append(indent)
            .append("  public io.grpc.stub.StreamObserver<")
            .append(inputType)
            .append("> ");
        sb.append(methodName)
            .append("(io.grpc.stub.StreamObserver<")
            .append(outputType)
            .append("> responseObserver) {\n");
        sb.append(indent).append("    ").append(STUB).append(";\n");
        sb.append(indent).append("  }\n\n");
      } else {
        // Unary
        sb.append(indent).append("  public void ").append(methodName).append("(");
        sb.append(inputType)
            .append(" request, io.grpc.stub.StreamObserver<")
            .append(outputType)
            .append("> responseObserver) {\n");
        sb.append(indent).append("    ").append(STUB).append(";\n");
        sb.append(indent).append("  }\n\n");
      }
    }
    sb.append(indent).append("  @Override\n");
    sb.append(indent).append("  public io.grpc.ServerServiceDefinition bindService() {\n");
    sb.append(indent).append("    ").append(STUB).append(";\n");
    sb.append(indent).append("  }\n");
    sb.append(indent).append("}\n\n");

    // Stub (blocking client)
    sb.append(indent)
        .append("public static final class ")
        .append(serviceName)
        .append("BlockingStub extends io.grpc.stub.AbstractBlockingStub<")
        .append(serviceName)
        .append("BlockingStub> {\n");
    sb.append(indent)
        .append("  private ")
        .append(serviceName)
        .append(
            "BlockingStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {"
                + " super(channel, callOptions); }\n");
    sb.append(indent).append("  @Override\n");
    sb.append(indent)
        .append("  protected ")
        .append(serviceName)
        .append("BlockingStub build(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {\n");
    sb.append(indent).append("    ").append(STUB).append(";\n");
    sb.append(indent).append("  }\n");
    // Add blocking methods for unary and server-streaming RPCs
    for (RpcDecl rpc : svc.rpcs()) {
      if (!rpc.clientStreaming()) {
        String methodName = decapitalize(rpc.name().value());
        String inputType = rpc.inputType().fullName();
        String outputType = rpc.outputType().fullName();

        if (rpc.serverStreaming()) {
          sb.append(indent).append("  public java.util.Iterator<").append(outputType).append("> ");
          sb.append(methodName).append("(").append(inputType).append(" request) {\n");
          sb.append(indent).append("    ").append(STUB).append(";\n");
          sb.append(indent).append("  }\n");
        } else {
          sb.append(indent).append("  public ").append(outputType).append(" ");
          sb.append(methodName).append("(").append(inputType).append(" request) {\n");
          sb.append(indent).append("    ").append(STUB).append(";\n");
          sb.append(indent).append("  }\n");
        }
      }
    }
    sb.append(indent).append("}\n\n");

    // FutureStub (async client)
    sb.append(indent)
        .append("public static final class ")
        .append(serviceName)
        .append("FutureStub extends io.grpc.stub.AbstractFutureStub<")
        .append(serviceName)
        .append("FutureStub> {\n");
    sb.append(indent)
        .append("  private ")
        .append(serviceName)
        .append(
            "FutureStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) { super(channel,"
                + " callOptions); }\n");
    sb.append(indent).append("  @Override\n");
    sb.append(indent)
        .append("  protected ")
        .append(serviceName)
        .append("FutureStub build(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {\n");
    sb.append(indent).append("    ").append(STUB).append(";\n");
    sb.append(indent).append("  }\n");
    // Add future methods for unary RPCs only
    for (RpcDecl rpc : svc.rpcs()) {
      if (!rpc.clientStreaming() && !rpc.serverStreaming()) {
        String methodName = decapitalize(rpc.name().value());
        String inputType = rpc.inputType().fullName();
        String outputType = rpc.outputType().fullName();

        sb.append(indent)
            .append("  public com.google.common.util.concurrent.ListenableFuture<")
            .append(outputType)
            .append("> ");
        sb.append(methodName).append("(").append(inputType).append(" request) {\n");
        sb.append(indent).append("    ").append(STUB).append(";\n");
        sb.append(indent).append("  }\n");
      }
    }
    sb.append(indent).append("}\n\n");

    // Stub (async client)
    sb.append(indent)
        .append("public static final class ")
        .append(serviceName)
        .append("Stub extends io.grpc.stub.AbstractAsyncStub<")
        .append(serviceName)
        .append("Stub> {\n");
    sb.append(indent)
        .append("  private ")
        .append(serviceName)
        .append(
            "Stub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) { super(channel,"
                + " callOptions); }\n");
    sb.append(indent).append("  @Override\n");
    sb.append(indent)
        .append("  protected ")
        .append(serviceName)
        .append("Stub build(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {\n");
    sb.append(indent).append("    ").append(STUB).append(";\n");
    sb.append(indent).append("  }\n");
    for (RpcDecl rpc : svc.rpcs()) {
      String methodName = decapitalize(rpc.name().value());
      String inputType = rpc.inputType().fullName();
      String outputType = rpc.outputType().fullName();

      if (rpc.clientStreaming()) {
        sb.append(indent)
            .append("  public io.grpc.stub.StreamObserver<")
            .append(inputType)
            .append("> ");
        sb.append(methodName)
            .append("(io.grpc.stub.StreamObserver<")
            .append(outputType)
            .append("> responseObserver) {\n");
        sb.append(indent).append("    ").append(STUB).append(";\n");
        sb.append(indent).append("  }\n");
      } else {
        sb.append(indent).append("  public void ").append(methodName).append("(");
        sb.append(inputType)
            .append(" request, io.grpc.stub.StreamObserver<")
            .append(outputType)
            .append("> responseObserver) {\n");
        sb.append(indent).append("    ").append(STUB).append(";\n");
        sb.append(indent).append("  }\n");
      }
    }
    sb.append(indent).append("}\n\n");

    // Static factory methods
    sb.append(indent)
        .append("public static ")
        .append(serviceName)
        .append("Stub newStub(io.grpc.Channel channel) {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");

    sb.append(indent)
        .append("public static ")
        .append(serviceName)
        .append("BlockingStub newBlockingStub(io.grpc.Channel channel) {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");

    sb.append(indent)
        .append("public static ")
        .append(serviceName)
        .append("FutureStub newFutureStub(io.grpc.Channel channel) {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n\n");

    // getServiceDescriptor()
    sb.append(indent).append("public static io.grpc.ServiceDescriptor getServiceDescriptor() {\n");
    sb.append(indent).append("  ").append(STUB).append(";\n");
    sb.append(indent).append("}\n");
  }

  private String decapitalize(String s) {
    if (s == null || s.isEmpty()) return s;
    return Character.toLowerCase(s.charAt(0)) + s.substring(1);
  }

  private OutputFile outputFile(String path, String content) {
    return new OutputFile(path, applyProtobufPackagePrefix(content));
  }

  private String applyProtobufPackagePrefix(String content) {
    if (javaPackagePrefix.isEmpty()) {
      return content;
    }
    return content.replace("com.google.protobuf.", javaPackagePrefix + "com.google.protobuf.");
  }

  private static String normalizePrefix(String prefix) {
    if (prefix == null) {
      return "";
    }
    String trimmed = prefix.trim();
    if (trimmed.isEmpty() || trimmed.endsWith(".")) {
      return trimmed;
    }
    return trimmed + ".";
  }
}

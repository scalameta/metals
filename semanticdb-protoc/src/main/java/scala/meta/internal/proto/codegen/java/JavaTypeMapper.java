package scala.meta.internal.proto.codegen.java;

import scala.meta.internal.proto.tree.Proto.TypeRef;

/**
 * Maps protobuf types to Java types.
 *
 * <p>Follows the protobuf-java naming conventions.
 */
public final class JavaTypeMapper {

  private JavaTypeMapper() {}

  /** Get the Java type for a protobuf type reference. */
  public static String toJavaType(TypeRef typeRef) {
    String name = typeRef.fullName();
    return toJavaType(name);
  }

  /** Get the Java type for a protobuf type name. */
  public static String toJavaType(String protoType) {
    switch (protoType) {
      case "double":
        return "double";
      case "float":
        return "float";
      case "int32":
        return "int";
      case "int64":
        return "long";
      case "uint32":
        return "int";
      case "uint64":
        return "long";
      case "sint32":
        return "int";
      case "sint64":
        return "long";
      case "fixed32":
        return "int";
      case "fixed64":
        return "long";
      case "sfixed32":
        return "int";
      case "sfixed64":
        return "long";
      case "bool":
        return "boolean";
      case "string":
        return "java.lang.String";
      case "bytes":
        return "com.google.protobuf.ByteString";
      default:
        // Message or enum type - just use the name
        return protoType;
    }
  }

  /** Get the Java boxed type for a protobuf type name. */
  public static String toBoxedJavaType(String protoType) {
    switch (protoType) {
      case "double":
        return "java.lang.Double";
      case "float":
        return "java.lang.Float";
      case "int32":
      case "uint32":
      case "sint32":
      case "fixed32":
      case "sfixed32":
        return "java.lang.Integer";
      case "int64":
      case "uint64":
      case "sint64":
      case "fixed64":
      case "sfixed64":
        return "java.lang.Long";
      case "bool":
        return "java.lang.Boolean";
      case "string":
        return "java.lang.String";
      case "bytes":
        return "com.google.protobuf.ByteString";
      default:
        return protoType;
    }
  }

  /** Get the default value expression for a Java type. */
  public static String defaultValue(String protoType) {
    switch (protoType) {
      case "double":
        return "0.0";
      case "float":
        return "0.0f";
      case "int32":
      case "uint32":
      case "sint32":
      case "fixed32":
      case "sfixed32":
        return "0";
      case "int64":
      case "uint64":
      case "sint64":
      case "fixed64":
      case "sfixed64":
        return "0L";
      case "bool":
        return "false";
      case "string":
        return "\"\"";
      case "bytes":
        return "com.google.protobuf.ByteString.EMPTY";
      default:
        // Message or enum - return null for messages, first value for enums
        return "null";
    }
  }

  /** Check if a protobuf type is a scalar type. */
  public static boolean isScalarType(String protoType) {
    switch (protoType) {
      case "double":
      case "float":
      case "int32":
      case "int64":
      case "uint32":
      case "uint64":
      case "sint32":
      case "sint64":
      case "fixed32":
      case "fixed64":
      case "sfixed32":
      case "sfixed64":
      case "bool":
      case "string":
      case "bytes":
        return true;
      default:
        return false;
    }
  }

  /** Convert a proto field name to Java getter name (camelCase). */
  public static String toGetterName(String fieldName) {
    return "get" + capitalize(toCamelCase(fieldName));
  }

  /** Convert a proto field name to Java setter name (camelCase). */
  public static String toSetterName(String fieldName) {
    return "set" + capitalize(toCamelCase(fieldName));
  }

  /** Convert a proto field name to Java has-er name (camelCase). */
  public static String toHasMethodName(String fieldName) {
    return "has" + capitalize(toCamelCase(fieldName));
  }

  /** Convert snake_case to camelCase. */
  public static String toCamelCase(String snakeCase) {
    StringBuilder sb = new StringBuilder();
    boolean capitalizeNext = false;
    for (char c : snakeCase.toCharArray()) {
      if (c == '_') {
        capitalizeNext = true;
      } else if (capitalizeNext) {
        sb.append(Character.toUpperCase(c));
        capitalizeNext = false;
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  /** Capitalize the first letter of a string. */
  public static String capitalize(String s) {
    if (s == null || s.isEmpty()) return s;
    return Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }
}

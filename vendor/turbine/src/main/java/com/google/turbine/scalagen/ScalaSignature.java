/*
 * Copyright 2026 Google Inc. All Rights Reserved.
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

package com.google.turbine.scalagen;

import com.google.common.collect.ImmutableList;
import com.google.turbine.bytecode.sig.Sig;
import com.google.turbine.bytecode.sig.Sig.ClassSig;
import com.google.turbine.bytecode.sig.Sig.ClassTySig;
import com.google.turbine.bytecode.sig.Sig.LowerBoundTySig;
import com.google.turbine.bytecode.sig.Sig.MethodSig;
import com.google.turbine.bytecode.sig.Sig.SimpleClassTySig;
import com.google.turbine.bytecode.sig.Sig.TyParamSig;
import com.google.turbine.bytecode.sig.Sig.TySig;
import com.google.turbine.bytecode.sig.Sig.UpperBoundTySig;
import com.google.turbine.bytecode.sig.Sig.WildTyArgSig;
import com.google.turbine.bytecode.sig.SigWriter;
import com.google.turbine.model.TurbineConstantTypeKind;
import com.google.turbine.scalagen.ScalaTypeMapper.ImportScope;
import com.google.turbine.scalagen.ScalaTypeMapper.TypeAliasScope;
import com.google.turbine.scalaparse.ScalaTree.ClassDef;
import com.google.turbine.scalaparse.ScalaTree.Param;
import com.google.turbine.scalaparse.ScalaTree.ParamList;
import com.google.turbine.scalaparse.ScalaTree.TypeParam;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Builds JVM Signature attributes for Scala outline types. */
public final class ScalaSignature {

  public static @Nullable String classSignature(
      ClassDef cls, ImportScope scope, TypeAliasScope aliasScope) {
    if (!emitSignatures()) {
      return null;
    }
    Set<String> typeParamNames = new HashSet<>();
    for (TypeParam param : cls.typeParams()) {
      typeParamNames.add(param.name());
    }
    if (!needsClassSignature(cls, typeParamNames)) {
      return null;
    }
    ImmutableList<TyParamSig> tyParams = typeParamSigs(cls.typeParams(), typeParamNames, scope, aliasScope);

    ClassTySig superClass = objectClass();
    ImmutableList.Builder<ClassTySig> interfaces = ImmutableList.builder();
    if (!cls.parents().isEmpty()) {
      String first = cls.parents().get(0);
      if (cls.kind() == ClassDef.Kind.TRAIT) {
        ClassTySig iface =
            classTySig(first, cls.packageName(), typeParamNames, scope, aliasScope);
        if (iface != null) {
          interfaces.add(iface);
        }
      } else {
        ClassTySig parsed =
            classTySig(first, cls.packageName(), typeParamNames, scope, aliasScope);
        if (parsed != null) {
          superClass = parsed;
        }
      }
      for (int i = 1; i < cls.parents().size(); i++) {
        ClassTySig iface =
            classTySig(cls.parents().get(i), cls.packageName(), typeParamNames, scope, aliasScope);
        if (iface != null) {
          interfaces.add(iface);
        }
      }
    }
    ClassSig sig = new ClassSig(tyParams, superClass, interfaces.build());
    return SigWriter.classSig(sig);
  }

  public static @Nullable String methodSignature(
      ImmutableList<TypeParam> declaredTypeParams,
      List<String> paramTypes,
      @Nullable String returnType,
      Set<String> typeParamNames,
      String pkg,
      ImportScope scope,
      TypeAliasScope aliasScope) {
    if (!emitSignatures()) {
      return null;
    }
    if (!needsMethodSignature(declaredTypeParams, paramTypes, returnType, typeParamNames)) {
      return null;
    }
    ImmutableList<TyParamSig> tyParams = typeParamSigs(declaredTypeParams, typeParamNames, scope, aliasScope);
    ImmutableList.Builder<TySig> params = ImmutableList.builder();
    for (String param : paramTypes) {
      params.add(signatureForType(param, /* isReturn= */ false, pkg, typeParamNames, scope, aliasScope));
    }
    TySig ret = signatureForType(returnType, /* isReturn= */ true, pkg, typeParamNames, scope, aliasScope);
    MethodSig sig = new MethodSig(tyParams, params.build(), ret, ImmutableList.of());
    return SigWriter.method(sig);
  }

  private static boolean needsClassSignature(ClassDef cls, Set<String> typeParamNames) {
    if (!cls.typeParams().isEmpty()) {
      return true;
    }
    for (String parent : cls.parents()) {
      if (needsSignatureType(parent, typeParamNames)) {
        return true;
      }
    }
    return false;
  }

  private static boolean needsMethodSignature(
      ImmutableList<TypeParam> declaredTypeParams,
      List<String> paramTypes,
      @Nullable String returnType,
      Set<String> typeParamNames) {
    if (!declaredTypeParams.isEmpty()) {
      return true;
    }
    for (String param : paramTypes) {
      if (needsSignatureType(param, typeParamNames)) {
        return true;
      }
    }
    return needsSignatureType(returnType, typeParamNames);
  }

  private static boolean needsSignatureType(@Nullable String typeText, Set<String> typeParamNames) {
    if (typeText == null || typeText.isEmpty()) {
      return false;
    }
    if (typeText.contains("[")) {
      return true;
    }
    List<String> tokens = Arrays.asList(typeText.trim().split("\\s+"));
    for (String token : tokens) {
      String stripped = stripBackticks(token);
      if (typeParamNames.contains(stripped)) {
        return true;
      }
      if ("_".equals(token) || "<:".equals(token) || ">:".equals(token)) {
        return true;
      }
    }
    return false;
  }

  private static ImmutableList<TyParamSig> typeParamSigs(
      ImmutableList<TypeParam> params,
      Set<String> typeParamNames,
      ImportScope scope,
      TypeAliasScope aliasScope) {
    if (params.isEmpty()) {
      return ImmutableList.of();
    }
    ImmutableList.Builder<TyParamSig> out = ImmutableList.builder();
    for (TypeParam param : params) {
      TySig bound = objectClass();
      if (param.upperBound() != null && !param.upperBound().isEmpty()) {
        bound = signatureForType(param.upperBound(), /* isReturn= */ false, "", typeParamNames, scope, aliasScope);
      }
      out.add(new TyParamSig(param.name(), bound, ImmutableList.of()));
    }
    return out.build();
  }

  private static TySig signatureForType(
      @Nullable String typeText,
      boolean isReturn,
      String pkg,
      Set<String> typeParams,
      ImportScope scope,
      TypeAliasScope aliasScope) {
    if (typeText == null || typeText.trim().isEmpty()) {
      return isReturn ? Sig.VOID : objectClass();
    }
    String trimmed = typeText.trim();
    if (trimmed.contains("=>")) {
      return objectClass();
    }
    if (!trimmed.contains(" ") && !trimmed.contains("[") && aliasScope != null) {
      String alias = aliasScope.aliases().get(trimmed);
      if (alias != null) {
        return signatureForType(alias, isReturn, pkg, typeParams, scope, aliasScope);
      }
    }
    if (containsUnsupportedTokens(trimmed)) {
      return objectClass();
    }
    TypeParser parser = new TypeParser(trimmed, pkg, typeParams, scope, aliasScope, isReturn);
    TySig sig = parser.parseType();
    return sig != null ? sig : objectClass();
  }

  private static boolean containsUnsupportedTokens(String text) {
    List<String> tokens = Arrays.asList(text.trim().split("\\s+"));
    for (String token : tokens) {
      if ("with".equals(token) || "&".equals(token) || "|".equals(token) || "match".equals(token)) {
        return true;
      }
    }
    return false;
  }

  private static @Nullable ClassTySig classTySig(
      @Nullable String typeText,
      String pkg,
      Set<String> typeParams,
      ImportScope scope,
      TypeAliasScope aliasScope) {
    TySig sig =
        signatureForType(typeText, /* isReturn= */ false, pkg, typeParams, scope, aliasScope);
    if (sig instanceof ClassTySig classSig) {
      return classSig;
    }
    return null;
  }

  private static ClassTySig objectClass() {
    return new ClassTySig("java/lang", ImmutableList.of(new SimpleClassTySig("Object", ImmutableList.of())));
  }

  private static boolean emitSignatures() {
    return !"false".equalsIgnoreCase(System.getProperty("turbine.scala.emitSignatures", "true"));
  }

  private static final class TypeParser {
    private final List<String> tokens;
    private final String pkg;
    private final Set<String> typeParams;
    private final ImportScope scope;
    private final TypeAliasScope aliasScope;
    private final boolean isReturn;
    private int index = 0;

    TypeParser(
        String text,
        String pkg,
        Set<String> typeParams,
        ImportScope scope,
        TypeAliasScope aliasScope,
        boolean isReturn) {
        this.tokens = Arrays.asList(text.trim().split("\\s+"));
        this.pkg = pkg;
        this.typeParams = typeParams;
        this.scope = scope;
        this.aliasScope = aliasScope;
        this.isReturn = isReturn;
    }

    @Nullable TySig parseType() {
      if (index >= tokens.size()) {
        return null;
      }
      String token = tokens.get(index);
      if ("_".equals(token)) {
        index++;
        return objectClass();
      }
      if ("(".equals(token)) {
        skipDelimited("(", ")");
        return objectClass();
      }
      if ("+".equals(token) || "-".equals(token)) {
        index++;
        TySig inner = parseType();
        if (inner == null) {
          return objectClass();
        }
        return "+".equals(token) ? new UpperBoundTySig(inner) : new LowerBoundTySig(inner);
      }
      if (!isIdentifierToken(token)) {
        return objectClass();
      }
      List<String> parts = new ArrayList<>();
      parts.add(stripBackticks(token));
      index++;
      while (index + 1 < tokens.size()
          && ".".equals(tokens.get(index))
          && isIdentifierToken(tokens.get(index + 1))) {
        index++; // consume dot
        parts.add(stripBackticks(tokens.get(index)));
        index++;
      }
      if (!parts.isEmpty() && "_root_".equals(parts.get(0))) {
        parts.remove(0);
      }
      if (parts.isEmpty()) {
        return objectClass();
      }
      String raw = String.join("/", parts);
      if (typeParams.contains(raw)) {
        return new Sig.TyVarSig(raw);
      }
      Sig.TySig primitive = mapPrimitive(raw, isReturn);
      if (primitive != null) {
        return primitive;
      }
      if (isArrayType(raw)) {
        ImmutableList<TySig> args = parseTypeArgs();
        TySig element = args.isEmpty() ? objectClass() : args.get(0);
        return new Sig.ArrayTySig(element);
      }

      ImmutableList<TySig> typeArgs = parseTypeArgs();
      String binary = resolveExplicit(raw);
      if (binary == null) {
        binary = mapKnownClass(raw);
      }
      if (binary == null) {
        binary = resolveWildcard(raw);
      }
      if (binary == null) {
        if (!raw.contains("/") && !pkg.isEmpty()) {
          binary = pkg.replace('.', '/') + "/" + raw;
        } else {
          binary = raw;
        }
      }
      int slash = binary.lastIndexOf('/');
      String pkgName = slash >= 0 ? binary.substring(0, slash) : "";
      String simple = slash >= 0 ? binary.substring(slash + 1) : binary;
      return new ClassTySig(pkgName, ImmutableList.of(new SimpleClassTySig(simple, typeArgs)));
    }

    private ImmutableList<TySig> parseTypeArgs() {
      if (index >= tokens.size() || !"[".equals(tokens.get(index))) {
        return ImmutableList.of();
      }
      index++; // skip '['
      ImmutableList.Builder<TySig> args = ImmutableList.builder();
      int count = 0;
      while (index < tokens.size()) {
        String token = tokens.get(index);
        if ("]".equals(token)) {
          index++;
          break;
        }
        if (",".equals(token)) {
          index++;
          continue;
        }
        if (count > 512) {
          // Bail out on runaway type argument lists to avoid OOM.
          while (index < tokens.size() && !"]".equals(tokens.get(index))) {
            index++;
          }
          if (index < tokens.size()) {
            index++;
          }
          break;
        }
        args.add(parseTypeArg());
        count++;
      }
      return args.build();
    }

    private TySig parseTypeArg() {
      if (index >= tokens.size()) {
        return objectClass();
      }
      String token = tokens.get(index);
      if ("_".equals(token)) {
        index++;
        if (index < tokens.size() && "<:".equals(tokens.get(index))) {
          index++;
          TySig bound = parseType();
          return new UpperBoundTySig(bound == null ? objectClass() : bound);
        }
        if (index < tokens.size() && ">:".equals(tokens.get(index))) {
          index++;
          TySig bound = parseType();
          return new LowerBoundTySig(bound == null ? objectClass() : bound);
        }
        return new WildTyArgSig();
      }
      if ("+".equals(token) || "-".equals(token)) {
        index++;
        TySig bound = parseType();
        if (bound == null) {
          bound = objectClass();
        }
        return "+".equals(token) ? new UpperBoundTySig(bound) : new LowerBoundTySig(bound);
      }
      TySig parsed = parseType();
      return parsed == null ? objectClass() : parsed;
    }

    private void skipDelimited(String open, String close) {
      int depth = 0;
      while (index < tokens.size()) {
        String token = tokens.get(index);
        if (open.equals(token)) {
          depth++;
        } else if (close.equals(token)) {
          depth--;
          if (depth == 0) {
            index++;
            return;
          }
        }
        index++;
      }
    }

    private @Nullable String resolveExplicit(String raw) {
      if (scope == null || scope.isEmpty()) {
        return null;
      }
      String resolved = scope.explicit().get(raw);
      if (resolved != null) {
        return resolved;
      }
      if (raw.endsWith("$")) {
        String base = raw.substring(0, raw.length() - 1);
        String withoutSuffix = scope.explicit().get(base);
        if (withoutSuffix != null) {
          return withoutSuffix.endsWith("$") ? withoutSuffix : withoutSuffix + "$";
        }
      }
      return null;
    }

    private @Nullable String resolveWildcard(String raw) {
      if (scope == null || scope.isEmpty() || raw.contains("/")) {
        return null;
      }
      ImmutableList<String> wildcards = scope.wildcards();
      for (int i = wildcards.size() - 1; i >= 0; i--) {
        String prefix = wildcards.get(i);
        // Object wildcard imports are too ambiguous for type signatures and
        // can hijack unrelated simple names.
        if (prefix.endsWith("$")) {
          continue;
        }
        return prefix + "/" + raw;
      }
      return null;
    }
  }

  private static Sig.@Nullable TySig mapPrimitive(String raw, boolean isReturn) {
    return switch (raw) {
      case "Unit", "scala/Unit" -> isReturn ? Sig.VOID : objectClass();
      case "Boolean", "scala/Boolean" -> new Sig.BaseTySig(TurbineConstantTypeKind.BOOLEAN);
      case "Byte", "scala/Byte" -> new Sig.BaseTySig(TurbineConstantTypeKind.BYTE);
      case "Short", "scala/Short" -> new Sig.BaseTySig(TurbineConstantTypeKind.SHORT);
      case "Char", "scala/Char" -> new Sig.BaseTySig(TurbineConstantTypeKind.CHAR);
      case "Int", "scala/Int" -> new Sig.BaseTySig(TurbineConstantTypeKind.INT);
      case "Long", "scala/Long" -> new Sig.BaseTySig(TurbineConstantTypeKind.LONG);
      case "Float", "scala/Float" -> new Sig.BaseTySig(TurbineConstantTypeKind.FLOAT);
      case "Double", "scala/Double" -> new Sig.BaseTySig(TurbineConstantTypeKind.DOUBLE);
      default -> null;
    };
  }

  private static @Nullable String mapKnownClass(String raw) {
    return switch (raw) {
      case "String", "scala/String", "scala/Predef/String" -> "java/lang/String";
      case "Any",
          "AnyRef",
          "Object",
          "scala/Any",
          "scala/AnyRef",
          "java/lang/Object" ->
          "java/lang/Object";
      case "Throwable", "scala/Throwable" -> "java/lang/Throwable";
      case "Exception", "scala/Exception" -> "java/lang/Exception";
      case "RuntimeException", "scala/RuntimeException" -> "java/lang/RuntimeException";
      case "Error", "scala/Error" -> "java/lang/Error";
      case "Class", "scala/Class" -> "java/lang/Class";
      case "ClassLoader", "scala/ClassLoader" -> "java/lang/ClassLoader";
      case "Serializable", "scala/Serializable" -> "java/io/Serializable";
      case "Comparable", "scala/Comparable" -> "java/lang/Comparable";
      case "Cloneable", "scala/Cloneable" -> "java/lang/Cloneable";
      case "Product", "scala/Product" -> "scala/Product";
      case "Option", "scala/Option" -> "scala/Option";
      case "Seq", "scala/Seq", "scala/collection/Seq", "scala/collection/immutable/Seq" ->
          "scala/collection/immutable/Seq";
      case "List", "scala/List", "scala/collection/immutable/List" ->
          "scala/collection/immutable/List";
      case "Set", "scala/Set", "scala/collection/immutable/Set" ->
          "scala/collection/immutable/Set";
      case "Map", "scala/Map", "scala/collection/immutable/Map" ->
          "scala/collection/immutable/Map";
      case "Iterator", "scala/collection/Iterator" -> "scala/collection/Iterator";
      default -> null;
    };
  }

  private static boolean isArrayType(String raw) {
    return raw.equals("Array") || raw.equals("scala/Array");
  }

  private static boolean isIdentifierToken(String token) {
    if (token.isEmpty()) {
      return false;
    }
    char first = token.charAt(0);
    if (first == '`') {
      return token.length() > 1;
    }
    return Character.isJavaIdentifierStart(first) || first == '$' || first == '_';
  }

  private static String stripBackticks(String token) {
    if (token.length() >= 2 && token.charAt(0) == '`' && token.charAt(token.length() - 1) == '`') {
      return token.substring(1, token.length() - 1);
    }
    return token;
  }

  private ScalaSignature() {}
}

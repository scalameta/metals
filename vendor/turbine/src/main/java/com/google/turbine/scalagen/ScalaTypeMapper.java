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
import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Maps a subset of Scala type syntax to JVM descriptors. */
public final class ScalaTypeMapper {

  public static String descriptorForParam(
      @Nullable String typeText, String currentPackage, Set<String> typeParams) {
    return descriptor(
        typeText,
        currentPackage,
        typeParams,
        ImportScope.empty(),
        TypeAliasScope.empty(),
        /* isReturn= */ false);
  }

  public static String descriptorForReturn(
      @Nullable String typeText, String currentPackage, Set<String> typeParams) {
    return descriptor(
        typeText,
        currentPackage,
        typeParams,
        ImportScope.empty(),
        TypeAliasScope.empty(),
        /* isReturn= */ true);
  }

  public static String descriptorForParam(
      @Nullable String typeText,
      String currentPackage,
      Set<String> typeParams,
      ImportScope importScope) {
    return descriptor(
        typeText,
        currentPackage,
        typeParams,
        importScope,
        TypeAliasScope.empty(),
        /* isReturn= */ false);
  }

  public static String descriptorForReturn(
      @Nullable String typeText,
      String currentPackage,
      Set<String> typeParams,
      ImportScope importScope) {
    return descriptor(
        typeText,
        currentPackage,
        typeParams,
        importScope,
        TypeAliasScope.empty(),
        /* isReturn= */ true);
  }

  public static String descriptorForParam(
      @Nullable String typeText,
      String currentPackage,
      Set<String> typeParams,
      ImportScope importScope,
      TypeAliasScope aliasScope) {
    return descriptor(
        typeText, currentPackage, typeParams, importScope, aliasScope, /* isReturn= */ false);
  }

  public static String descriptorForReturn(
      @Nullable String typeText,
      String currentPackage,
      Set<String> typeParams,
      ImportScope importScope,
      TypeAliasScope aliasScope) {
    return descriptor(
        typeText, currentPackage, typeParams, importScope, aliasScope, /* isReturn= */ true);
  }

  private static String descriptor(
      @Nullable String typeText,
      String currentPackage,
      Set<String> typeParams,
      ImportScope importScope,
      TypeAliasScope aliasScope,
      boolean isReturn) {
    return descriptor(typeText, currentPackage, typeParams, importScope, aliasScope, isReturn, new HashSet<>());
  }

  private static String descriptor(
      @Nullable String typeText,
      String currentPackage,
      Set<String> typeParams,
      ImportScope importScope,
      TypeAliasScope aliasScope,
      boolean isReturn,
      Set<String> seenAliases) {
    if (isVarArgsType(typeText)) {
      return "Lscala/collection/immutable/Seq;";
    }
    String raw = rawTypeName(typeText);
    if (raw == null) {
      return isReturn ? "V" : "Ljava/lang/Object;";
    }
    if (typeParams.contains(raw)) {
      return "Ljava/lang/Object;";
    }
    String alias = resolveAlias(raw, aliasScope, seenAliases);
    if (alias != null) {
      return descriptor(
          alias, currentPackage, typeParams, importScope, aliasScope, isReturn, seenAliases);
    }
    String mapped = mapPrimitive(raw, isReturn);
    if (mapped != null) {
      return mapped;
    }
    if (isArrayType(raw)) {
      String arg = extractFirstTypeArg(typeText);
      String component =
          descriptorForParam(arg, currentPackage, typeParams, importScope, aliasScope);
      return "[" + component;
    }
    String binary = resolveQualified(raw, currentPackage, importScope);
    if (binary == null) {
      binary = mapKnownClass(raw);
    }
    if (binary == null) {
      if (!raw.contains("/")) {
        if (!currentPackage.isEmpty()) {
          binary = currentPackage.replace('.', '/') + "/" + raw;
        } else {
          binary = raw;
        }
      } else {
        binary = raw;
      }
    }
    return "L" + binary + ";";
  }

  private static @Nullable String rawTypeName(@Nullable String typeText) {
    if (typeText == null) {
      return null;
    }
    String trimmed = typeText.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    if (trimmed.contains("=>")) {
      return "java/lang/Object";
    }
    List<String> tokens = Arrays.asList(trimmed.split("\\s+"));
    for (int i = 0; i < tokens.size(); i++) {
      String token = tokens.get(i);
      if ("_".equals(token)) {
        return "java/lang/Object";
      }
      if (isIdentifierToken(token)) {
        StringBuilder name = new StringBuilder(stripBackticks(token));
        int j = i + 1;
        while (j + 1 < tokens.size() && ".".equals(tokens.get(j)) && isIdentifierToken(tokens.get(j + 1))) {
          name.append('/').append(stripBackticks(tokens.get(j + 1)));
          j += 2;
        }
        return name.toString();
      }
    }
    return null;
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

  private static @Nullable String mapPrimitive(String raw, boolean isReturn) {
    return switch (raw) {
      case "Unit", "scala/Unit" -> isReturn ? "V" : "Ljava/lang/Object;";
      case "Boolean", "scala/Boolean" -> "Z";
      case "Byte", "scala/Byte" -> "B";
      case "Short", "scala/Short" -> "S";
      case "Char", "scala/Char" -> "C";
      case "Int", "scala/Int" -> "I";
      case "Long", "scala/Long" -> "J";
      case "Float", "scala/Float" -> "F";
      case "Double", "scala/Double" -> "D";
      default -> null;
    };
  }

  private static @Nullable String mapKnownClass(String raw) {
    return switch (raw) {
      case "String", "scala/String", "scala/Predef/String" -> "java/lang/String";
      case "Any", "AnyRef", "Object", "scala/Any", "scala/AnyRef", "java/lang/Object" ->
          "java/lang/Object";
      case "List", "scala/List", "scala/collection/immutable/List" ->
          "scala/collection/immutable/List";
      case "Seq", "scala/Seq", "scala/collection/Seq", "scala/collection/immutable/Seq" ->
          "scala/collection/immutable/Seq";
      case "Vector", "scala/Vector", "scala/collection/immutable/Vector" ->
          "scala/collection/immutable/Vector";
      case "Map", "scala/Map", "scala/collection/immutable/Map" ->
          "scala/collection/immutable/Map";
      case "Set", "scala/Set", "scala/collection/immutable/Set" ->
          "scala/collection/immutable/Set";
      case "Option", "scala/Option" -> "scala/Option";
      case "Iterator", "scala/collection/Iterator" -> "scala/collection/Iterator";
      default -> null;
    };
  }

  private static boolean isArrayType(String raw) {
    return raw.equals("Array") || raw.equals("scala/Array");
  }

  private static @Nullable String extractFirstTypeArg(@Nullable String typeText) {
    if (typeText == null) {
      return null;
    }
    List<String> tokens = Arrays.asList(typeText.trim().split("\\s+"));
    int open = -1;
    int depth = 0;
    for (int i = 0; i < tokens.size(); i++) {
      if ("[".equals(tokens.get(i))) {
        if (depth == 0) {
          open = i + 1;
        }
        depth++;
        continue;
      }
      if ("]".equals(tokens.get(i))) {
        depth--;
        if (depth == 0 && open >= 0) {
          StringBuilder sb = new StringBuilder();
          for (int j = open; j < i; j++) {
            if (sb.length() > 0) {
              sb.append(' ');
            }
            sb.append(tokens.get(j));
          }
          return sb.toString().trim();
        }
      }
    }
    return null;
  }

  private static @Nullable String resolveImport(String raw, ImportScope importScope) {
    if (importScope == null || importScope.isEmpty() || raw.contains("/")) {
      return null;
    }
    String explicit = importScope.explicit().get(raw);
    if (explicit != null) {
      return explicit;
    }
    List<String> wildcards = importScope.wildcards();
    for (int i = wildcards.size() - 1; i >= 0; i--) {
      return wildcards.get(i) + "/" + raw;
    }
    return null;
  }

  private static boolean isVarArgsType(@Nullable String typeText) {
    if (typeText == null) {
      return false;
    }
    String trimmed = typeText.trim();
    if (trimmed.isEmpty()) {
      return false;
    }
    if (trimmed.endsWith("*")) {
      return true;
    }
    List<String> tokens = Arrays.asList(trimmed.split("\\s+"));
    return !tokens.isEmpty() && "*".equals(tokens.get(tokens.size() - 1));
  }

  private static @Nullable String resolveQualified(
      String raw, String currentPackage, ImportScope importScope) {
    if (!raw.contains("/")) {
      return resolveImport(raw, importScope);
    }
    String[] parts = raw.split("/");
    if (parts.length == 0) {
      return raw;
    }
    String head = parts[0];
    String resolvedHead = resolveImport(head, importScope);
    if (resolvedHead != null) {
      return resolvedHead + "$" + String.join("$", Arrays.asList(parts).subList(1, parts.length));
    }
    if (!currentPackage.isEmpty() && isClassLike(head)) {
      return currentPackage.replace('.', '/')
          + "/"
          + head
          + "$"
          + String.join("$", Arrays.asList(parts).subList(1, parts.length));
    }
    return raw;
  }

  private static boolean isClassLike(String segment) {
    if (segment == null || segment.isEmpty()) {
      return false;
    }
    return Character.isUpperCase(segment.charAt(0));
  }

  private static @Nullable String resolveAlias(
      String raw, TypeAliasScope aliasScope, Set<String> seenAliases) {
    if (aliasScope == null || aliasScope.isEmpty()) {
      return null;
    }
    String rhs = aliasScope.aliases().get(raw);
    if (rhs == null) {
      return null;
    }
    if (!seenAliases.add(raw)) {
      return "java/lang/Object";
    }
    return rhs;
  }

  public record ImportScope(ImmutableMap<String, String> explicit, ImmutableList<String> wildcards) {
    public static ImportScope empty() {
      return new ImportScope(ImmutableMap.of(), ImmutableList.of());
    }

    public static Builder builder() {
      return new Builder();
    }

    public boolean isEmpty() {
      return explicit.isEmpty() && wildcards.isEmpty();
    }

    public static final class Builder {
      private final Map<String, String> explicit = new LinkedHashMap<>();
      private final List<String> wildcards = new ArrayList<>();

      public Builder addExplicit(String simpleName, String binaryName) {
        explicit.put(simpleName, binaryName);
        return this;
      }

      public Builder addWildcard(String binaryPrefix) {
        wildcards.add(binaryPrefix);
        return this;
      }

      public ImportScope build() {
        return new ImportScope(ImmutableMap.copyOf(explicit), ImmutableList.copyOf(wildcards));
      }
    }
  }

  public record TypeAliasScope(ImmutableMap<String, String> aliases) {
    public static TypeAliasScope empty() {
      return new TypeAliasScope(ImmutableMap.of());
    }

    public static Builder builder() {
      return new Builder();
    }

    public boolean isEmpty() {
      return aliases.isEmpty();
    }

    public static final class Builder {
      private final Map<String, String> aliases = new LinkedHashMap<>();

      public Builder addAlias(String name, String rhs) {
        aliases.put(name, rhs);
        return this;
      }

      public TypeAliasScope build() {
        return new TypeAliasScope(ImmutableMap.copyOf(aliases));
      }
    }
  }

  public static Set<String> typeParamNames(List<com.google.turbine.scalaparse.ScalaTree.TypeParam> tparams) {
    Set<String> names = new HashSet<>();
    for (com.google.turbine.scalaparse.ScalaTree.TypeParam param : tparams) {
      names.add(param.name());
    }
    return names;
  }

  private ScalaTypeMapper() {}
}

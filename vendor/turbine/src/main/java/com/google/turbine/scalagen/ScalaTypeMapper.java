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

  public static String descriptorForVarArgsParam(
      @Nullable String typeText,
      String currentPackage,
      Set<String> typeParams,
      ImportScope importScope,
      TypeAliasScope aliasScope) {
    String elementType = stripVarArgs(typeText);
    String component =
        descriptor(
            elementType,
            currentPackage,
            typeParams,
            importScope,
            aliasScope,
            /* isReturn= */ false);
    return "[" + component;
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
    String functionBinary = functionTypeBinary(typeText);
    if (functionBinary != null) {
      return "L" + functionBinary + ";";
    }
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
    if (!raw.contains("/")) {
      String explicit = resolveExplicit(raw, importScope);
      if (explicit != null) {
        String explicitAlias = resolveQualifiedAlias(explicit, aliasScope, seenAliases);
        if (explicitAlias != null) {
          return descriptor(
              explicitAlias, currentPackage, typeParams, importScope, aliasScope, isReturn, seenAliases);
        }
        return "L" + explicit + ";";
      }
      String known = mapKnownClass(raw);
      if (known != null) {
        return "L" + known + ";";
      }
    }
    String binary = resolveQualified(raw, currentPackage, importScope);
    if (binary != null) {
      String normalized = mapKnownQualified(binary);
      if (normalized != null) {
        binary = normalized;
      }
      String qualifiedAlias = resolveQualifiedAlias(binary, aliasScope, seenAliases);
      if (qualifiedAlias != null) {
        return descriptor(
            qualifiedAlias, currentPackage, typeParams, importScope, aliasScope, isReturn, seenAliases);
      }
    }
    if (binary == null) {
      binary = mapKnownClass(raw);
    }
    if (binary == null) {
      binary = mapKnownQualified(raw);
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

  private static @Nullable String functionTypeBinary(@Nullable String typeText) {
    if (typeText == null) {
      return null;
    }
    String trimmed = typeText.trim();
    if (trimmed.isEmpty() || !trimmed.contains("=>")) {
      return null;
    }
    List<String> tokens = Arrays.asList(trimmed.split("\\s+"));
    int arrow = tokens.indexOf("=>");
    if (arrow <= 0) {
      return null;
    }
    int arity = functionArity(tokens.subList(0, arrow));
    if (arity < 0 || arity > 22) {
      return null;
    }
    return "scala/Function" + arity;
  }

  private static int functionArity(List<String> tokens) {
    if (tokens.isEmpty()) {
      return 0;
    }
    int start = 0;
    int end = tokens.size();
    if ("(".equals(tokens.get(0)) && ")".equals(tokens.get(end - 1))) {
      start++;
      end = Math.max(start, end - 1);
    }
    int depth = 0;
    int commas = 0;
    boolean sawType = false;
    for (int i = start; i < end; i++) {
      String token = tokens.get(i);
      switch (token) {
        case "(":
        case "[":
          depth++;
          break;
        case ")":
        case "]":
          depth = Math.max(0, depth - 1);
          break;
        case ",":
          if (depth == 0) {
            commas++;
          }
          break;
        default:
          sawType = true;
          break;
      }
    }
    if (!sawType) {
      return 0;
    }
    return commas + 1;
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
        return name.toString().replace('.', '/');
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
    String product = mapProductClass(raw);
    if (product != null) {
      return product;
    }
    String tuple = mapTupleClass(raw);
    if (tuple != null) {
      return tuple;
    }
    return switch (raw) {
      case "String", "scala/String", "scala/Predef/String" -> "java/lang/String";
      case "Any", "AnyRef", "Object", "scala/Any", "scala/AnyRef", "java/lang/Object" ->
          "java/lang/Object";
      case "Throwable", "scala/Throwable" -> "java/lang/Throwable";
      case "Exception", "scala/Exception" -> "java/lang/Exception";
      case "RuntimeException", "scala/RuntimeException" -> "java/lang/RuntimeException";
      case "Error", "scala/Error" -> "java/lang/Error";
      case "Class", "scala/Class" -> "java/lang/Class";
      case "ClassLoader", "scala/ClassLoader" -> "java/lang/ClassLoader";
      case "Runnable", "java/lang/Runnable" -> "java/lang/Runnable";
      case "Comparable", "java/lang/Comparable" -> "java/lang/Comparable";
      case "Serializable", "scala/Serializable", "java/io/Serializable" ->
          "java/io/Serializable";
      case "File", "java/io/File" -> "java/io/File";
      case "InputStream", "java/io/InputStream" -> "java/io/InputStream";
      case "OutputStream", "java/io/OutputStream" -> "java/io/OutputStream";
      case "Collection", "java/util/Collection" -> "java/util/Collection";
      case "Optional", "java/util/Optional" -> "java/util/Optional";
      case "ConcurrentMap", "java/util/concurrent/ConcurrentMap" ->
          "java/util/concurrent/ConcurrentMap";
      case "ConcurrentHashMap", "java/util/concurrent/ConcurrentHashMap" ->
          "java/util/concurrent/ConcurrentHashMap";
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
      case "Some", "scala/Some" -> "scala/Some";
      case "Iterable", "scala/Iterable", "scala/collection/Iterable" -> "scala/collection/Iterable";
      case "Iterator", "scala/collection/Iterator" -> "scala/collection/Iterator";
      case "PartialFunction", "scala/PartialFunction" -> "scala/PartialFunction";
      default -> null;
    };
  }

  private static @Nullable String mapTupleClass(String raw) {
    String normalized = raw.startsWith("scala/") ? raw.substring("scala/".length()) : raw;
    if (!normalized.startsWith("Tuple")) {
      return null;
    }
    String suffix = normalized.substring("Tuple".length());
    if (suffix.isEmpty()) {
      return null;
    }
    int arity;
    try {
      arity = Integer.parseInt(suffix);
    } catch (NumberFormatException e) {
      return null;
    }
    if (arity < 1 || arity > 22) {
      return null;
    }
    return "scala/Tuple" + arity;
  }

  private static @Nullable String mapProductClass(String raw) {
    String normalized = raw.startsWith("scala/") ? raw.substring("scala/".length()) : raw;
    if ("Product".equals(normalized)) {
      return "scala/Product";
    }
    if (!normalized.startsWith("Product")) {
      return null;
    }
    String suffix = normalized.substring("Product".length());
    if (suffix.isEmpty()) {
      return null;
    }
    int arity;
    try {
      arity = Integer.parseInt(suffix);
    } catch (NumberFormatException e) {
      return null;
    }
    if (arity < 1 || arity > 22) {
      return null;
    }
    return "scala/Product" + arity;
  }

  private static @Nullable String mapKnownQualified(String raw) {
    if (raw.startsWith("immutable/")) {
      return "scala/collection/immutable/" + raw.substring("immutable/".length());
    }
    if (raw.startsWith("mutable/")) {
      return "scala/collection/mutable/" + raw.substring("mutable/".length());
    }
    if (raw.startsWith("collection/")) {
      return "scala/collection/" + raw.substring("collection/".length());
    }
    return null;
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
      String prefix = wildcards.get(i);
      // Object wildcard imports are too ambiguous for binary name resolution.
      // They frequently over-capture unrelated type names (for example
      // scala/jdk/* converters), which breaks Java-facing descriptors.
      if (prefix.endsWith("$")) {
        continue;
      }
      if (isLikelyTermWildcard(prefix)) {
        continue;
      }
      return prefix + "/" + raw;
    }
    return null;
  }

  private static boolean isLikelyTermWildcard(String prefix) {
    if (prefix == null || prefix.isEmpty()) {
      return true;
    }
    if (prefix.indexOf('/') >= 0) {
      return false;
    }
    if (isRootPackageHead(prefix)) {
      return false;
    }
    return !isClassLike(prefix);
  }

  private static @Nullable String resolveExplicit(String raw, ImportScope importScope) {
    if (importScope == null || importScope.isEmpty()) {
      return null;
    }
    String resolved = importScope.explicit().get(raw);
    if (resolved != null) {
      return resolved;
    }
    if (raw.endsWith("$")) {
      String base = raw.substring(0, raw.length() - 1);
      String withoutSuffix = importScope.explicit().get(base);
      if (withoutSuffix != null) {
        return withoutSuffix.endsWith("$") ? withoutSuffix : withoutSuffix + "$";
      }
    }
    return null;
  }

  public static boolean isVarArgsType(@Nullable String typeText) {
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

  private static @Nullable String stripVarArgs(@Nullable String typeText) {
    if (typeText == null) {
      return null;
    }
    String trimmed = typeText.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    if (!isVarArgsType(trimmed)) {
      return trimmed;
    }
    if (trimmed.endsWith("*")) {
      return trimmed.substring(0, trimmed.length() - 1).trim();
    }
    List<String> tokens = Arrays.asList(trimmed.split("\\s+"));
    if (tokens.isEmpty()) {
      return null;
    }
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < tokens.size() - 1; i++) {
      if (out.length() > 0) {
        out.append(' ');
      }
      out.append(tokens.get(i));
    }
    return out.toString().trim();
  }

  private static @Nullable String resolveQualified(
      String raw, String currentPackage, ImportScope importScope) {
    String explicitRaw = resolveExplicit(raw, importScope);
    if (explicitRaw != null) {
      return explicitRaw;
    }
    if (!raw.contains("/")) {
      return resolveImport(raw, importScope);
    }
    String[] parts = raw.split("/");
    if (parts.length == 0) {
      return raw;
    }
    String head = parts[0];
    String[] tail = Arrays.copyOfRange(parts, 1, parts.length);
    // For qualified names like "function/Procedure", only explicit imports should rewrite
    // the leading segment. Wildcard imports must not rewrite package-qualified names
    // such as "scala/Option".
    String resolvedHead = resolveExplicit(head, importScope);
    if (resolvedHead != null) {
      if (isClassLike(head)) {
        if (tail.length == 0) {
          return resolvedHead;
        }
        return resolvedHead + "$" + String.join("$", tail);
      }
      if (tail.length == 0) {
        return resolvedHead;
      }
      return resolvedHead + "/" + String.join("/", tail);
    }
    if (isClassLike(head)) {
      String importedHead = resolveImport(head, importScope);
      if (importedHead != null) {
        if (tail.length == 0) {
          return importedHead;
        }
        return importedHead + "$" + String.join("$", tail);
      }
      if (!currentPackage.isEmpty()) {
        return currentPackage.replace('.', '/')
            + "/"
            + head
            + (tail.length == 0 ? "" : "$" + String.join("$", tail));
      }
    }
    String importedHead = resolveWildcardHead(head, importScope);
    if (importedHead != null && !isRootPackageHead(head)) {
      if (tail.length == 0) {
        return importedHead;
      }
      return importedHead + "/" + String.join("/", tail);
    }
    String packageRelative = resolveRelativeToCurrentPackage(head, tail, currentPackage);
    if (packageRelative != null) {
      return packageRelative;
    }
    return raw;
  }

  private static boolean isRootPackageHead(String head) {
    return switch (head) {
      case "java", "javax", "scala", "sun", "com", "org", "kotlin", "dotty" -> true;
      default -> false;
    };
  }

  private static @Nullable String resolveWildcardHead(String head, ImportScope importScope) {
    if (importScope == null || importScope.isEmpty()) {
      return null;
    }
    List<String> wildcards = importScope.wildcards();
    for (int i = wildcards.size() - 1; i >= 0; i--) {
      String prefix = wildcards.get(i);
      if (prefix.endsWith("$")) {
        continue;
      }
      int slash = prefix.lastIndexOf('/');
      String segment = slash >= 0 ? prefix.substring(slash + 1) : prefix;
      if (head.equals(segment)) {
        return prefix;
      }
    }
    return null;
  }

  private static @Nullable String resolveRelativeToCurrentPackage(
      String head, String[] tail, String currentPackage) {
    if (currentPackage == null || currentPackage.isEmpty()) {
      return null;
    }
    if (isRootPackageHead(head)) {
      return null;
    }
    // Avoid rewriting already package-qualified names like foo/bar/Baz.
    // Relative package fallback is intended for single-segment heads (for example util/Foo).
    if (tail.length > 1 && !isClassLike(tail[0])) {
      return null;
    }
    String[] segments = currentPackage.split("\\.");
    if (segments.length == 0) {
      return null;
    }
    for (int i = segments.length - 1; i >= 0; i--) {
      if (!head.equals(segments[i])) {
        continue;
      }
      String prefix = String.join("/", Arrays.copyOfRange(segments, 0, i + 1));
      if (prefix.isEmpty()) {
        return null;
      }
      if (tail.length == 0) {
        return prefix;
      }
      return prefix + "/" + String.join("/", tail);
    }
    if (segments.length < 2) {
      return null;
    }
    // Allow package-qualified references to sibling packages under enclosing package prefixes.
    // Example: in com/example/feature, util/Foo should resolve to
    // com/example/util/Foo before falling back to raw util/Foo.
    for (int i = segments.length - 1; i >= 1; i--) {
      String prefix = String.join("/", Arrays.copyOfRange(segments, 0, i));
      if (prefix.isEmpty()) {
        continue;
      }
      if (tail.length == 0) {
        return prefix + "/" + head;
      }
      return prefix + "/" + head + "/" + String.join("/", tail);
    }
    return null;
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

  private static @Nullable String resolveQualifiedAlias(
      @Nullable String binary, TypeAliasScope aliasScope, Set<String> seenAliases) {
    if (binary == null || binary.isEmpty() || aliasScope == null || aliasScope.isEmpty()) {
      return null;
    }
    String alias = resolveAlias(binary, aliasScope, seenAliases);
    if (alias != null) {
      return alias;
    }
    if (binary.indexOf('$') >= 0) {
      return resolveAlias(binary.replace('$', '/'), aliasScope, seenAliases);
    }
    return null;
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

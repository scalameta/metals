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
import com.google.turbine.binder.ClassPath;
import com.google.turbine.binder.bytecode.BytecodeBoundClass;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.bytecode.ClassFile;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo;
import com.google.turbine.bytecode.ClassWriter;
import com.google.turbine.model.Const;
import com.google.turbine.model.TurbineFlag;
import com.google.turbine.model.TurbineTyKind;
import com.google.turbine.scalaparse.ScalaTree;
import com.google.turbine.scalaparse.ScalaTree.ClassDef;
import com.google.turbine.scalaparse.ScalaTree.DefDef;
import com.google.turbine.scalaparse.ScalaTree.Defn;
import com.google.turbine.scalaparse.ScalaTree.ImportStat;
import com.google.turbine.scalaparse.ScalaTree.Param;
import com.google.turbine.scalaparse.ScalaTree.ParamList;
import com.google.turbine.scalaparse.ScalaTree.TypeDef;
import com.google.turbine.scalaparse.ScalaTree.TypeParam;
import com.google.turbine.scalaparse.ScalaTree.ValDef;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Lowers Scala outline trees to minimal classfiles. */
public final class ScalaLower {

  @FunctionalInterface
  public interface ParentKindResolver {
    boolean isInterface(String binaryName);

    default String superName(String binaryName) {
      return null;
    }

    default ImmutableList<String> interfaces(String binaryName) {
      return ImmutableList.of();
    }

    static ParentKindResolver none() {
      return ignored -> false;
    }
  }

  public static ParentKindResolver classPathParentKindResolver(ClassPath classPath) {
    if (classPath == null) {
      return ParentKindResolver.none();
    }
    return new ParentKindResolver() {
      private final Map<String, BytecodeBoundClass> classCache = new HashMap<>();
      private final Map<String, Boolean> interfaceCache = new HashMap<>();

      private BytecodeBoundClass lookupClass(String binaryName) {
        if (binaryName == null || binaryName.isEmpty()) {
          return null;
        }
        if (classCache.containsKey(binaryName)) {
          return classCache.get(binaryName);
        }
        BytecodeBoundClass cls = classPath.env().get(new ClassSymbol(binaryName));
        classCache.put(binaryName, cls);
        return cls;
      }

      @Override
      public boolean isInterface(String binaryName) {
        if (binaryName == null || binaryName.isEmpty()) {
          return false;
        }
        Boolean cached = interfaceCache.get(binaryName);
        if (cached != null) {
          return cached;
        }
        BytecodeBoundClass cls = lookupClass(binaryName);
        boolean isInterface =
            cls != null
                && (cls.kind() == TurbineTyKind.INTERFACE || cls.kind() == TurbineTyKind.ANNOTATION);
        interfaceCache.put(binaryName, isInterface);
        return isInterface;
      }

      @Override
      public String superName(String binaryName) {
        BytecodeBoundClass cls = lookupClass(binaryName);
        if (cls == null || cls.superclass() == null) {
          return null;
        }
        return cls.superclass().binaryName();
      }

      @Override
      public ImmutableList<String> interfaces(String binaryName) {
        BytecodeBoundClass cls = lookupClass(binaryName);
        if (cls == null || cls.interfaces().isEmpty()) {
          return ImmutableList.of();
        }
        ImmutableList.Builder<String> result = ImmutableList.builder();
        for (ClassSymbol iface : cls.interfaces()) {
          result.add(iface.binaryName());
        }
        return result.build();
      }
    };
  }

  public static ImmutableMap<String, byte[]> lower(
      ImmutableList<ScalaTree.CompUnit> units, int majorVersion) {
    return lower(units, majorVersion, ParentKindResolver.none());
  }

  public static ImmutableMap<String, byte[]> lower(
      ImmutableList<ScalaTree.CompUnit> units,
      int majorVersion,
      ParentKindResolver parentKindResolver) {
    ParentKindResolver resolver =
        parentKindResolver == null ? ParentKindResolver.none() : parentKindResolver;
    ClassCollection defs = collectClassDefs(units);

    Map<String, Map<String, String>> objectTypeMembers =
        objectTypeMembers(defs.objectDefs(), defs.classBinaryNames(), defs.moduleBinaryNames());
    Map<String, Map<String, String>> packageObjectTypes =
        packageObjectTypes(
            defs.topLevelObjects(), defs.classBinaryNames(), defs.moduleBinaryNames());
    Map<String, Set<String>> packageTypeMembers =
        packageTypeMembers(defs.topLevelClassDefs(), defs.topLevelObjects());
    ScalaTypeMapper.TypeAliasScope qualifiedAliases =
        qualifiedTypeAliasScope(defs.allDefs(), defs.classBinaryNames(), defs.moduleBinaryNames());
    Map<ClassDef, ScalaTypeMapper.ImportScope> importScopes = new IdentityHashMap<>();
    Map<ClassDef, ScalaTypeMapper.TypeAliasScope> aliasScopes = new IdentityHashMap<>();
    Map<ClassDef, ScalaTypeMapper.ImportScope> ownerTypeScopes = new IdentityHashMap<>();
    for (ClassDef cls : defs.allDefs()) {
      ownerTypeScopes.put(
          cls, ownerTypeScope(cls, defs.classBinaryNames(), defs.moduleBinaryNames()));
    }
    for (ScalaTree.CompUnit unit : units) {
      ScalaTypeMapper.ImportScope unitScope =
          importScope(unit, objectTypeMembers, packageTypeMembers);
      List<ClassDef> unitDefs = defs.unitClassDefs().get(unit);
      if (unitDefs == null || unitDefs.isEmpty()) {
        continue;
      }
      for (ClassDef cls : unitDefs) {
        ScalaTypeMapper.ImportScope enclosingScope =
            enclosingOwnerTypeScope(cls, defs.owners(), ownerTypeScopes);
        ScalaTypeMapper.ImportScope localScope =
            importScope(
                cls.imports(),
                cls.packageName(),
                objectTypeMembers,
                packageTypeMembers,
                enclosingScope);
        ScalaTypeMapper.ImportScope ownerScope =
            ownerTypeScopes.getOrDefault(cls, ScalaTypeMapper.ImportScope.empty());
        ScalaTypeMapper.ImportScope scope =
            mergeImportScopes(
                mergeImportScopes(mergeImportScopes(unitScope, enclosingScope), localScope),
                ownerScope);
        String ownerBinary =
            cls.kind() == ClassDef.Kind.OBJECT || cls.isPackageObject()
                ? moduleBinaryName(cls, defs.classBinaryNames(), defs.moduleBinaryNames())
                : classBinaryName(cls, defs.classBinaryNames(), defs.moduleBinaryNames());
        scope = mergeImportScopes(scope, thisTypeScope(ownerBinary));
        importScopes.put(cls, scope);
        aliasScopes.put(cls, mergeAliasScopes(qualifiedAliases, typeAliasScope(cls)));
      }
    }

    Map<Key, ClassDef> objectsByKey = new HashMap<>();
    Map<String, ClassDef> traitsByBinary = new HashMap<>();
    Map<String, ClassDef> sourceTypesByBinary = new HashMap<>();
    Set<Key> caseCompanions = new HashSet<>();
    Map<Key, Integer> caseCompanionFunctionArity = new HashMap<>();
    for (ClassDef obj : defs.objectDefs()) {
      objectsByKey.put(
          new Key(classKey(obj, defs.classBinaryNames(), defs.moduleBinaryNames())), obj);
    }
    for (ClassDef cls : defs.classDefs()) {
      sourceTypesByBinary.put(
          classBinaryName(cls, defs.classBinaryNames(), defs.moduleBinaryNames()), cls);
      if (cls.kind() == ClassDef.Kind.TRAIT) {
        traitsByBinary.put(classBinaryName(cls, defs.classBinaryNames(), defs.moduleBinaryNames()), cls);
      }
    }

    Map<Key, List<ClassFile.MethodInfo>> companionExtras = new HashMap<>();
    Map<Key, Boolean> hasCtorDefaults = new HashMap<>();
    for (ClassDef cls : defs.classDefs()) {
      ScalaTypeMapper.ImportScope scope =
          importScopes.getOrDefault(cls, ScalaTypeMapper.ImportScope.empty());
      ScalaTypeMapper.TypeAliasScope aliases =
          aliasScopes.getOrDefault(cls, ScalaTypeMapper.TypeAliasScope.empty());
      List<ClassFile.MethodInfo> ctorDefaults =
          ctorDefaultGetters(cls, scope, aliases, /* staticContext= */ false);
      List<ClassFile.MethodInfo> caseCompanionMethods = ImmutableList.of();
      Key key = new Key(classKey(cls, defs.classBinaryNames(), defs.moduleBinaryNames()));
      if (cls.isCase()) {
        int arity = caseCompanionArity(cls);
        caseCompanions.add(key);
        if (arity >= 0) {
          caseCompanionFunctionArity.put(key, arity);
        }
        caseCompanionMethods =
            caseClassCompanionMethods(
                cls,
                classBinaryName(cls, defs.classBinaryNames(), defs.moduleBinaryNames()),
                scope,
                aliases);
      }
      if (!ctorDefaults.isEmpty()) {
        hasCtorDefaults.put(key, true);
      }
      List<ClassFile.MethodInfo> extras = concatMethods(ctorDefaults, caseCompanionMethods);
      if (!extras.isEmpty()) {
        companionExtras.put(key, extras);
        if (!objectsByKey.containsKey(key)) {
          ClassDef synthetic = synthesizeCompanion(cls);
          defs.objectDefs().add(synthetic);
          objectsByKey.put(key, synthetic);
          importScopes.put(synthetic, scope);
          aliasScopes.put(synthetic, qualifiedAliases);
          defs.moduleBinaryNames().put(synthetic, key.binaryName() + "$");
        }
      }
    }

    Set<Key> classKeys = new HashSet<>();
    for (ClassDef cls : defs.classDefs()) {
      classKeys.add(new Key(classKey(cls, defs.classBinaryNames(), defs.moduleBinaryNames())));
    }

    Map<String, byte[]> out = new LinkedHashMap<>();

    for (ClassDef cls : defs.classDefs()) {
      Key key = new Key(classKey(cls, defs.classBinaryNames(), defs.moduleBinaryNames()));
      ClassDef companion = objectsByKey.get(key);
      boolean ctorDefaults = hasCtorDefaults.getOrDefault(key, false);
      ScalaTypeMapper.ImportScope scope =
          importScopes.getOrDefault(cls, ScalaTypeMapper.ImportScope.empty());
      ScalaTypeMapper.TypeAliasScope aliases =
          aliasScopes.getOrDefault(cls, ScalaTypeMapper.TypeAliasScope.empty());
      ScalaTypeMapper.TypeAliasScope companionAliases =
          companion == null
              ? ScalaTypeMapper.TypeAliasScope.empty()
              : aliasScopes.getOrDefault(companion, ScalaTypeMapper.TypeAliasScope.empty());
      putAllUnique(
          out,
          generateClass(
              cls,
              classBinaryName(cls, defs.classBinaryNames(), defs.moduleBinaryNames()),
              companion,
              ctorDefaults,
              scope,
              aliases,
              companionAliases,
              traitsByBinary,
              sourceTypesByBinary,
              packageObjectTypes,
              importScopes,
              aliasScopes,
              resolver,
              majorVersion));
      if (cls.kind() == ClassDef.Kind.TRAIT) {
        putAllUnique(
            out,
            generateTraitImplClass(
                cls,
                classBinaryName(cls, defs.classBinaryNames(), defs.moduleBinaryNames()),
                scope,
                aliases,
                majorVersion));
      }
    }

    for (ClassDef obj : defs.objectDefs()) {
      Key key = new Key(classKey(obj, defs.classBinaryNames(), defs.moduleBinaryNames()));
      List<ClassFile.MethodInfo> extras = companionExtras.getOrDefault(key, ImmutableList.of());
      Integer arity = caseCompanionFunctionArity.get(key);
      int functionArity = arity == null ? -1 : arity;
      ScalaTypeMapper.ImportScope scope =
          importScopes.getOrDefault(obj, ScalaTypeMapper.ImportScope.empty());
      ScalaTypeMapper.TypeAliasScope aliases =
          aliasScopes.getOrDefault(obj, ScalaTypeMapper.TypeAliasScope.empty());
      putAllUnique(
          out,
          generateObject(
              obj,
              moduleBinaryName(obj, defs.classBinaryNames(), defs.moduleBinaryNames()),
              extras,
              scope,
              aliases,
              traitsByBinary,
              sourceTypesByBinary,
              packageObjectTypes,
              importScopes,
              aliasScopes,
              resolver,
              majorVersion,
              defs.topLevelObjects().contains(obj),
              functionArity));
      if (defs.topLevelObjects().contains(obj) && !classKeys.contains(key)) {
        putAllUnique(
            out,
            generateObjectMirror(
                obj,
                key.binaryName(),
                moduleBinaryName(obj, defs.classBinaryNames(), defs.moduleBinaryNames()),
                scope,
                aliases,
                traitsByBinary,
                packageObjectTypes,
                importScopes,
                aliasScopes,
                majorVersion));
      }
    }

    for (ClassDef pkgObj : defs.packageObjects()) {
      ScalaTypeMapper.ImportScope scope =
          importScopes.getOrDefault(pkgObj, ScalaTypeMapper.ImportScope.empty());
      ScalaTypeMapper.TypeAliasScope aliases =
          aliasScopes.getOrDefault(pkgObj, ScalaTypeMapper.TypeAliasScope.empty());
      putAllUnique(
          out,
          generatePackageObject(
              pkgObj,
              scope,
              aliases,
              traitsByBinary,
              packageObjectTypes,
              importScopes,
              aliasScopes,
              majorVersion));
    }

    return ImmutableMap.copyOf(out);
  }

  private static void putAllUnique(Map<String, byte[]> target, Map<String, byte[]> source) {
    for (Map.Entry<String, byte[]> entry : source.entrySet()) {
      target.putIfAbsent(entry.getKey(), entry.getValue());
    }
  }

  private static ImmutableMap<String, byte[]> generateClass(
      ClassDef cls,
      String binaryName,
      ClassDef companion,
      boolean hasCtorDefaults,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      ScalaTypeMapper.TypeAliasScope companionAliases,
      Map<String, ClassDef> traitsByBinary,
      Map<String, ClassDef> sourceTypesByBinary,
      Map<String, Map<String, String>> packageObjectTypes,
      Map<ClassDef, ScalaTypeMapper.ImportScope> importScopes,
      Map<ClassDef, ScalaTypeMapper.TypeAliasScope> aliasScopes,
      ParentKindResolver parentKindResolver,
      int majorVersion) {
    String pkg = cls.packageName();
    int access = TurbineFlag.ACC_PUBLIC | TurbineFlag.ACC_SUPER;
    boolean isTrait = cls.kind() == ClassDef.Kind.TRAIT;
    if (isTrait) {
      access = TurbineFlag.ACC_PUBLIC | TurbineFlag.ACC_INTERFACE | TurbineFlag.ACC_ABSTRACT;
    } else {
      if (cls.modifiers().contains("abstract")) {
        access |= TurbineFlag.ACC_ABSTRACT;
      }
      if (cls.modifiers().contains("final")) {
        access |= TurbineFlag.ACC_FINAL;
      }
    }

    String superName = "java/lang/Object";
    List<String> interfaces = new ArrayList<>();
    if (!cls.parents().isEmpty()) {
      String first = cls.parents().get(0);
      String firstErased = eraseType(first, pkg, cls.typeParams(), scope, aliasScope);
      firstErased =
          preferKnownLocalParentType(
              first,
              firstErased,
              pkg,
              sourceTypesByBinary,
              traitsByBinary,
              parentKindResolver);
      firstErased =
          preferLocalObjectParentType(
              first, firstErased, pkg, packageObjectTypes, traitsByBinary, parentKindResolver);
      firstErased = normalizeParentBinary(firstErased);
      if (isTrait) {
        addInterface(interfaces, firstErased);
      } else if (isInterfaceParent(firstErased, traitsByBinary, parentKindResolver)) {
        String firstSuper =
            interfaceSuperclass(
                firstErased,
                sourceTypesByBinary,
                traitsByBinary,
                packageObjectTypes,
                importScopes,
                aliasScopes,
                parentKindResolver);
        if (firstSuper != null && !firstSuper.isEmpty()) {
          superName = firstSuper;
        }
        addInterface(interfaces, firstErased);
      } else {
        superName = firstErased;
      }
      for (int i = 1; i < cls.parents().size(); i++) {
        String erasedParent =
            eraseType(cls.parents().get(i), pkg, cls.typeParams(), scope, aliasScope);
        erasedParent =
            preferKnownLocalParentType(
                cls.parents().get(i),
                erasedParent,
                pkg,
                sourceTypesByBinary,
                traitsByBinary,
                parentKindResolver);
        erasedParent =
            preferLocalObjectParentType(
                cls.parents().get(i),
                erasedParent,
                pkg,
                packageObjectTypes,
                traitsByBinary,
                parentKindResolver);
        erasedParent = normalizeParentBinary(erasedParent);
        addInterface(interfaces, erasedParent);
      }
    }
    if (cls.isCase()) {
      addInterfaceIfNotInherited(
          interfaces,
          superName,
          "scala/Product",
          sourceTypesByBinary,
          traitsByBinary,
          packageObjectTypes,
          importScopes,
          aliasScopes,
          parentKindResolver);
      addInterfaceIfNotInherited(
          interfaces,
          superName,
          "java/io/Serializable",
          sourceTypesByBinary,
          traitsByBinary,
          packageObjectTypes,
          importScopes,
          aliasScopes,
          parentKindResolver);
    }
    interfaces =
        pruneInheritedInterfaces(
            interfaces,
            superName,
            sourceTypesByBinary,
            traitsByBinary,
            packageObjectTypes,
            importScopes,
            aliasScopes,
            parentKindResolver);

    List<ClassFile.MethodInfo> methods = new ArrayList<>();
    if (!isTrait) {
      methods.add(ctorMethod(cls, scope, aliasScope));
    }
    methods.addAll(memberMethods(cls, scope, aliasScope, /* staticContext= */ false));
    if (!isTrait) {
      methods.addAll(
          traitForwarders(
              cls,
              traitsByBinary,
              importScopes,
              aliasScopes,
              packageObjectTypes,
              scope,
              aliasScope,
              /* targetModuleBinary= */ null,
              /* staticContext= */ false,
              /* publicOnly= */ false));
    }
    methods.addAll(
        defaultGettersForDefs(
            cls.members(),
            cls.packageName(),
            ScalaTypeMapper.typeParamNames(cls.typeParams()),
            scope,
            aliasScope,
            /* staticContext= */ false,
            cls.kind()));
    if (hasCtorDefaults) {
      methods.addAll(ctorDefaultGetters(cls, scope, aliasScope, /* staticContext= */ true));
    }
    if (cls.isCase()) {
      methods.addAll(caseClassInstanceMethods(cls, binaryName, scope, aliasScope));
      methods.addAll(caseClassStaticMethods(cls, binaryName, scope, aliasScope));
    }
    if (companion != null) {
      ScalaTypeMapper.ImportScope companionScope =
          importScopes.getOrDefault(companion, ScalaTypeMapper.ImportScope.empty());
      methods.addAll(
          forwarders(
              companion,
              companionScope,
              companionAliases,
              /* isTrait= */ isTrait));
    }
    methods = uniqueMethods(methods);
    List<ClassFile.FieldInfo> fields = memberFields(cls, scope, aliasScope);

    String classSignature = ScalaSignature.classSignature(cls, scope, aliasScope);
    ClassFile classFile =
        new ClassFile(
            access,
            majorVersion,
            binaryName,
            classSignature,
            superName,
            interfaces,
            /* permits= */ ImmutableList.of(),
            methods,
            fields,
            /* annotations= */ scalaClassAnnotations(),
            /* innerClasses= */ ImmutableList.of(),
            /* typeAnnotations= */ ImmutableList.of(),
            /* module= */ null,
            /* nestHost= */ null,
            /* nestMembers= */ ImmutableList.of(),
            /* record= */ null,
            /* transitiveJar= */ null);

    return ImmutableMap.of(binaryName, ClassWriter.writeClass(classFile));
  }

  private static ImmutableMap<String, byte[]> generateObject(ClassDef obj, int majorVersion) {
    return generateObject(
        obj,
        binaryName(obj.packageName(), obj.name() + "$"),
        ImmutableList.of(),
        ScalaTypeMapper.ImportScope.empty(),
        ScalaTypeMapper.TypeAliasScope.empty(),
        ImmutableMap.of(),
        ImmutableMap.of(),
        ImmutableMap.of(),
        ImmutableMap.of(),
        ImmutableMap.of(),
        ParentKindResolver.none(),
        majorVersion,
        /* isTopLevelObject= */ true,
        /* caseCompanionFunctionArity= */ -1);
  }

  private static ImmutableMap<String, byte[]> generateObject(
      ClassDef obj,
      String moduleBinary,
      List<ClassFile.MethodInfo> extraMethods,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      Map<String, ClassDef> traitsByBinary,
      Map<String, ClassDef> sourceTypesByBinary,
      Map<String, Map<String, String>> packageObjectTypes,
      Map<ClassDef, ScalaTypeMapper.ImportScope> importScopes,
      Map<ClassDef, ScalaTypeMapper.TypeAliasScope> aliasScopes,
      ParentKindResolver parentKindResolver,
      int majorVersion,
      boolean isTopLevelObject,
      int caseCompanionFunctionArity) {
    String pkg = obj.packageName();
    int access = TurbineFlag.ACC_PUBLIC | TurbineFlag.ACC_SUPER;
    if (isTopLevelObject) {
      access |= TurbineFlag.ACC_FINAL;
    }
    boolean isCaseObject = obj.isCase();
    boolean isApp = isAppObject(obj, scope, aliasScope);

    List<ClassFile.FieldInfo> fields = new ArrayList<>();
    fields.add(moduleField(moduleBinary));
    fields.addAll(memberFields(obj, scope, aliasScope));
    fields = uniqueFields(fields);

    List<ClassFile.MethodInfo> methods = new ArrayList<>();
    methods.add(defaultConstructor(/* isPublic= */ !isTopLevelObject));
    methods.addAll(memberMethods(obj, scope, aliasScope, /* staticContext= */ false));
    methods.addAll(
        traitForwarders(
            obj,
            traitsByBinary,
            importScopes,
            aliasScopes,
            packageObjectTypes,
            scope,
            aliasScope,
            moduleBinary,
            /* staticContext= */ false,
            /* publicOnly= */ false));
    methods.addAll(
        defaultGettersForDefs(
            obj.members(),
            obj.packageName(),
            ScalaTypeMapper.typeParamNames(obj.typeParams()),
            scope,
            aliasScope,
            /* staticContext= */ false,
            ClassDef.Kind.CLASS));
    if (isCaseObject) {
      methods.addAll(caseObjectInstanceMethods(obj, scope, aliasScope));
    }
    if (isApp) {
      methods.addAll(appInstanceMethods());
    }
    methods.addAll(extraMethods);
    methods = uniqueMethods(methods);

    String superName = "java/lang/Object";
    List<String> interfaces = new ArrayList<>();
    if (!obj.parents().isEmpty()) {
      String first = eraseType(obj.parents().get(0), pkg, obj.typeParams(), scope, aliasScope);
      first =
          preferKnownLocalParentType(
              obj.parents().get(0),
              first,
              pkg,
              sourceTypesByBinary,
              traitsByBinary,
              parentKindResolver);
      first =
          preferLocalObjectParentType(
              obj.parents().get(0),
              first,
              pkg,
              packageObjectTypes,
              traitsByBinary,
              parentKindResolver);
      first = normalizeObjectParentType(first, moduleBinary);
      first = normalizeParentBinary(first);
      if (isInterfaceParent(first, traitsByBinary, parentKindResolver)) {
        String firstSuper =
            interfaceSuperclass(
                first,
                sourceTypesByBinary,
                traitsByBinary,
                packageObjectTypes,
                importScopes,
                aliasScopes,
                parentKindResolver);
        if (firstSuper != null && !firstSuper.isEmpty()) {
          superName = normalizeObjectParentType(firstSuper, moduleBinary);
        }
        addInterface(interfaces, first);
      } else {
        superName = first;
      }
      for (int i = 1; i < obj.parents().size(); i++) {
        String erasedParent = eraseType(obj.parents().get(i), pkg, obj.typeParams(), scope, aliasScope);
        erasedParent =
            preferKnownLocalParentType(
                obj.parents().get(i),
                erasedParent,
                pkg,
                sourceTypesByBinary,
                traitsByBinary,
                parentKindResolver);
        erasedParent =
            preferLocalObjectParentType(
                obj.parents().get(i),
                erasedParent,
                pkg,
                packageObjectTypes,
                traitsByBinary,
                parentKindResolver);
        erasedParent = normalizeObjectParentType(erasedParent, moduleBinary);
        erasedParent = normalizeParentBinary(erasedParent);
        addInterface(interfaces, erasedParent);
      }
    }
    if ("java/lang/Object".equals(superName)
        && caseCompanionFunctionArity >= 0
        && caseCompanionFunctionArity <= 22) {
      superName = "scala/runtime/AbstractFunction" + caseCompanionFunctionArity;
    }
    addInterfaceIfNotInherited(
        interfaces,
        superName,
        "java/io/Serializable",
        sourceTypesByBinary,
        traitsByBinary,
        packageObjectTypes,
        importScopes,
        aliasScopes,
        parentKindResolver);
    if (isApp) {
      addInterface(interfaces, "scala/App");
    }
    if (isCaseObject) {
      addInterfaceIfNotInherited(
          interfaces,
          superName,
          "scala/Product",
          sourceTypesByBinary,
          traitsByBinary,
          packageObjectTypes,
          importScopes,
          aliasScopes,
          parentKindResolver);
    }
    interfaces =
        pruneInheritedInterfaces(
            interfaces,
            superName,
            sourceTypesByBinary,
            traitsByBinary,
            packageObjectTypes,
            importScopes,
            aliasScopes,
            parentKindResolver);

    ClassFile classFile =
        new ClassFile(
            access,
            majorVersion,
            moduleBinary,
            /* signature= */ null,
            superName,
            /* interfaces= */ interfaces,
            /* permits= */ ImmutableList.of(),
            methods,
            fields,
            /* annotations= */ scalaClassAnnotations(),
            /* innerClasses= */ ImmutableList.of(),
            /* typeAnnotations= */ ImmutableList.of(),
            /* module= */ null,
            /* nestHost= */ null,
            /* nestMembers= */ ImmutableList.of(),
            /* record= */ null,
            /* transitiveJar= */ null);

    return ImmutableMap.of(moduleBinary, ClassWriter.writeClass(classFile));
  }

  private static ImmutableMap<String, byte[]> generateObjectMirror(
      ClassDef obj,
      String mirrorBinary,
      String moduleBinary,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      Map<String, ClassDef> traitsByBinary,
      Map<String, Map<String, String>> packageObjectTypes,
      Map<ClassDef, ScalaTypeMapper.ImportScope> importScopes,
      Map<ClassDef, ScalaTypeMapper.TypeAliasScope> aliasScopes,
      int majorVersion) {
    int access = TurbineFlag.ACC_PUBLIC | TurbineFlag.ACC_FINAL | TurbineFlag.ACC_SUPER;
    boolean isApp = isAppObject(obj, scope, aliasScope);

    List<ClassFile.MethodInfo> methods = new ArrayList<>();
    methods.addAll(publicForwarders(obj, scope, aliasScope));
    methods.addAll(
        traitForwarders(
            obj,
            traitsByBinary,
            importScopes,
            aliasScopes,
            packageObjectTypes,
            scope,
            aliasScope,
            moduleBinary,
            /* staticContext= */ true,
            /* publicOnly= */ true));
    if (obj.isCase()) {
      methods.addAll(caseObjectStaticMethods(obj, scope, aliasScope));
    }
    if (isApp) {
      methods.addAll(appStaticMethods());
    }
    methods = uniqueMethods(methods);

    ClassFile classFile =
        new ClassFile(
            access,
            majorVersion,
            mirrorBinary,
            /* signature= */ null,
            "java/lang/Object",
            /* interfaces= */ ImmutableList.of(),
            /* permits= */ ImmutableList.of(),
            methods,
            /* fields= */ ImmutableList.of(),
            /* annotations= */ scalaClassAnnotations(),
            /* innerClasses= */ ImmutableList.of(),
            /* typeAnnotations= */ ImmutableList.of(),
            /* module= */ null,
            /* nestHost= */ null,
            /* nestMembers= */ ImmutableList.of(),
            /* record= */ null,
            /* transitiveJar= */ null);

    return ImmutableMap.of(mirrorBinary, ClassWriter.writeClass(classFile));
  }

  private static ImmutableMap<String, byte[]> generatePackageObject(
      ClassDef pkgObj,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      Map<String, ClassDef> traitsByBinary,
      Map<String, Map<String, String>> packageObjectTypes,
      Map<ClassDef, ScalaTypeMapper.ImportScope> importScopes,
      Map<ClassDef, ScalaTypeMapper.TypeAliasScope> aliasScopes,
      int majorVersion) {
    String pkg = pkgObj.packageName();
    String fullPackage = pkg.isEmpty() ? pkgObj.name() : pkg + "." + pkgObj.name();

    ClassDef companion = pkgObj;

    String moduleBinary = binaryName(fullPackage, "package$");
    String companionBinary = binaryName(fullPackage, "package");

    List<ClassFile.FieldInfo> fields = new ArrayList<>();
    fields.add(moduleField(moduleBinary));
    fields.addAll(memberFields(pkgObj, scope, aliasScope));
    fields = uniqueFields(fields);

    List<ClassFile.MethodInfo> moduleMethods = new ArrayList<>();
    moduleMethods.add(defaultConstructor(/* isPublic= */ false));
    moduleMethods.addAll(memberMethods(pkgObj, scope, aliasScope, /* staticContext= */ false));
    moduleMethods.addAll(
        traitForwarders(
            pkgObj,
            traitsByBinary,
            importScopes,
            aliasScopes,
            packageObjectTypes,
            scope,
            aliasScope,
            moduleBinary,
            /* staticContext= */ false,
            /* publicOnly= */ false));
    moduleMethods.addAll(
        defaultGettersForDefs(
            pkgObj.members(),
            pkgObj.packageName(),
            ScalaTypeMapper.typeParamNames(pkgObj.typeParams()),
            scope,
            aliasScope,
            /* staticContext= */ false,
            ClassDef.Kind.CLASS));
    moduleMethods = uniqueMethods(moduleMethods);

    ClassFile moduleClass =
        new ClassFile(
            TurbineFlag.ACC_PUBLIC | TurbineFlag.ACC_FINAL | TurbineFlag.ACC_SUPER,
            majorVersion,
            moduleBinary,
            /* signature= */ null,
            "java/lang/Object",
            /* interfaces= */ ImmutableList.of(),
            /* permits= */ ImmutableList.of(),
            moduleMethods,
            fields,
            /* annotations= */ scalaClassAnnotations(),
            /* innerClasses= */ ImmutableList.of(),
            /* typeAnnotations= */ ImmutableList.of(),
            /* module= */ null,
            /* nestHost= */ null,
            /* nestMembers= */ ImmutableList.of(),
            /* record= */ null,
            /* transitiveJar= */ null);

    List<ClassFile.MethodInfo> companionMethods = new ArrayList<>();
    companionMethods.addAll(
        forwarders(
            companion,
            scope,
            aliasScope,
            /* isTrait= */ false));
    companionMethods.addAll(
        traitForwarders(
            companion,
            traitsByBinary,
            importScopes,
            aliasScopes,
            packageObjectTypes,
            scope,
            aliasScope,
            moduleBinary,
            /* staticContext= */ true,
            /* publicOnly= */ true));
    companionMethods = uniqueMethods(companionMethods);

    ClassFile companionClass =
        new ClassFile(
            TurbineFlag.ACC_PUBLIC | TurbineFlag.ACC_FINAL | TurbineFlag.ACC_SUPER,
            majorVersion,
            companionBinary,
            /* signature= */ null,
            "java/lang/Object",
            /* interfaces= */ ImmutableList.of(),
            /* permits= */ ImmutableList.of(),
            companionMethods,
            /* fields= */ ImmutableList.of(),
            /* annotations= */ scalaClassAnnotations(),
            /* innerClasses= */ ImmutableList.of(),
            /* typeAnnotations= */ ImmutableList.of(),
            /* module= */ null,
            /* nestHost= */ null,
            /* nestMembers= */ ImmutableList.of(),
            /* record= */ null,
            /* transitiveJar= */ null);

    return ImmutableMap.of(
        moduleBinary, ClassWriter.writeClass(moduleClass),
        companionBinary, ClassWriter.writeClass(companionClass));
  }

  private static ImmutableMap<String, byte[]> generateTraitImplClass(
      ClassDef traitDef,
      String traitBinary,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      int majorVersion) {
    List<ClassFile.MethodInfo> methods = new ArrayList<>();
    boolean hasConcrete = false;
    for (Defn defn : traitDef.members()) {
      if (defn instanceof DefDef def) {
        if (!isAbstractDef(def, ClassDef.Kind.TRAIT)) {
          methods.add(buildTraitImplMethod(def, traitDef, traitBinary, scope, aliasScope));
          hasConcrete = true;
        }
      } else if (defn instanceof ValDef val) {
        if (!isAbstractVal(val, ClassDef.Kind.TRAIT)) {
          methods.addAll(buildTraitImplAccessors(val, traitDef, traitBinary, scope, aliasScope));
          hasConcrete = true;
        }
      }
    }
    if (!hasConcrete) {
      return ImmutableMap.of();
    }

    methods.add(traitInitMethod(traitDef, traitBinary, scope, aliasScope));
    methods.add(defaultConstructor(/* isPublic= */ false));
    methods = uniqueMethods(methods);

    String implBinary = traitBinary + "$class";
    ClassFile classFile =
        new ClassFile(
            TurbineFlag.ACC_PUBLIC | TurbineFlag.ACC_FINAL | TurbineFlag.ACC_SUPER,
            majorVersion,
            implBinary,
            /* signature= */ null,
            "java/lang/Object",
            /* interfaces= */ ImmutableList.of(),
            /* permits= */ ImmutableList.of(),
            methods,
            /* fields= */ ImmutableList.of(),
            /* annotations= */ scalaClassAnnotations(),
            /* innerClasses= */ ImmutableList.of(),
            /* typeAnnotations= */ ImmutableList.of(),
            /* module= */ null,
            /* nestHost= */ null,
            /* nestMembers= */ ImmutableList.of(),
            /* record= */ null,
            /* transitiveJar= */ null);

    return ImmutableMap.of(implBinary, ClassWriter.writeClass(classFile));
  }

  private static ClassFile.FieldInfo moduleField(String binaryName) {
    return new ClassFile.FieldInfo(
        TurbineFlag.ACC_PUBLIC | TurbineFlag.ACC_STATIC | TurbineFlag.ACC_FINAL,
        "MODULE$",
        "L" + binaryName + ";",
        /* signature= */ null,
        /* value= */ null,
        /* annotations= */ ImmutableList.of(),
        /* typeAnnotations= */ ImmutableList.of());
  }

  private static List<ClassFile.MethodInfo> memberMethods(
      ClassDef cls,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      boolean staticContext) {
    List<ClassFile.MethodInfo> methods = new ArrayList<>();
    Set<String> typeParams = ScalaTypeMapper.typeParamNames(cls.typeParams());
    for (Defn defn : cls.members()) {
      if (defn instanceof DefDef def) {
        if ("this".equals(def.name()) && cls.kind() == ClassDef.Kind.CLASS) {
          methods.add(ctorMethod(def, cls, scope, aliasScope));
        } else {
          methods.addAll(
              buildMethods(
                  def,
                  cls.packageName(),
                  typeParams,
                  scope,
                  aliasScope,
                  staticContext,
                  cls.kind()));
        }
      } else if (defn instanceof ValDef val) {
        methods.addAll(
            accessorsForVal(
                val,
                cls.packageName(),
                typeParams,
                scope,
                aliasScope,
                staticContext,
                cls.kind()));
      }
    }
    // constructor params with val/var become accessors
    for (ParamList list : cls.ctorParams()) {
      for (Param param : list.params()) {
        if (param.modifiers().contains("val") || param.modifiers().contains("var") || cls.isCase()) {
          ValDef val =
              new ValDef(
                  cls.packageName(),
                  param.name(),
                  param.modifiers().contains("var"),
                  param.modifiers(),
                  param.type(),
                  param.type() != null,
                  /* hasDefault= */ true,
                  cls.position());
          methods.addAll(
              accessorsForVal(
                  val,
                  cls.packageName(),
                  typeParams,
                  scope,
                  aliasScope,
                  staticContext,
                  cls.kind()));
        }
      }
    }
    return methods;
  }

  private static List<ClassFile.FieldInfo> memberFields(
      ClassDef cls,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope) {
    if (cls.kind() == ClassDef.Kind.TRAIT) {
      return ImmutableList.of();
    }
    List<ClassFile.FieldInfo> fields = new ArrayList<>();
    Set<String> typeParams = ScalaTypeMapper.typeParamNames(cls.typeParams());
    for (Defn defn : cls.members()) {
      if (defn instanceof ValDef val) {
        if (val.hasDefault()) {
          fields.add(fieldForVal(val, cls.packageName(), typeParams, scope, aliasScope));
        }
      }
    }
    for (ParamList list : cls.ctorParams()) {
      for (Param param : list.params()) {
        if (param.modifiers().contains("val") || param.modifiers().contains("var") || cls.isCase()) {
          fields.add(fieldForParam(param, cls.packageName(), typeParams, scope, aliasScope));
        }
      }
    }
    return uniqueFields(fields);
  }

  private static ClassFile.FieldInfo fieldForVal(
      ValDef val,
      String pkg,
      Set<String> typeParams,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope) {
    int access = fieldAccess(val.isVar(), val.modifiers());
    String name = encodeName(val.name());
    String desc = ScalaTypeMapper.descriptorForParam(val.type(), pkg, typeParams, scope, aliasScope);
    return new ClassFile.FieldInfo(
        access,
        name,
        desc,
        /* signature= */ null,
        /* value= */ null,
        /* annotations= */ ImmutableList.of(),
        /* typeAnnotations= */ ImmutableList.of());
  }

  private static ClassFile.FieldInfo fieldForParam(
      Param param,
      String pkg,
      Set<String> typeParams,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope) {
    boolean isVar = param.modifiers().contains("var");
    int access = fieldAccess(isVar, param.modifiers());
    String name = encodeName(param.name());
    String desc = ScalaTypeMapper.descriptorForParam(param.type(), pkg, typeParams, scope, aliasScope);
    return new ClassFile.FieldInfo(
        access,
        name,
        desc,
        /* signature= */ null,
        /* value= */ null,
        /* annotations= */ ImmutableList.of(),
        /* typeAnnotations= */ ImmutableList.of());
  }

  private static int fieldAccess(boolean isVar, ImmutableList<String> modifiers) {
    int access = TurbineFlag.ACC_PRIVATE;
    if (!isVar && !modifiers.contains("lazy")) {
      access |= TurbineFlag.ACC_FINAL;
    }
    if (modifiers.contains("lazy")) {
      access |= TurbineFlag.ACC_VOLATILE;
    }
    return access;
  }

  private static List<ClassFile.MethodInfo> forwarders(
      ClassDef obj,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      boolean isTrait) {
    List<ClassFile.MethodInfo> methods = new ArrayList<>();
    Set<String> typeParams = ScalaTypeMapper.typeParamNames(obj.typeParams());
    ClassDef.Kind ownerKind = isTrait ? ClassDef.Kind.TRAIT : ClassDef.Kind.CLASS;
    for (Defn defn : obj.members()) {
      if (defn instanceof DefDef def) {
        methods.addAll(
            buildMethods(
                def,
                obj.packageName(),
                typeParams,
                scope,
                aliasScope,
                /* staticContext= */ true,
                ownerKind));
      } else if (defn instanceof ValDef val) {
        methods.addAll(
            accessorsForVal(
                val,
                obj.packageName(),
                typeParams,
                scope,
                aliasScope,
                /* staticContext= */ true,
                ownerKind));
      }
    }
    methods.addAll(
        defaultGettersForDefs(
            obj.members(),
            obj.packageName(),
            typeParams,
            scope,
            aliasScope,
            /* staticContext= */ true,
            ownerKind));
    return methods;
  }

  private static List<ClassFile.MethodInfo> traitForwarders(
      ClassDef target,
      Map<String, ClassDef> traitsByBinary,
      Map<ClassDef, ScalaTypeMapper.ImportScope> importScopes,
      Map<ClassDef, ScalaTypeMapper.TypeAliasScope> aliasScopes,
      Map<String, Map<String, String>> packageObjectTypes,
      ScalaTypeMapper.ImportScope targetScope,
      ScalaTypeMapper.TypeAliasScope targetAliases,
      String targetModuleBinary,
      boolean staticContext,
      boolean publicOnly) {
    List<ClassFile.MethodInfo> methods = new ArrayList<>();
    if (traitsByBinary.isEmpty() || target.parents().isEmpty()) {
      return methods;
    }
    Set<String> classTypeParams = ScalaTypeMapper.typeParamNames(target.typeParams());
    Deque<TraitRef> pending = new ArrayDeque<>();
    for (String parent : target.parents()) {
      String binary =
          eraseType(parent, target.packageName(), target.typeParams(), targetScope, targetAliases);
      binary =
          normalizeTraitForwarderParent(
              parent,
              binary,
              target.packageName(),
              targetModuleBinary,
              packageObjectTypes,
              traitsByBinary);
      ClassDef trait = traitsByBinary.get(binary);
      if (trait != null) {
        pending.addLast(new TraitRef(trait, parent));
      }
    }
    Set<String> seen = new HashSet<>();
    while (!pending.isEmpty()) {
      TraitRef current = pending.removeFirst();
      String currentBinary = binaryName(current.trait().packageName(), current.trait().name());
      String currentKey = currentBinary + "::" + current.parentTypeText();
      if (!seen.add(currentKey)) {
        continue;
      }
      ScalaTypeMapper.ImportScope traitScope =
          importScopes.getOrDefault(current.trait(), ScalaTypeMapper.ImportScope.empty());
      ScalaTypeMapper.TypeAliasScope traitAliases =
          aliasScopes.getOrDefault(current.trait(), ScalaTypeMapper.TypeAliasScope.empty());
      Map<String, String> substitutions =
          traitTypeSubstitutions(current.trait(), current.parentTypeText());
      for (Defn defn : current.trait().members()) {
        if (defn instanceof DefDef def) {
          if (isAbstractDef(def, ClassDef.Kind.TRAIT)) {
            continue;
          }
          Map<String, String> filtered = substitutions;
          if (!substitutions.isEmpty() && !def.typeParams().isEmpty()) {
            filtered = new HashMap<>(substitutions);
            for (TypeParam tp : def.typeParams()) {
              filtered.remove(tp.name());
            }
          }
          DefDef adjusted = substituteDef(def, filtered);
          methods.addAll(
              buildMethods(
                  adjusted,
                  current.trait().packageName(),
                  classTypeParams,
                  traitScope,
                  traitAliases,
                  staticContext,
                  ClassDef.Kind.TRAIT));
        } else if (defn instanceof ValDef val) {
          if (isAbstractVal(val, ClassDef.Kind.TRAIT)) {
            continue;
          }
          ValDef adjusted = substituteVal(val, substitutions);
          methods.addAll(
              accessorsForVal(
                  adjusted,
                  current.trait().packageName(),
                  classTypeParams,
                  traitScope,
                  traitAliases,
                  staticContext,
                  ClassDef.Kind.TRAIT));
        }
      }
      if (current.trait().parents().isEmpty()) {
        continue;
      }
      for (String parent : current.trait().parents()) {
        String substitutedParent = substituteType(parent, substitutions);
        String parentBinary =
            eraseType(
                substitutedParent,
                current.trait().packageName(),
                current.trait().typeParams(),
                traitScope,
                traitAliases);
        parentBinary =
            normalizeTraitForwarderParent(
                substitutedParent,
                parentBinary,
                current.trait().packageName(),
                /* targetModuleBinary= */ null,
                packageObjectTypes,
                traitsByBinary);
        ClassDef parentTrait = traitsByBinary.get(parentBinary);
        if (parentTrait != null) {
          pending.addLast(new TraitRef(parentTrait, substitutedParent));
        }
      }
    }
    if (publicOnly) {
      List<ClassFile.MethodInfo> publicMethods = new ArrayList<>();
      for (ClassFile.MethodInfo method : methods) {
        if ((method.access() & TurbineFlag.ACC_PUBLIC) != 0) {
          publicMethods.add(method);
        }
      }
      return publicMethods;
    }
    return methods;
  }

  private static List<ClassFile.MethodInfo> publicForwarders(
      ClassDef obj,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope) {
    List<ClassFile.MethodInfo> methods = forwarders(obj, scope, aliasScope, /* isTrait= */ false);
    List<ClassFile.MethodInfo> publicMethods = new ArrayList<>();
    for (ClassFile.MethodInfo method : methods) {
      if ((method.access() & TurbineFlag.ACC_PUBLIC) != 0) {
        publicMethods.add(method);
      }
    }
    return publicMethods;
  }

  private static ClassFile.MethodInfo ctorMethod(
      ClassDef cls, ScalaTypeMapper.ImportScope scope, ScalaTypeMapper.TypeAliasScope aliasScope) {
    List<Param> params = new ArrayList<>();
    for (ParamList list : cls.ctorParams()) {
      params.addAll(list.params());
    }
    StringBuilder desc = new StringBuilder();
    desc.append('(');
    List<String> paramTypes = new ArrayList<>();
    Set<String> typeParams = ScalaTypeMapper.typeParamNames(cls.typeParams());
    for (Param param : params) {
      desc.append(
          ScalaTypeMapper.descriptorForParam(
              param.type(), cls.packageName(), typeParams, scope, aliasScope));
      paramTypes.add(param.type());
    }
    desc.append(')').append('V');
    String signature =
        ScalaSignature.methodSignature(
            ImmutableList.of(),
            paramTypes,
            null,
            typeParams,
            cls.packageName(),
            scope,
            aliasScope);
    return new ClassFile.MethodInfo(
        TurbineFlag.ACC_PUBLIC,
        "<init>",
        desc.toString(),
        signature,
        /* exceptions= */ ImmutableList.of(),
        /* defaultValue= */ null,
        /* annotations= */ ImmutableList.of(),
        /* parameterAnnotations= */ ImmutableList.of(),
        /* typeAnnotations= */ ImmutableList.of(),
        /* parameters= */ ImmutableList.of());
  }

  private static ClassFile.MethodInfo ctorMethod(
      DefDef def,
      ClassDef cls,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope) {
    StringBuilder desc = new StringBuilder();
    desc.append('(');
    List<String> paramTypes = new ArrayList<>();
    Set<String> typeParams = ScalaTypeMapper.typeParamNames(cls.typeParams());
    for (ParamList list : def.paramLists()) {
      for (Param param : list.params()) {
        desc.append(
            ScalaTypeMapper.descriptorForParam(
                param.type(), cls.packageName(), typeParams, scope, aliasScope));
        paramTypes.add(param.type());
      }
    }
    desc.append(')').append('V');
    String signature =
        ScalaSignature.methodSignature(
            ImmutableList.of(),
            paramTypes,
            null,
            typeParams,
            cls.packageName(),
            scope,
            aliasScope);
    int access = visibility(def.modifiers(), cls.kind());
    return new ClassFile.MethodInfo(
        access,
        "<init>",
        desc.toString(),
        signature,
        /* exceptions= */ ImmutableList.of(),
        /* defaultValue= */ null,
        /* annotations= */ ImmutableList.of(),
        /* parameterAnnotations= */ ImmutableList.of(),
        /* typeAnnotations= */ ImmutableList.of(),
        /* parameters= */ ImmutableList.of());
  }

  private static ClassFile.MethodInfo defaultConstructor(boolean isPublic) {
    int access = isPublic ? TurbineFlag.ACC_PUBLIC : TurbineFlag.ACC_PRIVATE;
    return new ClassFile.MethodInfo(
        access,
        "<init>",
        "()V",
        /* signature= */ null,
        /* exceptions= */ ImmutableList.of(),
        /* defaultValue= */ null,
        /* annotations= */ ImmutableList.of(),
        /* parameterAnnotations= */ ImmutableList.of(),
        /* typeAnnotations= */ ImmutableList.of(),
        /* parameters= */ ImmutableList.of());
  }

  private static boolean isAbstractDef(DefDef def, ClassDef.Kind ownerKind) {
    return def.modifiers().contains("abstract");
  }

  private static boolean isAbstractVal(ValDef val, ClassDef.Kind ownerKind) {
    if (val.modifiers().contains("abstract")) {
      return true;
    }
    return !val.hasDefault();
  }

  private static int methodAccess(
      ImmutableList<String> modifiers,
      boolean staticContext,
      boolean isAbstract,
      ClassDef.Kind ownerKind) {
    int access = visibility(modifiers, ownerKind);
    if (staticContext) {
      access |= TurbineFlag.ACC_STATIC;
    }
    if (isAbstract) {
      access |= TurbineFlag.ACC_ABSTRACT;
    }
    if (!staticContext && ownerKind != ClassDef.Kind.TRAIT && modifiers.contains("final")) {
      access |= TurbineFlag.ACC_FINAL;
    }
    return access;
  }

  private static int visibility(ImmutableList<String> modifiers, ClassDef.Kind ownerKind) {
    if (ownerKind == ClassDef.Kind.TRAIT) {
      return TurbineFlag.ACC_PUBLIC;
    }
    if (modifiers.contains("private")) {
      return TurbineFlag.ACC_PRIVATE;
    }
    if (modifiers.contains("protected")) {
      return TurbineFlag.ACC_PROTECTED;
    }
    return TurbineFlag.ACC_PUBLIC;
  }

  private static ClassFile.MethodInfo buildMethod(
      DefDef def,
      String pkg,
      Set<String> classTypeParams,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      boolean staticContext,
      ClassDef.Kind ownerKind) {
    Set<String> typeParams = new HashSet<>(classTypeParams);
    typeParams.addAll(ScalaTypeMapper.typeParamNames(def.typeParams()));

    StringBuilder desc = new StringBuilder();
    desc.append('(');
    List<String> paramTypes = new ArrayList<>();
    for (ParamList list : def.paramLists()) {
      for (Param param : list.params()) {
        desc.append(
            ScalaTypeMapper.descriptorForParam(param.type(), pkg, typeParams, scope, aliasScope));
        paramTypes.add(param.type());
      }
    }
    desc.append(')');
    desc.append(
        ScalaTypeMapper.descriptorForReturn(def.returnType(), pkg, typeParams, scope, aliasScope));

    boolean isAbstract = isAbstractDef(def, ownerKind);
    int access = methodAccess(def.modifiers(), staticContext, isAbstract, ownerKind);

    String signature =
        ScalaSignature.methodSignature(
            def.typeParams(), paramTypes, def.returnType(), typeParams, pkg, scope, aliasScope);
    return new ClassFile.MethodInfo(
        access,
        encodeName(def.name()),
        desc.toString(),
        signature,
        /* exceptions= */ ImmutableList.of(),
        /* defaultValue= */ null,
        /* annotations= */ ImmutableList.of(),
        /* parameterAnnotations= */ ImmutableList.of(),
        /* typeAnnotations= */ ImmutableList.of(),
        /* parameters= */ ImmutableList.of());
  }

  private static List<ClassFile.MethodInfo> buildMethods(
      DefDef def,
      String pkg,
      Set<String> classTypeParams,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      boolean staticContext,
      ClassDef.Kind ownerKind) {
    ClassFile.MethodInfo method =
        buildMethod(def, pkg, classTypeParams, scope, aliasScope, staticContext, ownerKind);
    if (isAbstractDef(def, ownerKind) || !hasVarArgsParam(def)) {
      return ImmutableList.of(method);
    }
    ClassFile.MethodInfo bridge =
        buildVarArgsBridge(def, pkg, classTypeParams, scope, aliasScope, staticContext, ownerKind);
    if (bridge == null) {
      return ImmutableList.of(method);
    }
    return ImmutableList.of(method, bridge);
  }

  private static boolean hasVarArgsParam(DefDef def) {
    for (ParamList list : def.paramLists()) {
      for (Param param : list.params()) {
        if (ScalaTypeMapper.isVarArgsType(param.type())) {
          return true;
        }
      }
    }
    return false;
  }

  private static ClassFile.MethodInfo buildVarArgsBridge(
      DefDef def,
      String pkg,
      Set<String> classTypeParams,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      boolean staticContext,
      ClassDef.Kind ownerKind) {
    StringBuilder desc = new StringBuilder();
    desc.append('(');
    Set<String> typeParams = new HashSet<>(classTypeParams);
    typeParams.addAll(ScalaTypeMapper.typeParamNames(def.typeParams()));
    boolean hasVarArgs = false;
    for (ParamList list : def.paramLists()) {
      for (Param param : list.params()) {
        if (ScalaTypeMapper.isVarArgsType(param.type())) {
          hasVarArgs = true;
          desc.append(
              ScalaTypeMapper.descriptorForVarArgsParam(
                  param.type(), pkg, typeParams, scope, aliasScope));
        } else {
          desc.append(
              ScalaTypeMapper.descriptorForParam(
                  param.type(), pkg, typeParams, scope, aliasScope));
        }
      }
    }
    if (!hasVarArgs) {
      return null;
    }
    desc.append(')');
    desc.append(
        ScalaTypeMapper.descriptorForReturn(def.returnType(), pkg, typeParams, scope, aliasScope));
    int access =
        methodAccess(def.modifiers(), staticContext, /* isAbstract= */ false, ownerKind)
            | TurbineFlag.ACC_VARARGS;
    return new ClassFile.MethodInfo(
        access,
        encodeName(def.name()),
        desc.toString(),
        /* signature= */ null,
        /* exceptions= */ ImmutableList.of(),
        /* defaultValue= */ null,
        /* annotations= */ ImmutableList.of(),
        /* parameterAnnotations= */ ImmutableList.of(),
        /* typeAnnotations= */ ImmutableList.of(),
        /* parameters= */ ImmutableList.of());
  }

  private static List<ClassFile.MethodInfo> accessorsForVal(
      ValDef val,
      String pkg,
      Set<String> typeParams,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      boolean staticContext,
      ClassDef.Kind ownerKind) {
    List<ClassFile.MethodInfo> methods = new ArrayList<>();
    String getterDesc =
        "()" + ScalaTypeMapper.descriptorForReturn(val.type(), pkg, typeParams, scope, aliasScope);
    boolean isAbstract = isAbstractVal(val, ownerKind);
    int access = methodAccess(val.modifiers(), staticContext, isAbstract, ownerKind);
    String encodedName = encodeName(val.name());
    String getterSignature =
        ScalaSignature.methodSignature(
            ImmutableList.of(),
            ImmutableList.of(),
            val.type(),
            typeParams,
            pkg,
            scope,
            aliasScope);
    methods.add(
        new ClassFile.MethodInfo(
            access,
            encodedName,
            getterDesc,
            getterSignature,
            /* exceptions= */ ImmutableList.of(),
            /* defaultValue= */ null,
            /* annotations= */ ImmutableList.of(),
            /* parameterAnnotations= */ ImmutableList.of(),
            /* typeAnnotations= */ ImmutableList.of(),
            /* parameters= */ ImmutableList.of()));

    if (val.isVar()) {
      String setterDesc =
          "("
              + ScalaTypeMapper.descriptorForParam(val.type(), pkg, typeParams, scope, aliasScope)
              + ")V";
      List<String> setterParamTypes = new ArrayList<>();
      setterParamTypes.add(val.type());
      String setterSignature =
          ScalaSignature.methodSignature(
              ImmutableList.of(),
              setterParamTypes,
              "Unit",
              typeParams,
              pkg,
              scope,
              aliasScope);
      methods.add(
          new ClassFile.MethodInfo(
              access,
              encodedName + "_$eq",
              setterDesc,
              setterSignature,
              /* exceptions= */ ImmutableList.of(),
              /* defaultValue= */ null,
              /* annotations= */ ImmutableList.of(),
              /* parameterAnnotations= */ ImmutableList.of(),
              /* typeAnnotations= */ ImmutableList.of(),
              /* parameters= */ ImmutableList.of()));
    }
    return methods;
  }

  private static ClassFile.MethodInfo buildTraitImplMethod(
      DefDef def,
      ClassDef traitDef,
      String traitBinary,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope) {
    Set<String> typeParams = new HashSet<>(ScalaTypeMapper.typeParamNames(traitDef.typeParams()));
    typeParams.addAll(ScalaTypeMapper.typeParamNames(def.typeParams()));

    StringBuilder desc = new StringBuilder();
    desc.append('(');
    desc.append('L').append(traitBinary).append(';');
    List<String> paramTypes = new ArrayList<>();
    paramTypes.add(traitSelfTypeText(traitDef));
    for (ParamList list : def.paramLists()) {
      for (Param param : list.params()) {
        desc.append(
            ScalaTypeMapper.descriptorForParam(
                param.type(), traitDef.packageName(), typeParams, scope, aliasScope));
        paramTypes.add(param.type());
      }
    }
    desc.append(')');
    desc.append(
        ScalaTypeMapper.descriptorForReturn(
            def.returnType(), traitDef.packageName(), typeParams, scope, aliasScope));

    ImmutableList<TypeParam> declared =
        concatTypeParams(traitDef.typeParams(), def.typeParams());
    String signature =
        ScalaSignature.methodSignature(
            declared,
            paramTypes,
            def.returnType(),
            typeParams,
            traitDef.packageName(),
            scope,
            aliasScope);

    return new ClassFile.MethodInfo(
        TurbineFlag.ACC_PUBLIC | TurbineFlag.ACC_STATIC,
        encodeName(def.name()),
        desc.toString(),
        signature,
        /* exceptions= */ ImmutableList.of(),
        /* defaultValue= */ null,
        /* annotations= */ ImmutableList.of(),
        /* parameterAnnotations= */ ImmutableList.of(),
        /* typeAnnotations= */ ImmutableList.of(),
        /* parameters= */ ImmutableList.of());
  }

  private static List<ClassFile.MethodInfo> buildTraitImplAccessors(
      ValDef val,
      ClassDef traitDef,
      String traitBinary,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope) {
    Set<String> typeParams = ScalaTypeMapper.typeParamNames(traitDef.typeParams());
    List<ClassFile.MethodInfo> methods = new ArrayList<>();
    String getterDesc =
        "(L"
            + traitBinary
            + ";)"
            + ScalaTypeMapper.descriptorForReturn(
                val.type(), traitDef.packageName(), typeParams, scope, aliasScope);
    String getterSignature =
        ScalaSignature.methodSignature(
            traitDef.typeParams(),
            ImmutableList.of(traitSelfTypeText(traitDef)),
            val.type(),
            typeParams,
            traitDef.packageName(),
            scope,
            aliasScope);
    methods.add(
        new ClassFile.MethodInfo(
            TurbineFlag.ACC_PUBLIC | TurbineFlag.ACC_STATIC,
            encodeName(val.name()),
            getterDesc,
            getterSignature,
            /* exceptions= */ ImmutableList.of(),
            /* defaultValue= */ null,
            /* annotations= */ ImmutableList.of(),
            /* parameterAnnotations= */ ImmutableList.of(),
            /* typeAnnotations= */ ImmutableList.of(),
            /* parameters= */ ImmutableList.of()));

    if (val.isVar()) {
      String setterDesc =
          "(L"
              + traitBinary
              + ";"
              + ScalaTypeMapper.descriptorForParam(
                  val.type(), traitDef.packageName(), typeParams, scope, aliasScope)
              + ")V";
      List<String> paramTypes = new ArrayList<>();
      paramTypes.add(traitSelfTypeText(traitDef));
      paramTypes.add(val.type());
      String setterSignature =
          ScalaSignature.methodSignature(
              traitDef.typeParams(),
              paramTypes,
              "Unit",
              typeParams,
              traitDef.packageName(),
              scope,
              aliasScope);
      methods.add(
          new ClassFile.MethodInfo(
              TurbineFlag.ACC_PUBLIC | TurbineFlag.ACC_STATIC,
              encodeName(val.name()) + "_$eq",
              setterDesc,
              setterSignature,
              /* exceptions= */ ImmutableList.of(),
              /* defaultValue= */ null,
              /* annotations= */ ImmutableList.of(),
              /* parameterAnnotations= */ ImmutableList.of(),
              /* typeAnnotations= */ ImmutableList.of(),
              /* parameters= */ ImmutableList.of()));
    }
    return methods;
  }

  private static ClassFile.MethodInfo traitInitMethod(
      ClassDef traitDef,
      String traitBinary,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope) {
    String desc = "(L" + traitBinary + ";)V";
    List<String> paramTypes = ImmutableList.of(traitSelfTypeText(traitDef));
    Set<String> typeParams = ScalaTypeMapper.typeParamNames(traitDef.typeParams());
    String signature =
        ScalaSignature.methodSignature(
            traitDef.typeParams(),
            paramTypes,
            null,
            typeParams,
            traitDef.packageName(),
            scope,
            aliasScope);
    return new ClassFile.MethodInfo(
        TurbineFlag.ACC_PUBLIC | TurbineFlag.ACC_STATIC,
        "$init$",
        desc,
        signature,
        /* exceptions= */ ImmutableList.of(),
        /* defaultValue= */ null,
        /* annotations= */ ImmutableList.of(),
        /* parameterAnnotations= */ ImmutableList.of(),
        /* typeAnnotations= */ ImmutableList.of(),
        /* parameters= */ ImmutableList.of());
  }

  private static String traitSelfTypeText(ClassDef traitDef) {
    if (traitDef.typeParams().isEmpty()) {
      return traitDef.name();
    }
    StringBuilder sb = new StringBuilder();
    sb.append(traitDef.name()).append(" [ ");
    for (int i = 0; i < traitDef.typeParams().size(); i++) {
      if (i > 0) {
        sb.append(" , ");
      }
      sb.append(traitDef.typeParams().get(i).name());
    }
    sb.append(" ]");
    return sb.toString();
  }

  private static ImmutableList<TypeParam> concatTypeParams(
      ImmutableList<TypeParam> first, ImmutableList<TypeParam> second) {
    if (first.isEmpty()) {
      return second;
    }
    if (second.isEmpty()) {
      return first;
    }
    ImmutableList.Builder<TypeParam> out = ImmutableList.builder();
    out.addAll(first);
    out.addAll(second);
    return out.build();
  }

  private static String eraseType(
      String typeText,
      String pkg,
      List<ScalaTree.TypeParam> tparams,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope) {
    String desc =
        ScalaTypeMapper.descriptorForParam(
            typeText, pkg, ScalaTypeMapper.typeParamNames(tparams), scope, aliasScope);
    if (desc.startsWith("L") && desc.endsWith(";")) {
      return desc.substring(1, desc.length() - 1);
    }
    if (desc.startsWith("[")) {
      return "java/lang/Object";
    }
    return "java/lang/Object";
  }

  private static String binaryName(String pkg, String name) {
    if (pkg == null || pkg.isEmpty()) {
      return name;
    }
    return pkg.replace('.', '/') + "/" + name;
  }

  private static String encodeName(String name) {
    if (name == null || name.isEmpty()) {
      return name;
    }
    if (name.startsWith("<") && name.endsWith(">")) {
      return name;
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (Character.isJavaIdentifierPart(c) || c == '$') {
        sb.append(c);
        continue;
      }
      String op = encodeOperatorChar(c);
      if (op != null) {
        sb.append(op);
      } else {
        sb.append("$u").append(toHex4(c));
      }
    }
    return sb.toString();
  }

  private static String encodeOperatorChar(char c) {
    return switch (c) {
      case '+' -> "$plus";
      case '-' -> "$minus";
      case '*' -> "$times";
      case '/' -> "$div";
      case '%' -> "$percent";
      case '&' -> "$amp";
      case '|' -> "$bar";
      case '^' -> "$up";
      case '!' -> "$bang";
      case '~' -> "$tilde";
      case '=' -> "$eq";
      case '<' -> "$less";
      case '>' -> "$greater";
      case ':' -> "$colon";
      case '?' -> "$qmark";
      case '@' -> "$at";
      case '#' -> "$hash";
      case '\\' -> "$bslash";
      default -> null;
    };
  }

  private static String toHex4(char c) {
    String hex = Integer.toHexString(c);
    if (hex.length() >= 4) {
      return hex;
    }
    StringBuilder sb = new StringBuilder(4);
    for (int i = hex.length(); i < 4; i++) {
      sb.append('0');
    }
    sb.append(hex);
    return sb.toString();
  }

  private record Key(String binaryName) {}

  private record TraitRef(ClassDef trait, String parentTypeText) {}

  private static ClassCollection collectClassDefs(ImmutableList<ScalaTree.CompUnit> units) {
    ClassCollection defs = new ClassCollection();
    for (ScalaTree.CompUnit unit : units) {
      List<ClassDef> unitDefs = new ArrayList<>();
      for (ScalaTree.Stat stat : unit.stats()) {
        if (stat instanceof ClassDef cls) {
          collectClassDef(cls, /* owner= */ null, /* topLevel= */ true, defs, unitDefs);
        }
      }
      defs.unitClassDefs().put(unit, unitDefs);
    }
    return defs;
  }

  private static void collectClassDef(
      ClassDef cls,
      ClassDef owner,
      boolean topLevel,
      ClassCollection defs,
      List<ClassDef> unitDefs) {
    defs.allDefs().add(cls);
    defs.owners().put(cls, owner);
    unitDefs.add(cls);
    if (cls.isPackageObject()) {
      defs.packageObjects().add(cls);
      defs.moduleBinaryNames().put(cls, objectModuleBinaryFromOwner(cls, owner, defs));
    } else if (cls.kind() == ClassDef.Kind.OBJECT) {
      defs.objectDefs().add(cls);
      defs.moduleBinaryNames().put(cls, objectModuleBinaryFromOwner(cls, owner, defs));
      if (topLevel) {
        defs.topLevelObjects().add(cls);
      }
    } else {
      defs.classDefs().add(cls);
      defs.classBinaryNames().put(cls, classBinaryFromOwner(cls, owner, defs));
      if (topLevel) {
        defs.topLevelClassDefs().add(cls);
      }
    }
    for (Defn defn : cls.members()) {
      if (defn instanceof ClassDef nested) {
        collectClassDef(nested, cls, /* topLevel= */ false, defs, unitDefs);
      }
    }
  }

  private static String classBinaryFromOwner(ClassDef cls, ClassDef owner, ClassCollection defs) {
    if (owner == null) {
      return binaryName(cls.packageName(), cls.name());
    }
    return nestedMemberBinaryPrefix(owner, defs.classBinaryNames(), defs.moduleBinaryNames())
        + cls.name();
  }

  private static String objectModuleBinaryFromOwner(
      ClassDef cls, ClassDef owner, ClassCollection defs) {
    if (cls.isPackageObject() && owner == null) {
      return packageObjectModuleBinary(cls);
    }
    if (owner == null) {
      return binaryName(cls.packageName(), cls.name() + "$");
    }
    return nestedMemberBinaryPrefix(owner, defs.classBinaryNames(), defs.moduleBinaryNames())
        + cls.name()
        + "$";
  }

  private static String packageObjectModuleBinary(ClassDef cls) {
    String pkg = cls.packageName();
    String fullPackage = pkg.isEmpty() ? cls.name() : pkg + "." + cls.name();
    return binaryName(fullPackage, "package$");
  }

  private static String classBinaryName(
      ClassDef cls,
      Map<ClassDef, String> classBinaryNames,
      Map<ClassDef, String> moduleBinaryNames) {
    String binary = classBinaryNames.get(cls);
    if (binary != null) {
      return binary;
    }
    if (cls.kind() == ClassDef.Kind.OBJECT || cls.isPackageObject()) {
      String module = moduleBinaryName(cls, classBinaryNames, moduleBinaryNames);
      if (module.endsWith("$")) {
        return module.substring(0, module.length() - 1);
      }
      return module;
    }
    return binaryName(cls.packageName(), cls.name());
  }

  private static String moduleBinaryName(
      ClassDef cls,
      Map<ClassDef, String> classBinaryNames,
      Map<ClassDef, String> moduleBinaryNames) {
    String binary = moduleBinaryNames.get(cls);
    if (binary != null) {
      return binary;
    }
    if (cls.isPackageObject()) {
      return packageObjectModuleBinary(cls);
    }
    if (cls.kind() == ClassDef.Kind.OBJECT) {
      return binaryName(cls.packageName(), cls.name() + "$");
    }
    return classBinaryName(cls, classBinaryNames, moduleBinaryNames) + "$";
  }

  private static String classKey(
      ClassDef cls,
      Map<ClassDef, String> classBinaryNames,
      Map<ClassDef, String> moduleBinaryNames) {
    if (cls.kind() == ClassDef.Kind.OBJECT || cls.isPackageObject()) {
      String module = moduleBinaryName(cls, classBinaryNames, moduleBinaryNames);
      if (module.endsWith("$")) {
        return module.substring(0, module.length() - 1);
      }
      return module;
    }
    return classBinaryName(cls, classBinaryNames, moduleBinaryNames);
  }

  private static String nestedMemberBinaryPrefix(
      ClassDef owner,
      Map<ClassDef, String> classBinaryNames,
      Map<ClassDef, String> moduleBinaryNames) {
    String ownerBinary = ownerMemberBinary(owner, classBinaryNames, moduleBinaryNames);
    if (owner.kind() == ClassDef.Kind.OBJECT || owner.isPackageObject()) {
      return ownerBinary;
    }
    return ownerBinary + "$";
  }

  private static String ownerMemberBinary(
      ClassDef owner,
      Map<ClassDef, String> classBinaryNames,
      Map<ClassDef, String> moduleBinaryNames) {
    if (owner.kind() == ClassDef.Kind.OBJECT || owner.isPackageObject()) {
      return moduleBinaryName(owner, classBinaryNames, moduleBinaryNames);
    }
    return classBinaryName(owner, classBinaryNames, moduleBinaryNames);
  }

  private static final class ClassCollection {
    private final List<ClassDef> allDefs = new ArrayList<>();
    private final List<ClassDef> classDefs = new ArrayList<>();
    private final List<ClassDef> topLevelClassDefs = new ArrayList<>();
    private final List<ClassDef> objectDefs = new ArrayList<>();
    private final List<ClassDef> packageObjects = new ArrayList<>();
    private final Set<ClassDef> topLevelObjects = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<ClassDef, ClassDef> owners = new IdentityHashMap<>();
    private final Map<ClassDef, String> classBinaryNames = new IdentityHashMap<>();
    private final Map<ClassDef, String> moduleBinaryNames = new IdentityHashMap<>();
    private final Map<ScalaTree.CompUnit, List<ClassDef>> unitClassDefs = new IdentityHashMap<>();

    List<ClassDef> allDefs() {
      return allDefs;
    }

    List<ClassDef> classDefs() {
      return classDefs;
    }

    List<ClassDef> topLevelClassDefs() {
      return topLevelClassDefs;
    }

    List<ClassDef> objectDefs() {
      return objectDefs;
    }

    List<ClassDef> packageObjects() {
      return packageObjects;
    }

    Set<ClassDef> topLevelObjects() {
      return topLevelObjects;
    }

    Map<ClassDef, ClassDef> owners() {
      return owners;
    }

    Map<ClassDef, String> classBinaryNames() {
      return classBinaryNames;
    }

    Map<ClassDef, String> moduleBinaryNames() {
      return moduleBinaryNames;
    }

    Map<ScalaTree.CompUnit, List<ClassDef>> unitClassDefs() {
      return unitClassDefs;
    }
  }

  private static ClassDef synthesizeCompanion(ClassDef cls) {
    return new ClassDef(
        cls.packageName(),
        cls.name(),
        ClassDef.Kind.OBJECT,
        /* isCase= */ false,
        /* isPackageObject= */ false,
        ImmutableList.of(),
        ImmutableList.of(),
        ImmutableList.of(),
        ImmutableList.of(),
        ImmutableList.of(),
        ImmutableList.of(),
        cls.position());
  }

  private static int caseCompanionArity(ClassDef cls) {
    if (!cls.isCase()) {
      return -1;
    }
    if (!cls.typeParams().isEmpty()) {
      return -1;
    }
    if (!cls.members().isEmpty()) {
      return -1;
    }
    ImmutableList<ParamList> params = cls.ctorParams();
    if (params == null || params.isEmpty()) {
      return 0;
    }
    if (params.size() != 1) {
      return -1;
    }
    return params.get(0).params().size();
  }

  private static List<ClassFile.MethodInfo> caseClassCompanionMethods(
      ClassDef cls,
      String classBinary,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope) {
    String pkg = cls.packageName();
    Set<String> typeParams = ScalaTypeMapper.typeParamNames(cls.typeParams());
    DefDef apply = syntheticDef(pkg, "apply", cls.ctorParams(), classBinary, cls.position());
    ParamList unapplyParams =
        new ParamList(
            ImmutableList.of(
                new Param(
                    "x",
                    ImmutableList.of(),
                    classBinary,
                    /* hasDefault= */ false,
                    /* defaultUsesParam= */ false)));
    DefDef unapply =
        syntheticDef(
            pkg,
            "unapply",
            ImmutableList.of(unapplyParams),
            "scala/Option",
            cls.position());
    ParamList fromProductParams =
        new ParamList(
            ImmutableList.of(
                new Param("p", ImmutableList.of(), "scala/Product", /* hasDefault= */ false, /* defaultUsesParam= */ false)));
    DefDef fromProduct =
        syntheticDef(
            pkg,
            "fromProduct",
            ImmutableList.of(fromProductParams),
            classBinary,
            cls.position());
    DefDef toString =
        syntheticDef(pkg, "toString", ImmutableList.of(), "String", cls.position());
    List<ClassFile.MethodInfo> methods = new ArrayList<>();
    methods.add(
        buildMethod(
            apply, pkg, typeParams, scope, aliasScope, /* staticContext= */ false, ClassDef.Kind.CLASS));
    methods.add(
        buildMethod(
            unapply,
            pkg,
            typeParams,
            scope,
            aliasScope,
            /* staticContext= */ false,
            ClassDef.Kind.CLASS));
    methods.add(
        buildMethod(
            fromProduct,
            pkg,
            typeParams,
            scope,
            aliasScope,
            /* staticContext= */ false,
            ClassDef.Kind.CLASS));
    methods.add(
        buildMethod(
            toString,
            pkg,
            typeParams,
            scope,
            aliasScope,
            /* staticContext= */ false,
            ClassDef.Kind.CLASS));
    return methods;
  }

  private static List<ClassFile.MethodInfo> caseClassStaticMethods(
      ClassDef cls,
      String classBinary,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope) {
    String pkg = cls.packageName();
    Set<String> typeParams = ScalaTypeMapper.typeParamNames(cls.typeParams());
    DefDef apply = syntheticDef(pkg, "apply", cls.ctorParams(), classBinary, cls.position());
    ParamList unapplyParams =
        new ParamList(
            ImmutableList.of(
                new Param(
                    "x",
                    ImmutableList.of(),
                    classBinary,
                    /* hasDefault= */ false,
                    /* defaultUsesParam= */ false)));
    DefDef unapply =
        syntheticDef(
            pkg,
            "unapply",
            ImmutableList.of(unapplyParams),
            "scala/Option",
            cls.position());
    ParamList fromProductParams =
        new ParamList(
            ImmutableList.of(
                new Param("p", ImmutableList.of(), "scala/Product", /* hasDefault= */ false, /* defaultUsesParam= */ false)));
    DefDef fromProduct =
        syntheticDef(
            pkg,
            "fromProduct",
            ImmutableList.of(fromProductParams),
            classBinary,
            cls.position());
    List<ClassFile.MethodInfo> methods = new ArrayList<>();
    methods.add(
        buildMethod(
            apply, pkg, typeParams, scope, aliasScope, /* staticContext= */ true, ClassDef.Kind.CLASS));
    methods.add(
        buildMethod(
            unapply,
            pkg,
            typeParams,
            scope,
            aliasScope,
            /* staticContext= */ true,
            ClassDef.Kind.CLASS));
    methods.add(
        buildMethod(
            fromProduct,
            pkg,
            typeParams,
            scope,
            aliasScope,
            /* staticContext= */ true,
            ClassDef.Kind.CLASS));
    return methods;
  }

  private static List<ClassFile.MethodInfo> caseClassInstanceMethods(
      ClassDef cls,
      String classBinary,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope) {
    String pkg = cls.packageName();
    Set<String> typeParams = ScalaTypeMapper.typeParamNames(cls.typeParams());
    List<ClassFile.MethodInfo> methods = new ArrayList<>();
    methods.add(
        buildMethod(
            syntheticDef(pkg, "copy", cls.ctorParams(), classBinary, cls.position()),
            pkg,
            typeParams,
            scope,
            aliasScope,
            /* staticContext= */ false,
            cls.kind()));
    methods.add(
        buildMethod(
            syntheticDef(pkg, "productArity", ImmutableList.of(), "Int", cls.position()),
            pkg,
            typeParams,
            scope,
            aliasScope,
            /* staticContext= */ false,
            cls.kind()));
    ParamList productElementParams =
        new ParamList(
            ImmutableList.of(
                new Param("n", ImmutableList.of(), "Int", /* hasDefault= */ false, /* defaultUsesParam= */ false)));
    methods.add(
        buildMethod(
            syntheticDef(
                pkg,
                "productElement",
                ImmutableList.of(productElementParams),
                "java/lang/Object",
                cls.position()),
            pkg,
            typeParams,
            scope,
            aliasScope,
            /* staticContext= */ false,
            cls.kind()));
    ParamList productElementNameParams =
        new ParamList(
            ImmutableList.of(
                new Param("n", ImmutableList.of(), "Int", /* hasDefault= */ false, /* defaultUsesParam= */ false)));
    methods.add(
        buildMethod(
            syntheticDef(
                pkg,
                "productElementName",
                ImmutableList.of(productElementNameParams),
                "String",
                cls.position()),
            pkg,
            typeParams,
            scope,
            aliasScope,
            /* staticContext= */ false,
            cls.kind()));
    methods.add(
        buildMethod(
            syntheticDef(
                pkg,
                "productElementNames",
                ImmutableList.of(),
                "scala/collection/Iterator",
                cls.position()),
            pkg,
            typeParams,
            scope,
            aliasScope,
            /* staticContext= */ false,
            cls.kind()));
    methods.add(
        buildMethod(
            syntheticDef(pkg, "productPrefix", ImmutableList.of(), "String", cls.position()),
            pkg,
            typeParams,
            scope,
            aliasScope,
            /* staticContext= */ false,
            cls.kind()));
    methods.add(
        buildMethod(
            syntheticDef(
                pkg,
                "productIterator",
                ImmutableList.of(),
                "scala/collection/Iterator",
                cls.position()),
            pkg,
            typeParams,
            scope,
            aliasScope,
            /* staticContext= */ false,
            cls.kind()));
    methods.add(
        buildMethod(
            syntheticDef(pkg, "hashCode", ImmutableList.of(), "Int", cls.position()),
            pkg,
            typeParams,
            scope,
            aliasScope,
            /* staticContext= */ false,
            cls.kind()));
    ParamList equalsParams =
        new ParamList(
            ImmutableList.of(
                new Param("x", ImmutableList.of(), "java/lang/Object", /* hasDefault= */ false, /* defaultUsesParam= */ false)));
    methods.add(
        buildMethod(
            syntheticDef(
                pkg,
                "equals",
                ImmutableList.of(equalsParams),
                "Boolean",
                cls.position()),
            pkg,
            typeParams,
            scope,
            aliasScope,
            /* staticContext= */ false,
            cls.kind()));
    ParamList canEqualParams =
        new ParamList(
            ImmutableList.of(
                new Param("x", ImmutableList.of(), "java/lang/Object", /* hasDefault= */ false, /* defaultUsesParam= */ false)));
    methods.add(
        buildMethod(
            syntheticDef(
                pkg,
                "canEqual",
                ImmutableList.of(canEqualParams),
                "Boolean",
                cls.position()),
            pkg,
            typeParams,
            scope,
            aliasScope,
            /* staticContext= */ false,
            cls.kind()));
    methods.add(
        buildMethod(
            syntheticDef(pkg, "toString", ImmutableList.of(), "String", cls.position()),
            pkg,
            typeParams,
            scope,
            aliasScope,
            /* staticContext= */ false,
            cls.kind()));
    methods.addAll(caseClassElementAccessors(cls, scope, aliasScope));
    methods.addAll(caseClassCopyDefaultGetters(cls, scope, aliasScope));
    return methods;
  }

  private static List<ClassFile.MethodInfo> caseClassElementAccessors(
      ClassDef cls, ScalaTypeMapper.ImportScope scope, ScalaTypeMapper.TypeAliasScope aliasScope) {
    List<ClassFile.MethodInfo> methods = new ArrayList<>();
    List<Param> params = flattenParams(cls.ctorParams());
    if (params.isEmpty()) {
      return methods;
    }
    String pkg = cls.packageName();
    Set<String> typeParams = ScalaTypeMapper.typeParamNames(cls.typeParams());
    for (int i = 0; i < params.size(); i++) {
      Param param = params.get(i);
      String returnType = param.type() == null ? "java/lang/Object" : param.type();
      methods.add(
          buildMethod(
              syntheticDef(pkg, "_" + (i + 1), ImmutableList.of(), returnType, cls.position()),
              pkg,
              typeParams,
              scope,
              aliasScope,
              /* staticContext= */ false,
              cls.kind()));
    }
    return methods;
  }

  private static List<ClassFile.MethodInfo> caseClassCopyDefaultGetters(
      ClassDef cls, ScalaTypeMapper.ImportScope scope, ScalaTypeMapper.TypeAliasScope aliasScope) {
    Set<String> typeParams = ScalaTypeMapper.typeParamNames(cls.typeParams());
    int access = methodAccess(ImmutableList.of(), /* staticContext= */ false, /* isAbstract= */ false, cls.kind());
    return defaultGettersForParamLists(
        "copy",
        cls.ctorParams(),
        cls.packageName(),
        typeParams,
        ImmutableList.of(),
        scope,
        aliasScope,
        access);
  }

  private static List<ClassFile.MethodInfo> caseObjectInstanceMethods(
      ClassDef obj, ScalaTypeMapper.ImportScope scope, ScalaTypeMapper.TypeAliasScope aliasScope) {
    return caseObjectMethods(obj, scope, aliasScope, /* staticContext= */ false);
  }

  private static List<ClassFile.MethodInfo> caseObjectStaticMethods(
      ClassDef obj, ScalaTypeMapper.ImportScope scope, ScalaTypeMapper.TypeAliasScope aliasScope) {
    return caseObjectMethods(obj, scope, aliasScope, /* staticContext= */ true);
  }

  private static List<ClassFile.MethodInfo> caseObjectMethods(
      ClassDef obj,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      boolean staticContext) {
    List<ClassFile.MethodInfo> methods = new ArrayList<>();
    String pkg = obj.packageName();
    Set<String> typeParams = ScalaTypeMapper.typeParamNames(obj.typeParams());
    ParamList productElementParams =
        new ParamList(
            ImmutableList.of(
                new Param("n", ImmutableList.of(), "Int", /* hasDefault= */ false, /* defaultUsesParam= */ false)));
    ParamList productElementNameParams =
        new ParamList(
            ImmutableList.of(
                new Param("n", ImmutableList.of(), "Int", /* hasDefault= */ false, /* defaultUsesParam= */ false)));
    ParamList canEqualParams =
        new ParamList(
            ImmutableList.of(
                new Param("x", ImmutableList.of(), "java/lang/Object", /* hasDefault= */ false, /* defaultUsesParam= */ false)));
    methods.add(
        buildMethod(
            syntheticDef(pkg, "productArity", ImmutableList.of(), "Int", obj.position()),
            pkg,
            typeParams,
            scope,
            aliasScope,
            staticContext,
            ClassDef.Kind.CLASS));
    methods.add(
        buildMethod(
            syntheticDef(
                pkg,
                "productElement",
                ImmutableList.of(productElementParams),
                "java/lang/Object",
                obj.position()),
            pkg,
            typeParams,
            scope,
            aliasScope,
            staticContext,
            ClassDef.Kind.CLASS));
    methods.add(
        buildMethod(
            syntheticDef(
                pkg,
                "productElementName",
                ImmutableList.of(productElementNameParams),
                "String",
                obj.position()),
            pkg,
            typeParams,
            scope,
            aliasScope,
            staticContext,
            ClassDef.Kind.CLASS));
    methods.add(
        buildMethod(
            syntheticDef(
                pkg,
                "productElementNames",
                ImmutableList.of(),
                "scala/collection/Iterator",
                obj.position()),
            pkg,
            typeParams,
            scope,
            aliasScope,
            staticContext,
            ClassDef.Kind.CLASS));
    methods.add(
        buildMethod(
            syntheticDef(pkg, "productPrefix", ImmutableList.of(), "String", obj.position()),
            pkg,
            typeParams,
            scope,
            aliasScope,
            staticContext,
            ClassDef.Kind.CLASS));
    methods.add(
        buildMethod(
            syntheticDef(
                pkg,
                "productIterator",
                ImmutableList.of(),
                "scala/collection/Iterator",
                obj.position()),
            pkg,
            typeParams,
            scope,
            aliasScope,
            staticContext,
            ClassDef.Kind.CLASS));
    methods.add(
        buildMethod(
            syntheticDef(pkg, "hashCode", ImmutableList.of(), "Int", obj.position()),
            pkg,
            typeParams,
            scope,
            aliasScope,
            staticContext,
            ClassDef.Kind.CLASS));
    methods.add(
        buildMethod(
            syntheticDef(
                pkg,
                "canEqual",
                ImmutableList.of(canEqualParams),
                "Boolean",
                obj.position()),
            pkg,
            typeParams,
            scope,
            aliasScope,
            staticContext,
            ClassDef.Kind.CLASS));
    methods.add(
        buildMethod(
            syntheticDef(pkg, "toString", ImmutableList.of(), "String", obj.position()),
            pkg,
            typeParams,
            scope,
            aliasScope,
            staticContext,
            ClassDef.Kind.CLASS));
    return methods;
  }

  private static Map<String, String> traitTypeSubstitutions(ClassDef trait, String parentTypeText) {
    if (trait.typeParams().isEmpty()) {
      return Map.of();
    }
    List<String> args = extractTypeArgs(parentTypeText);
    if (args.isEmpty()) {
      return Map.of();
    }
    Map<String, String> substitutions = new HashMap<>();
    int count = Math.min(args.size(), trait.typeParams().size());
    for (int i = 0; i < count; i++) {
      substitutions.put(trait.typeParams().get(i).name(), args.get(i));
    }
    return substitutions;
  }

  private static List<String> extractTypeArgs(String typeText) {
    if (typeText == null) {
      return List.of();
    }
    String trimmed = typeText.trim();
    if (trimmed.isEmpty()) {
      return List.of();
    }
    List<String> tokens = Arrays.asList(trimmed.split("\\s+"));
    int open = -1;
    int close = -1;
    int depth = 0;
    for (int i = 0; i < tokens.size(); i++) {
      String token = tokens.get(i);
      if ("[".equals(token)) {
        if (depth == 0) {
          open = i + 1;
        }
        depth++;
        continue;
      }
      if ("]".equals(token)) {
        depth = Math.max(0, depth - 1);
        if (depth == 0 && open >= 0) {
          close = i;
          break;
        }
      }
    }
    if (open < 0 || close <= open) {
      return List.of();
    }
    List<String> argTokens = tokens.subList(open, close);
    List<String> args = new ArrayList<>();
    int nested = 0;
    StringBuilder current = new StringBuilder();
    for (String token : argTokens) {
      switch (token) {
        case "[", "(", "{" -> nested++;
        case "]", ")", "}" -> nested = Math.max(0, nested - 1);
        default -> {}
      }
      if (",".equals(token) && nested == 0) {
        if (current.length() > 0) {
          args.add(current.toString().trim());
          current.setLength(0);
        }
        continue;
      }
      if (current.length() > 0) {
        current.append(' ');
      }
      current.append(token);
    }
    if (current.length() > 0) {
      args.add(current.toString().trim());
    }
    return args;
  }

  private static DefDef substituteDef(DefDef def, Map<String, String> substitutions) {
    if (substitutions.isEmpty()) {
      return def;
    }
    ImmutableList.Builder<ParamList> lists = ImmutableList.builder();
    for (ParamList list : def.paramLists()) {
      ImmutableList.Builder<Param> params = ImmutableList.builder();
      for (Param param : list.params()) {
        params.add(
            new Param(
                param.name(),
                param.modifiers(),
                substituteType(param.type(), substitutions),
                param.hasDefault(),
                param.defaultUsesParam()));
      }
      lists.add(new ParamList(params.build()));
    }
    return new DefDef(
        def.packageName(),
        def.name(),
        def.modifiers(),
        def.typeParams(),
        lists.build(),
        substituteType(def.returnType(), substitutions),
        def.position());
  }

  private static ValDef substituteVal(ValDef val, Map<String, String> substitutions) {
    if (substitutions.isEmpty()) {
      return val;
    }
    return new ValDef(
        val.packageName(),
        val.name(),
        val.isVar(),
        val.modifiers(),
        substituteType(val.type(), substitutions),
        val.hasExplicitType(),
        val.hasDefault(),
        val.position());
  }

  private static String substituteType(String typeText, Map<String, String> substitutions) {
    if (typeText == null || substitutions.isEmpty()) {
      return typeText;
    }
    String trimmed = typeText.trim();
    if (trimmed.isEmpty()) {
      return typeText;
    }
    List<String> tokens = Arrays.asList(trimmed.split("\\s+"));
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < tokens.size(); i++) {
      String token = tokens.get(i);
      String replacement = substitutions.get(token);
      if (replacement != null && !(i > 0 && ".".equals(tokens.get(i - 1)))) {
        token = replacement;
      }
      if (sb.length() > 0) {
        sb.append(' ');
      }
      sb.append(token);
    }
    return sb.toString();
  }

  private static List<ClassFile.MethodInfo> appInstanceMethods() {
    return appMethods(/* staticContext= */ false);
  }

  private static List<ClassFile.MethodInfo> appStaticMethods() {
    return appMethods(/* staticContext= */ true);
  }

  private static List<ClassFile.MethodInfo> appMethods(boolean staticContext) {
    List<ClassFile.MethodInfo> methods = new ArrayList<>();
    methods.add(appMethod("main", "([Ljava/lang/String;)V", staticContext));
    methods.add(appMethod("delayedInit", "(Lscala/Function0;)V", staticContext));
    methods.add(appMethod("executionStart", "()J", staticContext));
    return methods;
  }

  private static ClassFile.MethodInfo appMethod(
      String name, String descriptor, boolean staticContext) {
    int access = TurbineFlag.ACC_PUBLIC;
    if (staticContext) {
      access |= TurbineFlag.ACC_STATIC;
    }
    return new ClassFile.MethodInfo(
        access,
        name,
        descriptor,
        /* signature= */ null,
        /* exceptions= */ ImmutableList.of(),
        /* defaultValue= */ null,
        /* annotations= */ ImmutableList.of(),
        /* parameterAnnotations= */ ImmutableList.of(),
        /* typeAnnotations= */ ImmutableList.of(),
        /* parameters= */ ImmutableList.of());
  }

  private static boolean isAppObject(
      ClassDef obj,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope) {
    if (obj.parents().isEmpty()) {
      return false;
    }
    for (String parent : obj.parents()) {
      String erased = eraseType(parent, obj.packageName(), obj.typeParams(), scope, aliasScope);
      if ("scala/App".equals(erased)) {
        return true;
      }
      String raw = rawTypeName(parent);
      if (raw != null) {
        String cleaned = stripRootPrefix(raw);
        if ("scala/App".equals(cleaned) || "App".equals(cleaned)) {
          return true;
        }
      }
    }
    return false;
  }

  private static List<Param> flattenParams(ImmutableList<ParamList> paramLists) {
    List<Param> params = new ArrayList<>();
    for (ParamList list : paramLists) {
      params.addAll(list.params());
    }
    return params;
  }

  private static DefDef syntheticDef(
      String pkg,
      String name,
      ImmutableList<ParamList> params,
      String returnType,
      int position) {
    return new DefDef(
        pkg,
        name,
        ImmutableList.of(),
        ImmutableList.of(),
        params,
        returnType,
        position);
  }

  private static List<ClassFile.MethodInfo> concatMethods(
      List<ClassFile.MethodInfo> first, List<ClassFile.MethodInfo> second) {
    if (first.isEmpty()) {
      return second;
    }
    if (second.isEmpty()) {
      return first;
    }
    List<ClassFile.MethodInfo> out = new ArrayList<>(first.size() + second.size());
    out.addAll(first);
    out.addAll(second);
    return out;
  }

  private static ScalaTypeMapper.TypeAliasScope typeAliasScope(ClassDef cls) {
    ScalaTypeMapper.TypeAliasScope.Builder builder = ScalaTypeMapper.TypeAliasScope.builder();
    for (Defn defn : cls.members()) {
      if (defn instanceof TypeDef type) {
        if (type.rhs() == null || type.rhs().isEmpty()) {
          continue;
        }
        if (!type.typeParams().isEmpty()) {
          continue;
        }
        builder.addAlias(type.name(), type.rhs());
      }
    }
    return builder.build();
  }

  private static ScalaTypeMapper.TypeAliasScope qualifiedTypeAliasScope(
      List<ClassDef> defs,
      Map<ClassDef, String> classBinaryNames,
      Map<ClassDef, String> moduleBinaryNames) {
    ScalaTypeMapper.TypeAliasScope.Builder builder = ScalaTypeMapper.TypeAliasScope.builder();
    for (ClassDef owner : defs) {
      String ownerMemberBinary = ownerMemberBinary(owner, classBinaryNames, moduleBinaryNames);
      String ownerClassBinary = classBinaryName(owner, classBinaryNames, moduleBinaryNames);
      for (Defn defn : owner.members()) {
        if (!(defn instanceof TypeDef type)) {
          continue;
        }
        if (type.rhs() == null || type.rhs().isEmpty()) {
          continue;
        }
        if (!type.typeParams().isEmpty()) {
          continue;
        }
        addQualifiedTypeAlias(
            builder, ownerMemberBinary, ownerClassBinary, type.name(), type.rhs());
      }
    }
    return builder.build();
  }

  private static void addQualifiedTypeAlias(
      ScalaTypeMapper.TypeAliasScope.Builder builder,
      String ownerMemberBinary,
      String ownerClassBinary,
      String aliasName,
      String rhs) {
    if (ownerMemberBinary == null || ownerMemberBinary.isEmpty()) {
      return;
    }
    if (ownerMemberBinary.endsWith("$")) {
      builder.addAlias(ownerMemberBinary + aliasName, rhs);
      if (ownerClassBinary != null && !ownerClassBinary.isEmpty()) {
        builder.addAlias(ownerClassBinary + "/" + aliasName, rhs);
      }
      return;
    }
    builder.addAlias(ownerMemberBinary + "$" + aliasName, rhs);
    builder.addAlias(ownerMemberBinary + "/" + aliasName, rhs);
  }

  private static Map<String, Set<String>> packageTypeMembers(
      List<ClassDef> classDefs, Set<ClassDef> objectDefs) {
    Map<String, Set<String>> members = new HashMap<>();
    for (ClassDef cls : classDefs) {
      members.computeIfAbsent(cls.packageName(), ignored -> new HashSet<>()).add(cls.name());
    }
    for (ClassDef obj : objectDefs) {
      if (!obj.isPackageObject()) {
        members.computeIfAbsent(obj.packageName(), ignored -> new HashSet<>()).add(obj.name());
      }
    }
    return members;
  }

  private static Map<String, Map<String, String>> packageObjectTypes(
      Set<ClassDef> objectDefs,
      Map<ClassDef, String> classBinaryNames,
      Map<ClassDef, String> moduleBinaryNames) {
    Map<String, Map<String, String>> out = new HashMap<>();
    for (ClassDef obj : objectDefs) {
      if (obj.isPackageObject()) {
        continue;
      }
      out.computeIfAbsent(obj.packageName(), ignored -> new LinkedHashMap<>())
          .put(obj.name(), classKey(obj, classBinaryNames, moduleBinaryNames));
    }
    Map<String, Map<String, String>> copy = new HashMap<>();
    out.forEach((pkg, names) -> copy.put(pkg, Map.copyOf(names)));
    return copy;
  }

  private static void addEnclosingPackageMembers(
      ScalaTypeMapper.ImportScope.Builder builder,
      String currentPackage,
      Map<String, Set<String>> packageTypeMembers) {
    if (currentPackage == null || currentPackage.isEmpty() || packageTypeMembers.isEmpty()) {
      return;
    }
    String[] segments = currentPackage.split("\\.");
    StringBuilder pkg = new StringBuilder();
    for (int i = 0; i < segments.length; i++) {
      if (segments[i].isEmpty()) {
        continue;
      }
      if (pkg.length() > 0) {
        pkg.append('.');
      }
      pkg.append(segments[i]);
      String pkgName = pkg.toString();
      Set<String> names = packageTypeMembers.get(pkgName);
      if (names == null || names.isEmpty()) {
        continue;
      }
      String binaryPrefix = pkgName.replace('.', '/');
      for (String name : names) {
        builder.addExplicit(name, binaryPrefix + "/" + name);
      }
    }
  }

  private static ScalaTypeMapper.ImportScope importScope(
      ScalaTree.CompUnit unit,
      Map<String, Map<String, String>> objectTypeMembers,
      Map<String, Set<String>> packageTypeMembers) {
    ScalaTypeMapper.ImportScope.Builder builder = ScalaTypeMapper.ImportScope.builder();
    addEnclosingPackageMembers(builder, unitPackageName(unit), packageTypeMembers);
    for (ScalaTree.Stat stat : unit.stats()) {
      if (stat instanceof ImportStat imp) {
        parseImportText(
            builder,
            imp.text(),
            imp.packageName(),
            objectTypeMembers,
            packageTypeMembers,
            ScalaTypeMapper.ImportScope.empty());
      }
    }
    return builder.build();
  }

  private static String unitPackageName(ScalaTree.CompUnit unit) {
    for (ScalaTree.Stat stat : unit.stats()) {
      if (stat instanceof ClassDef cls && cls.packageName() != null && !cls.packageName().isEmpty()) {
        return cls.packageName();
      }
      if (stat instanceof ImportStat imp && imp.packageName() != null && !imp.packageName().isEmpty()) {
        return imp.packageName();
      }
    }
    return "";
  }

  private static ScalaTypeMapper.ImportScope importScope(
      ImmutableList<String> imports,
      String currentPackage,
      Map<String, Map<String, String>> objectTypeMembers,
      Map<String, Set<String>> packageTypeMembers,
      ScalaTypeMapper.ImportScope qualifierScope) {
    if (imports == null || imports.isEmpty()) {
      return ScalaTypeMapper.ImportScope.empty();
    }
    ScalaTypeMapper.ImportScope.Builder builder = ScalaTypeMapper.ImportScope.builder();
    for (String text : imports) {
      parseImportText(
          builder, text, currentPackage, objectTypeMembers, packageTypeMembers, qualifierScope);
    }
    return builder.build();
  }

  private static ScalaTypeMapper.ImportScope mergeImportScopes(
      ScalaTypeMapper.ImportScope base, ScalaTypeMapper.ImportScope extra) {
    if (extra == null || extra.isEmpty()) {
      return base;
    }
    ScalaTypeMapper.ImportScope.Builder builder = ScalaTypeMapper.ImportScope.builder();
    if (base != null && !base.isEmpty()) {
      base.explicit().forEach(builder::addExplicit);
      base.wildcards().forEach(builder::addWildcard);
    }
    extra.explicit().forEach(builder::addExplicit);
    extra.wildcards().forEach(builder::addWildcard);
    return builder.build();
  }

  private static ScalaTypeMapper.TypeAliasScope mergeAliasScopes(
      ScalaTypeMapper.TypeAliasScope base, ScalaTypeMapper.TypeAliasScope extra) {
    if (base == null || base.isEmpty()) {
      return extra == null ? ScalaTypeMapper.TypeAliasScope.empty() : extra;
    }
    if (extra == null || extra.isEmpty()) {
      return base;
    }
    ScalaTypeMapper.TypeAliasScope.Builder builder = ScalaTypeMapper.TypeAliasScope.builder();
    base.aliases().forEach(builder::addAlias);
    extra.aliases().forEach(builder::addAlias);
    return builder.build();
  }

  private static ScalaTypeMapper.ImportScope thisTypeScope(String ownerBinary) {
    if (ownerBinary == null || ownerBinary.isEmpty()) {
      return ScalaTypeMapper.ImportScope.empty();
    }
    ScalaTypeMapper.ImportScope.Builder builder = ScalaTypeMapper.ImportScope.builder();
    builder.addExplicit("this/type", ownerBinary);
    return builder.build();
  }

  private static ScalaTypeMapper.ImportScope ownerTypeScope(
      ClassDef owner,
      Map<ClassDef, String> classBinaryNames,
      Map<ClassDef, String> moduleBinaryNames) {
    if (owner == null || owner.members().isEmpty()) {
      return ScalaTypeMapper.ImportScope.empty();
    }
    String ownerBinaryPrefix = nestedMemberBinaryPrefix(owner, classBinaryNames, moduleBinaryNames);
    ScalaTypeMapper.ImportScope.Builder builder = ScalaTypeMapper.ImportScope.builder();
    boolean hasEntries = false;
    for (Defn defn : owner.members()) {
      if (defn instanceof ClassDef cls) {
        String binary = ownerBinaryPrefix + cls.name();
        if (cls.kind() == ClassDef.Kind.OBJECT) {
          binary += "$";
        }
        builder.addExplicit(cls.name(), binary);
        hasEntries = true;
      } else if (defn instanceof TypeDef type) {
        builder.addExplicit(type.name(), ownerBinaryPrefix + type.name());
        hasEntries = true;
      }
    }
    if (!hasEntries) {
      return ScalaTypeMapper.ImportScope.empty();
    }
    return builder.build();
  }

  private static ScalaTypeMapper.ImportScope enclosingOwnerTypeScope(
      ClassDef cls,
      Map<ClassDef, ClassDef> owners,
      Map<ClassDef, ScalaTypeMapper.ImportScope> ownerTypeScopes) {
    List<ClassDef> chain = new ArrayList<>();
    ClassDef owner = owners.get(cls);
    while (owner != null) {
      chain.add(owner);
      owner = owners.get(owner);
    }
    ScalaTypeMapper.ImportScope scope = ScalaTypeMapper.ImportScope.empty();
    for (int i = chain.size() - 1; i >= 0; i--) {
      ScalaTypeMapper.ImportScope ownerScope = ownerTypeScopes.get(chain.get(i));
      if (ownerScope != null && !ownerScope.isEmpty()) {
        scope = mergeImportScopes(scope, ownerScope);
      }
    }
    return scope;
  }

  private static Map<String, Map<String, String>> objectTypeMembers(
      List<ClassDef> objectDefs,
      Map<ClassDef, String> classBinaryNames,
      Map<ClassDef, String> moduleBinaryNames) {
    Map<String, Map<String, String>> members = new HashMap<>();
    for (ClassDef obj : objectDefs) {
      String moduleBinary = moduleBinaryName(obj, classBinaryNames, moduleBinaryNames);
      Map<String, String> names = new LinkedHashMap<>();
      for (Defn defn : obj.members()) {
        if (defn instanceof ClassDef cls) {
          String binary = moduleBinary + cls.name();
          if (cls.kind() == ClassDef.Kind.OBJECT) {
            binary += "$";
            // For `import Obj._`, a class/object companion pair with the same simple name
            // should resolve type positions to the class, not the module class.
            names.putIfAbsent(cls.name(), binary);
            continue;
          }
          names.put(cls.name(), binary);
        } else if (defn instanceof TypeDef type) {
          names.put(type.name(), moduleBinary + type.name());
        }
      }
      if (!names.isEmpty()) {
        members.put(moduleBinary, Map.copyOf(names));
      }
    }
    return members;
  }

  private static void addWildcardImport(
      ScalaTypeMapper.ImportScope.Builder builder,
      String qualifier,
      Map<String, Map<String, String>> objectTypeMembers,
      Map<String, Set<String>> packageTypeMembers) {
    String wildcard = wildcardQualifier(qualifier);
    Map<String, String> members = objectTypeMembers.get(wildcard);
    if (members != null && !members.isEmpty()) {
      members.forEach(builder::addExplicit);
      return;
    }
    // Unknown object wildcard imports are too ambiguous for type mapping.
    // Keep only package wildcards and object members we can resolve explicitly.
    if (wildcard != null && wildcard.endsWith("$")) {
      return;
    }
    if (wildcard != null && !wildcard.isEmpty()) {
      String pkgName = wildcard.replace('/', '.');
      Set<String> packageMembers = packageTypeMembers.get(pkgName);
      if (packageMembers != null && !packageMembers.isEmpty()) {
        for (String name : packageMembers) {
          builder.addExplicit(name, wildcard + "/" + name);
        }
      }
    }
    builder.addWildcard(wildcard);
  }

  private static void parseImportText(
      ScalaTypeMapper.ImportScope.Builder builder,
      String text,
      String currentPackage,
      Map<String, Map<String, String>> objectTypeMembers,
      Map<String, Set<String>> packageTypeMembers,
      ScalaTypeMapper.ImportScope qualifierScope) {
    if (text == null || text.isEmpty()) {
      return;
    }
    List<String> tokens = Arrays.asList(text.split("\\s+"));
    int brace = tokens.indexOf("{");
    if (brace >= 0) {
      int close = tokens.indexOf("}");
      if (close < 0) {
        return;
      }
      String qualifier =
          qualifierFromTokens(tokens.subList(0, brace), currentPackage, qualifierScope);
      if (qualifier == null || qualifier.isEmpty()) {
        return;
      }
      for (int i = brace + 1; i < close; i++) {
        String token = tokens.get(i);
        if (",".equals(token)) {
          continue;
        }
        if ("_".equals(token)) {
          addWildcardImport(builder, qualifier, objectTypeMembers, packageTypeMembers);
          continue;
        }
        if (isImportIdent(token)) {
          String cleaned = stripBackticks(token);
          if (i + 2 < close && "=>".equals(tokens.get(i + 1))) {
            String renamed = tokens.get(i + 2);
            if ("_".equals(renamed)) {
              i += 2;
              continue;
            }
            if (isImportIdent(renamed)) {
              String alias = stripBackticks(renamed);
              builder.addExplicit(alias, joinQualifier(qualifier, cleaned));
              i += 2;
              continue;
            }
          }
          builder.addExplicit(cleaned, joinQualifier(qualifier, cleaned));
        }
      }
      return;
    }
    if (!tokens.isEmpty() && "_".equals(tokens.get(tokens.size() - 1))) {
      String qualifier =
          qualifierFromTokens(tokens.subList(0, tokens.size() - 1), currentPackage, qualifierScope);
      if (qualifier != null && !qualifier.isEmpty()) {
        addWildcardImport(builder, qualifier, objectTypeMembers, packageTypeMembers);
      }
      return;
    }
    List<String> idents = identifierTokens(tokens);
    if (idents.size() < 2) {
      return;
    }
    String name = idents.get(idents.size() - 1);
    String qualifier =
        qualifierFromIdents(idents.subList(0, idents.size() - 1), currentPackage, qualifierScope);
    if (qualifier.isEmpty()) {
      return;
    }
    builder.addExplicit(name, joinQualifier(qualifier, name));
  }

  private static List<String> identifierTokens(List<String> tokens) {
    List<String> idents = new ArrayList<>();
    for (String token : tokens) {
      if (isImportIdent(token)) {
        idents.add(stripBackticks(token));
      }
    }
    if (!idents.isEmpty() && "_root_".equals(idents.get(0))) {
      idents.remove(0);
    }
    return idents;
  }

  private static String qualifierFromTokens(
      List<String> tokens, String currentPackage, ScalaTypeMapper.ImportScope qualifierScope) {
    List<String> idents = identifierTokens(tokens);
    return qualifierFromIdents(idents, currentPackage, qualifierScope);
  }

  private static String qualifierFromIdents(
      List<String> idents, String currentPackage, ScalaTypeMapper.ImportScope qualifierScope) {
    if (idents.isEmpty()) {
      return null;
    }
    List<String> cleaned = new ArrayList<>(idents);
    if (!cleaned.isEmpty() && "_root_".equals(cleaned.get(0))) {
      cleaned.remove(0);
    }
    if (cleaned.isEmpty()) {
      return null;
    }
    String first = cleaned.get(0);
    if (qualifierScope != null && !qualifierScope.isEmpty()) {
      String resolved = qualifierScope.explicit().get(first);
      if (resolved != null) {
        return appendQualifierSegments(resolved, cleaned.subList(1, cleaned.size()));
      }
    }
    if (currentPackage != null && !currentPackage.isEmpty() && isClassLike(cleaned.get(0))) {
      List<String> prefixed = new ArrayList<>(Arrays.asList(currentPackage.split("\\.")));
      prefixed.addAll(cleaned);
      cleaned = prefixed;
    }
    return toBinaryQualifier(cleaned);
  }

  private static String appendQualifierSegments(String qualifier, List<String> tail) {
    String out = qualifier;
    for (String segment : tail) {
      out = joinQualifier(out, segment);
    }
    return out;
  }

  private static String toBinaryQualifier(List<String> idents) {
    int firstClass = -1;
    for (int i = 0; i < idents.size(); i++) {
      if (isClassLike(idents.get(i))) {
        firstClass = i;
        break;
      }
    }
    if (firstClass < 0) {
      return String.join("/", idents);
    }
    String pkg = String.join("/", idents.subList(0, firstClass));
    String classes = String.join("$", idents.subList(firstClass, idents.size()));
    if (pkg.isEmpty()) {
      return classes;
    }
    return pkg + "/" + classes;
  }

  private static String wildcardQualifier(String qualifier) {
    if (qualifier == null || qualifier.isEmpty()) {
      return qualifier;
    }
    String last = lastSegment(qualifier);
    if (isClassLike(last) && !qualifier.endsWith("$")) {
      return qualifier + "$";
    }
    return qualifier;
  }

  private static String joinQualifier(String qualifier, String name) {
    if (qualifier == null || qualifier.isEmpty()) {
      return name;
    }
    if (qualifier.endsWith("$")) {
      return qualifier + name;
    }
    String last = lastSegment(qualifier);
    if (isClassLike(last)) {
      return qualifier + "$" + name;
    }
    return qualifier + "/" + name;
  }

  private static String lastSegment(String qualifier) {
    int slash = qualifier.lastIndexOf('/');
    return slash >= 0 ? qualifier.substring(slash + 1) : qualifier;
  }

  private static boolean isImportIdent(String token) {
    if (token.isEmpty()) {
      return false;
    }
    char first = token.charAt(0);
    if (first == '`') {
      return token.length() > 1;
    }
    return Character.isJavaIdentifierStart(first) || first == '$' || first == '_';
  }

  private static boolean isClassLike(String segment) {
    if (segment == null || segment.isEmpty()) {
      return false;
    }
    char first = segment.charAt(0);
    return Character.isUpperCase(first);
  }

  private static String stripBackticks(String token) {
    if (token.length() >= 2 && token.charAt(0) == '`' && token.charAt(token.length() - 1) == '`') {
      return token.substring(1, token.length() - 1);
    }
    return token;
  }

  private static String rawTypeName(String typeText) {
    if (typeText == null) {
      return null;
    }
    String trimmed = typeText.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    List<String> tokens = Arrays.asList(trimmed.split("\\s+"));
    for (int i = 0; i < tokens.size(); i++) {
      String token = tokens.get(i);
      if (isImportIdent(token)) {
        StringBuilder name = new StringBuilder(stripBackticks(token));
        int j = i + 1;
        while (j + 1 < tokens.size()
            && ".".equals(tokens.get(j))
            && isImportIdent(tokens.get(j + 1))) {
          name.append('/').append(stripBackticks(tokens.get(j + 1)));
          j += 2;
        }
        return name.toString().replace('.', '/');
      }
    }
    return null;
  }

  private static String stripRootPrefix(String raw) {
    if (raw == null) {
      return null;
    }
    String prefix = "_root_/";
    if (raw.startsWith(prefix)) {
      return raw.substring(prefix.length());
    }
    return raw;
  }

  private static String normalizeObjectParentType(String erasedType, String moduleBinary) {
    if (erasedType == null || moduleBinary == null) {
      return erasedType;
    }
    if (erasedType.equals(moduleBinary) && moduleBinary.endsWith("$")) {
      return moduleBinary.substring(0, moduleBinary.length() - 1);
    }
    return erasedType;
  }

  private static String normalizeTraitForwarderParent(
      String parentTypeText,
      String erasedType,
      String currentPackage,
      String targetModuleBinary,
      Map<String, Map<String, String>> packageObjectTypes,
      Map<String, ClassDef> traitsByBinary) {
    String normalized = erasedType;
    String raw = stripRootPrefix(rawTypeName(parentTypeText));
    if (raw != null
        && !raw.isEmpty()
        && raw.indexOf('/') < 0
        && isClassLike(raw)
        && currentPackage != null
        && !currentPackage.isEmpty()) {
      String localCandidate = currentPackage.replace('.', '/') + "/" + raw;
      if (traitsByBinary.containsKey(localCandidate)) {
        normalized = localCandidate;
      }
    }
    normalized =
        preferLocalObjectParentType(
            parentTypeText,
            normalized,
            currentPackage,
            packageObjectTypes,
            traitsByBinary,
            ParentKindResolver.none());
    if (targetModuleBinary != null && !targetModuleBinary.isEmpty()) {
      normalized = normalizeObjectParentType(normalized, targetModuleBinary);
    }
    return normalized;
  }

  private static List<ClassFile.MethodInfo> ctorDefaultGetters(
      ClassDef cls,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      boolean staticContext) {
    Set<String> typeParams = ScalaTypeMapper.typeParamNames(cls.typeParams());
    int access = TurbineFlag.ACC_PUBLIC;
    if (staticContext) {
      access |= TurbineFlag.ACC_STATIC;
    }
    return defaultGettersForParamLists(
        "$lessinit$greater",
        cls.ctorParams(),
        cls.packageName(),
        typeParams,
        ImmutableList.of(),
        scope,
        aliasScope,
        access);
  }

  private static List<ClassFile.MethodInfo> defaultGettersForDefs(
      ImmutableList<Defn> members,
      String pkg,
      Set<String> classTypeParams,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      boolean staticContext,
      ClassDef.Kind ownerKind) {
    List<ClassFile.MethodInfo> methods = new ArrayList<>();
    for (Defn defn : members) {
      if (defn instanceof DefDef def) {
        Set<String> typeParams = new HashSet<>(classTypeParams);
        typeParams.addAll(ScalaTypeMapper.typeParamNames(def.typeParams()));
        int access = methodAccess(def.modifiers(), staticContext, /* isAbstract= */ false, ownerKind);
        methods.addAll(
            defaultGettersForParamLists(
                def.name(),
                def.paramLists(),
                pkg,
                typeParams,
                def.typeParams(),
                scope,
                aliasScope,
                access));
      }
    }
    return methods;
  }

  private static List<ClassFile.MethodInfo> defaultGettersForParamLists(
      String baseName,
      ImmutableList<ParamList> paramLists,
      String pkg,
      Set<String> typeParams,
      ImmutableList<TypeParam> methodTypeParams,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      int access) {
    String encodedBaseName = encodeName(baseName);
    List<Param> params = new ArrayList<>();
    for (ParamList list : paramLists) {
      params.addAll(list.params());
    }
    if (params.isEmpty()) {
      return ImmutableList.of();
    }
    List<ClassFile.MethodInfo> methods = new ArrayList<>();
    for (int i = 0; i < params.size(); i++) {
      Param param = params.get(i);
      if (!param.hasDefault()) {
        continue;
      }
      String name = encodedBaseName + "$default$" + (i + 1);
      List<String> paramTypes = new ArrayList<>();
      if (param.defaultUsesParam()) {
        for (int j = 0; j < i; j++) {
          paramTypes.add(params.get(j).type());
        }
      }
      String desc =
          defaultGetterDescriptor(params, i, pkg, typeParams, scope, aliasScope, param.defaultUsesParam());
      String signature =
          ScalaSignature.methodSignature(
              methodTypeParams,
              paramTypes,
              param.type(),
              typeParams,
              pkg,
              scope,
              aliasScope);
      methods.add(
          new ClassFile.MethodInfo(
              access,
              name,
              desc,
              signature,
              /* exceptions= */ ImmutableList.of(),
              /* defaultValue= */ null,
              /* annotations= */ ImmutableList.of(),
              /* parameterAnnotations= */ ImmutableList.of(),
              /* typeAnnotations= */ ImmutableList.of(),
              /* parameters= */ ImmutableList.of()));
    }
    return methods;
  }

  private static String defaultGetterDescriptor(
      List<Param> params,
      int defaultIndex,
      String pkg,
      Set<String> typeParams,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      boolean includeParams) {
    StringBuilder desc = new StringBuilder();
    desc.append('(');
    if (includeParams) {
      for (int i = 0; i < defaultIndex; i++) {
        desc.append(
            ScalaTypeMapper.descriptorForParam(
                params.get(i).type(), pkg, typeParams, scope, aliasScope));
      }
    }
    desc.append(')');
    desc.append(
        ScalaTypeMapper.descriptorForParam(
            params.get(defaultIndex).type(), pkg, typeParams, scope, aliasScope));
    return desc.toString();
  }

  private static List<ClassFile.MethodInfo> uniqueMethods(List<ClassFile.MethodInfo> methods) {
    Map<String, ClassFile.MethodInfo> unique = new LinkedHashMap<>();
    for (ClassFile.MethodInfo method : methods) {
      unique.putIfAbsent(method.name() + method.descriptor(), method);
    }
    return new ArrayList<>(unique.values());
  }

  private static List<ClassFile.FieldInfo> uniqueFields(List<ClassFile.FieldInfo> fields) {
    Map<String, ClassFile.FieldInfo> unique = new LinkedHashMap<>();
    for (ClassFile.FieldInfo field : fields) {
      unique.putIfAbsent(field.name() + field.descriptor(), field);
    }
    return new ArrayList<>(unique.values());
  }

  private static ImmutableList<AnnotationInfo> scalaClassAnnotations() {
    AnnotationInfo scalaSignature =
        new AnnotationInfo(
            "Lscala/reflect/ScalaSignature;",
            AnnotationInfo.RuntimeVisibility.VISIBLE,
            ImmutableMap.of(
                "bytes",
                new AnnotationInfo.ElementValue.ConstValue(new Const.StringValue(""))));
    AnnotationInfo scalaLongSignature =
        new AnnotationInfo(
            "Lscala/reflect/ScalaLongSignature;",
            AnnotationInfo.RuntimeVisibility.VISIBLE,
            ImmutableMap.of(
                "bytes",
                new AnnotationInfo.ElementValue.ArrayValue(
                    ImmutableList.of(
                        new AnnotationInfo.ElementValue.ConstValue(new Const.StringValue(""))))));
    return ImmutableList.of(scalaSignature, scalaLongSignature);
  }

  private static boolean isKnownParentBinary(
      String binaryName,
      Map<String, ClassDef> sourceTypesByBinary,
      Map<String, ClassDef> traitsByBinary,
      ParentKindResolver parentKindResolver) {
    binaryName = normalizeParentBinary(binaryName);
    if (binaryName == null || binaryName.isEmpty()) {
      return false;
    }
    if ("java/lang/Object".equals(binaryName)) {
      return true;
    }
    if (sourceTypesByBinary.containsKey(binaryName) || traitsByBinary.containsKey(binaryName)) {
      return true;
    }
    if (parentKindResolver == null) {
      return false;
    }
    if (parentKindResolver.isInterface(binaryName)) {
      return true;
    }
    String superName = parentKindResolver.superName(binaryName);
    if (superName != null && !superName.isEmpty()) {
      return true;
    }
    ImmutableList<String> interfaces = parentKindResolver.interfaces(binaryName);
    return interfaces != null && !interfaces.isEmpty();
  }

  private static String preferKnownLocalParentType(
      String parentTypeText,
      String erasedType,
      String currentPackage,
      Map<String, ClassDef> sourceTypesByBinary,
      Map<String, ClassDef> traitsByBinary,
      ParentKindResolver parentKindResolver) {
    if (parentTypeText == null
        || parentTypeText.isEmpty()
        || currentPackage == null
        || currentPackage.isEmpty()
        || erasedType == null
        || erasedType.isEmpty()) {
      return erasedType;
    }
    String raw = stripRootPrefix(rawTypeName(parentTypeText));
    if (raw == null || raw.isEmpty() || raw.indexOf('/') >= 0 || !isClassLike(raw)) {
      return erasedType;
    }
    if (isKnownParentBinary(erasedType, sourceTypesByBinary, traitsByBinary, parentKindResolver)) {
      return erasedType;
    }
    String localCandidate = currentPackage.replace('.', '/') + "/" + raw;
    if (localCandidate.equals(erasedType)) {
      return erasedType;
    }
    if (isKnownParentBinary(
        localCandidate, sourceTypesByBinary, traitsByBinary, parentKindResolver)) {
      return localCandidate;
    }
    return erasedType;
  }

  private static String preferLocalObjectParentType(
      String parentTypeText,
      String erasedType,
      String currentPackage,
      Map<String, Map<String, String>> packageObjectTypes,
      Map<String, ClassDef> traitsByBinary,
      ParentKindResolver parentKindResolver) {
    if (parentTypeText == null
        || parentTypeText.isEmpty()
        || currentPackage == null
        || currentPackage.isEmpty()
        || packageObjectTypes.isEmpty()) {
      return erasedType;
    }
    Map<String, String> packageObjects = packageObjectTypes.get(currentPackage);
    if (packageObjects == null || packageObjects.isEmpty()) {
      return erasedType;
    }
    String raw = stripRootPrefix(rawTypeName(parentTypeText));
    if (raw == null || raw.isEmpty()) {
      return erasedType;
    }
    String[] parts = raw.split("/");
    if (parts.length < 2 || !isClassLike(parts[0])) {
      return erasedType;
    }
    String localObject = packageObjects.get(parts[0]);
    if (localObject == null || localObject.isEmpty()) {
      return erasedType;
    }
    StringBuilder candidate = new StringBuilder(localObject);
    for (int i = 1; i < parts.length; i++) {
      candidate.append('$').append(parts[i]);
    }
    String localCandidate = candidate.toString();
    if (isInterfaceParent(localCandidate, traitsByBinary, parentKindResolver)) {
      return localCandidate;
    }
    return erasedType;
  }

  private static String normalizeParentBinary(String binaryName) {
    if (binaryName == null || binaryName.isEmpty()) {
      return binaryName;
    }
    return switch (binaryName) {
      case "scala/Serializable" -> "java/io/Serializable";
      default -> binaryName;
    };
  }

  private static boolean isKnownInterfaceBinary(String binaryName) {
    if (binaryName == null || binaryName.isEmpty()) {
      return false;
    }
    if (binaryName.startsWith("scala/Product")) {
      return true;
    }
    return switch (binaryName) {
      case "java/io/Serializable", "scala/Equals", "scala/Serializable" -> true;
      default -> false;
    };
  }

  private static String interfaceSuperclass(
      String interfaceBinary,
      Map<String, ClassDef> sourceTypesByBinary,
      Map<String, ClassDef> traitsByBinary,
      Map<String, Map<String, String>> packageObjectTypes,
      Map<ClassDef, ScalaTypeMapper.ImportScope> importScopes,
      Map<ClassDef, ScalaTypeMapper.TypeAliasScope> aliasScopes,
      ParentKindResolver parentKindResolver) {
    return interfaceSuperclass(
        interfaceBinary,
        sourceTypesByBinary,
        traitsByBinary,
        packageObjectTypes,
        importScopes,
        aliasScopes,
        parentKindResolver,
        new HashSet<>());
  }

  private static String interfaceSuperclass(
      String interfaceBinary,
      Map<String, ClassDef> sourceTypesByBinary,
      Map<String, ClassDef> traitsByBinary,
      Map<String, Map<String, String>> packageObjectTypes,
      Map<ClassDef, ScalaTypeMapper.ImportScope> importScopes,
      Map<ClassDef, ScalaTypeMapper.TypeAliasScope> aliasScopes,
      ParentKindResolver parentKindResolver,
      Set<String> seen) {
    interfaceBinary = normalizeParentBinary(interfaceBinary);
    if (interfaceBinary == null || interfaceBinary.isEmpty()) {
      return null;
    }
    if (!seen.add(interfaceBinary)) {
      return null;
    }
    ClassDef traitDef = traitsByBinary.get(interfaceBinary);
    if (traitDef != null) {
      ScalaTypeMapper.ImportScope traitScope =
          importScopes.getOrDefault(traitDef, ScalaTypeMapper.ImportScope.empty());
      ScalaTypeMapper.TypeAliasScope traitAliases =
          aliasScopes.getOrDefault(traitDef, ScalaTypeMapper.TypeAliasScope.empty());
      for (String parent : traitDef.parents()) {
        String erased =
            eraseType(parent, traitDef.packageName(), traitDef.typeParams(), traitScope, traitAliases);
        erased =
            preferKnownLocalParentType(
                parent,
                erased,
                traitDef.packageName(),
                sourceTypesByBinary,
                traitsByBinary,
                parentKindResolver);
        erased =
            preferLocalObjectParentType(
                parent,
                erased,
                traitDef.packageName(),
                packageObjectTypes,
                traitsByBinary,
                parentKindResolver);
        erased = normalizeParentBinary(erased);
        if (erased == null || erased.isEmpty() || "java/lang/Object".equals(erased)) {
          continue;
        }
        if (isInterfaceParent(erased, traitsByBinary, parentKindResolver)) {
          String nested =
              interfaceSuperclass(
                  erased,
                  sourceTypesByBinary,
                  traitsByBinary,
                  packageObjectTypes,
                  importScopes,
                  aliasScopes,
                  parentKindResolver,
                  seen);
          if (nested != null && !nested.isEmpty()) {
            return nested;
          }
        } else if (sourceTypesByBinary.containsKey(erased) && !traitsByBinary.containsKey(erased)) {
          return erased;
        }
      }
      return null;
    }
    return null;
  }

  private static List<String> pruneInheritedInterfaces(
      List<String> interfaces,
      String superName,
      Map<String, ClassDef> sourceTypesByBinary,
      Map<String, ClassDef> traitsByBinary,
      Map<String, Map<String, String>> packageObjectTypes,
      Map<ClassDef, ScalaTypeMapper.ImportScope> importScopes,
      Map<ClassDef, ScalaTypeMapper.TypeAliasScope> aliasScopes,
      ParentKindResolver parentKindResolver) {
    if (interfaces.isEmpty()) {
      return interfaces;
    }
    List<String> normalized = new ArrayList<>();
    for (String iface : interfaces) {
      String normalizedIface = normalizeParentBinary(iface);
      if (normalizedIface == null
          || normalizedIface.isEmpty()
          || normalized.contains(normalizedIface)) {
        continue;
      }
      normalized.add(normalizedIface);
    }
    if (normalized.size() <= 1) {
      return normalized;
    }
    List<String> pruned = new ArrayList<>();
    for (int i = 0; i < normalized.size(); i++) {
      String iface = normalized.get(i);
      List<String> otherDirectInterfaces = new ArrayList<>(normalized);
      otherDirectInterfaces.remove(i);
      if (!isInheritedInterface(
          superName,
          otherDirectInterfaces,
          iface,
          sourceTypesByBinary,
          traitsByBinary,
          packageObjectTypes,
          importScopes,
          aliasScopes,
          parentKindResolver)) {
        pruned.add(iface);
      }
    }
    return pruned;
  }

  private static boolean isInterfaceParent(
      String binaryName,
      Map<String, ClassDef> traitsByBinary,
      ParentKindResolver parentKindResolver) {
    binaryName = normalizeParentBinary(binaryName);
    if (binaryName == null || binaryName.isEmpty() || "java/lang/Object".equals(binaryName)) {
      return false;
    }
    if (isKnownInterfaceBinary(binaryName)) {
      return true;
    }
    if (traitsByBinary.containsKey(binaryName)) {
      return true;
    }
    return parentKindResolver != null && parentKindResolver.isInterface(binaryName);
  }

  private static void addInterfaceIfNotInherited(
      List<String> interfaces,
      String superName,
      String iface,
      Map<String, ClassDef> sourceTypesByBinary,
      Map<String, ClassDef> traitsByBinary,
      Map<String, Map<String, String>> packageObjectTypes,
      Map<ClassDef, ScalaTypeMapper.ImportScope> importScopes,
      Map<ClassDef, ScalaTypeMapper.TypeAliasScope> aliasScopes,
      ParentKindResolver parentKindResolver) {
    String normalizedIface = normalizeParentBinary(iface);
    if (normalizedIface == null || normalizedIface.isEmpty() || interfaces.contains(normalizedIface)) {
      return;
    }
    if (isInheritedInterface(
        superName,
        interfaces,
        normalizedIface,
        sourceTypesByBinary,
        traitsByBinary,
        packageObjectTypes,
        importScopes,
        aliasScopes,
        parentKindResolver)) {
      return;
    }
    interfaces.add(normalizedIface);
  }

  private static boolean isInheritedInterface(
      String superName,
      List<String> interfaces,
      String iface,
      Map<String, ClassDef> sourceTypesByBinary,
      Map<String, ClassDef> traitsByBinary,
      Map<String, Map<String, String>> packageObjectTypes,
      Map<ClassDef, ScalaTypeMapper.ImportScope> importScopes,
      Map<ClassDef, ScalaTypeMapper.TypeAliasScope> aliasScopes,
      ParentKindResolver parentKindResolver) {
    superName = normalizeParentBinary(superName);
    iface = normalizeParentBinary(iface);
    Map<String, Boolean> inheritanceCache = new HashMap<>();
    if (inheritsInterface(
        superName,
        iface,
        sourceTypesByBinary,
        traitsByBinary,
        packageObjectTypes,
        importScopes,
        aliasScopes,
        parentKindResolver,
        new HashSet<>(),
        inheritanceCache)) {
      return true;
    }
    for (String directParent : interfaces) {
      if (inheritsInterface(
          directParent,
          iface,
          sourceTypesByBinary,
          traitsByBinary,
          packageObjectTypes,
          importScopes,
          aliasScopes,
          parentKindResolver,
          new HashSet<>(),
          inheritanceCache)) {
        return true;
      }
    }
    return false;
  }

  private static boolean inheritsInterface(
      String binaryName,
      String iface,
      Map<String, ClassDef> sourceTypesByBinary,
      Map<String, ClassDef> traitsByBinary,
      Map<String, Map<String, String>> packageObjectTypes,
      Map<ClassDef, ScalaTypeMapper.ImportScope> importScopes,
      Map<ClassDef, ScalaTypeMapper.TypeAliasScope> aliasScopes,
      ParentKindResolver parentKindResolver,
      Set<String> seen,
      Map<String, Boolean> inheritanceCache) {
    binaryName = normalizeParentBinary(binaryName);
    iface = normalizeParentBinary(iface);
    if (binaryName == null || binaryName.isEmpty()) {
      return false;
    }
    if (binaryName.equals(iface)) {
      return true;
    }
    Boolean cached = inheritanceCache.get(binaryName);
    if (cached != null) {
      return cached;
    }
    if (!seen.add(binaryName)) {
      return false;
    }
    boolean result = false;
    ClassDef source = sourceTypesByBinary.get(binaryName);
    if (source != null) {
      if (source.isCase() && ("scala/Product".equals(iface) || "java/io/Serializable".equals(iface))) {
        result = true;
      } else {
        ScalaTypeMapper.ImportScope sourceScope =
            importScopes.getOrDefault(source, ScalaTypeMapper.ImportScope.empty());
        ScalaTypeMapper.TypeAliasScope sourceAliases =
            aliasScopes.getOrDefault(source, ScalaTypeMapper.TypeAliasScope.empty());
        for (String parent : source.parents()) {
          String erased =
              eraseType(
                  parent, source.packageName(), source.typeParams(), sourceScope, sourceAliases);
          erased =
              preferKnownLocalParentType(
                  parent,
                  erased,
                  source.packageName(),
                  sourceTypesByBinary,
                  traitsByBinary,
                  parentKindResolver);
          erased =
              preferLocalObjectParentType(
                  parent,
                  erased,
                  source.packageName(),
                  packageObjectTypes,
                  traitsByBinary,
                  parentKindResolver);
          erased = normalizeParentBinary(erased);
          if (inheritsInterface(
              erased,
              iface,
              sourceTypesByBinary,
              traitsByBinary,
              packageObjectTypes,
              importScopes,
              aliasScopes,
              parentKindResolver,
              seen,
              inheritanceCache)) {
            result = true;
            break;
          }
        }
      }
    } else if (parentKindResolver != null) {
      ImmutableList<String> resolvedInterfaces = parentKindResolver.interfaces(binaryName);
      if (resolvedInterfaces != null) {
        for (String parentInterface : resolvedInterfaces) {
          parentInterface = normalizeParentBinary(parentInterface);
          if (inheritsInterface(
              parentInterface,
              iface,
              sourceTypesByBinary,
              traitsByBinary,
              packageObjectTypes,
              importScopes,
              aliasScopes,
              parentKindResolver,
              seen,
              inheritanceCache)) {
            result = true;
            break;
          }
        }
      }
      if (!result) {
        String parentSuper = normalizeParentBinary(parentKindResolver.superName(binaryName));
        result =
            inheritsInterface(
                parentSuper,
                iface,
                sourceTypesByBinary,
                traitsByBinary,
                packageObjectTypes,
                importScopes,
                aliasScopes,
                parentKindResolver,
                seen,
                inheritanceCache);
      }
    }
    seen.remove(binaryName);
    inheritanceCache.put(binaryName, result);
    return result;
  }

  private static void addInterface(List<String> interfaces, String iface) {
    iface = normalizeParentBinary(iface);
    if (iface == null || iface.isEmpty()) {
      return;
    }
    if (!interfaces.contains(iface)) {
      interfaces.add(iface);
    }
  }

  private ScalaLower() {}
}

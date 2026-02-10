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
import com.google.turbine.bytecode.sig.SigParser;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

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
    Set<ClassDef> topLevelAndPackageObjects = new LinkedHashSet<>(defs.topLevelObjects());
    topLevelAndPackageObjects.addAll(defs.packageObjects());
    Map<String, Set<String>> packageTypeMembers =
        packageTypeMembers(defs.topLevelClassDefs(), topLevelAndPackageObjects);
    ScalaTypeMapper.TypeAliasScope qualifiedAliases =
        qualifiedTypeAliasScope(defs.allDefs(), defs.classBinaryNames(), defs.moduleBinaryNames());
    Map<String, String> qualifiedStableMemberTypes =
        qualifiedStableMemberTypes(defs.allDefs(), defs.classBinaryNames(), defs.moduleBinaryNames());
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
      ScalaTypeMapper.ImportScope unitExplicitScope = unitExplicitImportScope(unit);
      List<ClassDef> unitDefs = defs.unitClassDefs().get(unit);
      if (unitDefs == null || unitDefs.isEmpty()) {
        continue;
      }
      for (ClassDef cls : unitDefs) {
        ScalaTypeMapper.ImportScope enclosingScope =
            enclosingOwnerScope(
                cls,
                defs.owners(),
                ownerTypeScopes,
                objectTypeMembers,
                packageTypeMembers,
                unitScope);
        ScalaTypeMapper.ImportScope localQualifierScope =
            mergeImportScopes(unitScope, enclosingScope);
        ScalaTypeMapper.ImportScope localScope =
            importScope(
                cls.imports(),
                cls.packageName(),
                objectTypeMembers,
                packageTypeMembers,
                localQualifierScope);
        ScalaTypeMapper.ImportScope currentPackageScope =
            currentPackageClasspathScope(cls, parentKindResolver);
        ScalaTypeMapper.ImportScope ownerScope =
            ownerTypeScopes.getOrDefault(cls, ScalaTypeMapper.ImportScope.empty());
        ScalaTypeMapper.ImportScope scope =
            mergeImportScopes(
                mergeImportScopes(
                    mergeImportScopes(
                        mergeImportScopes(
                            mergeImportScopes(unitScope, currentPackageScope), unitExplicitScope),
                        enclosingScope),
                    localScope),
                ownerScope);
        scope = addClasspathResolvedWildcardTypes(cls, scope, parentKindResolver);
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
              qualifiedStableMemberTypes,
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
              qualifiedStableMemberTypes,
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
                sourceTypesByBinary,
                packageObjectTypes,
                importScopes,
                aliasScopes,
                qualifiedStableMemberTypes,
                resolver,
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
              sourceTypesByBinary,
              packageObjectTypes,
              importScopes,
              aliasScopes,
              qualifiedStableMemberTypes,
              resolver,
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
      Map<String, String> globalStableMemberTypes,
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
      String firstErased =
          canonicalParentType(
              first,
              eraseType(first, pkg, cls.typeParams(), scope, aliasScope),
              pkg,
              cls.typeParams(),
              scope,
              aliasScope,
              sourceTypesByBinary,
              traitsByBinary,
              packageObjectTypes,
              parentKindResolver);
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
            canonicalParentType(
                cls.parents().get(i),
                eraseType(cls.parents().get(i), pkg, cls.typeParams(), scope, aliasScope),
                pkg,
                cls.typeParams(),
                scope,
                aliasScope,
                sourceTypesByBinary,
                traitsByBinary,
                packageObjectTypes,
                parentKindResolver);
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
    methods.addAll(
        memberMethods(
            cls, scope, aliasScope, globalStableMemberTypes, /* staticContext= */ false));
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
              globalStableMemberTypes,
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
              globalStableMemberTypes,
              /* isTrait= */ isTrait));
      methods.addAll(
          traitForwarders(
              companion,
              traitsByBinary,
              importScopes,
              aliasScopes,
              packageObjectTypes,
              companionScope,
              companionAliases,
              globalStableMemberTypes,
              /* targetModuleBinary= */ null,
              /* staticContext= */ true,
              /* publicOnly= */ true));
      methods.addAll(
          classParentForwarders(
              companion,
              sourceTypesByBinary,
              traitsByBinary,
              packageObjectTypes,
              importScopes,
              aliasScopes,
              companionScope,
              companionAliases,
              globalStableMemberTypes,
              parentKindResolver,
              /* staticContext= */ true,
              /* publicOnly= */ true));
    }
    methods = uniqueMethods(methods);
    List<ClassFile.FieldInfo> fields = memberFields(cls, scope, aliasScope);

    String classSignature = safeClassSignature(ScalaSignature.classSignature(cls, scope, aliasScope));
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
      Map<String, String> globalStableMemberTypes,
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
    methods.addAll(
        memberMethods(
            obj, scope, aliasScope, globalStableMemberTypes, /* staticContext= */ false));
    methods.addAll(
        traitForwarders(
            obj,
            traitsByBinary,
            importScopes,
            aliasScopes,
            packageObjectTypes,
            scope,
            aliasScope,
            globalStableMemberTypes,
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
      String first =
          canonicalParentType(
              obj.parents().get(0),
              eraseType(obj.parents().get(0), pkg, obj.typeParams(), scope, aliasScope),
              pkg,
              obj.typeParams(),
              scope,
              aliasScope,
              sourceTypesByBinary,
              traitsByBinary,
              packageObjectTypes,
              parentKindResolver);
      first = normalizeObjectParentType(first, moduleBinary);
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
        String erasedParent =
            canonicalParentType(
                obj.parents().get(i),
                eraseType(obj.parents().get(i), pkg, obj.typeParams(), scope, aliasScope),
                pkg,
                obj.typeParams(),
                scope,
                aliasScope,
                sourceTypesByBinary,
                traitsByBinary,
                packageObjectTypes,
                parentKindResolver);
        erasedParent = normalizeObjectParentType(erasedParent, moduleBinary);
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
      Map<String, ClassDef> sourceTypesByBinary,
      Map<String, Map<String, String>> packageObjectTypes,
      Map<ClassDef, ScalaTypeMapper.ImportScope> importScopes,
      Map<ClassDef, ScalaTypeMapper.TypeAliasScope> aliasScopes,
      Map<String, String> globalStableMemberTypes,
      ParentKindResolver parentKindResolver,
      int majorVersion) {
    int access = TurbineFlag.ACC_PUBLIC | TurbineFlag.ACC_FINAL | TurbineFlag.ACC_SUPER;
    boolean isApp = isAppObject(obj, scope, aliasScope);

    List<ClassFile.MethodInfo> methods = new ArrayList<>();
    methods.addAll(publicForwarders(obj, scope, aliasScope, globalStableMemberTypes));
    methods.addAll(
        traitForwarders(
            obj,
            traitsByBinary,
            importScopes,
            aliasScopes,
            packageObjectTypes,
            scope,
            aliasScope,
            globalStableMemberTypes,
            moduleBinary,
            /* staticContext= */ true,
            /* publicOnly= */ true));
    methods.addAll(
        classParentForwarders(
            obj,
            sourceTypesByBinary,
            traitsByBinary,
            packageObjectTypes,
            importScopes,
            aliasScopes,
            scope,
            aliasScope,
            globalStableMemberTypes,
            parentKindResolver,
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
      Map<String, ClassDef> sourceTypesByBinary,
      Map<String, Map<String, String>> packageObjectTypes,
      Map<ClassDef, ScalaTypeMapper.ImportScope> importScopes,
      Map<ClassDef, ScalaTypeMapper.TypeAliasScope> aliasScopes,
      Map<String, String> globalStableMemberTypes,
      ParentKindResolver parentKindResolver,
      int majorVersion) {
    String fullPackage = effectiveTypePackage(pkgObj);

    ClassDef companion = pkgObj;

    String moduleBinary = binaryName(fullPackage, "package$");
    String companionBinary = binaryName(fullPackage, "package");

    List<ClassFile.FieldInfo> fields = new ArrayList<>();
    fields.add(moduleField(moduleBinary));
    fields.addAll(memberFields(pkgObj, scope, aliasScope));
    fields = uniqueFields(fields);

    List<ClassFile.MethodInfo> moduleMethods = new ArrayList<>();
    moduleMethods.add(defaultConstructor(/* isPublic= */ false));
    moduleMethods.addAll(
        memberMethods(
            pkgObj, scope, aliasScope, globalStableMemberTypes, /* staticContext= */ false));
    moduleMethods.addAll(
        traitForwarders(
            pkgObj,
            traitsByBinary,
            importScopes,
            aliasScopes,
            packageObjectTypes,
            scope,
            aliasScope,
            globalStableMemberTypes,
            moduleBinary,
            /* staticContext= */ false,
            /* publicOnly= */ false));
    moduleMethods.addAll(
        defaultGettersForDefs(
            pkgObj.members(),
            fullPackage,
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
            globalStableMemberTypes,
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
            globalStableMemberTypes,
            moduleBinary,
            /* staticContext= */ true,
            /* publicOnly= */ true));
    companionMethods.addAll(
        classParentForwarders(
            companion,
            sourceTypesByBinary,
            traitsByBinary,
            packageObjectTypes,
            importScopes,
            aliasScopes,
            scope,
            aliasScope,
            globalStableMemberTypes,
            parentKindResolver,
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
      Map<String, String> globalStableMemberTypes,
      boolean staticContext) {
    List<ClassFile.MethodInfo> methods = new ArrayList<>();
    String pkg = effectiveTypePackage(cls);
    Set<String> typeParams = ScalaTypeMapper.typeParamNames(cls.typeParams());
    Map<String, String> classTypeParamErasures = typeParamErasures(cls.typeParams());
    Map<String, String> stableMemberTypes =
        mergeStableMemberTypes(stableMemberTypes(cls), globalStableMemberTypes);
    Set<String> reservedMethodKeys =
        declaredMethodKeys(
            cls,
            pkg,
            typeParams,
            classTypeParamErasures,
            scope,
            aliasScope,
            stableMemberTypes,
            staticContext);
    for (Defn defn : cls.members()) {
      if (defn instanceof DefDef def) {
        if ("this".equals(def.name()) && cls.kind() == ClassDef.Kind.CLASS) {
          methods.add(ctorMethod(def, cls, scope, aliasScope));
        } else {
          methods.addAll(
              buildMethods(
                  def,
                  pkg,
                  typeParams,
                  classTypeParamErasures,
                  scope,
                  aliasScope,
                  stableMemberTypes,
                  staticContext,
                  cls.kind()));
        }
      } else if (defn instanceof ValDef val) {
        methods.addAll(
            accessorsForVal(
                val,
                pkg,
                typeParams,
                scope,
                aliasScope,
                scope,
                aliasScope,
                classTypeParamErasures,
                stableMemberTypes,
                staticContext,
                cls.kind(),
                reservedMethodKeys));
      }
    }
    // constructor params with val/var become accessors
    for (ParamList list : cls.ctorParams()) {
      for (Param param : list.params()) {
        if (param.modifiers().contains("val") || param.modifiers().contains("var") || cls.isCase()) {
          ValDef val =
              new ValDef(
                  pkg,
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
                  pkg,
                  typeParams,
                  scope,
                  aliasScope,
                  scope,
                  aliasScope,
                  classTypeParamErasures,
                  stableMemberTypes,
                  staticContext,
                  cls.kind(),
                  reservedMethodKeys));
        }
      }
    }
    return methods;
  }

  private static Set<String> declaredMethodKeys(
      ClassDef cls,
      String pkg,
      Set<String> typeParams,
      Map<String, String> classTypeParamErasures,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      Map<String, String> stableMemberTypes,
      boolean staticContext) {
    Set<String> keys = new HashSet<>();
    for (Defn defn : cls.members()) {
      if (!(defn instanceof DefDef def)) {
        continue;
      }
      if ("this".equals(def.name()) && cls.kind() == ClassDef.Kind.CLASS) {
        continue;
      }
      for (ClassFile.MethodInfo method :
          buildMethods(
              def,
              pkg,
              typeParams,
              classTypeParamErasures,
              scope,
              aliasScope,
              stableMemberTypes,
              staticContext,
              cls.kind())) {
        keys.add(method.name() + method.descriptor());
      }
    }
    return keys;
  }

  private static List<ClassFile.FieldInfo> memberFields(
      ClassDef cls,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope) {
    if (cls.kind() == ClassDef.Kind.TRAIT) {
      return ImmutableList.of();
    }
    List<ClassFile.FieldInfo> fields = new ArrayList<>();
    String pkg = effectiveTypePackage(cls);
    Set<String> typeParams = ScalaTypeMapper.typeParamNames(cls.typeParams());
    for (Defn defn : cls.members()) {
      if (defn instanceof ValDef val) {
        if (val.hasDefault()) {
          fields.add(fieldForVal(val, pkg, typeParams, scope, aliasScope));
        }
      }
    }
    for (ParamList list : cls.ctorParams()) {
      for (Param param : list.params()) {
        if (param.modifiers().contains("val") || param.modifiers().contains("var") || cls.isCase()) {
          fields.add(fieldForParam(param, pkg, typeParams, scope, aliasScope));
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
      Map<String, String> globalStableMemberTypes,
      boolean isTrait) {
    List<ClassFile.MethodInfo> methods = new ArrayList<>();
    String pkg = effectiveTypePackage(obj);
    Set<String> typeParams = ScalaTypeMapper.typeParamNames(obj.typeParams());
    Map<String, String> classTypeParamErasures = typeParamErasures(obj.typeParams());
    Map<String, String> stableMemberTypes =
        mergeStableMemberTypes(stableMemberTypes(obj), globalStableMemberTypes);
    ClassDef.Kind ownerKind = isTrait ? ClassDef.Kind.TRAIT : ClassDef.Kind.CLASS;
    for (Defn defn : obj.members()) {
      if (defn instanceof DefDef def) {
        methods.addAll(
            buildMethods(
                def,
                pkg,
                typeParams,
                classTypeParamErasures,
                scope,
                aliasScope,
                stableMemberTypes,
                /* staticContext= */ true,
                ownerKind));
      } else if (defn instanceof ValDef val) {
        methods.addAll(
            accessorsForVal(
                val,
                pkg,
                typeParams,
                scope,
                aliasScope,
                scope,
                aliasScope,
                classTypeParamErasures,
                stableMemberTypes,
                /* staticContext= */ true,
                ownerKind));
      }
    }
    methods.addAll(
        defaultGettersForDefs(
            obj.members(),
            pkg,
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
      Map<String, String> globalStableMemberTypes,
      String targetModuleBinary,
      boolean staticContext,
      boolean publicOnly) {
    List<ClassFile.MethodInfo> methods = new ArrayList<>();
    if (traitsByBinary.isEmpty() || target.parents().isEmpty()) {
      return methods;
    }
    Set<String> classTypeParams = ScalaTypeMapper.typeParamNames(target.typeParams());
    Map<String, String> classTypeParamErasures = typeParamErasures(target.typeParams());
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
      ScalaTypeMapper.ImportScope forwarderScope = mergeImportScopes(traitScope, targetScope);
      ScalaTypeMapper.TypeAliasScope forwarderAliases =
          mergeAliasScopes(traitAliases, targetAliases);
      Map<String, String> traitStableMemberTypes =
          mergeStableMemberTypes(stableMemberTypes(current.trait()), globalStableMemberTypes);
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
                  classTypeParamErasures,
                  targetScope,
                  targetAliases,
                  forwarderScope,
                  forwarderAliases,
                  traitStableMemberTypes,
                  staticContext,
                  ClassDef.Kind.CLASS));
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
                  targetScope,
                  targetAliases,
                  forwarderScope,
                  forwarderAliases,
                  classTypeParamErasures,
                  traitStableMemberTypes,
                  staticContext,
                  ClassDef.Kind.CLASS));
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
                forwarderScope,
                forwarderAliases);
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

  private static List<ClassFile.MethodInfo> classParentForwarders(
      ClassDef target,
      Map<String, ClassDef> sourceTypesByBinary,
      Map<String, ClassDef> traitsByBinary,
      Map<String, Map<String, String>> packageObjectTypes,
      Map<ClassDef, ScalaTypeMapper.ImportScope> importScopes,
      Map<ClassDef, ScalaTypeMapper.TypeAliasScope> aliasScopes,
      ScalaTypeMapper.ImportScope targetScope,
      ScalaTypeMapper.TypeAliasScope targetAliases,
      Map<String, String> globalStableMemberTypes,
      ParentKindResolver parentKindResolver,
      boolean staticContext,
      boolean publicOnly) {
    List<ClassFile.MethodInfo> methods = new ArrayList<>();
    if (sourceTypesByBinary.isEmpty() || target.parents().isEmpty()) {
      return methods;
    }
    Set<String> classTypeParams = ScalaTypeMapper.typeParamNames(target.typeParams());
    Map<String, String> classTypeParamErasures = typeParamErasures(target.typeParams());
    Deque<ParentRef> pending = new ArrayDeque<>();
    for (String parent : target.parents()) {
      String binary =
          canonicalParentType(
              parent,
              eraseType(parent, target.packageName(), target.typeParams(), targetScope, targetAliases),
              target.packageName(),
              target.typeParams(),
              targetScope,
              targetAliases,
              sourceTypesByBinary,
              traitsByBinary,
              packageObjectTypes,
              parentKindResolver);
      ClassDef parentDef = sourceTypesByBinary.get(binary);
      if (parentDef != null && parentDef.kind() == ClassDef.Kind.CLASS) {
        pending.addLast(new ParentRef(parentDef, parent));
      }
    }
    Set<String> seen = new HashSet<>();
    while (!pending.isEmpty()) {
      ParentRef current = pending.removeFirst();
      String currentBinary = sourceBinaryName(current.parent(), sourceTypesByBinary);
      if (currentBinary == null || currentBinary.isEmpty()) {
        currentBinary = binaryName(current.parent().packageName(), current.parent().name());
      }
      String currentKey = currentBinary + "::" + current.parentTypeText();
      if (!seen.add(currentKey)) {
        continue;
      }
      ScalaTypeMapper.ImportScope parentScope =
          importScopes.getOrDefault(current.parent(), ScalaTypeMapper.ImportScope.empty());
      ScalaTypeMapper.TypeAliasScope parentAliases =
          aliasScopes.getOrDefault(current.parent(), ScalaTypeMapper.TypeAliasScope.empty());
      ScalaTypeMapper.ImportScope forwarderScope = mergeImportScopes(parentScope, targetScope);
      ScalaTypeMapper.TypeAliasScope forwarderAliases =
          mergeAliasScopes(parentAliases, targetAliases);
      Map<String, String> parentStableMemberTypes =
          mergeStableMemberTypes(stableMemberTypes(current.parent()), globalStableMemberTypes);
      Map<String, String> substitutions =
          traitTypeSubstitutions(current.parent(), current.parentTypeText());
      for (Defn defn : current.parent().members()) {
        if (defn instanceof DefDef def) {
          if ("this".equals(def.name()) || isAbstractDef(def, ClassDef.Kind.CLASS)) {
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
                  current.parent().packageName(),
                  classTypeParams,
                  classTypeParamErasures,
                  targetScope,
                  targetAliases,
                  forwarderScope,
                  forwarderAliases,
                  parentStableMemberTypes,
                  staticContext,
                  ClassDef.Kind.CLASS));
        } else if (defn instanceof ValDef val) {
          if (isAbstractVal(val, ClassDef.Kind.CLASS)) {
            continue;
          }
          ValDef adjusted = substituteVal(val, substitutions);
          methods.addAll(
              accessorsForVal(
                  adjusted,
                  current.parent().packageName(),
                  classTypeParams,
                  targetScope,
                  targetAliases,
                  forwarderScope,
                  forwarderAliases,
                  classTypeParamErasures,
                  parentStableMemberTypes,
                  staticContext,
                  ClassDef.Kind.CLASS));
        }
      }
      for (String parent : current.parent().parents()) {
        String substitutedParent = substituteType(parent, substitutions);
        String parentBinary =
            canonicalParentType(
                substitutedParent,
                eraseType(
                    substitutedParent,
                    current.parent().packageName(),
                    current.parent().typeParams(),
                    forwarderScope,
                    forwarderAliases),
                current.parent().packageName(),
                current.parent().typeParams(),
                forwarderScope,
                forwarderAliases,
                sourceTypesByBinary,
                traitsByBinary,
                packageObjectTypes,
                parentKindResolver);
        ClassDef parentDef = sourceTypesByBinary.get(parentBinary);
        if (parentDef != null && parentDef.kind() == ClassDef.Kind.CLASS) {
          pending.addLast(new ParentRef(parentDef, substitutedParent));
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

  private static @Nullable String sourceBinaryName(
      ClassDef source, Map<String, ClassDef> sourceTypesByBinary) {
    if (source == null || sourceTypesByBinary.isEmpty()) {
      return null;
    }
    for (Map.Entry<String, ClassDef> entry : sourceTypesByBinary.entrySet()) {
      if (entry.getValue() == source) {
        return entry.getKey();
      }
    }
    return null;
  }

  private static List<ClassFile.MethodInfo> publicForwarders(
      ClassDef obj,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      Map<String, String> globalStableMemberTypes) {
    List<ClassFile.MethodInfo> methods =
        forwarders(
            obj,
            scope,
            aliasScope,
            globalStableMemberTypes,
            /* isTrait= */ false);
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
    Map<String, String> typeParamErasures = typeParamErasures(cls.typeParams());
    for (Param param : params) {
      desc.append(
          methodParamDescriptor(
              param.type(), cls.packageName(), typeParams, scope, aliasScope, typeParamErasures));
      paramTypes.add(param.type());
    }
    desc.append(')').append('V');
    String signature =
        safeMethodSignature(
            ScalaSignature.methodSignature(
                ImmutableList.of(),
                paramTypes,
                null,
                typeParams,
                cls.packageName(),
                scope,
                aliasScope));
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
    Map<String, String> typeParamErasures =
        mergeTypeParamErasures(
            typeParamErasures(cls.typeParams()), typeParamErasures(def.typeParams()));
    for (ParamList list : def.paramLists()) {
      for (Param param : list.params()) {
        desc.append(
            methodParamDescriptor(
                param.type(), cls.packageName(), typeParams, scope, aliasScope, typeParamErasures));
        paramTypes.add(param.type());
      }
    }
    desc.append(')').append('V');
    String signature =
        safeMethodSignature(
            ScalaSignature.methodSignature(
                ImmutableList.of(),
                paramTypes,
                null,
                typeParams,
                cls.packageName(),
                scope,
                aliasScope));
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
    if (modifiers.contains("synchronized")) {
      access |= TurbineFlag.ACC_SYNCHRONIZED;
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
    return buildMethod(
        def,
        pkg,
        classTypeParams,
        Map.of(),
        scope,
        aliasScope,
        scope,
        aliasScope,
        Map.of(),
        staticContext,
        ownerKind);
  }

  private static ClassFile.MethodInfo buildMethod(
      DefDef def,
      String pkg,
      Set<String> classTypeParams,
      Map<String, String> classTypeParamErasures,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      ScalaTypeMapper.ImportScope fallbackScope,
      ScalaTypeMapper.TypeAliasScope fallbackAliasScope,
      Map<String, String> stableMemberTypes,
      boolean staticContext,
      ClassDef.Kind ownerKind) {
    Set<String> typeParams = new HashSet<>(classTypeParams);
    typeParams.addAll(ScalaTypeMapper.typeParamNames(def.typeParams()));
    Map<String, String> typeParamErasures =
        mergeTypeParamErasures(classTypeParamErasures, typeParamErasures(def.typeParams()));

    StringBuilder desc = new StringBuilder();
    desc.append('(');
    List<String> paramTypes = new ArrayList<>();
    for (JvmMethodParam param : jvmMethodParams(def)) {
      desc.append(
          methodParamDescriptor(
              param.typeText(),
              pkg,
              typeParams,
              scope,
              aliasScope,
              fallbackScope,
              fallbackAliasScope,
              typeParamErasures));
      paramTypes.add(param.typeText());
    }
    desc.append(')');
    desc.append(
        methodReturnDescriptor(
            def.returnType(),
            pkg,
            typeParams,
            scope,
            aliasScope,
            fallbackScope,
            fallbackAliasScope,
            typeParamErasures,
            stableMemberTypes));

    boolean isAbstract = isAbstractDef(def, ownerKind);
    int access = methodAccess(def.modifiers(), staticContext, isAbstract, ownerKind);

    String signature =
        safeMethodSignature(
            ScalaSignature.methodSignature(
                def.typeParams(), paramTypes, def.returnType(), typeParams, pkg, scope, aliasScope));
    return new ClassFile.MethodInfo(
        access,
        encodeName(def.name()),
        desc.toString(),
        signature,
        methodExceptions(
            def.modifiers(),
            pkg,
            typeParams,
            scope,
            aliasScope,
            fallbackScope,
            fallbackAliasScope,
            typeParamErasures),
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
    return buildMethods(
        def,
        pkg,
        classTypeParams,
        Map.of(),
        scope,
        aliasScope,
        Map.of(),
        staticContext,
        ownerKind);
  }

  private static List<ClassFile.MethodInfo> buildMethods(
      DefDef def,
      String pkg,
      Set<String> classTypeParams,
      Map<String, String> classTypeParamErasures,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      Map<String, String> stableMemberTypes,
      boolean staticContext,
      ClassDef.Kind ownerKind) {
    return buildMethods(
        def,
        pkg,
        classTypeParams,
        classTypeParamErasures,
        scope,
        aliasScope,
        scope,
        aliasScope,
        stableMemberTypes,
        staticContext,
        ownerKind);
  }

  private static List<ClassFile.MethodInfo> buildMethods(
      DefDef def,
      String pkg,
      Set<String> classTypeParams,
      Map<String, String> classTypeParamErasures,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      ScalaTypeMapper.ImportScope fallbackScope,
      ScalaTypeMapper.TypeAliasScope fallbackAliasScope,
      Map<String, String> stableMemberTypes,
      boolean staticContext,
      ClassDef.Kind ownerKind) {
    ClassFile.MethodInfo method =
        buildMethod(
            def,
            pkg,
            classTypeParams,
            classTypeParamErasures,
            scope,
            aliasScope,
            fallbackScope,
            fallbackAliasScope,
            stableMemberTypes,
            staticContext,
            ownerKind);
    if (!hasVarArgsParam(def) || !hasVarArgsAnnotation(def.modifiers())) {
      return ImmutableList.of(method);
    }
    ClassFile.MethodInfo bridge =
        buildVarArgsBridge(
            def,
            pkg,
            classTypeParams,
            classTypeParamErasures,
            scope,
            aliasScope,
            fallbackScope,
            fallbackAliasScope,
            stableMemberTypes,
            staticContext,
            ownerKind);
    if (bridge == null) {
      return ImmutableList.of(method);
    }
    return ImmutableList.of(method, bridge);
  }

  private static boolean hasVarArgsParam(DefDef def) {
    for (JvmMethodParam param : jvmMethodParams(def)) {
      if (param.varArgs()) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasVarArgsAnnotation(ImmutableList<String> modifiers) {
    return modifiers != null && modifiers.contains("varargs");
  }

  private static ClassFile.MethodInfo buildVarArgsBridge(
      DefDef def,
      String pkg,
      Set<String> classTypeParams,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      boolean staticContext,
      ClassDef.Kind ownerKind) {
    return buildVarArgsBridge(
        def,
        pkg,
        classTypeParams,
        Map.of(),
        scope,
        aliasScope,
        scope,
        aliasScope,
        Map.of(),
        staticContext,
        ownerKind);
  }

  private static ClassFile.MethodInfo buildVarArgsBridge(
      DefDef def,
      String pkg,
      Set<String> classTypeParams,
      Map<String, String> classTypeParamErasures,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      ScalaTypeMapper.ImportScope fallbackScope,
      ScalaTypeMapper.TypeAliasScope fallbackAliasScope,
      Map<String, String> stableMemberTypes,
      boolean staticContext,
      ClassDef.Kind ownerKind) {
    StringBuilder desc = new StringBuilder();
    desc.append('(');
    Set<String> typeParams = new HashSet<>(classTypeParams);
    typeParams.addAll(ScalaTypeMapper.typeParamNames(def.typeParams()));
    Map<String, String> typeParamErasures =
        mergeTypeParamErasures(classTypeParamErasures, typeParamErasures(def.typeParams()));
    boolean hasVarArgs = false;
    for (JvmMethodParam param : jvmMethodParams(def)) {
      if (param.varArgs()) {
        hasVarArgs = true;
        desc.append(
            methodVarArgsDescriptor(
                param.typeText(),
                pkg,
                typeParams,
                scope,
                aliasScope,
                fallbackScope,
                fallbackAliasScope,
                typeParamErasures));
      } else {
        desc.append(
            methodParamDescriptor(
                param.typeText(),
                pkg,
                typeParams,
                scope,
                aliasScope,
                fallbackScope,
                fallbackAliasScope,
                typeParamErasures));
      }
    }
    if (!hasVarArgs) {
      return null;
    }
    desc.append(')');
    desc.append(
        methodReturnDescriptor(
            def.returnType(),
            pkg,
            typeParams,
            scope,
            aliasScope,
            fallbackScope,
            fallbackAliasScope,
            typeParamErasures,
            stableMemberTypes));
    int access =
        methodAccess(def.modifiers(), staticContext, /* isAbstract= */ false, ownerKind)
            | TurbineFlag.ACC_VARARGS;
    return new ClassFile.MethodInfo(
        access,
        encodeName(def.name()),
        desc.toString(),
        /* signature= */ null,
        methodExceptions(
            def.modifiers(),
            pkg,
            typeParams,
            scope,
            aliasScope,
            fallbackScope,
            fallbackAliasScope,
            typeParamErasures),
        /* defaultValue= */ null,
        /* annotations= */ ImmutableList.of(),
        /* parameterAnnotations= */ ImmutableList.of(),
        /* typeAnnotations= */ ImmutableList.of(),
        /* parameters= */ ImmutableList.of());
  }

  private static String methodParamDescriptor(
      @Nullable String typeText,
      String pkg,
      Set<String> typeParams,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      Map<String, String> typeParamErasures) {
    return methodParamDescriptor(
        typeText,
        pkg,
        typeParams,
        scope,
        aliasScope,
        scope,
        aliasScope,
        typeParamErasures);
  }

  private static String methodParamDescriptor(
      @Nullable String typeText,
      String pkg,
      Set<String> typeParams,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      ScalaTypeMapper.ImportScope fallbackScope,
      ScalaTypeMapper.TypeAliasScope fallbackAliasScope,
      Map<String, String> typeParamErasures) {
    return methodTypeDescriptor(
        typeText,
        pkg,
        typeParams,
        scope,
        aliasScope,
        fallbackScope,
        fallbackAliasScope,
        typeParamErasures,
        Map.of(),
        MethodTypePosition.PARAM);
  }

  private static String methodVarArgsDescriptor(
      @Nullable String typeText,
      String pkg,
      Set<String> typeParams,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      Map<String, String> typeParamErasures) {
    return methodVarArgsDescriptor(
        typeText,
        pkg,
        typeParams,
        scope,
        aliasScope,
        scope,
        aliasScope,
        typeParamErasures);
  }

  private static String methodVarArgsDescriptor(
      @Nullable String typeText,
      String pkg,
      Set<String> typeParams,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      ScalaTypeMapper.ImportScope fallbackScope,
      ScalaTypeMapper.TypeAliasScope fallbackAliasScope,
      Map<String, String> typeParamErasures) {
    return methodTypeDescriptor(
        typeText,
        pkg,
        typeParams,
        scope,
        aliasScope,
        fallbackScope,
        fallbackAliasScope,
        typeParamErasures,
        Map.of(),
        MethodTypePosition.VARARGS_PARAM);
  }

  private static String methodReturnDescriptor(
      @Nullable String typeText,
      String pkg,
      Set<String> typeParams,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      Map<String, String> typeParamErasures) {
    return methodReturnDescriptor(
        typeText, pkg, typeParams, scope, aliasScope, typeParamErasures, Map.of());
  }

  private static String methodReturnDescriptor(
      @Nullable String typeText,
      String pkg,
      Set<String> typeParams,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      Map<String, String> typeParamErasures,
      Map<String, String> stableMemberTypes) {
    return methodReturnDescriptor(
        typeText,
        pkg,
        typeParams,
        scope,
        aliasScope,
        scope,
        aliasScope,
        typeParamErasures,
        stableMemberTypes);
  }

  private static String methodReturnDescriptor(
      @Nullable String typeText,
      String pkg,
      Set<String> typeParams,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      ScalaTypeMapper.ImportScope fallbackScope,
      ScalaTypeMapper.TypeAliasScope fallbackAliasScope,
      Map<String, String> typeParamErasures,
      Map<String, String> stableMemberTypes) {
    return methodTypeDescriptor(
        typeText,
        pkg,
        typeParams,
        scope,
        aliasScope,
        fallbackScope,
        fallbackAliasScope,
        typeParamErasures,
        stableMemberTypes,
        MethodTypePosition.RETURN);
  }

  private enum MethodTypePosition {
    PARAM,
    VARARGS_PARAM,
    RETURN
  }

  private static String methodTypeDescriptor(
      @Nullable String typeText,
      String pkg,
      Set<String> typeParams,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      ScalaTypeMapper.ImportScope fallbackScope,
      ScalaTypeMapper.TypeAliasScope fallbackAliasScope,
      Map<String, String> typeParamErasures,
      Map<String, String> stableMemberTypes,
      MethodTypePosition position) {
    String primary =
        methodTypeDescriptorInScope(
            typeText,
            pkg,
            typeParams,
            scope,
            aliasScope,
            typeParamErasures,
            stableMemberTypes,
            position);
    if ((fallbackScope == null && fallbackAliasScope == null)
        || (fallbackScope == scope && fallbackAliasScope == aliasScope)) {
      return primary;
    }
    ScalaTypeMapper.ImportScope normalizedFallbackScope =
        fallbackScope == null ? scope : fallbackScope;
    ScalaTypeMapper.TypeAliasScope normalizedFallbackAliases =
        fallbackAliasScope == null ? aliasScope : fallbackAliasScope;
    String fallback =
        methodTypeDescriptorInScope(
            typeText,
            pkg,
            typeParams,
            normalizedFallbackScope,
            normalizedFallbackAliases,
            typeParamErasures,
            stableMemberTypes,
            position);
    if (shouldPreferFallbackMethodDescriptor(primary, fallback, typeText, pkg)) {
      return fallback;
    }
    return primary;
  }

  private static String methodTypeDescriptorInScope(
      @Nullable String typeText,
      String pkg,
      Set<String> typeParams,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      Map<String, String> typeParamErasures,
      Map<String, String> stableMemberTypes,
      MethodTypePosition position) {
    String normalizedTypeText = typeText;
    if (position == MethodTypePosition.RETURN) {
      normalizedTypeText = resolveStableMemberReturnType(typeText, pkg, stableMemberTypes);
    }
    if (position != MethodTypePosition.VARARGS_PARAM) {
      normalizedTypeText =
          eraseTypeParamArrayMethodType(normalizedTypeText, typeParams, typeParamErasures);
    }
    normalizedTypeText = normalizeTupleTypeForErasure(normalizedTypeText);
    String directDescriptor =
        methodPositionDescriptor(
            normalizedTypeText, pkg, typeParams, scope, aliasScope, typeParamErasures, position);
    String resolvedTypeText = resolveMethodTypeAlias(normalizedTypeText, pkg, scope, aliasScope);
    String descriptor = directDescriptor;
    if (resolvedTypeText != null && !resolvedTypeText.equals(normalizedTypeText)) {
      String aliasedDescriptor =
          methodPositionDescriptor(
              resolvedTypeText, pkg, typeParams, scope, aliasScope, typeParamErasures, position);
      descriptor =
          chooseMethodDescriptorForAlias(
              aliasedDescriptor, directDescriptor, normalizedTypeText, pkg, scope);
    }
    if (isSpeculativeCurrentPackageMethodDescriptor(descriptor, normalizedTypeText, pkg)) {
      String ownerQualifiedNested =
          ownerQualifiedNestedMethodBinaryCandidate(normalizedTypeText, pkg, scope, aliasScope);
      if (ownerQualifiedNested != null && !ownerQualifiedNested.isEmpty()) {
        descriptor = "L" + ownerQualifiedNested + ";";
      } else {
        String objectWildcardNested =
            wildcardObjectNestedMethodBinaryCandidate(normalizedTypeText, scope);
        if (objectWildcardNested != null && !objectWildcardNested.isEmpty()) {
          descriptor = "L" + objectWildcardNested + ";";
        }
      }
    }
    if (position == MethodTypePosition.RETURN) {
      descriptor = normalizeMethodReturnDescriptor(descriptor, normalizedTypeText);
    }
    return descriptor;
  }

  private static String methodPositionDescriptor(
      @Nullable String typeText,
      String pkg,
      Set<String> typeParams,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      Map<String, String> typeParamErasures,
      MethodTypePosition position) {
    return switch (position) {
      case PARAM ->
          ScalaTypeMapper.descriptorForParam(
              typeText, pkg, typeParams, scope, aliasScope, typeParamErasures);
      case VARARGS_PARAM ->
          ScalaTypeMapper.descriptorForVarArgsParam(
              typeText, pkg, typeParams, scope, aliasScope, typeParamErasures);
      case RETURN ->
          ScalaTypeMapper.descriptorForReturn(
              typeText, pkg, typeParams, scope, aliasScope, typeParamErasures);
    };
  }

  private static String chooseMethodDescriptorForAlias(
      String aliasedDescriptor,
      String directDescriptor,
      @Nullable String typeText,
      String pkg,
      ScalaTypeMapper.ImportScope scope) {
    if (aliasedDescriptor == null || aliasedDescriptor.isEmpty()) {
      return directDescriptor;
    }
    if (directDescriptor == null || directDescriptor.isEmpty()) {
      return aliasedDescriptor;
    }
    if (aliasedDescriptor.equals(directDescriptor)) {
      return aliasedDescriptor;
    }
    if (shouldPreferDirectOwnerVisibleDescriptor(aliasedDescriptor, directDescriptor, typeText, pkg, scope)) {
      return directDescriptor;
    }
    return aliasedDescriptor;
  }

  private static boolean shouldPreferDirectOwnerVisibleDescriptor(
      String aliasedDescriptor,
      String directDescriptor,
      @Nullable String typeText,
      String pkg,
      ScalaTypeMapper.ImportScope scope) {
    String raw = stripRootPrefix(rawTypeName(typeText));
    if (raw == null || raw.isEmpty()) {
      return false;
    }
    String aliasedBinary = descriptorBinary(aliasedDescriptor);
    String directBinary = descriptorBinary(directDescriptor);
    if (aliasedBinary == null || directBinary == null || aliasedBinary.equals(directBinary)) {
      return false;
    }
    if (shouldPreferDirectSelfTypeDescriptor(directBinary, aliasedBinary, scope)) {
      return true;
    }
    String explicitSimpleBinary = explicitMethodImportBinary(scope, raw);
    if (explicitSimpleBinary != null
        && directBinary.equals(explicitSimpleBinary)
        && !aliasedBinary.equals(explicitSimpleBinary)) {
      return true;
    }
    if (raw.indexOf('/') >= 0) {
      String explicitHeadBinary = explicitHeadQualifiedMethodBinary(raw, pkg, scope);
      if (explicitHeadBinary != null
          && directBinary.equals(explicitHeadBinary)
          && !aliasedBinary.equals(explicitHeadBinary)
          && isCurrentPackageMethodBinary(aliasedBinary, pkg)) {
        return true;
      }
      // Keep owner-qualified API aliases (for example Outer.Inner) when alias expansion
      // drifts to broad scala function/object fallbacks.
      if (!isSpeculativeCurrentPackageMethodDescriptor(directDescriptor, typeText, pkg)
          && directBinary.indexOf('$') >= 0
          && (aliasedBinary.startsWith("scala/")
              || aliasedBinary.startsWith("java/lang/")
              || "java/lang/Object".equals(aliasedBinary))) {
        return true;
      }
      return false;
    }
    if (!isClassLike(raw)) {
      return false;
    }
    String pkgPrefix = (pkg == null || pkg.isEmpty()) ? "" : pkg.replace('.', '/') + "/";
    if (pkgPrefix.isEmpty() || !directBinary.equals(pkgPrefix + raw)) {
      return false;
    }
    if (aliasedBinary.indexOf('$') < 0) {
      return false;
    }
    String explicit = (scope == null || scope.isEmpty()) ? null : scope.explicit().get(raw);
    if (explicit == null || explicit.isEmpty()) {
      return false;
    }
    String normalizedExplicit = explicit.endsWith("$") ? explicit.substring(0, explicit.length() - 1) : explicit;
    if (normalizedExplicit.equals(directBinary)) {
      return true;
    }
    return false;
  }

  private static @Nullable String explicitMethodImportBinary(
      ScalaTypeMapper.ImportScope scope, @Nullable String raw) {
    if (scope == null || scope.isEmpty() || raw == null || raw.isEmpty()) {
      return null;
    }
    String explicit = scope.explicit().get(raw);
    if (explicit == null || explicit.isEmpty()) {
      return null;
    }
    if (explicit.endsWith("$")) {
      return explicit.substring(0, explicit.length() - 1);
    }
    return explicit;
  }

  private static boolean isCurrentPackageMethodBinary(@Nullable String binary, String pkg) {
    if (binary == null || binary.isEmpty() || pkg == null || pkg.isEmpty()) {
      return false;
    }
    return binary.startsWith(pkg.replace('.', '/') + "/");
  }

  private static boolean shouldPreferDirectSelfTypeDescriptor(
      String directBinary, String aliasedBinary, ScalaTypeMapper.ImportScope scope) {
    if (scope == null || scope.isEmpty()) {
      return false;
    }
    String thisBinary = scope.explicit().get("this/type");
    if (thisBinary == null || thisBinary.isEmpty()) {
      return false;
    }
    if (thisBinary.endsWith("$")) {
      return false;
    }
    return directBinary.equals(thisBinary) && !aliasedBinary.equals(thisBinary);
  }

  private static @Nullable String explicitHeadQualifiedMethodBinary(
      String raw, String pkg, ScalaTypeMapper.ImportScope scope) {
    if (raw == null || raw.isEmpty() || scope == null || scope.isEmpty()) {
      return null;
    }
    int slash = raw.indexOf('/');
    if (slash <= 0 || slash >= raw.length() - 1) {
      return null;
    }
    String head = raw.substring(0, slash);
    String tail = raw.substring(slash + 1);
    String resolvedHead = scope.explicit().get(head);
    if (resolvedHead == null || resolvedHead.isEmpty()) {
      return null;
    }
    String pkgPrefix = (pkg == null || pkg.isEmpty()) ? "" : pkg.replace('.', '/');
    if (!pkgPrefix.isEmpty() && resolvedHead.startsWith(pkgPrefix + "/")) {
      return null;
    }
    if (isClassLike(head)) {
      return resolvedHead + "$" + tail.replace('/', '$');
    }
    return resolvedHead + "/" + tail;
  }

  private static final String NESTED_TYPE_ALIAS_MARKER_PREFIX = "__nested_type_alias__:";

  private static String nestedTypeAliasMarker(@Nullable String ownerBinary, String nestedName) {
    String ownerPart = ownerBinary == null ? "" : ownerBinary;
    return NESTED_TYPE_ALIAS_MARKER_PREFIX + ownerPart + ":" + nestedName;
  }

  private static boolean isNestedTypeAliasMarker(@Nullable String key) {
    return key != null && key.startsWith(NESTED_TYPE_ALIAS_MARKER_PREFIX);
  }

  private static @Nullable String ownerQualifiedNestedMethodBinaryCandidate(
      @Nullable String typeText,
      String pkg,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope) {
    if (aliasScope == null || aliasScope.isEmpty()) {
      return null;
    }
    String raw = stripRootPrefix(rawTypeName(typeText));
    if (raw == null || raw.isEmpty() || raw.indexOf('/') >= 0 || !isClassLike(raw)) {
      return null;
    }
    if (scope != null && !scope.isEmpty()) {
      String explicitRaw = scope.explicit().get(raw);
      if (explicitRaw != null && !explicitRaw.isEmpty()) {
        // If the simple name already resolves explicitly, keep that binding and
        // avoid forcing an owner-qualified nested fallback.
        return null;
      }
    }
    String pkgPrefix = (pkg == null || pkg.isEmpty()) ? "" : pkg.replace('.', '/');
    Set<String> explicitOwners = new LinkedHashSet<>();
    if (scope != null && !scope.isEmpty()) {
      explicitOwners.addAll(scope.explicit().values());
    }
    String best = null;
    int bestScore = Integer.MIN_VALUE;
    boolean ambiguousBest = false;
    for (Map.Entry<String, String> entry : aliasScope.aliases().entrySet()) {
      if (!isNestedTypeAliasMarker(entry.getKey())) {
        continue;
      }
      String binary = normalizeMethodAliasBinary(entry.getValue());
      if (binary == null || binary.isEmpty() || binary.indexOf('$') < 0) {
        continue;
      }
      String binarySimple = nestedSimpleName(binary);
      if (binarySimple == null || binarySimple.isEmpty() || !raw.equals(binarySimple)) {
        continue;
      }
      int score = 0;
      if (!pkgPrefix.isEmpty()) {
        if (!binary.startsWith(pkgPrefix + "/")) {
          continue;
        }
        score += 6;
      }
      for (String owner : explicitOwners) {
        if (owner == null || owner.isEmpty()) {
          continue;
        }
        String ownerBinary = owner.endsWith("$") ? owner.substring(0, owner.length() - 1) : owner;
        if (binary.startsWith(ownerBinary + "$") || binary.startsWith(ownerBinary + "/")) {
          score += 3;
          break;
        }
      }
      if (binary.indexOf('$') >= 0) {
        score += 2;
      }
      if (binary.endsWith("$")) {
        score -= 3;
      }
      if (score > bestScore) {
        bestScore = score;
        best = binary;
        ambiguousBest = false;
      } else if (score == bestScore && best != null && !best.equals(binary)) {
        ambiguousBest = true;
      }
    }
    if (ambiguousBest) {
      return null;
    }
    return best;
  }

  private static @Nullable String nestedSimpleName(@Nullable String binary) {
    if (binary == null || binary.isEmpty()) {
      return null;
    }
    int slash = binary.lastIndexOf('/');
    int dollar = binary.lastIndexOf('$');
    int idx = Math.max(slash, dollar);
    if (idx < 0) {
      return binary;
    }
    if (idx >= binary.length() - 1) {
      return null;
    }
    return binary.substring(idx + 1);
  }

  private static @Nullable String wildcardObjectNestedMethodBinaryCandidate(
      @Nullable String typeText, ScalaTypeMapper.ImportScope scope) {
    if (scope == null || scope.isEmpty()) {
      return null;
    }
    String raw = stripRootPrefix(rawTypeName(typeText));
    if (raw == null || raw.isEmpty() || raw.indexOf('/') >= 0 || !isClassLike(raw)) {
      return null;
    }
    List<String> wildcards = scope.wildcards();
    for (int i = wildcards.size() - 1; i >= 0; i--) {
      String prefix = wildcards.get(i);
      if (prefix == null || !prefix.endsWith("$")) {
        continue;
      }
      String owner = prefix.substring(0, prefix.length() - 1);
      if (owner.isEmpty()) {
        continue;
      }
      return owner + "$" + raw;
    }
    return null;
  }

  private static @Nullable String normalizeTupleTypeForErasure(@Nullable String typeText) {
    if (typeText == null) {
      return null;
    }
    String trimmed = typeText.trim();
    if (trimmed.isEmpty() || trimmed.charAt(0) != '(' || trimmed.charAt(trimmed.length() - 1) != ')') {
      return typeText;
    }
    int depth = 0;
    int arity = 1;
    boolean hasTopLevelComma = false;
    for (int i = 1; i < trimmed.length() - 1; i++) {
      char c = trimmed.charAt(i);
      switch (c) {
        case '(':
        case '[':
        case '{':
          depth++;
          break;
        case ')':
        case ']':
        case '}':
          depth = Math.max(0, depth - 1);
          break;
        case ',':
          if (depth == 0) {
            arity++;
            hasTopLevelComma = true;
          }
          break;
        default:
          break;
      }
    }
    if (!hasTopLevelComma || arity < 1 || arity > 22) {
      return typeText;
    }
    return "scala/Tuple" + arity;
  }

  private static @Nullable String eraseTypeParamArrayMethodType(
      @Nullable String typeText,
      Set<String> typeParams,
      Map<String, String> typeParamErasures) {
    if (typeText == null || typeText.isEmpty()) {
      return typeText;
    }
    String raw = stripRootPrefix(rawTypeName(typeText));
    if (!isArrayTypeName(raw)) {
      return typeText;
    }
    List<String> args = extractTypeArgs(typeText);
    if (args.size() != 1) {
      return typeText;
    }
    String elementRaw = stripRootPrefix(rawTypeName(args.get(0)));
    if (elementRaw == null || elementRaw.isEmpty()) {
      return typeText;
    }
    if (!typeParams.contains(elementRaw) && !typeParamErasures.containsKey(elementRaw)) {
      return typeText;
    }
    String erased = typeParamErasures.get(elementRaw);
    if (erased == null || erased.isEmpty() || erased.equals(elementRaw)) {
      return "java/lang/Object";
    }
    return erased;
  }

  private static boolean isArrayTypeName(@Nullable String raw) {
    return "Array".equals(raw) || "scala/Array".equals(raw);
  }

  private static @Nullable String resolveStableMemberReturnType(
      @Nullable String typeText, String pkg, Map<String, String> stableMemberTypes) {
    if (typeText == null
        || typeText.isEmpty()
        || stableMemberTypes == null
        || stableMemberTypes.isEmpty()) {
      return typeText;
    }
    String current = typeText;
    Set<String> seen = new HashSet<>();
    while (true) {
      String raw = stripRootPrefix(rawTypeName(current));
      if (raw == null || raw.isEmpty() || !seen.add(raw)) {
        return current;
      }
      String resolved = null;
      for (String candidate : stableMemberLookupCandidates(raw)) {
        resolved = stableMemberTypes.get(candidate);
        if (resolved == null || resolved.isEmpty()) {
          String pkgQualified = packageQualifiedStableMemberCandidate(candidate, pkg);
          if (pkgQualified != null && !pkgQualified.isEmpty()) {
            resolved = stableMemberTypes.get(pkgQualified);
          }
        }
        if (resolved != null && !resolved.isEmpty()) {
          break;
        }
      }
      if (resolved == null || resolved.isEmpty() || resolved.equals(current)) {
        return current;
      }
      current = resolved;
    }
  }

  private static @Nullable String packageQualifiedStableMemberCandidate(
      @Nullable String candidate, String pkg) {
    if (candidate == null || candidate.isEmpty() || pkg == null || pkg.isEmpty()) {
      return null;
    }
    int slash = candidate.indexOf('/');
    if (slash <= 0) {
      return null;
    }
    String head = candidate.substring(0, slash);
    if (isRootPackagePrefix(head)) {
      return null;
    }
    String pkgPrefix = pkg.replace('.', '/');
    if (pkgPrefix.isEmpty() || candidate.startsWith(pkgPrefix + "/")) {
      return null;
    }
    return pkgPrefix + "/" + candidate;
  }

  private static Set<String> stableMemberLookupCandidates(String raw) {
    Set<String> candidates = new LinkedHashSet<>();
    addStableMemberCandidate(candidates, raw);
    String simple = parentSimpleName(raw);
    addStableMemberCandidate(candidates, simple);
    addStableMemberCandidate(candidates, singletonTermName(raw));
    if (simple != null && simple.indexOf('$') >= 0) {
      addStableMemberCandidate(candidates, simple.substring(simple.lastIndexOf('$') + 1));
    }
    return candidates;
  }

  private static @Nullable String singletonTermName(@Nullable String raw) {
    if (raw == null || raw.isEmpty()) {
      return null;
    }
    String normalized = raw;
    if (normalized.endsWith("/type")) {
      normalized = normalized.substring(0, normalized.length() - "/type".length());
    }
    if (normalized.endsWith("$type")) {
      normalized = normalized.substring(0, normalized.length() - "$type".length());
    }
    int slash = normalized.lastIndexOf('/');
    int dollar = normalized.lastIndexOf('$');
    int idx = Math.max(slash, dollar);
    if (idx < 0 || idx >= normalized.length() - 1) {
      return null;
    }
    return normalized.substring(idx + 1);
  }

  private static void addStableMemberCandidate(Set<String> candidates, @Nullable String candidate) {
    if (candidate == null || candidate.isEmpty()) {
      return;
    }
    candidates.add(candidate);
    String normalized = candidate;
    if (normalized.endsWith("/type")) {
      normalized = normalized.substring(0, normalized.length() - "/type".length());
      if (!normalized.isEmpty()) {
        candidates.add(normalized);
      }
    }
    if (normalized.endsWith("$type")) {
      normalized = normalized.substring(0, normalized.length() - "$type".length());
      if (!normalized.isEmpty()) {
        candidates.add(normalized);
      }
    }
    if (normalized.endsWith("$")) {
      normalized = normalized.substring(0, normalized.length() - 1);
      if (!normalized.isEmpty()) {
        candidates.add(normalized);
      }
    }
  }

  private static boolean shouldPreferFallbackMethodDescriptor(
      String primary, String fallback, @Nullable String typeText, String pkg) {
    if (primary == null || fallback == null || primary.equals(fallback)) {
      return false;
    }
    if ("Ljava/lang/Object;".equals(primary) && !"Ljava/lang/Object;".equals(fallback)) {
      return true;
    }
    if (isReferenceDescriptor(primary) && isPrimitiveOrStringDescriptor(fallback)) {
      return true;
    }
    if (isSpeculativeCurrentPackageMethodDescriptor(primary, typeText, pkg)
        && !isSpeculativeCurrentPackageMethodDescriptor(fallback, typeText, pkg)) {
      return true;
    }
    if (isModuleClassDescriptor(primary)
        && !isModuleClassDescriptor(fallback)
        && !looksLikeSingletonType(typeText)) {
      return true;
    }
    String primaryBinary = descriptorBinary(primary);
    String fallbackBinary = descriptorBinary(fallback);
    if (primaryBinary == null || fallbackBinary == null) {
      return false;
    }
    if (isCurrentPackageNestedMethodBinary(primaryBinary, pkg)
        && !isCurrentPackageMethodBinary(fallbackBinary, pkg)) {
      return true;
    }
    if (primaryBinary.endsWith("$type") && !fallbackBinary.endsWith("$type")) {
      return true;
    }
    String pkgPrefix =
        (pkg == null || pkg.isEmpty()) ? "" : pkg.replace('.', '/') + "/";
    if (!pkgPrefix.isEmpty()
        && primaryBinary.startsWith(pkgPrefix)
        && primaryBinary.indexOf('$') < 0
        && fallbackBinary.startsWith(pkgPrefix)
        && fallbackBinary.indexOf('$') >= 0) {
      return true;
    }
    return false;
  }

  private static boolean isCurrentPackageNestedMethodBinary(@Nullable String binary, String pkg) {
    if (!isCurrentPackageMethodBinary(binary, pkg)) {
      return false;
    }
    return binary != null && binary.indexOf('$') >= 0;
  }

  private static boolean isModuleClassDescriptor(@Nullable String descriptor) {
    String binary = descriptorBinary(descriptor);
    return binary != null && binary.endsWith("$");
  }

  private static boolean isReferenceDescriptor(@Nullable String descriptor) {
    return descriptor != null && descriptor.length() >= 3 && descriptor.startsWith("L") && descriptor.endsWith(";");
  }

  private static boolean isPrimitiveOrStringDescriptor(@Nullable String descriptor) {
    if (descriptor == null || descriptor.isEmpty()) {
      return false;
    }
    return switch (descriptor) {
      case "Z", "B", "S", "C", "I", "J", "F", "D", "Ljava/lang/String;" -> true;
      default -> false;
    };
  }

  private static boolean looksLikeSingletonType(@Nullable String typeText) {
    if (typeText == null) {
      return false;
    }
    String trimmed = typeText.trim();
    return trimmed.endsWith(".type")
        || trimmed.endsWith("/type")
        || trimmed.endsWith("$type")
        || trimmed.contains(". type");
  }

  private static boolean isSpeculativeCurrentPackageMethodDescriptor(
      @Nullable String descriptor, @Nullable String typeText, String pkg) {
    String binary = descriptorBinary(descriptor);
    if (binary == null || pkg == null || pkg.isEmpty()) {
      return false;
    }
    String raw = stripRootPrefix(rawTypeName(typeText));
    if (raw == null || raw.isEmpty()) {
      return false;
    }
    String pkgPrefix = pkg.replace('.', '/') + "/";
    if (raw.indexOf('/') < 0) {
      return isClassLike(raw)
          && (binary.equals(pkgPrefix + raw)
              || binary.equals(pkgPrefix + raw + "$")
              || binary.equals(pkgPrefix + raw + "$type"));
    }
    String head = raw;
    int slash = raw.indexOf('/');
    if (slash >= 0) {
      head = raw.substring(0, slash);
    }
    if (!isClassLike(head)) {
      return false;
    }
    String dollarCandidate = pkgPrefix + head + "$" + raw.substring(slash + 1).replace('/', '$');
    String slashCandidate = pkgPrefix + raw;
    return binary.equals(dollarCandidate) || binary.equals(slashCandidate);
  }

  private static @Nullable String descriptorBinary(@Nullable String descriptor) {
    if (descriptor == null || descriptor.length() < 3) {
      return null;
    }
    if (!descriptor.startsWith("L") || !descriptor.endsWith(";")) {
      return null;
    }
    return descriptor.substring(1, descriptor.length() - 1);
  }

  private static String normalizeMethodReturnDescriptor(
      String descriptor, @Nullable String typeText) {
    String binary = descriptorBinary(descriptor);
    if (binary == null || binary.isEmpty()) {
      return descriptor;
    }
    if (binary.endsWith("$type")) {
      String objectBinary = binary.substring(0, binary.length() - "type".length());
      return "L" + objectBinary + ";";
    }
    return descriptor;
  }

  private static @Nullable String safeClassSignature(@Nullable String signature) {
    if (signature == null || signature.isEmpty()) {
      return null;
    }
    if (!isValidClassSignature(signature)) {
      return null;
    }
    return signature;
  }

  private static @Nullable String safeMethodSignature(@Nullable String signature) {
    if (signature == null || signature.isEmpty()) {
      return null;
    }
    if (!isValidMethodSignature(signature)) {
      return null;
    }
    return signature;
  }

  private static boolean isValidClassSignature(String signature) {
    try {
      new SigParser(signature).parseClassSig();
      return true;
    } catch (RuntimeException | AssertionError e) {
      return false;
    }
  }

  private static boolean isValidMethodSignature(String signature) {
    try {
      new SigParser(signature).parseMethodSig();
      return true;
    } catch (RuntimeException | AssertionError e) {
      return false;
    }
  }

  private static @Nullable String resolveMethodTypeAlias(
      @Nullable String typeText,
      String pkg,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope) {
    if (typeText == null || typeText.isEmpty()) {
      return typeText;
    }
    String raw = stripRootPrefix(rawTypeName(typeText));
    if (raw == null || raw.isEmpty()) {
      return typeText;
    }
    Set<String> candidates = new LinkedHashSet<>();
    addParentAliasLookupCandidates(candidates, raw);
    String simple = parentSimpleName(raw);
    addParentAliasLookupCandidates(candidates, simple);
    if (scope != null && !scope.isEmpty()) {
      addParentAliasLookupCandidates(candidates, scope.explicit().get(raw));
      if (simple != null && !simple.isEmpty()) {
        addParentAliasLookupCandidates(candidates, scope.explicit().get(simple));
      }
    }
    addParentAliasLookupCandidates(candidates, methodAliasQualifiedCandidate(raw, pkg, scope));
    String resolved = null;
    for (String candidate : candidates) {
      resolved = resolveMethodAliasCandidate(candidate, aliasScope);
      if (resolved != null && !resolved.isEmpty()) {
        break;
      }
    }
    if ((resolved == null || resolved.isEmpty())
        && shouldTryMethodAliasSuffixFallback(raw, scope)) {
      resolved = resolveMethodAliasBySuffix(raw, simple, pkg, scope, aliasScope);
    }
    if (resolved == null || resolved.isEmpty()) {
      String known = knownMethodTypeAlias(raw);
      if (known == null
          && raw.indexOf('/') < 0
          && simple != null
          && !simple.isEmpty()) {
        known = knownMethodTypeAlias(simple);
      }
      if (known != null && !known.isEmpty()) {
        return known;
      }
    }
    return resolved == null || resolved.isEmpty() ? typeText : resolved;
  }

  private static @Nullable String knownMethodTypeAlias(@Nullable String raw) {
    if (raw == null || raw.isEmpty()) {
      return null;
    }
    return switch (raw) {
      case "BigInt", "scala/BigInt", "scala/math/BigInt" -> "scala/math/BigInt";
      case "OutOfMemoryError", "java/lang/OutOfMemoryError" -> "java/lang/OutOfMemoryError";
      case "IllegalArgumentException", "java/lang/IllegalArgumentException" ->
          "java/lang/IllegalArgumentException";
      case "UnsupportedOperationException", "java/lang/UnsupportedOperationException" ->
          "java/lang/UnsupportedOperationException";
      case "InterruptedException", "java/lang/InterruptedException" ->
          "java/lang/InterruptedException";
      case "Function", "scala/Function" -> "scala/Function1";
      default -> null;
    };
  }

  private static @Nullable String methodAliasQualifiedCandidate(
      String raw, String pkg, ScalaTypeMapper.ImportScope scope) {
    return methodAliasQualifiedCandidate(raw, pkg, scope, /* allowPackageFallback= */ true);
  }

  private static @Nullable String methodAliasQualifiedCandidate(
      String raw,
      String pkg,
      ScalaTypeMapper.ImportScope scope,
      boolean allowPackageFallback) {
    if (raw == null || raw.isEmpty()) {
      return null;
    }
    String pkgPrefix = (pkg == null || pkg.isEmpty()) ? "" : pkg.replace('.', '/');
    if (raw.indexOf('/') < 0) {
      if (raw.indexOf('$') >= 0) {
        String qualified =
            methodAliasQualifiedCandidate(
                raw.replace('$', '/'), pkg, scope, allowPackageFallback);
        if (qualified != null && !qualified.isEmpty()) {
          return qualified;
        }
      }
      if (scope != null && !scope.isEmpty()) {
        String explicit = scope.explicit().get(raw);
        if (explicit != null && !explicit.isEmpty()) {
          return explicit;
        }
      }
      if (allowPackageFallback && !pkgPrefix.isEmpty() && isClassLike(raw)) {
        return pkgPrefix + "/" + raw;
      }
      return null;
    }
    int slash = raw.indexOf('/');
    if (slash <= 0 || slash >= raw.length() - 1) {
      return null;
    }
    String head = raw.substring(0, slash);
    String tail = raw.substring(slash + 1);
    String resolvedHead = null;
    if (scope != null && !scope.isEmpty()) {
      resolvedHead = scope.explicit().get(head);
    }
    if (resolvedHead != null && !resolvedHead.isEmpty()) {
      if (isClassLike(head)) {
        return joinMethodAliasQualifiedHead(resolvedHead, tail, /* classLikeHead= */ true);
      }
      return joinMethodAliasQualifiedHead(resolvedHead, tail, /* classLikeHead= */ false);
    }
    if (allowPackageFallback && !pkgPrefix.isEmpty()) {
      if (isClassLike(head)) {
        return joinMethodAliasQualifiedHead(
            pkgPrefix + "/" + head, tail, /* classLikeHead= */ true);
      }
      return pkgPrefix + "/" + raw;
    }
    return null;
  }

  private static String joinMethodAliasQualifiedHead(
      String resolvedHead, String tail, boolean classLikeHead) {
    if (!classLikeHead) {
      String normalizedHead =
          resolvedHead.endsWith("/")
              ? resolvedHead.substring(0, resolvedHead.length() - 1)
              : resolvedHead;
      return normalizedHead + "/" + tail;
    }
    String normalizedHead =
        resolvedHead.endsWith("$")
            ? resolvedHead.substring(0, resolvedHead.length() - 1)
            : resolvedHead;
    return normalizedHead + "$" + tail.replace('/', '$');
  }

  private static @Nullable String resolveMethodAliasCandidate(
      String candidate, ScalaTypeMapper.TypeAliasScope aliasScope) {
    if (candidate == null || candidate.isEmpty() || aliasScope == null || aliasScope.isEmpty()) {
      return null;
    }
    for (String lookup : methodAliasLookupCandidates(candidate)) {
      String resolved = aliasScope.aliases().get(lookup);
      if (resolved != null && !resolved.isEmpty()) {
        return resolved;
      }
    }
    return null;
  }

  private static Set<String> methodAliasLookupCandidates(String candidate) {
    Set<String> out = new LinkedHashSet<>();
    addParentAliasLookupCandidates(out, candidate);
    int slash = candidate.lastIndexOf('/');
    if (slash > 0 && slash < candidate.length() - 1) {
      String owner = candidate.substring(0, slash);
      String name = candidate.substring(slash + 1);
      addParentAliasLookupCandidates(out, owner.replace('/', '$') + "$" + name);
    }
    int dollar = candidate.lastIndexOf('$');
    if (dollar > 0 && dollar < candidate.length() - 1) {
      String owner = candidate.substring(0, dollar);
      String name = candidate.substring(dollar + 1);
      addParentAliasLookupCandidates(out, owner.replace('$', '/') + "/" + name);
    }
    return out;
  }

  private static @Nullable String resolveMethodAliasBySuffix(
      String raw,
      @Nullable String simple,
      String pkg,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope) {
    if (simple == null
        || simple.isEmpty()
        || aliasScope == null
        || aliasScope.isEmpty()) {
      return null;
    }
    List<Set<String>> groups = methodAliasSuffixLookupKeyGroups(raw, simple, pkg, scope);
    for (Set<String> group : groups) {
      boolean[] ambiguous = new boolean[1];
      String resolved = resolveMethodAliasCandidatesAtPrecedence(group, aliasScope, ambiguous);
      if (ambiguous[0]) {
        return null;
      }
      if (resolved != null && !resolved.isEmpty()) {
        return resolved;
      }
    }
    return resolveMethodAliasByAnchoredSimpleScan(raw, simple, pkg, scope, aliasScope);
  }

  private static List<Set<String>> methodAliasSuffixLookupKeyGroups(
      String raw,
      String simple,
      String pkg,
      ScalaTypeMapper.ImportScope scope) {
    List<Set<String>> groups = new ArrayList<>();

    Set<String> explicitHead = methodAliasExplicitHeadLookupKeys(raw, scope);
    if (!explicitHead.isEmpty()) {
      groups.add(explicitHead);
    }

    Set<String> lexicalParent = methodAliasLexicalParentLookupKeys(raw, simple, pkg, scope);
    if (!lexicalParent.isEmpty()) {
      groups.add(lexicalParent);
    }

    Set<String> wildcardOwners = methodAliasWildcardLookupKeys(simple, scope);
    if (!wildcardOwners.isEmpty()) {
      groups.add(wildcardOwners);
    }

    Set<String> packageQualified = methodAliasPackageLookupKeys(raw, simple, pkg);
    if (!packageQualified.isEmpty()) {
      groups.add(packageQualified);
    }

    return groups;
  }

  private static Set<String> methodAliasExplicitHeadLookupKeys(
      String raw, ScalaTypeMapper.ImportScope scope) {
    Set<String> keys = new LinkedHashSet<>();
    if (scope == null || scope.isEmpty()) {
      return keys;
    }
    int slash = raw.indexOf('/');
    if (slash <= 0 || slash >= raw.length() - 1) {
      return keys;
    }
    String head = raw.substring(0, slash);
    String tail = raw.substring(slash + 1);
    String explicitHead = scope.explicit().get(head);
    if (explicitHead == null || explicitHead.isEmpty()) {
      return keys;
    }
    String candidate =
        joinMethodAliasQualifiedHead(explicitHead, tail, /* classLikeHead= */ isClassLike(head));
    addParentAliasLookupCandidates(keys, candidate);
    return keys;
  }

  private static Set<String> methodAliasLexicalParentLookupKeys(
      String raw,
      String simple,
      String pkg,
      ScalaTypeMapper.ImportScope scope) {
    Set<String> keys = new LinkedHashSet<>();
    addParentAliasLookupCandidates(keys, raw);
    addParentAliasLookupCandidates(
        keys,
        methodAliasQualifiedCandidate(
            raw, pkg, scope, /* allowPackageFallback= */ false));
    int slash = raw.lastIndexOf('/');
    if (slash > 0 && slash < raw.length() - 1) {
      String parentRaw = raw.substring(0, slash);
      String parentQualified =
          methodAliasQualifiedCandidate(
              parentRaw, pkg, scope, /* allowPackageFallback= */ false);
      addMethodAliasOwnerCandidates(keys, parentQualified, simple);
      String parentSimple = parentSimpleName(parentRaw);
      if (scope != null && !scope.isEmpty()) {
        if (parentSimple != null && !parentSimple.isEmpty()) {
          addMethodAliasOwnerCandidates(keys, scope.explicit().get(parentSimple), simple);
        }
      }
    }
    return keys;
  }

  private static Set<String> methodAliasWildcardLookupKeys(
      String simple, ScalaTypeMapper.ImportScope scope) {
    Set<String> keys = new LinkedHashSet<>();
    if (simple == null || simple.isEmpty() || scope == null || scope.isEmpty()) {
      return keys;
    }
    for (String owner : scope.wildcards()) {
      addMethodAliasOwnerCandidates(keys, owner, simple);
    }
    return keys;
  }

  private static Set<String> methodAliasPackageLookupKeys(
      String raw, String simple, String pkg) {
    Set<String> keys = new LinkedHashSet<>();
    if (pkg == null || pkg.isEmpty()) {
      return keys;
    }
    addParentAliasLookupCandidates(
        keys,
        methodAliasQualifiedCandidate(
            raw,
            pkg,
            ScalaTypeMapper.ImportScope.empty(),
            /* allowPackageFallback= */ true));
    int slash = raw.lastIndexOf('/');
    if (slash > 0 && slash < raw.length() - 1) {
      String parentRaw = raw.substring(0, slash);
      if (parentRaw.indexOf('/') < 0 && isClassLike(parentRaw)) {
        addMethodAliasOwnerCandidates(keys, pkg.replace('.', '/') + "/" + parentRaw, simple);
      }
    }
    return keys;
  }

  private static void addMethodAliasOwnerCandidates(
      Set<String> out, @Nullable String owner, String simple) {
    if (owner == null || owner.isEmpty() || simple == null || simple.isEmpty()) {
      return;
    }
    addMethodAliasOwnerCandidate(out, owner, simple);
    if (owner.endsWith("$") && owner.length() > 1) {
      addMethodAliasOwnerCandidate(out, owner.substring(0, owner.length() - 1), simple);
    }
    if (owner.indexOf('/') >= 0) {
      addMethodAliasOwnerCandidate(out, owner.replace('/', '$'), simple);
    }
    if (owner.indexOf('$') >= 0) {
      addMethodAliasOwnerCandidate(out, owner.replace('$', '/'), simple);
    }
  }

  private static void addMethodAliasOwnerCandidate(Set<String> out, String owner, String simple) {
    if (owner == null || owner.isEmpty()) {
      return;
    }
    out.add(owner + "/" + simple);
    if (owner.endsWith("$")) {
      out.add(owner + simple);
    } else {
      out.add(owner + "$" + simple);
    }
  }

  private static @Nullable String resolveMethodAliasCandidatesAtPrecedence(
      Iterable<String> keys,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      boolean[] ambiguousOut) {
    ambiguousOut[0] = false;
    String best = null;
    for (String key : keys) {
      if (key == null || key.isEmpty()) {
        continue;
      }
      String resolved = resolveMethodAliasCandidate(key, aliasScope);
      if (resolved == null || resolved.isEmpty()) {
        resolved = resolveMethodAliasByOwnerAnchor(key, aliasScope);
      }
      if (resolved == null || resolved.isEmpty()) {
        continue;
      }
      if (best == null) {
        best = resolved;
      } else if (!best.equals(resolved)) {
        ambiguousOut[0] = true;
        return null;
      }
    }
    return best;
  }

  private static @Nullable String resolveMethodAliasByOwnerAnchor(
      String candidate, ScalaTypeMapper.TypeAliasScope aliasScope) {
    if (candidate == null || candidate.isEmpty() || aliasScope == null || aliasScope.isEmpty()) {
      return null;
    }
    int slash = candidate.lastIndexOf('/');
    int dollar = candidate.lastIndexOf('$');
    int sep = Math.max(slash, dollar);
    if (sep <= 0 || sep >= candidate.length() - 1) {
      return null;
    }
    String owner = candidate.substring(0, sep);
    String simple = candidate.substring(sep + 1);
    String normalizedOwner = owner.replace('$', '/');
    while (normalizedOwner.endsWith("/")) {
      normalizedOwner = normalizedOwner.substring(0, normalizedOwner.length() - 1);
    }
    if (normalizedOwner.isEmpty()) {
      return null;
    }
    String best = null;
    for (Map.Entry<String, String> entry : aliasScope.aliases().entrySet()) {
      String key = entry.getKey();
      if (isNestedTypeAliasMarker(key) || !methodAliasKeyMatchesSimple(key, simple)) {
        continue;
      }
      String normalizedKey = key.replace('$', '/');
      if (!normalizedKey.startsWith(normalizedOwner + "/")) {
        continue;
      }
      String resolved = normalizeMethodAliasBinary(entry.getValue());
      if (resolved == null || resolved.isEmpty()) {
        continue;
      }
      if (best == null) {
        best = resolved;
      } else if (!best.equals(resolved)) {
        return null;
      }
    }
    return best;
  }

  private static @Nullable String resolveMethodAliasByAnchoredSimpleScan(
      String raw,
      String simple,
      String pkg,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope) {
    if (raw == null
        || raw.isEmpty()
        || simple == null
        || simple.isEmpty()
        || aliasScope == null
        || aliasScope.isEmpty()) {
      return null;
    }
    String pkgPrefix = (pkg == null || pkg.isEmpty()) ? "" : pkg.replace('.', '/');
    String enclosingPkgPrefix = "";
    if (!pkgPrefix.isEmpty()) {
      int slash = pkgPrefix.lastIndexOf('/');
      if (slash > 0) {
        enclosingPkgPrefix = pkgPrefix.substring(0, slash);
      }
    }
    Set<String> ownerAnchors = new LinkedHashSet<>();
    if (scope != null && !scope.isEmpty()) {
      ownerAnchors.addAll(scope.explicit().values());
      ownerAnchors.addAll(scope.wildcards());
    }
    String parentSimple = null;
    int slash = raw.lastIndexOf('/');
    if (slash > 0) {
      parentSimple = parentSimpleName(raw.substring(0, slash));
    }
    String best = null;
    for (Map.Entry<String, String> entry : aliasScope.aliases().entrySet()) {
      String key = entry.getKey();
      if (isNestedTypeAliasMarker(key) || !methodAliasKeyMatchesSimple(key, simple)) {
        continue;
      }
      String normalizedKey = key.replace('$', '/');
      if (!pkgPrefix.isEmpty()
          && !normalizedKey.startsWith(pkgPrefix + "/")
          && (enclosingPkgPrefix.isEmpty() || !normalizedKey.startsWith(enclosingPkgPrefix + "/"))) {
        continue;
      }
      boolean anchored = false;
      if (parentSimple != null
          && !parentSimple.isEmpty()
          && normalizedKey.contains("/" + parentSimple + "/")) {
        anchored = true;
      }
      if (!anchored) {
        for (String owner : ownerAnchors) {
          if (methodAliasMatchesOwnerPrefix(normalizedKey, owner)) {
            anchored = true;
            break;
          }
        }
      }
      if (!anchored) {
        continue;
      }
      String resolved = normalizeMethodAliasBinary(entry.getValue());
      if (resolved == null || resolved.isEmpty()) {
        continue;
      }
      if (best == null) {
        best = resolved;
      } else if (!best.equals(resolved)) {
        return null;
      }
    }
    return best;
  }

  private static boolean shouldTryMethodAliasSuffixFallback(
      @Nullable String raw, ScalaTypeMapper.ImportScope scope) {
    if (raw == null || raw.isEmpty()) {
      return false;
    }
    int slash = raw.indexOf('/');
    if (slash <= 0) {
      return false;
    }
    String head = raw.substring(0, slash);
    if (isRootPackagePrefix(head)) {
      return false;
    }
    if (scope != null && !scope.isEmpty()) {
      String explicitHead = scope.explicit().get(head);
      if (explicitHead != null
          && !explicitHead.isEmpty()
          && explicitHead.indexOf('/') >= 0
          && !isClassLike(head)) {
        return false;
      }
    }
    return true;
  }

  private static boolean methodAliasKeyMatchesSimple(String key, String simple) {
    if (key == null || key.isEmpty() || simple == null || simple.isEmpty()) {
      return false;
    }
    return key.endsWith("$" + simple) || key.endsWith("/" + simple);
  }

  private static boolean methodAliasMatchesOwnerPrefix(
      String normalizedKey, @Nullable String owner) {
    if (owner == null || owner.isEmpty()) {
      return false;
    }
    String normalizedOwner = owner;
    while (normalizedOwner.endsWith("$") || normalizedOwner.endsWith("/")) {
      normalizedOwner = normalizedOwner.substring(0, normalizedOwner.length() - 1);
    }
    if (normalizedOwner.isEmpty()) {
      return false;
    }
    normalizedOwner = normalizedOwner.replace('$', '/');
    return normalizedKey.startsWith(normalizedOwner + "/");
  }

  private static @Nullable String normalizeMethodAliasBinary(@Nullable String aliasTarget) {
    if (aliasTarget == null || aliasTarget.isEmpty()) {
      return null;
    }
    String raw = stripRootPrefix(rawTypeName(aliasTarget));
    if (raw != null && !raw.isEmpty()) {
      return raw;
    }
    String trimmed = aliasTarget.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    return stripRootPrefix(trimmed.replace('.', '/'));
  }

  private static Map<String, String> typeParamErasures(ImmutableList<TypeParam> typeParams) {
    if (typeParams == null || typeParams.isEmpty()) {
      return Map.of();
    }
    Map<String, String> erasures = new LinkedHashMap<>();
    for (TypeParam param : typeParams) {
      String bound = param.upperBound();
      if (bound == null || bound.isEmpty()) {
        continue;
      }
      erasures.put(param.name(), bound);
    }
    if (erasures.isEmpty()) {
      return Map.of();
    }
    return Map.copyOf(erasures);
  }

  private static Map<String, String> mergeTypeParamErasures(
      Map<String, String> classTypeParamErasures, Map<String, String> methodTypeParamErasures) {
    boolean classEmpty = classTypeParamErasures == null || classTypeParamErasures.isEmpty();
    boolean methodEmpty = methodTypeParamErasures == null || methodTypeParamErasures.isEmpty();
    if (classEmpty && methodEmpty) {
      return Map.of();
    }
    if (classEmpty) {
      return methodTypeParamErasures;
    }
    if (methodEmpty) {
      return classTypeParamErasures;
    }
    Map<String, String> merged = new LinkedHashMap<>(classTypeParamErasures);
    merged.putAll(methodTypeParamErasures);
    return Map.copyOf(merged);
  }

  private static ImmutableList<String> methodExceptions(
      ImmutableList<String> modifiers,
      String pkg,
      Set<String> typeParams,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      Map<String, String> typeParamErasures) {
    return methodExceptions(
        modifiers, pkg, typeParams, scope, aliasScope, scope, aliasScope, typeParamErasures);
  }

  private static ImmutableList<String> methodExceptions(
      ImmutableList<String> modifiers,
      String pkg,
      Set<String> typeParams,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      ScalaTypeMapper.ImportScope fallbackScope,
      ScalaTypeMapper.TypeAliasScope fallbackAliasScope,
      Map<String, String> typeParamErasures) {
    if (modifiers == null || modifiers.isEmpty()) {
      return ImmutableList.of();
    }
    ImmutableList.Builder<String> exceptions = ImmutableList.builder();
    for (String modifier : modifiers) {
      if (modifier == null || !modifier.startsWith("throws:")) {
        continue;
      }
      String exceptionType = modifier.substring("throws:".length());
      if (exceptionType.isEmpty()) {
        continue;
      }
      String descriptor =
          methodParamDescriptor(
              exceptionType,
              pkg,
              typeParams,
              scope,
              aliasScope,
              fallbackScope,
              fallbackAliasScope,
              typeParamErasures);
      if (descriptor.length() >= 3 && descriptor.startsWith("L") && descriptor.endsWith(";")) {
        exceptions.add(descriptor.substring(1, descriptor.length() - 1));
      }
    }
    return exceptions.build();
  }

  private static List<ClassFile.MethodInfo> accessorsForVal(
      ValDef val,
      String pkg,
      Set<String> typeParams,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      boolean staticContext,
      ClassDef.Kind ownerKind) {
    return accessorsForVal(
        val,
        pkg,
        typeParams,
        scope,
        aliasScope,
        scope,
        aliasScope,
        Map.of(),
        Map.of(),
        staticContext,
        ownerKind,
        new HashSet<>());
  }

  private static List<ClassFile.MethodInfo> accessorsForVal(
      ValDef val,
      String pkg,
      Set<String> typeParams,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      ScalaTypeMapper.ImportScope fallbackScope,
      ScalaTypeMapper.TypeAliasScope fallbackAliasScope,
      Map<String, String> typeParamErasures,
      Map<String, String> stableMemberTypes,
      boolean staticContext,
      ClassDef.Kind ownerKind) {
    return accessorsForVal(
        val,
        pkg,
        typeParams,
        scope,
        aliasScope,
        fallbackScope,
        fallbackAliasScope,
        typeParamErasures,
        stableMemberTypes,
        staticContext,
        ownerKind,
        new HashSet<>());
  }

  private static List<ClassFile.MethodInfo> accessorsForVal(
      ValDef val,
      String pkg,
      Set<String> typeParams,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      ScalaTypeMapper.ImportScope fallbackScope,
      ScalaTypeMapper.TypeAliasScope fallbackAliasScope,
      Map<String, String> typeParamErasures,
      Map<String, String> stableMemberTypes,
      boolean staticContext,
      ClassDef.Kind ownerKind,
      Set<String> reservedMethodKeys) {
    List<ClassFile.MethodInfo> methods = new ArrayList<>();
    String getterDesc =
        "()"
            + methodReturnDescriptor(
                val.type(),
                pkg,
                typeParams,
                scope,
                aliasScope,
                fallbackScope,
                fallbackAliasScope,
                typeParamErasures,
                stableMemberTypes);
    boolean isAbstract = isAbstractVal(val, ownerKind);
    int access = methodAccess(val.modifiers(), staticContext, isAbstract, ownerKind);
    String encodedName = encodeName(val.name());
    String getterSignature =
        safeMethodSignature(
            ScalaSignature.methodSignature(
                ImmutableList.of(),
                ImmutableList.of(),
                val.type(),
                typeParams,
                pkg,
                scope,
                aliasScope));
    addMethodIfAbsent(
        methods,
        reservedMethodKeys,
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

    if (val.modifiers().contains("bean-property")) {
      addMethodIfAbsent(
          methods,
          reservedMethodKeys,
          new ClassFile.MethodInfo(
              access,
              encodeName(beanGetterName(val.name())),
              getterDesc,
              getterSignature,
              /* exceptions= */ ImmutableList.of(),
              /* defaultValue= */ null,
              /* annotations= */ ImmutableList.of(),
              /* parameterAnnotations= */ ImmutableList.of(),
              /* typeAnnotations= */ ImmutableList.of(),
              /* parameters= */ ImmutableList.of()));
    }
    if (val.modifiers().contains("boolean-bean-property") && "()Z".equals(getterDesc)) {
      addMethodIfAbsent(
          methods,
          reservedMethodKeys,
          new ClassFile.MethodInfo(
              access,
              encodeName(booleanBeanGetterName(val.name())),
              getterDesc,
              getterSignature,
              /* exceptions= */ ImmutableList.of(),
              /* defaultValue= */ null,
              /* annotations= */ ImmutableList.of(),
              /* parameterAnnotations= */ ImmutableList.of(),
              /* typeAnnotations= */ ImmutableList.of(),
              /* parameters= */ ImmutableList.of()));
    }

    if (val.isVar()) {
      String setterDesc =
          "("
              + methodParamDescriptor(
                  val.type(),
                  pkg,
                  typeParams,
                  scope,
                  aliasScope,
                  fallbackScope,
                  fallbackAliasScope,
                  typeParamErasures)
              + ")V";
      List<String> setterParamTypes = new ArrayList<>();
      setterParamTypes.add(val.type());
      String setterSignature =
          safeMethodSignature(
              ScalaSignature.methodSignature(
                  ImmutableList.of(),
                  setterParamTypes,
                  "Unit",
                  typeParams,
                  pkg,
                  scope,
                  aliasScope));
      addMethodIfAbsent(
          methods,
          reservedMethodKeys,
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
      if (val.modifiers().contains("bean-property")
          || val.modifiers().contains("boolean-bean-property")) {
        addMethodIfAbsent(
            methods,
            reservedMethodKeys,
            new ClassFile.MethodInfo(
                access,
                encodeName(beanSetterName(val.name())),
                setterDesc,
                setterSignature,
                /* exceptions= */ ImmutableList.of(),
                /* defaultValue= */ null,
                /* annotations= */ ImmutableList.of(),
                /* parameterAnnotations= */ ImmutableList.of(),
                /* typeAnnotations= */ ImmutableList.of(),
                /* parameters= */ ImmutableList.of()));
      }
    }
    return methods;
  }

  private static void addMethodIfAbsent(
      List<ClassFile.MethodInfo> methods,
      Set<String> reservedMethodKeys,
      ClassFile.MethodInfo method) {
    String key = method.name() + method.descriptor();
    if (reservedMethodKeys.contains(key)) {
      return;
    }
    methods.add(method);
    reservedMethodKeys.add(key);
  }

  private static String beanGetterName(String name) {
    return "get" + beanAccessorSuffix(name);
  }

  private static String booleanBeanGetterName(String name) {
    return "is" + beanAccessorSuffix(name);
  }

  private static String beanSetterName(String name) {
    return "set" + beanAccessorSuffix(name);
  }

  private static String beanAccessorSuffix(String name) {
    if (name == null || name.isEmpty()) {
      return "";
    }
    return Character.toUpperCase(name.charAt(0)) + name.substring(1);
  }

  private static ClassFile.MethodInfo buildTraitImplMethod(
      DefDef def,
      ClassDef traitDef,
      String traitBinary,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope) {
    Set<String> typeParams = new HashSet<>(ScalaTypeMapper.typeParamNames(traitDef.typeParams()));
    typeParams.addAll(ScalaTypeMapper.typeParamNames(def.typeParams()));
    Map<String, String> typeParamErasures =
        mergeTypeParamErasures(
            typeParamErasures(traitDef.typeParams()), typeParamErasures(def.typeParams()));

    StringBuilder desc = new StringBuilder();
    desc.append('(');
    desc.append('L').append(traitBinary).append(';');
    List<String> paramTypes = new ArrayList<>();
    paramTypes.add(traitSelfTypeText(traitDef));
    for (JvmMethodParam param : jvmMethodParams(def)) {
      desc.append(
          methodParamDescriptor(
              param.type(),
              traitDef.packageName(),
              typeParams,
              scope,
              aliasScope,
              typeParamErasures));
      paramTypes.add(param.type());
    }
    desc.append(')');
    desc.append(
        methodReturnDescriptor(
            def.returnType(),
            traitDef.packageName(),
            typeParams,
            scope,
            aliasScope,
            typeParamErasures));

    ImmutableList<TypeParam> declared =
        concatTypeParams(traitDef.typeParams(), def.typeParams());
    String signature =
        safeMethodSignature(
            ScalaSignature.methodSignature(
                declared,
                paramTypes,
                def.returnType(),
                typeParams,
                traitDef.packageName(),
                scope,
                aliasScope));

    return new ClassFile.MethodInfo(
        TurbineFlag.ACC_PUBLIC | TurbineFlag.ACC_STATIC,
        encodeName(def.name()),
        desc.toString(),
        signature,
        methodExceptions(
            def.modifiers(),
            traitDef.packageName(),
            typeParams,
            scope,
            aliasScope,
            typeParamErasures),
        /* defaultValue= */ null,
        /* annotations= */ ImmutableList.of(),
        /* parameterAnnotations= */ ImmutableList.of(),
        /* typeAnnotations= */ ImmutableList.of(),
        /* parameters= */ ImmutableList.of());
  }

  private record JvmMethodParam(@Nullable String typeText, boolean varArgs) {
    @Nullable
    String type() {
      return typeText;
    }
  }

  private static ImmutableList<JvmMethodParam> jvmMethodParams(DefDef def) {
    ImmutableList<JvmMethodParam> evidenceParams = syntheticEvidenceParams(def.typeParams());
    ImmutableList<ParamList> paramLists = def.paramLists();
    if (paramLists.isEmpty()) {
      return evidenceParams;
    }
    ImmutableList.Builder<JvmMethodParam> params = ImmutableList.builder();
    boolean insertedEvidence = evidenceParams.isEmpty();
    for (ParamList list : paramLists) {
      if (!insertedEvidence && isImplicitParamList(list)) {
        params.addAll(evidenceParams);
        insertedEvidence = true;
      }
      for (Param param : list.params()) {
        params.add(new JvmMethodParam(param.type(), ScalaTypeMapper.isVarArgsType(param.type())));
      }
    }
    if (!insertedEvidence) {
      params.addAll(evidenceParams);
    }
    return params.build();
  }

  private static boolean isImplicitParamList(ParamList list) {
    for (Param param : list.params()) {
      if (param.modifiers().contains("implicit")) {
        return true;
      }
    }
    return false;
  }

  private static ImmutableList<JvmMethodParam> syntheticEvidenceParams(
      ImmutableList<TypeParam> typeParams) {
    if (typeParams == null || typeParams.isEmpty()) {
      return ImmutableList.of();
    }
    ImmutableList.Builder<JvmMethodParam> evidence = ImmutableList.builder();
    for (TypeParam param : typeParams) {
      for (String viewBound : param.viewBounds()) {
        String typeText = viewBoundEvidenceType(param.name(), viewBound);
        if (typeText != null && !typeText.isEmpty()) {
          evidence.add(new JvmMethodParam(typeText, /* varArgs= */ false));
        }
      }
      for (String contextBound : param.contextBounds()) {
        if (contextBound != null && !contextBound.isEmpty()) {
          evidence.add(new JvmMethodParam(contextBound, /* varArgs= */ false));
        }
      }
    }
    return evidence.build();
  }

  private static @Nullable String viewBoundEvidenceType(
      @Nullable String typeParamName, @Nullable String viewBound) {
    if (viewBound == null || viewBound.isEmpty()) {
      return null;
    }
    if (typeParamName == null || typeParamName.isEmpty()) {
      return viewBound;
    }
    return typeParamName + " => " + viewBound;
  }

  private static List<ClassFile.MethodInfo> buildTraitImplAccessors(
      ValDef val,
      ClassDef traitDef,
      String traitBinary,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope) {
    Set<String> typeParams = ScalaTypeMapper.typeParamNames(traitDef.typeParams());
    Map<String, String> typeParamErasures = typeParamErasures(traitDef.typeParams());
    List<ClassFile.MethodInfo> methods = new ArrayList<>();
    String getterDesc =
        "(L"
            + traitBinary
            + ";)"
            + methodReturnDescriptor(
                val.type(),
                traitDef.packageName(),
                typeParams,
                scope,
                aliasScope,
                typeParamErasures,
                Map.of());
    String getterSignature =
        safeMethodSignature(
            ScalaSignature.methodSignature(
                traitDef.typeParams(),
                ImmutableList.of(traitSelfTypeText(traitDef)),
                val.type(),
                typeParams,
                traitDef.packageName(),
                scope,
                aliasScope));
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
              + methodParamDescriptor(
                  val.type(),
                  traitDef.packageName(),
                  typeParams,
                  scope,
                  aliasScope,
                  typeParamErasures)
              + ")V";
      List<String> paramTypes = new ArrayList<>();
      paramTypes.add(traitSelfTypeText(traitDef));
      paramTypes.add(val.type());
      String setterSignature =
          safeMethodSignature(
              ScalaSignature.methodSignature(
                  traitDef.typeParams(),
                  paramTypes,
                  "Unit",
                  typeParams,
                  traitDef.packageName(),
                  scope,
                  aliasScope));
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
        safeMethodSignature(
            ScalaSignature.methodSignature(
                traitDef.typeParams(),
                paramTypes,
                null,
                typeParams,
                traitDef.packageName(),
                scope,
                aliasScope));
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

  private record ParentRef(ClassDef parent, String parentTypeText) {}

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
    String fullPackage = effectiveTypePackage(cls);
    return binaryName(fullPackage, "package$");
  }

  private static String effectiveTypePackage(ClassDef cls) {
    if (cls == null || !cls.isPackageObject()) {
      return cls == null ? "" : cls.packageName();
    }
    String pkg = cls.packageName();
    return pkg == null || pkg.isEmpty() ? cls.name() : pkg + "." + cls.name();
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

  private static Map<String, String> mergeStableMemberTypes(
      Map<String, String> localStableMemberTypes, Map<String, String> qualifiedStableMemberTypes) {
    boolean localEmpty = localStableMemberTypes == null || localStableMemberTypes.isEmpty();
    boolean qualifiedEmpty = qualifiedStableMemberTypes == null || qualifiedStableMemberTypes.isEmpty();
    if (localEmpty && qualifiedEmpty) {
      return Map.of();
    }
    if (localEmpty) {
      return qualifiedStableMemberTypes;
    }
    if (qualifiedEmpty) {
      return localStableMemberTypes;
    }
    Map<String, String> merged = new LinkedHashMap<>(qualifiedStableMemberTypes);
    merged.putAll(localStableMemberTypes);
    return Map.copyOf(merged);
  }

  private static Map<String, String> stableMemberTypes(ClassDef cls) {
    if (cls == null || cls.members().isEmpty()) {
      return Map.of();
    }
    Map<String, String> stable = new LinkedHashMap<>();
    collectStableMemberTypes(stable, cls, "");
    if (stable.isEmpty()) {
      return Map.of();
    }
    return Map.copyOf(stable);
  }

  private static Map<String, String> qualifiedStableMemberTypes(
      List<ClassDef> defs,
      Map<ClassDef, String> classBinaryNames,
      Map<ClassDef, String> moduleBinaryNames) {
    if (defs == null || defs.isEmpty()) {
      return Map.of();
    }
    Map<String, String> stable = new LinkedHashMap<>();
    for (ClassDef owner : defs) {
      addQualifiedStableMemberTypesForOwner(stable, owner, classBinaryNames, moduleBinaryNames);
    }
    if (stable.isEmpty()) {
      return Map.of();
    }
    return Map.copyOf(stable);
  }

  private static void addQualifiedStableMemberTypesForOwner(
      Map<String, String> out,
      ClassDef owner,
      Map<ClassDef, String> classBinaryNames,
      Map<ClassDef, String> moduleBinaryNames) {
    if (owner == null || out == null) {
      return;
    }
    Map<String, String> localStable = stableMemberTypes(owner);
    if (localStable.isEmpty()) {
      return;
    }
    String ownerClassBinary = classBinaryName(owner, classBinaryNames, moduleBinaryNames);
    String ownerMemberBinary = ownerMemberBinary(owner, classBinaryNames, moduleBinaryNames);
    for (Map.Entry<String, String> entry : localStable.entrySet()) {
      String key = entry.getKey();
      String value = entry.getValue();
      if (key == null || key.isEmpty() || value == null || value.isEmpty()) {
        continue;
      }
      addQualifiedStableMemberCandidate(out, ownerClassBinary, key, value);
      if (ownerMemberBinary != null && !ownerMemberBinary.equals(ownerClassBinary)) {
        addQualifiedStableMemberCandidate(out, ownerMemberBinary, key, value);
      }
    }
  }

  private static void addQualifiedStableMemberCandidate(
      Map<String, String> out, @Nullable String ownerBinary, String key, String value) {
    if (ownerBinary == null || ownerBinary.isEmpty() || key == null || key.isEmpty()) {
      return;
    }
    String normalizedKey = key.replace('$', '/');
    out.putIfAbsent(ownerBinary + "/" + normalizedKey, value);
    out.putIfAbsent(ownerBinary + "$" + normalizedKey.replace('/', '$'), value);
    if (ownerBinary.endsWith("$")) {
      out.putIfAbsent(ownerBinary + normalizedKey, value);
    }
  }

  private static void collectStableMemberTypes(
      Map<String, String> stable, ClassDef owner, String prefix) {
    if (owner == null || owner.members().isEmpty()) {
      return;
    }
    for (Defn defn : owner.members()) {
      if (defn instanceof ValDef val) {
        if (val.isVar()) {
          continue;
        }
        String type = val.type();
        if (type == null || type.isEmpty()) {
          continue;
        }
        stable.putIfAbsent(val.name(), type);
        if (!prefix.isEmpty()) {
          stable.putIfAbsent(prefix + "/" + val.name(), type);
          stable.putIfAbsent(prefix + "$" + val.name(), type);
        }
        continue;
      }
      if (defn instanceof ClassDef nested && nested.kind() == ClassDef.Kind.OBJECT) {
        String nestedPrefix = prefix.isEmpty() ? nested.name() : prefix + "/" + nested.name();
        collectStableMemberTypes(stable, nested, nestedPrefix);
      }
    }
  }

  private static ScalaTypeMapper.TypeAliasScope typeAliasScope(ClassDef cls) {
    ScalaTypeMapper.TypeAliasScope.Builder builder = ScalaTypeMapper.TypeAliasScope.builder();
    for (Defn defn : cls.members()) {
      if (defn instanceof TypeDef type) {
        String target = typeAliasErasureTarget(type);
        if (target == null || target.isEmpty()) {
          continue;
        }
        builder.addAlias(type.name(), target);
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
      if (owner.kind() == ClassDef.Kind.OBJECT || owner.isPackageObject()) {
        continue;
      }
      addQualifiedTypeAliasesForOwner(builder, owner, classBinaryNames, moduleBinaryNames);
    }
    for (ClassDef owner : defs) {
      if (owner.kind() != ClassDef.Kind.OBJECT && !owner.isPackageObject()) {
        continue;
      }
      addQualifiedTypeAliasesForOwner(builder, owner, classBinaryNames, moduleBinaryNames);
    }
    return builder.build();
  }

  private static void addQualifiedTypeAliasesForOwner(
      ScalaTypeMapper.TypeAliasScope.Builder builder,
      ClassDef owner,
      Map<ClassDef, String> classBinaryNames,
      Map<ClassDef, String> moduleBinaryNames) {
    String ownerMemberBinary = ownerMemberBinary(owner, classBinaryNames, moduleBinaryNames);
    String ownerClassBinary = classBinaryName(owner, classBinaryNames, moduleBinaryNames);
    for (Defn defn : owner.members()) {
      if (defn instanceof TypeDef type) {
        String target = typeAliasErasureTarget(type);
        if (target == null || target.isEmpty()) {
          continue;
        }
        addQualifiedTypeAlias(
            builder, ownerMemberBinary, ownerClassBinary, type.name(), target);
      } else if (defn instanceof ClassDef nested) {
        String erased = valueClassErasedType(nested);
        if (erased != null && !erased.isEmpty()) {
          addQualifiedTypeAlias(builder, ownerMemberBinary, ownerClassBinary, nested.name(), erased);
          continue;
        }
        if (nested.kind() == ClassDef.Kind.OBJECT || nested.isPackageObject()) {
          continue;
        }
        String nestedBinary = classBinaryName(nested, classBinaryNames, moduleBinaryNames);
        if (nestedBinary == null || nestedBinary.isEmpty()) {
          continue;
        }
        // Record nested class binaries behind a marker key so they are only used by
        // owner-qualified method descriptor fallback, not general alias expansion.
        builder.addAlias(nestedTypeAliasMarker(ownerClassBinary, nested.name()), nestedBinary);
        if (ownerMemberBinary != null && !ownerMemberBinary.equals(ownerClassBinary)) {
          builder.addAlias(nestedTypeAliasMarker(ownerMemberBinary, nested.name()), nestedBinary);
        }
      }
    }
  }

  private static @Nullable String typeAliasErasureTarget(TypeDef type) {
    if (type == null || !type.typeParams().isEmpty()) {
      return null;
    }
    if (type.rhs() != null && !type.rhs().isEmpty()) {
      return type.rhs();
    }
    if (type.upperBound() != null && !type.upperBound().isEmpty()) {
      return type.upperBound();
    }
    if (type.lowerBound() != null && !type.lowerBound().isEmpty()) {
      return type.lowerBound();
    }
    return null;
  }

  private static @Nullable String valueClassErasedType(ClassDef cls) {
    if (cls == null || cls.kind() != ClassDef.Kind.CLASS) {
      return null;
    }
    if (cls.parents() == null || cls.parents().isEmpty()) {
      return null;
    }
    boolean extendsAnyVal = false;
    for (String parent : cls.parents()) {
      String raw = stripRootPrefix(rawTypeName(parent));
      if (raw == null || raw.isEmpty()) {
        continue;
      }
      if ("AnyVal".equals(raw) || "scala/AnyVal".equals(raw)) {
        extendsAnyVal = true;
        break;
      }
    }
    if (!extendsAnyVal) {
      return null;
    }
    List<Param> params = flattenParams(cls.ctorParams());
    if (params.size() != 1) {
      return null;
    }
    String erased = params.get(0).type();
    if (erased == null || erased.isEmpty()) {
      return null;
    }
    return erased;
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
      // Package object aliases are referenced from package wildcard imports as
      // `pkg/Alias` rather than `pkg/package/Alias`.
      String packageObjectSuffix = "/package$";
      if (ownerMemberBinary.endsWith(packageObjectSuffix)) {
        String packageBinary =
            ownerMemberBinary.substring(0, ownerMemberBinary.length() - packageObjectSuffix.length());
        if (!packageBinary.isEmpty()) {
          builder.addAlias(packageBinary + "/" + aliasName, rhs);
        }
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
        Set<String> packageMembers =
            members.computeIfAbsent(obj.packageName(), ignored -> new HashSet<>());
        packageMembers.add(obj.name());
        continue;
      }
      Set<String> packageMembers =
          members.computeIfAbsent(effectiveTypePackage(obj), ignored -> new HashSet<>());
      for (Defn defn : obj.members()) {
        if (!(defn instanceof TypeDef type)) {
          continue;
        }
        if (type.rhs() == null || type.rhs().isEmpty()) {
          continue;
        }
        if (!type.typeParams().isEmpty()) {
          continue;
        }
        packageMembers.add(type.name());
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

  private static void addRelativePackageMembers(
      ScalaTypeMapper.ImportScope.Builder builder,
      String currentPackage,
      Map<String, Set<String>> packageTypeMembers) {
    if (currentPackage == null || currentPackage.isEmpty() || packageTypeMembers.isEmpty()) {
      return;
    }
    String[] segments = currentPackage.split("\\.");
    if (segments.length == 0) {
      return;
    }
    for (int i = segments.length; i >= 1; i--) {
      String basePackage = String.join(".", Arrays.copyOfRange(segments, 0, i));
      if (basePackage.isEmpty()) {
        continue;
      }
      String basePrefix = basePackage + ".";
      for (Map.Entry<String, Set<String>> entry : packageTypeMembers.entrySet()) {
        String packageName = entry.getKey();
        if (packageName == null || packageName.isEmpty() || !packageName.startsWith(basePrefix)) {
          continue;
        }
        String relative = packageName.substring(basePrefix.length());
        if (relative.isEmpty() || relative.indexOf('.') >= 0) {
          continue;
        }
        Set<String> names = entry.getValue();
        if (names == null || names.isEmpty()) {
          continue;
        }
        String binaryPrefix = packageName.replace('.', '/');
        for (String name : names) {
          if (name == null || name.isEmpty()) {
            continue;
          }
          // Prefer the nearest enclosing package when multiple relative paths exist
          // (for example api.javadsl before javadsl).
          builder.addExplicitIfAbsent(relative + "/" + name, binaryPrefix + "/" + name);
        }
      }
    }
  }

  private static ScalaTypeMapper.ImportScope importScope(
      ScalaTree.CompUnit unit,
      Map<String, Map<String, String>> objectTypeMembers,
      Map<String, Set<String>> packageTypeMembers) {
    ScalaTypeMapper.ImportScope.Builder builder = ScalaTypeMapper.ImportScope.builder();
    addEnclosingPackageMembers(builder, unitPackageName(unit), packageTypeMembers);
    addRelativePackageMembers(builder, unitPackageName(unit), packageTypeMembers);
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

  private static ScalaTypeMapper.ImportScope unitExplicitImportScope(ScalaTree.CompUnit unit) {
    if (unit == null || unit.stats() == null || unit.stats().isEmpty()) {
      return ScalaTypeMapper.ImportScope.empty();
    }
    ScalaTypeMapper.ImportScope.Builder builder = ScalaTypeMapper.ImportScope.builder();
    for (ScalaTree.Stat stat : unit.stats()) {
      if (stat instanceof ImportStat imp) {
        parseImportText(
            builder,
            imp.text(),
            imp.packageName(),
            Map.of(),
            Map.of(),
            ScalaTypeMapper.ImportScope.empty());
      }
    }
    ScalaTypeMapper.ImportScope parsed = builder.build();
    if (parsed.explicit().isEmpty()) {
      return ScalaTypeMapper.ImportScope.empty();
    }
    ScalaTypeMapper.ImportScope.Builder explicitOnly = ScalaTypeMapper.ImportScope.builder();
    parsed.explicit().forEach(explicitOnly::addExplicit);
    return explicitOnly.build();
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
    ScalaTypeMapper.ImportScope scope = ScalaTypeMapper.ImportScope.empty();
    ScalaTypeMapper.ImportScope baseQualifierScope =
        qualifierScope == null ? ScalaTypeMapper.ImportScope.empty() : qualifierScope;
    for (String text : imports) {
      ScalaTypeMapper.ImportScope.Builder builder = ScalaTypeMapper.ImportScope.builder();
      parseImportText(
          builder,
          text,
          currentPackage,
          objectTypeMembers,
          packageTypeMembers,
          mergeImportScopes(baseQualifierScope, scope));
      ScalaTypeMapper.ImportScope parsed = builder.build();
      if (!parsed.isEmpty()) {
        scope = mergeImportScopes(scope, parsed);
      }
    }
    return scope;
  }

  private static ScalaTypeMapper.ImportScope currentPackageClasspathScope(
      ClassDef cls, ParentKindResolver parentKindResolver) {
    if (cls == null || parentKindResolver == null) {
      return ScalaTypeMapper.ImportScope.empty();
    }
    String currentPackage = cls.packageName();
    if (currentPackage == null || currentPackage.isEmpty()) {
      return ScalaTypeMapper.ImportScope.empty();
    }
    Set<String> candidates = currentPackageTypeCandidates(cls);
    if (candidates.isEmpty()) {
      return ScalaTypeMapper.ImportScope.empty();
    }
    String packageBinary = currentPackage.replace('.', '/');
    ScalaTypeMapper.ImportScope.Builder builder = ScalaTypeMapper.ImportScope.builder();
    for (String candidate : candidates) {
      if (candidate == null || candidate.isEmpty()) {
        continue;
      }
      String binaryName = packageBinary + "/" + candidate;
      if (classExists(binaryName, parentKindResolver)) {
        builder.addExplicit(candidate, binaryName);
      }
    }
    return builder.build();
  }

  private static ScalaTypeMapper.ImportScope addClasspathResolvedWildcardTypes(
      ClassDef cls,
      ScalaTypeMapper.ImportScope scope,
      ParentKindResolver parentKindResolver) {
    if (cls == null || scope == null || scope.isEmpty() || parentKindResolver == null) {
      return scope == null ? ScalaTypeMapper.ImportScope.empty() : scope;
    }
    List<String> wildcards = scope.wildcards();
    if (wildcards == null || wildcards.isEmpty()) {
      return scope;
    }
    Set<String> candidates = currentPackageTypeCandidates(cls);
    if (candidates.isEmpty()) {
      return scope;
    }

    ScalaTypeMapper.ImportScope.Builder builder = ScalaTypeMapper.ImportScope.builder();
    scope.explicit().forEach(builder::addExplicit);
    wildcards.forEach(builder::addWildcard);

    Set<String> explicitNames = new HashSet<>(scope.explicit().keySet());
    boolean changed = false;
    for (int i = wildcards.size() - 1; i >= 0; i--) {
      String prefix = wildcards.get(i);
      if (prefix == null || prefix.isEmpty()) {
        continue;
      }
      if (prefix.endsWith("$")) {
        continue;
      }
      if (isLikelyTermWildcardPrefix(prefix)) {
        continue;
      }
      for (String candidate : candidates) {
        if (candidate == null || candidate.isEmpty() || !explicitNames.add(candidate)) {
          continue;
        }
        String binaryName = prefix + "/" + candidate;
        if (classExists(binaryName, parentKindResolver)) {
          builder.addExplicit(candidate, binaryName);
          changed = true;
        } else {
          explicitNames.remove(candidate);
        }
      }
    }
    return changed ? builder.build() : scope;
  }

  private static boolean classExists(String binaryName, ParentKindResolver parentKindResolver) {
    if (binaryName == null || binaryName.isEmpty() || parentKindResolver == null) {
      return false;
    }
    if (parentKindResolver.isInterface(binaryName)) {
      return true;
    }
    String superName = parentKindResolver.superName(binaryName);
    return superName != null && !superName.isEmpty();
  }

  private static Set<String> currentPackageTypeCandidates(ClassDef cls) {
    Set<String> out = new LinkedHashSet<>();
    if (cls == null) {
      return out;
    }
    for (String parent : cls.parents()) {
      addCurrentPackageTypeCandidate(parent, out);
    }
    addTypeParamBounds(cls.typeParams(), out);
    for (Param param : flattenParams(cls.ctorParams())) {
      addCurrentPackageTypeCandidate(param.type(), out);
    }
    for (Defn member : cls.members()) {
      if (member instanceof DefDef def) {
        addCurrentPackageTypeCandidate(def.returnType(), out);
        addTypeParamBounds(def.typeParams(), out);
        for (Param param : flattenParams(def.paramLists())) {
          addCurrentPackageTypeCandidate(param.type(), out);
        }
      } else if (member instanceof ValDef val) {
        addCurrentPackageTypeCandidate(val.type(), out);
      } else if (member instanceof TypeDef type) {
        addCurrentPackageTypeCandidate(type.rhs(), out);
        addTypeParamBounds(type.typeParams(), out);
      }
    }
    return out;
  }

  private static void addTypeParamBounds(ImmutableList<TypeParam> params, Set<String> out) {
    if (params == null || params.isEmpty()) {
      return;
    }
    for (TypeParam param : params) {
      addCurrentPackageTypeCandidate(param.lowerBound(), out);
      addCurrentPackageTypeCandidate(param.upperBound(), out);
      for (String bound : param.viewBounds()) {
        addCurrentPackageTypeCandidate(bound, out);
      }
      for (String bound : param.contextBounds()) {
        addCurrentPackageTypeCandidate(bound, out);
      }
    }
  }

  private static void addCurrentPackageTypeCandidate(@Nullable String typeText, Set<String> out) {
    if (typeText == null || typeText.isEmpty()) {
      return;
    }
    String raw = stripRootPrefix(rawTypeName(typeText));
    if (raw == null || raw.isEmpty() || raw.indexOf('/') >= 0 || !isClassLike(raw)) {
      return;
    }
    out.add(raw);
  }

  private static boolean isLikelyTermWildcardPrefix(String prefix) {
    if (prefix == null || prefix.isEmpty()) {
      return true;
    }
    if (prefix.indexOf('/') >= 0) {
      return false;
    }
    if (isRootPackagePrefix(prefix)) {
      return false;
    }
    return !isClassLike(prefix);
  }

  private static boolean isRootPackagePrefix(String prefix) {
    return switch (prefix) {
      case "java", "javax", "scala", "sun", "com", "org", "kotlin", "dotty" -> true;
      default -> false;
    };
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
          // Prefer class companions over module classes on name collisions.
          builder.addExplicitIfAbsent(cls.name(), binary);
          hasEntries = true;
          continue;
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

  private static ScalaTypeMapper.ImportScope enclosingOwnerScope(
      ClassDef cls,
      Map<ClassDef, ClassDef> owners,
      Map<ClassDef, ScalaTypeMapper.ImportScope> ownerTypeScopes,
      Map<String, Map<String, String>> objectTypeMembers,
      Map<String, Set<String>> packageTypeMembers,
      ScalaTypeMapper.ImportScope qualifierBaseScope) {
    List<ClassDef> chain = new ArrayList<>();
    ClassDef owner = owners.get(cls);
    while (owner != null) {
      chain.add(owner);
      owner = owners.get(owner);
    }
    ScalaTypeMapper.ImportScope scope = ScalaTypeMapper.ImportScope.empty();
    ScalaTypeMapper.ImportScope baseScope =
        qualifierBaseScope == null
            ? ScalaTypeMapper.ImportScope.empty()
            : qualifierBaseScope;
    for (int i = chain.size() - 1; i >= 0; i--) {
      ClassDef enclosing = chain.get(i);
      ScalaTypeMapper.ImportScope ownerScope = ownerTypeScopes.get(enclosing);
      if (ownerScope != null && !ownerScope.isEmpty()) {
        scope = mergeImportScopes(scope, ownerScope);
      }
      ScalaTypeMapper.ImportScope ownerImports =
          importScope(
              enclosing.imports(),
              enclosing.packageName(),
              objectTypeMembers,
              packageTypeMembers,
              mergeImportScopes(baseScope, scope));
      if (!ownerImports.isEmpty()) {
        scope = mergeImportScopes(scope, ownerImports);
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
      members.forEach(builder::addExplicitIfAbsent);
      return;
    }
    // Unknown object wildcard imports are too ambiguous for type mapping.
    // Keep only package wildcards and object members we can resolve explicitly.
    if (wildcard != null && wildcard.endsWith("$")) {
      return;
    }
    if (wildcard != null && !wildcard.isEmpty()) {
      String packageObject = wildcard + "/package$";
      Map<String, String> packageObjectMembers = objectTypeMembers.get(packageObject);
      if (packageObjectMembers != null && !packageObjectMembers.isEmpty()) {
        packageObjectMembers.forEach(builder::addExplicitIfAbsent);
      }
    }
    if (wildcard != null && !wildcard.isEmpty()) {
      String pkgName = wildcard.replace('/', '.');
      Set<String> packageMembers = packageTypeMembers.get(pkgName);
      if (packageMembers != null && !packageMembers.isEmpty()) {
        for (String name : packageMembers) {
          builder.addExplicitIfAbsent(name, wildcard + "/" + name);
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
              builder.addExplicit(
                  alias, importedMemberBinary(qualifier, cleaned, objectTypeMembers));
              i += 2;
              continue;
            }
          }
          builder.addExplicit(cleaned, importedMemberBinary(qualifier, cleaned, objectTypeMembers));
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
    builder.addExplicit(name, importedMemberBinary(qualifier, name, objectTypeMembers));
  }

  private static String importedMemberBinary(
      String qualifier, String member, Map<String, Map<String, String>> objectTypeMembers) {
    String direct = joinQualifier(qualifier, member);
    if (qualifier == null
        || qualifier.isEmpty()
        || qualifier.endsWith("$")
        || objectTypeMembers == null
        || objectTypeMembers.isEmpty()) {
      return direct;
    }
    Map<String, String> packageObjectMembers = objectTypeMembers.get(qualifier + "/package$");
    if (packageObjectMembers == null || packageObjectMembers.isEmpty()) {
      return direct;
    }
    String memberBinary = packageObjectMembers.get(member);
    if (memberBinary == null || memberBinary.isEmpty()) {
      return direct;
    }
    return memberBinary;
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
          safeMethodSignature(
              ScalaSignature.methodSignature(
                  methodTypeParams,
                  paramTypes,
                  param.type(),
                  typeParams,
                  pkg,
                  scope,
                  aliasScope));
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
            methodParamDescriptor(
                params.get(i).type(), pkg, typeParams, scope, aliasScope, /* typeParamErasures= */ Map.of()));
      }
    }
    desc.append(')');
    desc.append(
        methodParamDescriptor(
            params.get(defaultIndex).type(),
            pkg,
            typeParams,
            scope,
            aliasScope,
            /* typeParamErasures= */ Map.of()));
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

  private static String canonicalParentType(
      String parentTypeText,
      String erasedType,
      String currentPackage,
      List<TypeParam> typeParams,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      Map<String, ClassDef> sourceTypesByBinary,
      Map<String, ClassDef> traitsByBinary,
      Map<String, Map<String, String>> packageObjectTypes,
      ParentKindResolver parentKindResolver) {
    String resolved =
        preferKnownLocalParentType(
            parentTypeText,
            erasedType,
            currentPackage,
            scope,
            sourceTypesByBinary,
            traitsByBinary,
            parentKindResolver);
    resolved =
        preferQualifiedParentAliasType(
            parentTypeText,
            resolved,
            currentPackage,
            typeParams,
            scope,
            aliasScope,
            sourceTypesByBinary,
            traitsByBinary,
            parentKindResolver);
    resolved =
        preferLocalObjectParentType(
            parentTypeText,
            resolved,
            currentPackage,
            packageObjectTypes,
            traitsByBinary,
            parentKindResolver);
    return normalizeParentBinary(resolved);
  }

  private static String preferKnownLocalParentType(
      String parentTypeText,
      String erasedType,
      String currentPackage,
      ScalaTypeMapper.ImportScope scope,
      Map<String, ClassDef> sourceTypesByBinary,
      Map<String, ClassDef> traitsByBinary,
      ParentKindResolver parentKindResolver) {
    if (parentTypeText == null
        || parentTypeText.isEmpty()
        || erasedType == null
        || erasedType.isEmpty()) {
      return erasedType;
    }
    String raw = stripRootPrefix(rawTypeName(parentTypeText));
    if (raw == null || raw.isEmpty() || raw.indexOf('/') >= 0 || !isClassLike(raw)) {
      return erasedType;
    }
    String localCandidate = localSimpleParentCandidate(currentPackage, raw);
    boolean speculativeSimpleLocal =
        localCandidate != null && !localCandidate.isEmpty() && localCandidate.equals(erasedType);
    if (!speculativeSimpleLocal
        && isKnownParentBinary(erasedType, sourceTypesByBinary, traitsByBinary, parentKindResolver)) {
      return erasedType;
    }
    String wildcardCandidate =
        wildcardSimpleParentCandidate(
            raw, scope, sourceTypesByBinary, traitsByBinary, parentKindResolver);
    if (wildcardCandidate != null && !wildcardCandidate.isEmpty()) {
      return wildcardCandidate;
    }
    String knownAlias = knownSimpleParentAlias(raw);
    if (knownAlias != null
        && isKnownParentBinary(knownAlias, sourceTypesByBinary, traitsByBinary, parentKindResolver)) {
      return knownAlias;
    }
    String packageObjectCandidate = packageObjectMemberParentCandidate(currentPackage, raw);
    if (packageObjectCandidate != null
        && isKnownParentBinary(
            packageObjectCandidate, sourceTypesByBinary, traitsByBinary, parentKindResolver)) {
      return packageObjectCandidate;
    }
    if (localCandidate != null
        && isKnownParentBinary(localCandidate, sourceTypesByBinary, traitsByBinary, parentKindResolver)) {
      return localCandidate;
    }
    return erasedType;
  }

  private static @Nullable String localSimpleParentCandidate(String currentPackage, String raw) {
    if (currentPackage == null || currentPackage.isEmpty() || raw == null || raw.isEmpty()) {
      return null;
    }
    return currentPackage.replace('.', '/') + "/" + raw;
  }

  private static @Nullable String wildcardSimpleParentCandidate(
      String raw,
      ScalaTypeMapper.ImportScope scope,
      Map<String, ClassDef> sourceTypesByBinary,
      Map<String, ClassDef> traitsByBinary,
      ParentKindResolver parentKindResolver) {
    if (raw == null
        || raw.isEmpty()
        || scope == null
        || scope.isEmpty()
        || !isClassLike(raw)) {
      return null;
    }
    List<String> wildcards = scope.wildcards();
    if (wildcards == null || wildcards.isEmpty()) {
      return null;
    }
    for (int i = wildcards.size() - 1; i >= 0; i--) {
      String prefix = wildcards.get(i);
      if (prefix == null || prefix.isEmpty()) {
        continue;
      }
      if (prefix.endsWith("$") || isLikelyTermWildcardPrefix(prefix)) {
        continue;
      }
      String candidate = prefix + "/" + raw;
      if (isKnownParentBinary(candidate, sourceTypesByBinary, traitsByBinary, parentKindResolver)) {
        return candidate;
      }
    }
    return null;
  }

  private static @Nullable String knownSimpleParentAlias(String raw) {
    if (raw == null || raw.isEmpty()) {
      return null;
    }
    return switch (raw) {
      case "IllegalArgumentException" -> "java/lang/IllegalArgumentException";
      case "UnsupportedOperationException" -> "java/lang/UnsupportedOperationException";
      case "Ordering" -> "scala/math/Ordering";
      case "Ordered" -> "scala/math/Ordered";
      case "Equals" -> "scala/Equals";
      case "PartialFunction" -> "scala/PartialFunction";
      default -> null;
    };
  }

  private static @Nullable String packageObjectMemberParentCandidate(String currentPackage, String raw) {
    if (currentPackage == null || currentPackage.isEmpty() || raw == null || raw.isEmpty()) {
      return null;
    }
    return currentPackage.replace('.', '/') + "/package$" + raw;
  }

  private static String preferQualifiedParentAliasType(
      String parentTypeText,
      String erasedType,
      String currentPackage,
      List<TypeParam> typeParams,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      Map<String, ClassDef> sourceTypesByBinary,
      Map<String, ClassDef> traitsByBinary,
      ParentKindResolver parentKindResolver) {
    if (erasedType == null
        || erasedType.isEmpty()
        || aliasScope == null
        || aliasScope.isEmpty()
        || isKnownParentBinary(erasedType, sourceTypesByBinary, traitsByBinary, parentKindResolver)) {
      return erasedType;
    }
    String aliasRhs = qualifiedParentAliasRhs(parentTypeText, erasedType, scope, aliasScope);
    if (aliasRhs == null || aliasRhs.isEmpty()) {
      return erasedType;
    }
    String resolved =
        eraseType(
            aliasRhs,
            currentPackage == null ? "" : currentPackage,
            typeParams == null ? ImmutableList.of() : typeParams,
            scope == null ? ScalaTypeMapper.ImportScope.empty() : scope,
            aliasScope);
    return resolved == null || resolved.isEmpty() ? erasedType : resolved;
  }

  private static @Nullable String qualifiedParentAliasRhs(
      String parentTypeText,
      String erasedType,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope) {
    if (aliasScope == null || aliasScope.isEmpty()) {
      return null;
    }
    Set<String> candidates = new LinkedHashSet<>();
    addParentAliasLookupCandidates(candidates, erasedType);
    addParentAliasLookupCandidates(candidates, normalizeParentBinary(erasedType));
    String raw = stripRootPrefix(rawTypeName(parentTypeText));
    addParentAliasLookupCandidates(candidates, raw);
    if (raw != null && !raw.isEmpty() && scope != null && !scope.isEmpty()) {
      addParentAliasLookupCandidates(candidates, scope.explicit().get(raw));
      String simpleName = parentSimpleName(raw);
      if (simpleName != null && !simpleName.isEmpty()) {
        addParentAliasLookupCandidates(candidates, scope.explicit().get(simpleName));
      }
    }
    for (String candidate : candidates) {
      String rhs = aliasScope.aliases().get(candidate);
      if (rhs != null && !rhs.isEmpty()) {
        return rhs;
      }
    }
    return null;
  }

  private static void addParentAliasLookupCandidates(Set<String> out, @Nullable String candidate) {
    if (candidate == null || candidate.isEmpty()) {
      return;
    }
    out.add(candidate);
    if (candidate.indexOf('$') >= 0) {
      out.add(candidate.replace('$', '/'));
    }
    if (candidate.indexOf('/') >= 0) {
      out.add(candidate.replace('/', '$'));
    }
  }

  private static @Nullable String parentSimpleName(@Nullable String raw) {
    if (raw == null || raw.isEmpty()) {
      return null;
    }
    int slash = raw.lastIndexOf('/');
    if (slash < 0) {
      return raw;
    }
    if (slash == raw.length() - 1) {
      return null;
    }
    return raw.substring(slash + 1);
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
      case "java/io/Serializable",
          "scala/Equals",
          "scala/Serializable",
          "scala/PartialFunction",
          "scala/math/Ordered",
          "scala/math/Ordering" -> true;
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
            canonicalParentType(
                parent,
                eraseType(
                    parent,
                    traitDef.packageName(),
                    traitDef.typeParams(),
                    traitScope,
                    traitAliases),
                traitDef.packageName(),
                traitDef.typeParams(),
                traitScope,
                traitAliases,
                sourceTypesByBinary,
                traitsByBinary,
                packageObjectTypes,
                parentKindResolver);
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
    if (normalized.isEmpty()) {
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
              canonicalParentType(
                  parent,
                  eraseType(
                      parent, source.packageName(), source.typeParams(), sourceScope, sourceAliases),
                  source.packageName(),
                  source.typeParams(),
                  sourceScope,
                  sourceAliases,
                  sourceTypesByBinary,
                  traitsByBinary,
                  packageObjectTypes,
                  parentKindResolver);
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

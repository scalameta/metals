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
import com.google.common.collect.ImmutableSet;
import com.google.turbine.bytecode.ClassFile;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo;
import com.google.turbine.bytecode.ClassWriter;
import com.google.turbine.model.Const;
import com.google.turbine.model.TurbineFlag;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Lowers Scala outline trees to minimal classfiles. */
public final class ScalaLower {

  public static ImmutableMap<String, byte[]> lower(
      ImmutableList<ScalaTree.CompUnit> units, int majorVersion) {
    List<ClassDef> classDefs = new ArrayList<>();
    List<ClassDef> objectDefs = new ArrayList<>();
    List<ClassDef> packageObjects = new ArrayList<>();
    List<List<ClassDef>> unitClassDefs = new ArrayList<>();
    Map<ClassDef, @Nullable String> outerBinaryNames = new IdentityHashMap<>();
    for (ScalaTree.CompUnit unit : units) {
      List<ClassDef> unitDefs = new ArrayList<>();
      for (ScalaTree.Stat stat : unit.stats()) {
        collectClassDefs(
            stat,
            /* outerBinarySimpleName= */ null,
            outerBinaryNames,
            classDefs,
            objectDefs,
            packageObjects,
            unitDefs);
      }
      unitClassDefs.add(unitDefs);
    }

    Map<String, Set<String>> objectTypeMembers = objectTypeMembers(objectDefs);
    Map<String, ScalaTypeMapper.ImportScope> packageTypeScopes = packageTypeScopes(classDefs);
    Map<String, ScalaTypeMapper.ImportScope> memberTypeScopes =
        memberTypeScopes(classDefs, outerBinaryNames);
    Map<Key, @Nullable String> outerByKey =
        outerByKey(classDefs, objectDefs, packageObjects, outerBinaryNames);
    Map<ClassDef, ScalaTypeMapper.ImportScope> importScopes = new IdentityHashMap<>();
    Map<ClassDef, ScalaTypeMapper.TypeAliasScope> aliasScopes = new IdentityHashMap<>();
    for (int i = 0; i < units.size(); i++) {
      ScalaTree.CompUnit unit = units.get(i);
      ScalaTypeMapper.ImportScope unitScope = importScope(unit, objectTypeMembers);
      for (ClassDef cls : unitClassDefs.get(i)) {
        ScalaTypeMapper.ImportScope localScope =
            importScope(cls.imports(), cls.packageName(), objectTypeMembers);
        ScalaTypeMapper.ImportScope scope = mergeImportScopes(unitScope, localScope);
        ScalaTypeMapper.ImportScope packageScope =
            packageTypeScopes.getOrDefault(cls.packageName(), ScalaTypeMapper.ImportScope.empty());
        scope = mergeImportScopes(scope, packageScope);
        ScalaTypeMapper.ImportScope nestedScope =
            nestedTypeScope(cls, memberTypeScopes, outerByKey);
        scope = mergeImportScopes(scope, nestedScope);
        importScopes.put(cls, scope);
        aliasScopes.put(cls, typeAliasScope(cls));
      }
    }

    Map<Key, ClassDef> objectsByKey = new HashMap<>();
    Map<String, ClassDef> objectsByBinary = new HashMap<>();
    Map<String, ClassDef> traitsByBinary = new HashMap<>();
    Map<String, ClassDef> classesByBinary = new HashMap<>();
    Set<Key> caseCompanions = new HashSet<>();
    Map<Key, Integer> caseCompanionFunctionArity = new HashMap<>();
    for (ClassDef obj : objectDefs) {
      objectsByKey.put(new Key(obj.packageName(), obj.name()), obj);
      objectsByBinary.put(binaryName(obj.packageName(), obj.name()), obj);
    }
    for (ClassDef cls : classDefs) {
      if (cls.kind() == ClassDef.Kind.TRAIT) {
        traitsByBinary.put(binaryName(cls.packageName(), cls.name()), cls);
      } else if (cls.kind() == ClassDef.Kind.CLASS) {
        classesByBinary.put(binaryName(cls.packageName(), cls.name()), cls);
      }
    }

    Map<ClassDef, ScalaTypeMapper.TypeAliasScope> memberAliasScopes =
        mergedAliasScopes(aliasScopes, importScopes, traitsByBinary, classesByBinary, objectsByBinary);

    Map<Key, List<ClassFile.MethodInfo>> companionExtras = new HashMap<>();
    Map<Key, Boolean> hasCtorDefaults = new HashMap<>();
    for (ClassDef cls : classDefs) {
      ScalaTypeMapper.ImportScope scope =
          importScopes.getOrDefault(cls, ScalaTypeMapper.ImportScope.empty());
      ScalaTypeMapper.TypeAliasScope aliases =
          memberAliasScopes.getOrDefault(cls, ScalaTypeMapper.TypeAliasScope.empty());
      List<ClassFile.MethodInfo> ctorDefaults =
          ctorDefaultGetters(cls, scope, aliases, /* staticContext= */ false);
      List<ClassFile.MethodInfo> caseCompanionMethods = ImmutableList.of();
      if (cls.isCase()) {
        int arity = caseCompanionArity(cls);
        Key key = new Key(cls.packageName(), cls.name());
        caseCompanions.add(key);
        if (arity >= 0) {
          caseCompanionFunctionArity.put(key, arity);
        }
        caseCompanionMethods = caseClassCompanionMethods(cls, scope, aliases);
      }
      if (!ctorDefaults.isEmpty()) {
        hasCtorDefaults.put(new Key(cls.packageName(), cls.name()), true);
      }
      List<ClassFile.MethodInfo> extras = concatMethods(ctorDefaults, caseCompanionMethods);
      if (!extras.isEmpty()) {
        Key key = new Key(cls.packageName(), cls.name());
        companionExtras.put(key, extras);
        if (!objectsByKey.containsKey(key)) {
          ClassDef synthetic = synthesizeCompanion(cls);
          objectDefs.add(synthetic);
          objectsByKey.put(key, synthetic);
          objectsByBinary.put(binaryName(synthetic.packageName(), synthetic.name()), synthetic);
          importScopes.put(synthetic, scope);
          aliasScopes.put(synthetic, ScalaTypeMapper.TypeAliasScope.empty());
          memberAliasScopes.put(synthetic, ScalaTypeMapper.TypeAliasScope.empty());
        }
      }
    }

    Set<Key> classKeys = new HashSet<>();
    for (ClassDef cls : classDefs) {
      classKeys.add(new Key(cls.packageName(), cls.name()));
    }

    Map<String, byte[]> out = new LinkedHashMap<>();

    for (ClassDef cls : classDefs) {
      ClassDef companion = objectsByKey.get(new Key(cls.packageName(), cls.name()));
      Key key = new Key(cls.packageName(), cls.name());
      boolean ctorDefaults = hasCtorDefaults.getOrDefault(key, false);
      ScalaTypeMapper.ImportScope scope =
          importScopes.getOrDefault(cls, ScalaTypeMapper.ImportScope.empty());
      ScalaTypeMapper.TypeAliasScope aliases =
          aliasScopes.getOrDefault(cls, ScalaTypeMapper.TypeAliasScope.empty());
      putAllUnique(
          out,
          generateClass(
              cls,
              companion,
              ctorDefaults,
              scope,
              aliases,
              traitsByBinary,
              classesByBinary,
              importScopes,
              aliasScopes,
              memberAliasScopes,
              majorVersion));
      if (cls.kind() == ClassDef.Kind.TRAIT) {
        ScalaTypeMapper.TypeAliasScope memberAliases =
            memberAliasScopes.getOrDefault(cls, aliases);
        putAllUnique(out, generateTraitImplClass(cls, scope, memberAliases, majorVersion));
      }
    }

    for (ClassDef obj : objectDefs) {
      Key key = new Key(obj.packageName(), obj.name());
      List<ClassFile.MethodInfo> extras = companionExtras.getOrDefault(key, ImmutableList.of());
      boolean isCaseCompanion = caseCompanions.contains(key);
      Integer arity = caseCompanionFunctionArity.get(key);
      int functionArity = arity == null ? -1 : arity;
      ScalaTypeMapper.ImportScope scope =
          importScopes.getOrDefault(obj, ScalaTypeMapper.ImportScope.empty());
      ScalaTypeMapper.TypeAliasScope aliases =
          aliasScopes.getOrDefault(obj, ScalaTypeMapper.TypeAliasScope.empty());
      boolean hasCompanionClass = classKeys.contains(key);
      putAllUnique(
          out,
          generateObject(
              obj,
              extras,
              scope,
              aliases,
              traitsByBinary,
              classesByBinary,
              importScopes,
              aliasScopes,
              memberAliasScopes,
              majorVersion,
              isCaseCompanion,
              functionArity,
              hasCompanionClass));
      if (!classKeys.contains(key)) {
        putAllUnique(
            out,
            generateObjectMirror(
                obj,
                scope,
                aliases,
                traitsByBinary,
                classesByBinary,
                importScopes,
                aliasScopes,
                memberAliasScopes,
                majorVersion));
      }
    }

    for (ClassDef pkgObj : packageObjects) {
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
              classesByBinary,
              importScopes,
              aliasScopes,
              memberAliasScopes,
              majorVersion));
    }

    return ImmutableMap.copyOf(out);
  }

  private static void collectClassDefs(
      ScalaTree.Stat stat,
      @Nullable String outerBinarySimpleName,
      Map<ClassDef, @Nullable String> outerBinaryNames,
      List<ClassDef> classDefs,
      List<ClassDef> objectDefs,
      List<ClassDef> packageObjects,
      List<ClassDef> unitDefs) {
    if (!(stat instanceof ClassDef cls)) {
      return;
    }
    String adjustedName = cls.name();
    if (outerBinarySimpleName != null && !outerBinarySimpleName.isEmpty()) {
      String sep = outerBinarySimpleName.endsWith("$") ? "" : "$";
      adjustedName = outerBinarySimpleName + sep + cls.name();
    }
    ClassDef adjusted = renameClassDef(cls, adjustedName);
    outerBinaryNames.put(adjusted, outerBinarySimpleName);
    unitDefs.add(adjusted);
    if (adjusted.isPackageObject()) {
      packageObjects.add(adjusted);
    } else if (adjusted.kind() == ClassDef.Kind.OBJECT) {
      objectDefs.add(adjusted);
    } else {
      classDefs.add(adjusted);
    }
    String childOuter = adjustedName;
    if (cls.kind() == ClassDef.Kind.OBJECT) {
      childOuter = adjustedName + "$";
    }
    for (Defn defn : cls.members()) {
      if (defn instanceof ClassDef nested) {
        collectClassDefs(
            nested, childOuter, outerBinaryNames, classDefs, objectDefs, packageObjects, unitDefs);
      }
    }
  }

  private static ClassDef renameClassDef(ClassDef cls, String adjustedName) {
    if (cls.name().equals(adjustedName)) {
      return cls;
    }
    return new ClassDef(
        cls.packageName(),
        adjustedName,
        cls.kind(),
        cls.isCase(),
        cls.isPackageObject(),
        cls.modifiers(),
        cls.typeParams(),
        cls.ctorParams(),
        cls.parents(),
        cls.imports(),
        cls.members(),
        cls.position());
  }

  private static void putAllUnique(Map<String, byte[]> target, Map<String, byte[]> source) {
    for (Map.Entry<String, byte[]> entry : source.entrySet()) {
      target.putIfAbsent(entry.getKey(), entry.getValue());
    }
  }

  private static ImmutableMap<String, byte[]> generateClass(
      ClassDef cls,
      ClassDef companion,
      boolean hasCtorDefaults,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      Map<String, ClassDef> traitsByBinary,
      Map<String, ClassDef> classesByBinary,
      Map<ClassDef, ScalaTypeMapper.ImportScope> importScopes,
      Map<ClassDef, ScalaTypeMapper.TypeAliasScope> aliasScopes,
      Map<ClassDef, ScalaTypeMapper.TypeAliasScope> memberAliasScopes,
      int majorVersion) {
    String pkg = cls.packageName();
    String name = cls.name();
    String binaryName = binaryName(pkg, name);
    ScalaTypeMapper.TypeAliasScope memberAliases =
        memberAliasScopes.getOrDefault(cls, aliasScope);
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

    ParentInfo parentInfo =
        resolveParents(
            cls,
            scope,
            aliasScope,
            traitsByBinary,
            classesByBinary,
            importScopes,
            aliasScopes);
    String superName = parentInfo.superName();
    List<String> interfaces = new ArrayList<>(parentInfo.interfaces());
    if (cls.isCase()) {
      if (!interfaces.contains("scala/Product")) {
        interfaces.add("scala/Product");
      }
      if (!interfaces.contains("java/io/Serializable")) {
        interfaces.add("java/io/Serializable");
      }
    }

    List<ClassFile.MethodInfo> methods = new ArrayList<>();
    if (!isTrait) {
      methods.add(ctorMethod(cls, scope, memberAliases));
    }
    methods.addAll(memberMethods(cls, scope, memberAliases, /* staticContext= */ false));
    if (isTrait) {
      methods.addAll(traitStaticMethods(cls, scope, memberAliases));
    }
    if (isTrait && hasConcreteTraitMembers(cls)) {
      methods.add(traitInitMethod(cls, scope, memberAliases));
    }
    if (!isTrait) {
      methods.addAll(
          traitForwarders(
              cls,
              traitsByBinary,
              classesByBinary,
              importScopes,
              memberAliasScopes,
              scope,
              aliasScope,
              /* staticContext= */ false,
              /* publicOnly= */ false));
    }
    methods.addAll(
        defaultGettersForDefs(
            cls.members(),
            cls.packageName(),
            ScalaTypeMapper.typeParamNames(cls.typeParams()),
            scope,
            memberAliases,
            /* staticContext= */ false,
            cls.kind()));
    if (hasCtorDefaults) {
      methods.addAll(ctorDefaultGetters(cls, scope, memberAliases, /* staticContext= */ true));
    }
    if (cls.isCase()) {
      methods.addAll(caseClassInstanceMethods(cls, scope, memberAliases));
      methods.addAll(caseClassStaticMethods(cls, scope, memberAliases));
    }
    if (companion != null) {
      ScalaTypeMapper.TypeAliasScope companionAliases =
          aliasScopes.getOrDefault(companion, ScalaTypeMapper.TypeAliasScope.empty());
      ScalaTypeMapper.TypeAliasScope companionMemberAliases =
          memberAliasScopes.getOrDefault(companion, companionAliases);
      methods.addAll(
          forwarders(
              companion,
              scope,
              companionMemberAliases,
              /* isTrait= */ isTrait));
      methods.addAll(
          classForwarders(
              companion,
              ScalaTypeMapper.typeParamNames(cls.typeParams()),
              classesByBinary,
              traitsByBinary,
              importScopes,
              memberAliasScopes,
              scope,
              companionAliases,
              /* staticContext= */ true,
              /* publicOnly= */ false));
    }
    methods = uniqueMethods(methods);
    List<ClassFile.FieldInfo> fields =
        memberFields(cls, scope, memberAliases, /* staticContext= */ false);

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
            /* annotations= */ classAnnotations(cls, pkg, scope, aliasScope),
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
        ImmutableList.of(),
        ScalaTypeMapper.ImportScope.empty(),
        ScalaTypeMapper.TypeAliasScope.empty(),
        ImmutableMap.of(),
        ImmutableMap.of(),
        ImmutableMap.of(),
        ImmutableMap.of(),
        ImmutableMap.of(),
        majorVersion,
        /* isCaseCompanion= */ false,
        /* caseCompanionFunctionArity= */ -1,
        /* hasCompanionClass= */ false);
  }

  private static ImmutableMap<String, byte[]> generateObject(
      ClassDef obj,
      List<ClassFile.MethodInfo> extraMethods,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      Map<String, ClassDef> traitsByBinary,
      Map<String, ClassDef> classesByBinary,
      Map<ClassDef, ScalaTypeMapper.ImportScope> importScopes,
      Map<ClassDef, ScalaTypeMapper.TypeAliasScope> aliasScopes,
      Map<ClassDef, ScalaTypeMapper.TypeAliasScope> memberAliasScopes,
      int majorVersion,
      boolean isCaseCompanion,
      int caseCompanionFunctionArity,
      boolean hasCompanionClass) {
    String pkg = obj.packageName();
    String name = obj.name() + "$";
    String binaryName = binaryName(pkg, name);
    ScalaTypeMapper.TypeAliasScope memberAliases =
        memberAliasScopes.getOrDefault(obj, aliasScope);
    int access = TurbineFlag.ACC_PUBLIC | TurbineFlag.ACC_FINAL | TurbineFlag.ACC_SUPER;
    boolean isCaseObject = obj.isCase();
    boolean isApp = isAppObject(obj, scope, aliasScope);

    List<ClassFile.FieldInfo> fields = new ArrayList<>();
    fields.add(moduleField(binaryName));
    fields.addAll(memberFields(obj, scope, memberAliases, /* staticContext= */ true));
    if (hasCompanionClass) {
      fields.addAll(companionPrivateFields(obj, scope, memberAliases));
    }
    fields = uniqueFields(fields);

    List<ClassFile.MethodInfo> methods = new ArrayList<>();
    methods.add(defaultConstructor(/* isPublic= */ false));
    methods.add(staticInitializer());
    methods.add(writeReplaceMethod());
    methods.addAll(memberMethods(obj, scope, memberAliases, /* staticContext= */ false));
    if (hasCompanionClass) {
      methods.addAll(companionPrivateAccessors(obj, scope, memberAliases));
    }
    methods.addAll(
        traitForwarders(
            obj,
            traitsByBinary,
            classesByBinary,
            importScopes,
            memberAliasScopes,
            scope,
            aliasScope,
            /* staticContext= */ false,
            /* publicOnly= */ false));
    methods.addAll(
        defaultGettersForDefs(
            obj.members(),
            obj.packageName(),
            ScalaTypeMapper.typeParamNames(obj.typeParams()),
            scope,
            memberAliases,
            /* staticContext= */ false,
            ClassDef.Kind.CLASS));
    if (isCaseObject) {
      methods.addAll(caseObjectInstanceMethods(obj, scope, memberAliases));
    }
    if (isApp) {
      methods.addAll(appInstanceMethods());
    }
    methods.addAll(extraMethods);
    methods = uniqueMethods(methods);

    ParentInfo parentInfo =
        resolveParents(
            obj,
            scope,
            aliasScope,
            traitsByBinary,
            classesByBinary,
            importScopes,
            aliasScopes);
    List<String> interfaces = new ArrayList<>(parentInfo.interfaces());
    if (isApp) {
      addInterface(interfaces, "scala/App");
    }
    if (isCaseCompanion) {
      addInterface(interfaces, "scala/deriving/Mirror$Product");
    }
    if (isCaseObject) {
      addInterface(interfaces, "scala/Product");
      addInterface(interfaces, "scala/deriving/Mirror$Singleton");
    }
    addInterface(interfaces, "java/io/Serializable");
    String superName = parentInfo.superName();
    if (caseCompanionFunctionArity >= 0 && caseCompanionFunctionArity <= 22) {
      superName = "scala/runtime/AbstractFunction" + caseCompanionFunctionArity;
    }

    ClassFile classFile =
        new ClassFile(
            access,
            majorVersion,
            binaryName,
            /* signature= */ null,
            superName,
            /* interfaces= */ interfaces,
            /* permits= */ ImmutableList.of(),
            methods,
            fields,
            /* annotations= */ classAnnotations(obj, pkg, scope, aliasScope),
            /* innerClasses= */ ImmutableList.of(),
            /* typeAnnotations= */ ImmutableList.of(),
            /* module= */ null,
            /* nestHost= */ null,
            /* nestMembers= */ ImmutableList.of(),
            /* record= */ null,
            /* transitiveJar= */ null);

    return ImmutableMap.of(binaryName, ClassWriter.writeClass(classFile));
  }

  private static ImmutableMap<String, byte[]> generateObjectMirror(
      ClassDef obj,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      Map<String, ClassDef> traitsByBinary,
      Map<String, ClassDef> classesByBinary,
      Map<ClassDef, ScalaTypeMapper.ImportScope> importScopes,
      Map<ClassDef, ScalaTypeMapper.TypeAliasScope> aliasScopes,
      Map<ClassDef, ScalaTypeMapper.TypeAliasScope> memberAliasScopes,
      int majorVersion) {
    String pkg = obj.packageName();
    String binaryName = binaryName(pkg, obj.name());
    int access = TurbineFlag.ACC_PUBLIC | TurbineFlag.ACC_FINAL | TurbineFlag.ACC_SUPER;
    ScalaTypeMapper.TypeAliasScope memberAliases =
        memberAliasScopes.getOrDefault(obj, aliasScope);
    boolean isApp = isAppObject(obj, scope, aliasScope);

    List<ClassFile.MethodInfo> methods = new ArrayList<>();
    methods.addAll(publicForwarders(obj, scope, memberAliases));
    methods.addAll(
        traitForwarders(
            obj,
            traitsByBinary,
            classesByBinary,
            importScopes,
            memberAliasScopes,
            scope,
            aliasScope,
            /* staticContext= */ true,
            /* publicOnly= */ true));
    methods.addAll(
        classForwarders(
            obj,
            ScalaTypeMapper.typeParamNames(obj.typeParams()),
            classesByBinary,
            traitsByBinary,
            importScopes,
            memberAliasScopes,
            scope,
            aliasScope,
            /* staticContext= */ true,
            /* publicOnly= */ true));
    if (obj.isCase()) {
      methods.addAll(caseObjectStaticMethods(obj, scope, memberAliases));
    }
    if (isApp) {
      methods.addAll(appStaticMethods());
    }
    methods = uniqueMethods(methods);

    ClassFile classFile =
        new ClassFile(
            access,
            majorVersion,
            binaryName,
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

    return ImmutableMap.of(binaryName, ClassWriter.writeClass(classFile));
  }

  private static ImmutableMap<String, byte[]> generatePackageObject(
      ClassDef pkgObj,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      Map<String, ClassDef> traitsByBinary,
      Map<String, ClassDef> classesByBinary,
      Map<ClassDef, ScalaTypeMapper.ImportScope> importScopes,
      Map<ClassDef, ScalaTypeMapper.TypeAliasScope> aliasScopes,
      Map<ClassDef, ScalaTypeMapper.TypeAliasScope> memberAliasScopes,
      int majorVersion) {
    String pkg = pkgObj.packageName();
    String fullPackage = pkg.isEmpty() ? pkgObj.name() : pkg + "." + pkgObj.name();

    ClassDef companion = pkgObj;
    ScalaTypeMapper.TypeAliasScope memberAliases =
        memberAliasScopes.getOrDefault(pkgObj, aliasScope);

    String moduleBinary = binaryName(fullPackage, "package$");
    String companionBinary = binaryName(fullPackage, "package");

    List<ClassFile.FieldInfo> fields = new ArrayList<>();
    fields.add(moduleField(moduleBinary));
    fields.addAll(memberFields(pkgObj, scope, memberAliases, /* staticContext= */ true));
    fields = uniqueFields(fields);

    List<ClassFile.MethodInfo> moduleMethods = new ArrayList<>();
    moduleMethods.add(defaultConstructor(/* isPublic= */ false));
    moduleMethods.add(staticInitializer());
    moduleMethods.add(writeReplaceMethod());
    moduleMethods.addAll(memberMethods(pkgObj, scope, memberAliases, /* staticContext= */ false));
    moduleMethods.addAll(
        traitForwarders(
            pkgObj,
            traitsByBinary,
            classesByBinary,
            importScopes,
            memberAliasScopes,
            scope,
            aliasScope,
            /* staticContext= */ false,
            /* publicOnly= */ false));
    moduleMethods.addAll(
        defaultGettersForDefs(
            pkgObj.members(),
            pkgObj.packageName(),
            ScalaTypeMapper.typeParamNames(pkgObj.typeParams()),
            scope,
            memberAliases,
            /* staticContext= */ false,
            ClassDef.Kind.CLASS));
    moduleMethods = uniqueMethods(moduleMethods);

    ParentInfo parentInfo =
        resolveParents(
            pkgObj,
            scope,
            aliasScope,
            traitsByBinary,
            classesByBinary,
            importScopes,
            aliasScopes);
    List<String> moduleInterfaces = new ArrayList<>(parentInfo.interfaces());
    addInterface(moduleInterfaces, "java/io/Serializable");
    ClassFile moduleClass =
        new ClassFile(
            TurbineFlag.ACC_PUBLIC | TurbineFlag.ACC_FINAL | TurbineFlag.ACC_SUPER,
            majorVersion,
            moduleBinary,
            /* signature= */ null,
            parentInfo.superName(),
            /* interfaces= */ moduleInterfaces,
            /* permits= */ ImmutableList.of(),
            moduleMethods,
            fields,
            /* annotations= */ classAnnotations(pkgObj, pkgObj.packageName(), scope, aliasScope),
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
            memberAliases,
            /* isTrait= */ false));
    companionMethods.addAll(
        traitForwarders(
            companion,
            traitsByBinary,
            classesByBinary,
            importScopes,
            memberAliasScopes,
            scope,
            aliasScope,
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
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      int majorVersion) {
    List<ClassFile.MethodInfo> methods = new ArrayList<>();
    boolean hasConcrete = false;
    for (Defn defn : traitDef.members()) {
      if (defn instanceof DefDef def) {
        if (!isAbstractDef(def, ClassDef.Kind.TRAIT)) {
          methods.add(buildTraitImplMethod(def, traitDef, scope, aliasScope));
          hasConcrete = true;
        }
      } else if (defn instanceof ValDef val) {
        if (!isAbstractVal(val, ClassDef.Kind.TRAIT)) {
          methods.addAll(buildTraitImplAccessors(val, traitDef, scope, aliasScope));
          hasConcrete = true;
        }
      }
    }
    if (!hasConcrete) {
      return ImmutableMap.of();
    }

    methods.add(traitInitMethod(traitDef, scope, aliasScope));
    methods.add(defaultConstructor(/* isPublic= */ false));
    methods = uniqueMethods(methods);

    String pkg = traitDef.packageName();
    String implBinary = binaryName(pkg, traitDef.name() + "$class");
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
          methods.add(
              buildMethod(
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
                cls.kind(),
                /* isCtorParam= */ false));
        if (shouldEmitLazyCompute(val, cls.kind())) {
          methods.add(lazyComputeMethod(val, cls.packageName(), typeParams, scope, aliasScope));
        }
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
                  param.hasDefault(),
                  cls.position());
          methods.addAll(
              accessorsForVal(
                  val,
                  cls.packageName(),
                  typeParams,
                  scope,
                  aliasScope,
                  staticContext,
                  cls.kind(),
                  /* isCtorParam= */ true));
        }
      }
    }
    methods.addAll(implicitClassConversionMethods(cls, scope, aliasScope, staticContext));
    return methods;
  }

  private static List<ClassFile.MethodInfo> implicitClassConversionMethods(
      ClassDef owner,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      boolean staticContext) {
    List<ClassFile.MethodInfo> methods = new ArrayList<>();
    Set<String> classTypeParams = ScalaTypeMapper.typeParamNames(owner.typeParams());
    for (Defn defn : owner.members()) {
      if (!(defn instanceof ClassDef nested)) {
        continue;
      }
      if (nested.kind() != ClassDef.Kind.CLASS) {
        continue;
      }
      if (!nested.modifiers().contains("implicit")) {
        continue;
      }
      methods.add(implicitClassConversionMethod(owner, nested, classTypeParams, scope, aliasScope, staticContext));
    }
    return methods;
  }

  private static ClassFile.MethodInfo implicitClassConversionMethod(
      ClassDef owner,
      ClassDef nested,
      Set<String> classTypeParams,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      boolean staticContext) {
    Set<String> typeParams = new HashSet<>(classTypeParams);
    typeParams.addAll(ScalaTypeMapper.typeParamNames(nested.typeParams()));
    StringBuilder desc = new StringBuilder();
    desc.append('(');
    for (ParamList list : nested.ctorParams()) {
      for (Param param : list.params()) {
        desc.append(
            ScalaTypeMapper.descriptorForParam(param.type(), owner.packageName(), typeParams, scope, aliasScope));
      }
    }
    desc.append(')').append(implicitClassReturnDescriptor(owner, nested));

    int access = TurbineFlag.ACC_PUBLIC;
    if (staticContext) {
      access |= TurbineFlag.ACC_STATIC;
    } else if (owner.kind() != ClassDef.Kind.TRAIT) {
      access |= TurbineFlag.ACC_FINAL;
    }
    return new ClassFile.MethodInfo(
        access,
        encodeName(nested.name()),
        desc.toString(),
        /* signature= */ null,
        /* exceptions= */ ImmutableList.of(),
        /* defaultValue= */ null,
        /* annotations= */ ImmutableList.of(),
        /* parameterAnnotations= */ ImmutableList.of(),
        /* typeAnnotations= */ ImmutableList.of(),
        /* parameters= */ ImmutableList.of());
  }

  private static List<ClassFile.MethodInfo> implicitClassStaticMethods(
      ClassDef traitDef,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope) {
    List<ClassFile.MethodInfo> methods = new ArrayList<>();
    Set<String> classTypeParams = ScalaTypeMapper.typeParamNames(traitDef.typeParams());
    for (Defn defn : traitDef.members()) {
      if (!(defn instanceof ClassDef nested)) {
        continue;
      }
      if (nested.kind() != ClassDef.Kind.CLASS) {
        continue;
      }
      if (!nested.modifiers().contains("implicit")) {
        continue;
      }
      methods.add(implicitClassStaticMethod(traitDef, nested, classTypeParams, scope, aliasScope));
    }
    return methods;
  }

  private static ClassFile.MethodInfo implicitClassStaticMethod(
      ClassDef traitDef,
      ClassDef nested,
      Set<String> classTypeParams,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope) {
    Set<String> typeParams = new HashSet<>(classTypeParams);
    typeParams.addAll(ScalaTypeMapper.typeParamNames(nested.typeParams()));
    StringBuilder desc = new StringBuilder();
    desc.append('(');
    desc.append('L').append(binaryName(traitDef.packageName(), traitDef.name())).append(';');
    for (ParamList list : nested.ctorParams()) {
      for (Param param : list.params()) {
        desc.append(
            ScalaTypeMapper.descriptorForParam(param.type(), traitDef.packageName(), typeParams, scope, aliasScope));
      }
    }
    desc.append(')').append(implicitClassReturnDescriptor(traitDef, nested));
    return new ClassFile.MethodInfo(
        TurbineFlag.ACC_PUBLIC | TurbineFlag.ACC_STATIC,
        encodeName(nested.name()) + "$",
        desc.toString(),
        /* signature= */ null,
        /* exceptions= */ ImmutableList.of(),
        /* defaultValue= */ null,
        /* annotations= */ ImmutableList.of(),
        /* parameterAnnotations= */ ImmutableList.of(),
        /* typeAnnotations= */ ImmutableList.of(),
        /* parameters= */ ImmutableList.of());
  }

  private static String implicitClassReturnDescriptor(ClassDef owner, ClassDef nested) {
    String nestedBinary = binaryName(owner.packageName(), owner.name() + "$" + nested.name());
    return "L" + nestedBinary + ";";
  }

  private static List<ClassFile.FieldInfo> memberFields(
      ClassDef cls,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      boolean staticContext) {
    if (cls.kind() == ClassDef.Kind.TRAIT) {
      return ImmutableList.of();
    }
    List<ClassFile.FieldInfo> fields = new ArrayList<>();
    int lazyCount = 0;
    Set<String> typeParams = ScalaTypeMapper.typeParamNames(cls.typeParams());
    for (Defn defn : cls.members()) {
      if (defn instanceof ValDef val) {
        if (val.hasDefault()) {
          if (val.modifiers().contains("lazy")) {
            lazyCount++;
          }
          fields.add(fieldForVal(val, cls.packageName(), typeParams, scope, aliasScope, staticContext));
        }
      }
    }
    if (lazyCount > 0) {
      fields.addAll(lazyBitmapFields(lazyCount, staticContext));
    }
    for (ParamList list : cls.ctorParams()) {
      for (Param param : list.params()) {
        if (param.modifiers().contains("val") || param.modifiers().contains("var") || cls.isCase()) {
          fields.add(
              fieldForParam(param, cls.packageName(), typeParams, scope, aliasScope, staticContext));
        }
      }
    }
    return uniqueFields(fields);
  }

  private static List<ClassFile.FieldInfo> companionPrivateFields(
      ClassDef obj,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope) {
    List<ClassFile.FieldInfo> fields = new ArrayList<>();
    Set<String> typeParams = ScalaTypeMapper.typeParamNames(obj.typeParams());
    for (Defn defn : obj.members()) {
      if (defn instanceof ValDef val) {
        if (!val.hasDefault() || !isPrivateMember(val.modifiers())) {
          continue;
        }
        String mangled = companionPrivateName(obj, val.name());
        ValDef adjusted =
            new ValDef(
                obj.packageName(),
                mangled,
                val.isVar(),
                val.modifiers(),
                val.type(),
                val.hasExplicitType(),
                val.hasDefault(),
                val.position());
        fields.add(
            fieldForVal(
                adjusted,
                obj.packageName(),
                typeParams,
                scope,
                aliasScope,
                /* staticContext= */ true));
      }
    }
    return fields;
  }

  private static List<ClassFile.MethodInfo> companionPrivateAccessors(
      ClassDef obj,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope) {
    List<ClassFile.MethodInfo> methods = new ArrayList<>();
    Set<String> typeParams = ScalaTypeMapper.typeParamNames(obj.typeParams());
    for (Defn defn : obj.members()) {
      if (defn instanceof DefDef def) {
        if (isAbstractDef(def, ClassDef.Kind.CLASS) || !isPrivateMember(def.modifiers())) {
          continue;
        }
        String mangled = companionPrivateName(obj, def.name());
        DefDef adjusted =
            new DefDef(
                def.packageName(),
                mangled,
                publicizeModifiers(def.modifiers()),
                def.typeParams(),
                def.paramLists(),
                def.returnType(),
                def.position());
        methods.add(
            buildMethod(
                adjusted,
                obj.packageName(),
                typeParams,
                scope,
                aliasScope,
                /* staticContext= */ false,
                ClassDef.Kind.CLASS));
      } else if (defn instanceof ValDef val) {
        if (!val.hasDefault() || !isPrivateMember(val.modifiers())) {
          continue;
        }
        String mangled = companionPrivateName(obj, val.name());
        ValDef adjusted =
            new ValDef(
                obj.packageName(),
                mangled,
                val.isVar(),
                publicizeModifiers(val.modifiers()),
                val.type(),
                val.hasExplicitType(),
                val.hasDefault(),
                val.position());
        methods.addAll(
            accessorsForVal(
                adjusted,
                obj.packageName(),
                typeParams,
                scope,
                aliasScope,
                /* staticContext= */ false,
                ClassDef.Kind.CLASS,
                /* isCtorParam= */ false));
      }
    }
    return methods;
  }

  private static boolean isPrivateMember(ImmutableList<String> modifiers) {
    for (String modifier : modifiers) {
      if ("private".equals(modifier) || modifier.startsWith("private[")) {
        return true;
      }
    }
    return false;
  }

  private static ImmutableList<String> publicizeModifiers(ImmutableList<String> modifiers) {
    ImmutableList.Builder<String> out = ImmutableList.builder();
    for (String modifier : modifiers) {
      if (isAccessModifier(modifier)) {
        continue;
      }
      out.add(modifier);
    }
    return out.build();
  }

  private static boolean isAccessModifier(String modifier) {
    return "private".equals(modifier)
        || "protected".equals(modifier)
        || modifier.startsWith("private[")
        || modifier.startsWith("protected[");
  }

  private static ClassFile.FieldInfo fieldForVal(
      ValDef val,
      String pkg,
      Set<String> typeParams,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      boolean staticContext) {
    boolean isLazy = val.modifiers().contains("lazy");
    int access = fieldAccess(val.isVar(), isLazy, staticContext);
    String name = encodeName(val.name());
    String desc = ScalaTypeMapper.descriptorForParam(val.type(), pkg, typeParams, scope, aliasScope);
    List<AnnotationInfo> annotations =
        annotationsFromModifiers(val.modifiers(), pkg, scope, aliasScope);
    return new ClassFile.FieldInfo(
        access,
        name,
        desc,
        /* signature= */ null,
        /* value= */ null,
        /* annotations= */ annotations,
        /* typeAnnotations= */ ImmutableList.of());
  }

  private static ClassFile.FieldInfo fieldForParam(
      Param param,
      String pkg,
      Set<String> typeParams,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      boolean staticContext) {
    boolean isVar = param.modifiers().contains("var");
    int access = fieldAccess(isVar, /* isLazy= */ false, staticContext);
    String name = encodeName(param.name());
    String desc = ScalaTypeMapper.descriptorForParam(param.type(), pkg, typeParams, scope, aliasScope);
    List<AnnotationInfo> annotations =
        annotationsFromModifiers(param.modifiers(), pkg, scope, aliasScope);
    return new ClassFile.FieldInfo(
        access,
        name,
        desc,
        /* signature= */ null,
        /* value= */ null,
        /* annotations= */ annotations,
        /* typeAnnotations= */ ImmutableList.of());
  }

  private static int fieldAccess(boolean isVar, boolean isLazy, boolean staticContext) {
    int access = TurbineFlag.ACC_PRIVATE;
    if (staticContext) {
      access |= TurbineFlag.ACC_STATIC;
    }
    if (!isVar && !isLazy) {
      access |= TurbineFlag.ACC_FINAL;
    }
    return access;
  }

  private static List<ClassFile.FieldInfo> lazyBitmapFields(int lazyCount, boolean staticContext) {
    if (lazyCount <= 0) {
      return ImmutableList.of();
    }
    int bits;
    String desc;
    if (lazyCount == 1) {
      bits = 1;
      desc = "Z";
    } else if (lazyCount <= 8) {
      bits = 8;
      desc = "B";
    } else if (lazyCount <= 16) {
      bits = 16;
      desc = "S";
    } else if (lazyCount <= 32) {
      bits = 32;
      desc = "I";
    } else {
      bits = 64;
      desc = "J";
    }
    int fieldCount = (lazyCount + bits - 1) / bits;
    List<ClassFile.FieldInfo> fields = new ArrayList<>();
    for (int i = 0; i < fieldCount; i++) {
      int access = TurbineFlag.ACC_PRIVATE | TurbineFlag.ACC_VOLATILE;
      if (staticContext) {
        access |= TurbineFlag.ACC_STATIC;
      }
      fields.add(
          new ClassFile.FieldInfo(
              access,
              "bitmap$" + i,
              desc,
              /* signature= */ null,
              /* value= */ null,
              /* annotations= */ ImmutableList.of(),
              /* typeAnnotations= */ ImmutableList.of()));
    }
    return fields;
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
        methods.add(
            buildMethod(
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
                ownerKind,
                /* isCtorParam= */ false));
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
    methods.addAll(implicitClassConversionMethods(obj, scope, aliasScope, /* staticContext= */ true));
    return methods;
  }

  private static List<ClassFile.MethodInfo> traitForwarders(
      ClassDef target,
      Map<String, ClassDef> traitsByBinary,
      Map<String, ClassDef> classesByBinary,
      Map<ClassDef, ScalaTypeMapper.ImportScope> importScopes,
      Map<ClassDef, ScalaTypeMapper.TypeAliasScope> memberAliasScopes,
      ScalaTypeMapper.ImportScope targetScope,
      ScalaTypeMapper.TypeAliasScope targetAliases,
      boolean staticContext,
      boolean publicOnly) {
    Set<String> classTypeParams = ScalaTypeMapper.typeParamNames(target.typeParams());
    return traitForwardersFromParents(
        target.parents(),
        target.packageName(),
        target.typeParams(),
        classTypeParams,
        traitsByBinary,
        classesByBinary,
        importScopes,
        memberAliasScopes,
        targetScope,
        targetAliases,
        staticContext,
        publicOnly);
  }

  private static List<ClassFile.MethodInfo> traitForwardersFromParents(
      Iterable<String> parentTypeTexts,
      String targetPkg,
      List<TypeParam> targetTypeParams,
      Set<String> classTypeParams,
      Map<String, ClassDef> traitsByBinary,
      Map<String, ClassDef> classesByBinary,
      Map<ClassDef, ScalaTypeMapper.ImportScope> importScopes,
      Map<ClassDef, ScalaTypeMapper.TypeAliasScope> memberAliasScopes,
      ScalaTypeMapper.ImportScope targetScope,
      ScalaTypeMapper.TypeAliasScope targetAliases,
      boolean staticContext,
      boolean publicOnly) {
    List<ClassFile.MethodInfo> methods = new ArrayList<>();
    if (traitsByBinary.isEmpty()) {
      return methods;
    }
    Deque<TraitRef> pending = new ArrayDeque<>();
    for (String parent : parentTypeTexts) {
      String binary =
          resolveParentBinary(
              parent,
              targetPkg,
              targetTypeParams,
              targetScope,
              targetAliases,
              traitsByBinary,
              classesByBinary);
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
          memberAliasScopes.getOrDefault(current.trait(), ScalaTypeMapper.TypeAliasScope.empty());
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
          methods.add(
              buildMethod(
                  adjusted,
                  current.trait().packageName(),
                  classTypeParams,
                  traitScope,
                  traitAliases,
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
                  traitScope,
                  traitAliases,
                  staticContext,
                  ClassDef.Kind.CLASS,
                  /* isCtorParam= */ false));
        }
      }
      methods.addAll(
          implicitClassConversionMethods(current.trait(), traitScope, traitAliases, staticContext));
      if (current.trait().parents().isEmpty()) {
        continue;
      }
      for (String parent : current.trait().parents()) {
        String substitutedParent = substituteType(parent, substitutions);
        String parentBinary =
            resolveParentBinary(
                substitutedParent,
                current.trait().packageName(),
                current.trait().typeParams(),
                traitScope,
                traitAliases,
                traitsByBinary,
                classesByBinary);
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

  private static List<ClassFile.MethodInfo> classForwarders(
      ClassDef target,
      Set<String> classTypeParams,
      Map<String, ClassDef> classesByBinary,
      Map<String, ClassDef> traitsByBinary,
      Map<ClassDef, ScalaTypeMapper.ImportScope> importScopes,
      Map<ClassDef, ScalaTypeMapper.TypeAliasScope> memberAliasScopes,
      ScalaTypeMapper.ImportScope targetScope,
      ScalaTypeMapper.TypeAliasScope targetAliases,
      boolean staticContext,
      boolean publicOnly) {
    List<ClassFile.MethodInfo> methods = new ArrayList<>();
    if (classesByBinary.isEmpty() || target.parents().isEmpty()) {
      return methods;
    }
    Deque<ClassParentRef> pending = new ArrayDeque<>();
    for (String parent : target.parents()) {
      String binary =
          resolveParentBinary(
              parent,
              target.packageName(),
              target.typeParams(),
              targetScope,
              targetAliases,
              traitsByBinary,
              classesByBinary);
      ClassDef parentClass = classesByBinary.get(binary);
      if (parentClass != null) {
        pending.addLast(new ClassParentRef(parentClass, parent));
      }
    }
    Set<String> seen = new HashSet<>();
    while (!pending.isEmpty()) {
      ClassParentRef current = pending.removeFirst();
      String currentBinary = binaryName(current.parent().packageName(), current.parent().name());
      String currentKey = currentBinary + "::" + current.parentTypeText();
      if (!seen.add(currentKey)) {
        continue;
      }
      ScalaTypeMapper.ImportScope parentScope =
          importScopes.getOrDefault(current.parent(), ScalaTypeMapper.ImportScope.empty());
      ScalaTypeMapper.TypeAliasScope parentAliases =
          memberAliasScopes.getOrDefault(current.parent(), ScalaTypeMapper.TypeAliasScope.empty());
      Map<String, String> substitutions =
          traitTypeSubstitutions(current.parent(), current.parentTypeText());
      methods.addAll(
          classMemberForwarders(
              current.parent(),
              substitutions,
              classTypeParams,
              parentScope,
              parentAliases,
              staticContext));
      if (!current.parent().parents().isEmpty()) {
        List<String> substitutedParents = new ArrayList<>();
        for (String parent : current.parent().parents()) {
          substitutedParents.add(substituteType(parent, substitutions));
        }
        methods.addAll(
            traitForwardersFromParents(
                substitutedParents,
                current.parent().packageName(),
                current.parent().typeParams(),
            classTypeParams,
            traitsByBinary,
            classesByBinary,
            importScopes,
            memberAliasScopes,
            parentScope,
            parentAliases,
            staticContext,
            publicOnly));
        for (String parent : substitutedParents) {
          String parentBinary =
              resolveParentBinary(
                  parent,
                  current.parent().packageName(),
                  current.parent().typeParams(),
                  parentScope,
                  parentAliases,
                  traitsByBinary,
                  classesByBinary);
          ClassDef parentClass = classesByBinary.get(parentBinary);
          if (parentClass != null) {
            pending.addLast(new ClassParentRef(parentClass, parent));
          }
        }
      }
    }
    methods = uniqueMethods(methods);
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

  private static List<ClassFile.MethodInfo> classMemberForwarders(
      ClassDef cls,
      Map<String, String> substitutions,
      Set<String> classTypeParams,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      boolean staticContext) {
    List<ClassFile.MethodInfo> methods = new ArrayList<>();
    for (Defn defn : cls.members()) {
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
        methods.add(
            buildMethod(
                adjusted,
                cls.packageName(),
                classTypeParams,
                scope,
                aliasScope,
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
                cls.packageName(),
                classTypeParams,
                scope,
                aliasScope,
                staticContext,
                ClassDef.Kind.CLASS,
                /* isCtorParam= */ false));
      }
    }
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
                  param.hasDefault(),
                  cls.position());
          ValDef adjusted = substituteVal(val, substitutions);
          methods.addAll(
              accessorsForVal(
                  adjusted,
                  cls.packageName(),
                  classTypeParams,
                  scope,
                  aliasScope,
                  staticContext,
                  ClassDef.Kind.CLASS,
                  /* isCtorParam= */ true));
        }
      }
    }
    methods.addAll(implicitClassConversionMethods(cls, scope, aliasScope, staticContext));
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

  private static ClassFile.MethodInfo staticInitializer() {
    return new ClassFile.MethodInfo(
        TurbineFlag.ACC_PUBLIC | TurbineFlag.ACC_STATIC,
        "<clinit>",
        "()V",
        /* signature= */ null,
        /* exceptions= */ ImmutableList.of(),
        /* defaultValue= */ null,
        /* annotations= */ ImmutableList.of(),
        /* parameterAnnotations= */ ImmutableList.of(),
        /* typeAnnotations= */ ImmutableList.of(),
        /* parameters= */ ImmutableList.of());
  }

  private static ClassFile.MethodInfo writeReplaceMethod() {
    return new ClassFile.MethodInfo(
        TurbineFlag.ACC_PRIVATE,
        "writeReplace",
        "()Ljava/lang/Object;",
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
    return isAbstractVal(val, ownerKind, /* isCtorParam= */ false);
  }

  private static boolean isAbstractVal(ValDef val, ClassDef.Kind ownerKind, boolean isCtorParam) {
    if (val.modifiers().contains("abstract")) {
      return true;
    }
    if (isCtorParam) {
      return false;
    }
    return !val.hasDefault();
  }

  private static boolean hasConcreteTraitMembers(ClassDef traitDef) {
    for (Defn defn : traitDef.members()) {
      if (defn instanceof DefDef def) {
        if (!isAbstractDef(def, ClassDef.Kind.TRAIT)) {
          return true;
        }
      } else if (defn instanceof ValDef val) {
        if (!isAbstractVal(val, ClassDef.Kind.TRAIT)) {
          return true;
        }
      } else if (defn instanceof ClassDef cls) {
        if (cls.modifiers().contains("implicit")) {
          return true;
        }
      }
    }
    return false;
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
    if (modifiers.contains("final") && !staticContext) {
      access |= TurbineFlag.ACC_FINAL;
    }
    return access;
  }

  private static int visibility(ImmutableList<String> modifiers, ClassDef.Kind ownerKind) {
    String privateQualifier = accessQualifier(modifiers, "private");
    String protectedQualifier = accessQualifier(modifiers, "protected");
    if (ownerKind == ClassDef.Kind.TRAIT) {
      if (privateQualifier != null) {
        if (privateQualifier.isEmpty() || isThisQualifier(privateQualifier)) {
          return TurbineFlag.ACC_PRIVATE;
        }
      }
      return TurbineFlag.ACC_PUBLIC;
    }
    if (privateQualifier != null) {
      if (privateQualifier.isEmpty() || isThisQualifier(privateQualifier)) {
        return TurbineFlag.ACC_PRIVATE;
      }
      return TurbineFlag.ACC_PUBLIC;
    }
    if (protectedQualifier != null) {
      if (protectedQualifier.isEmpty() || isThisQualifier(protectedQualifier)) {
        return TurbineFlag.ACC_PROTECTED;
      }
      return TurbineFlag.ACC_PUBLIC;
    }
    return TurbineFlag.ACC_PUBLIC;
  }

  private static String accessQualifier(ImmutableList<String> modifiers, String keyword) {
    for (String modifier : modifiers) {
      if (modifier.equals(keyword)) {
        return "";
      }
      String prefix = keyword + "[";
      if (modifier.startsWith(prefix) && modifier.endsWith("]")) {
        return modifier.substring(prefix.length(), modifier.length() - 1);
      }
    }
    return null;
  }

  private static boolean isThisQualifier(String qualifier) {
    if (qualifier == null) {
      return false;
    }
    String normalized = qualifier.replace(" ", "");
    return "this".equals(normalized);
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
    List<AnnotationInfo> annotations =
        annotationsFromModifiers(def.modifiers(), pkg, scope, aliasScope);
    return new ClassFile.MethodInfo(
        access,
        encodeName(def.name()),
        desc.toString(),
        signature,
        /* exceptions= */ ImmutableList.of(),
        /* defaultValue= */ null,
        /* annotations= */ annotations,
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
      ClassDef.Kind ownerKind,
      boolean isCtorParam) {
    List<ClassFile.MethodInfo> methods = new ArrayList<>();
    String getterDesc =
        "()" + ScalaTypeMapper.descriptorForReturn(val.type(), pkg, typeParams, scope, aliasScope);
    boolean isAbstract = isAbstractVal(val, ownerKind, isCtorParam);
    int access = methodAccess(val.modifiers(), staticContext, isAbstract, ownerKind);
    String encodedName = encodeName(val.name());
    List<AnnotationInfo> annotations =
        annotationsFromModifiers(val.modifiers(), pkg, scope, aliasScope);
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
        /* annotations= */ annotations,
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
          /* annotations= */ annotations,
          /* parameterAnnotations= */ ImmutableList.of(),
          /* typeAnnotations= */ ImmutableList.of(),
          /* parameters= */ ImmutableList.of()));
    }
    return methods;
  }

  private static boolean shouldEmitLazyCompute(ValDef val, ClassDef.Kind ownerKind) {
    return ownerKind != ClassDef.Kind.TRAIT && val.modifiers().contains("lazy") && val.hasDefault();
  }

  private static ClassFile.MethodInfo lazyComputeMethod(
      ValDef val,
      String pkg,
      Set<String> typeParams,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope) {
    String desc =
        "()" + ScalaTypeMapper.descriptorForReturn(val.type(), pkg, typeParams, scope, aliasScope);
    String signature =
        ScalaSignature.methodSignature(
            ImmutableList.of(),
            ImmutableList.of(),
            val.type(),
            typeParams,
            pkg,
            scope,
            aliasScope);
    return new ClassFile.MethodInfo(
        TurbineFlag.ACC_PRIVATE,
        encodeName(val.name()) + "$lzycompute",
        desc,
        signature,
        /* exceptions= */ ImmutableList.of(),
        /* defaultValue= */ null,
        /* annotations= */ ImmutableList.of(),
        /* parameterAnnotations= */ ImmutableList.of(),
        /* typeAnnotations= */ ImmutableList.of(),
        /* parameters= */ ImmutableList.of());
  }

  private static ClassFile.MethodInfo buildTraitImplMethod(
      DefDef def,
      ClassDef traitDef,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope) {
    Set<String> typeParams = new HashSet<>(ScalaTypeMapper.typeParamNames(traitDef.typeParams()));
    typeParams.addAll(ScalaTypeMapper.typeParamNames(def.typeParams()));

    StringBuilder desc = new StringBuilder();
    desc.append('(');
    desc.append('L').append(binaryName(traitDef.packageName(), traitDef.name())).append(';');
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

  private static List<ClassFile.MethodInfo> traitStaticMethods(
      ClassDef traitDef,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope) {
    List<ClassFile.MethodInfo> methods = new ArrayList<>();
    for (Defn defn : traitDef.members()) {
      if (defn instanceof DefDef def) {
        if (isAbstractDef(def, ClassDef.Kind.TRAIT)) {
          continue;
        }
        methods.add(buildTraitStaticMethod(def, traitDef, scope, aliasScope));
      }
    }
    methods.addAll(implicitClassStaticMethods(traitDef, scope, aliasScope));
    return methods;
  }

  private static ClassFile.MethodInfo buildTraitStaticMethod(
      DefDef def,
      ClassDef traitDef,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope) {
    Set<String> typeParams = new HashSet<>(ScalaTypeMapper.typeParamNames(traitDef.typeParams()));
    typeParams.addAll(ScalaTypeMapper.typeParamNames(def.typeParams()));

    StringBuilder desc = new StringBuilder();
    desc.append('(');
    desc.append('L').append(binaryName(traitDef.packageName(), traitDef.name())).append(';');
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
        TurbineFlag.ACC_PUBLIC | TurbineFlag.ACC_STATIC | TurbineFlag.ACC_SYNTHETIC,
        encodeName(def.name()) + "$",
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
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope) {
    Set<String> typeParams = ScalaTypeMapper.typeParamNames(traitDef.typeParams());
    List<ClassFile.MethodInfo> methods = new ArrayList<>();
    String traitBinary = binaryName(traitDef.packageName(), traitDef.name());
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
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope) {
    String binary = binaryName(traitDef.packageName(), traitDef.name());
    String desc = "(L" + binary + ";)V";
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

  private static String companionPrivateName(ClassDef obj, String memberName) {
    String pkg = obj.packageName();
    StringBuilder sb = new StringBuilder();
    if (pkg != null && !pkg.isEmpty()) {
      sb.append(pkg.replace('.', '$')).append('$');
    }
    sb.append(obj.name()).append("$$").append(encodeName(memberName));
    return sb.toString();
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

  private record Key(String pkg, String name) {}

  private record ParentInfo(String superName, List<String> interfaces) {}

  private static String resolveParentBinary(
      String typeText,
      String pkg,
      List<ScalaTree.TypeParam> tparams,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      Map<String, ClassDef> traitsByBinary,
      Map<String, ClassDef> classesByBinary) {
    String simple = simpleTypeName(typeText);
    if (simple != null) {
      String local = binaryName(pkg, simple);
      if (classesByBinary.containsKey(local) || traitsByBinary.containsKey(local)) {
        return local;
      }
    }
    return eraseType(typeText, pkg, tparams, scope, aliasScope);
  }

  private static String simpleTypeName(String typeText) {
    if (typeText == null) {
      return null;
    }
    String trimmed = typeText.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    StringBuilder sb = new StringBuilder();
    boolean inBackticks = false;
    for (int i = 0; i < trimmed.length(); i++) {
      char c = trimmed.charAt(i);
      if (sb.length() == 0) {
        if (Character.isWhitespace(c)) {
          continue;
        }
        if (c == '`') {
          inBackticks = true;
          continue;
        }
        if (Character.isJavaIdentifierStart(c) || c == '$' || c == '_') {
          sb.append(c);
          continue;
        }
        return null;
      }
      if (inBackticks) {
        if (c == '`') {
          break;
        }
        sb.append(c);
        continue;
      }
      if (Character.isJavaIdentifierPart(c) || c == '$' || c == '_') {
        sb.append(c);
        continue;
      }
      if (c == '.') {
        return null;
      }
      break;
    }
    if (sb.length() == 0) {
      return null;
    }
    return sb.toString();
  }

  private static boolean isSerializableInterface(String binary) {
    return "java/io/Serializable".equals(binary) || "scala/Serializable".equals(binary);
  }

  private static ParentInfo resolveParents(
      ClassDef cls,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      Map<String, ClassDef> traitsByBinary,
      Map<String, ClassDef> classesByBinary,
      Map<ClassDef, ScalaTypeMapper.ImportScope> importScopes,
      Map<ClassDef, ScalaTypeMapper.TypeAliasScope> aliasScopes) {
    String pkg = cls.packageName();
    if (cls.parents().isEmpty()) {
      return new ParentInfo("java/lang/Object", ImmutableList.of());
    }
    List<String> interfaces = new ArrayList<>();
    if (cls.kind() == ClassDef.Kind.TRAIT) {
      for (String parent : cls.parents()) {
        String binary =
            resolveParentBinary(
                parent, pkg, cls.typeParams(), scope, aliasScope, traitsByBinary, classesByBinary);
        if (traitsByBinary.containsKey(binary)) {
          addInterface(interfaces, binary);
          continue;
        }
        if (classesByBinary.containsKey(binary)) {
          continue;
        }
        // Unknown parent: treat as interface by default.
        addInterface(interfaces, binary);
      }
      return new ParentInfo("java/lang/Object", interfaces);
    }

    String classParent = null;
    for (String parent : cls.parents()) {
      String binary =
          resolveParentBinary(
              parent, pkg, cls.typeParams(), scope, aliasScope, traitsByBinary, classesByBinary);
      if (isSerializableInterface(binary)) {
        addInterface(interfaces, binary);
        continue;
      }
      ClassDef trait = traitsByBinary.get(binary);
      if (trait != null) {
        addInterface(interfaces, binary);
        String traitParent =
            traitClassParentBinary(
                trait, traitsByBinary, classesByBinary, importScopes, aliasScopes, new HashSet<>());
        if (traitParent != null && classParent == null) {
          classParent = traitParent;
        }
        continue;
      }
      if (classesByBinary.containsKey(binary)) {
        if (classParent == null) {
          classParent = binary;
        } else {
          addInterface(interfaces, binary);
        }
        continue;
      }
      if (classParent == null) {
        classParent = binary;
      } else {
        addInterface(interfaces, binary);
      }
    }
    if (classParent == null) {
      classParent = "java/lang/Object";
    }
    return new ParentInfo(classParent, interfaces);
  }

  private static String traitClassParentBinary(
      ClassDef traitDef,
      Map<String, ClassDef> traitsByBinary,
      Map<String, ClassDef> classesByBinary,
      Map<ClassDef, ScalaTypeMapper.ImportScope> importScopes,
      Map<ClassDef, ScalaTypeMapper.TypeAliasScope> aliasScopes,
      Set<String> seen) {
    String traitBinary = binaryName(traitDef.packageName(), traitDef.name());
    if (!seen.add(traitBinary)) {
      return null;
    }
    ScalaTypeMapper.ImportScope scope =
        importScopes.getOrDefault(traitDef, ScalaTypeMapper.ImportScope.empty());
    ScalaTypeMapper.TypeAliasScope aliases =
        aliasScopes.getOrDefault(traitDef, ScalaTypeMapper.TypeAliasScope.empty());
    for (String parent : traitDef.parents()) {
      String binary =
          resolveParentBinary(
              parent,
              traitDef.packageName(),
              traitDef.typeParams(),
              scope,
              aliases,
              traitsByBinary,
              classesByBinary);
      ClassDef parentTrait = traitsByBinary.get(binary);
      if (parentTrait != null) {
        String nested =
            traitClassParentBinary(
                parentTrait, traitsByBinary, classesByBinary, importScopes, aliasScopes, seen);
        if (nested != null) {
          return nested;
        }
        continue;
      }
      if (classesByBinary.containsKey(binary)) {
        return binary;
      }
      // Unknown parent: ignore to avoid guessing class vs interface.
    }
    return null;
  }


  private record TraitRef(ClassDef trait, String parentTypeText) {}

  private record ClassParentRef(ClassDef parent, String parentTypeText) {}

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
      ClassDef cls, ScalaTypeMapper.ImportScope scope, ScalaTypeMapper.TypeAliasScope aliasScope) {
    String pkg = cls.packageName();
    Set<String> typeParams = ScalaTypeMapper.typeParamNames(cls.typeParams());
    String binary = binaryName(pkg, cls.name());
    DefDef apply =
        syntheticDef(
            pkg, "apply", cls.ctorParams(), binary, cls.position());
    ParamList unapplyParams =
        new ParamList(
            ImmutableList.of(
                new Param("x", ImmutableList.of(), binary, /* hasDefault= */ false, /* defaultUsesParam= */ false)));
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
            binary,
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
      ClassDef cls, ScalaTypeMapper.ImportScope scope, ScalaTypeMapper.TypeAliasScope aliasScope) {
    String pkg = cls.packageName();
    Set<String> typeParams = ScalaTypeMapper.typeParamNames(cls.typeParams());
    String binary = binaryName(pkg, cls.name());
    DefDef apply = syntheticDef(pkg, "apply", cls.ctorParams(), binary, cls.position());
    ParamList unapplyParams =
        new ParamList(
            ImmutableList.of(
                new Param("x", ImmutableList.of(), binary, /* hasDefault= */ false, /* defaultUsesParam= */ false)));
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
            binary,
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
      ClassDef cls, ScalaTypeMapper.ImportScope scope, ScalaTypeMapper.TypeAliasScope aliasScope) {
    String pkg = cls.packageName();
    Set<String> typeParams = ScalaTypeMapper.typeParamNames(cls.typeParams());
    String binary = binaryName(pkg, cls.name());
    List<ClassFile.MethodInfo> methods = new ArrayList<>();
    methods.add(
        buildMethod(
            syntheticDef(pkg, "copy", cls.ctorParams(), binary, cls.position()),
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
    ParamList fromProductParams =
        new ParamList(
            ImmutableList.of(
                new Param("p", ImmutableList.of(), "scala/Product", /* hasDefault= */ false, /* defaultUsesParam= */ false)));
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
    methods.add(
        buildMethod(
            syntheticDef(
                pkg,
                "fromProduct",
                ImmutableList.of(fromProductParams),
                "scala/deriving/Mirror$Singleton",
                obj.position()),
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

  private static Map<ClassDef, ScalaTypeMapper.TypeAliasScope> mergedAliasScopes(
      Map<ClassDef, ScalaTypeMapper.TypeAliasScope> aliasScopes,
      Map<ClassDef, ScalaTypeMapper.ImportScope> importScopes,
      Map<String, ClassDef> traitsByBinary,
      Map<String, ClassDef> classesByBinary,
      Map<String, ClassDef> objectsByBinary) {
    Map<ClassDef, ScalaTypeMapper.TypeAliasScope> merged = new IdentityHashMap<>();
    Set<ClassDef> inProgress = Collections.newSetFromMap(new IdentityHashMap<>());
    for (ClassDef cls : aliasScopes.keySet()) {
      merged.put(
          cls,
          mergedAliasScope(
              cls,
              aliasScopes,
              importScopes,
              traitsByBinary,
              classesByBinary,
              objectsByBinary,
              merged,
              inProgress));
    }
    return merged;
  }

  private static ScalaTypeMapper.TypeAliasScope mergedAliasScope(
      ClassDef cls,
      Map<ClassDef, ScalaTypeMapper.TypeAliasScope> aliasScopes,
      Map<ClassDef, ScalaTypeMapper.ImportScope> importScopes,
      Map<String, ClassDef> traitsByBinary,
      Map<String, ClassDef> classesByBinary,
      Map<String, ClassDef> objectsByBinary,
      Map<ClassDef, ScalaTypeMapper.TypeAliasScope> cache,
      Set<ClassDef> inProgress) {
    ScalaTypeMapper.TypeAliasScope cached = cache.get(cls);
    if (cached != null) {
      return cached;
    }
    if (!inProgress.add(cls)) {
      return aliasScopes.getOrDefault(cls, ScalaTypeMapper.TypeAliasScope.empty());
    }
    ScalaTypeMapper.TypeAliasScope own =
        aliasScopes.getOrDefault(cls, ScalaTypeMapper.TypeAliasScope.empty());
    ScalaTypeMapper.TypeAliasScope.Builder builder = ScalaTypeMapper.TypeAliasScope.builder();
    ScalaTypeMapper.ImportScope scope =
        importScopes.getOrDefault(cls, ScalaTypeMapper.ImportScope.empty());
    for (String parent : cls.parents()) {
      String parentBinary =
          resolveParentBinary(
              parent,
              cls.packageName(),
              cls.typeParams(),
              scope,
              own,
              traitsByBinary,
              classesByBinary);
      ClassDef parentDef = traitsByBinary.get(parentBinary);
      if (parentDef == null) {
        parentDef = classesByBinary.get(parentBinary);
      }
      if (parentDef == null) {
        continue;
      }
      ScalaTypeMapper.TypeAliasScope parentAliases =
          mergedAliasScope(
              parentDef,
              aliasScopes,
              importScopes,
              traitsByBinary,
              classesByBinary,
              objectsByBinary,
              cache,
              inProgress);
      Map<String, String> substitutions = traitTypeSubstitutions(parentDef, parent);
      for (Map.Entry<String, String> entry : parentAliases.aliases().entrySet()) {
        String rhs = entry.getValue();
        if (!substitutions.isEmpty()) {
          rhs = substituteType(rhs, substitutions);
        }
        rhs = expandQualifiedAlias(rhs, cls.packageName(), scope, aliasScopes, objectsByBinary);
        builder.addAlias(entry.getKey(), rhs);
      }
    }
    own.aliases()
        .forEach(
            (name, rhs) -> {
              String expanded =
                  expandQualifiedAlias(rhs, cls.packageName(), scope, aliasScopes, objectsByBinary);
              builder.addAlias(name, expanded);
            });
    addObjectAliasEntries(
        builder,
        cls.packageName(),
        scope,
        importScopes,
        aliasScopes,
        objectsByBinary);
    ScalaTypeMapper.TypeAliasScope merged = builder.build();
    cache.put(cls, merged);
    inProgress.remove(cls);
    return merged;
  }

  private static ScalaTypeMapper.ImportScope importScope(
      ScalaTree.CompUnit unit, Map<String, Set<String>> objectTypeMembers) {
    ScalaTypeMapper.ImportScope.Builder builder = ScalaTypeMapper.ImportScope.builder();
    for (ScalaTree.Stat stat : unit.stats()) {
      if (stat instanceof ImportStat imp) {
        parseImportText(builder, imp.text(), imp.packageName(), objectTypeMembers);
      }
    }
    return builder.build();
  }

  private static ScalaTypeMapper.ImportScope importScope(
      ImmutableList<String> imports,
      String currentPackage,
      Map<String, Set<String>> objectTypeMembers) {
    if (imports == null || imports.isEmpty()) {
      return ScalaTypeMapper.ImportScope.empty();
    }
    ScalaTypeMapper.ImportScope.Builder builder = ScalaTypeMapper.ImportScope.builder();
    for (String text : imports) {
      parseImportText(builder, text, currentPackage, objectTypeMembers);
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

  private static Map<String, Set<String>> objectTypeMembers(List<ClassDef> objectDefs) {
    Map<String, Set<String>> members = new HashMap<>();
    for (ClassDef obj : objectDefs) {
      String moduleBinary = binaryName(obj.packageName(), obj.name() + "$");
      Set<String> names = new HashSet<>();
      for (Defn defn : obj.members()) {
        if (defn instanceof ClassDef cls) {
          names.add(cls.name());
        } else if (defn instanceof TypeDef type) {
          names.add(type.name());
        }
      }
      if (!names.isEmpty()) {
        members.put(moduleBinary, names);
      }
    }
    return members;
  }

  private static Map<String, ScalaTypeMapper.ImportScope> packageTypeScopes(List<ClassDef> classDefs) {
    Map<String, ScalaTypeMapper.ImportScope.Builder> builders = new HashMap<>();
    for (ClassDef cls : classDefs) {
      if (cls.kind() != ClassDef.Kind.CLASS && cls.kind() != ClassDef.Kind.TRAIT) {
        continue;
      }
      if (cls.name().contains("$")) {
        // Nested types are not imported by package wildcard.
        continue;
      }
      String pkg = cls.packageName();
      String binary = binaryName(pkg, cls.name());
      builders
          .computeIfAbsent(pkg, unused -> ScalaTypeMapper.ImportScope.builder())
          .addExplicit(cls.name(), binary);
    }
    Map<String, ScalaTypeMapper.ImportScope> scopes = new HashMap<>();
    for (Map.Entry<String, ScalaTypeMapper.ImportScope.Builder> entry : builders.entrySet()) {
      scopes.put(entry.getKey(), entry.getValue().build());
    }
    return scopes;
  }

  private static void addWildcardImport(
      ScalaTypeMapper.ImportScope.Builder builder,
      String qualifier,
      Map<String, Set<String>> objectTypeMembers) {
    String wildcard = wildcardQualifier(qualifier);
    Set<String> members = objectTypeMembers.get(wildcard);
    if (members != null && !members.isEmpty()) {
      for (String member : members) {
        builder.addExplicit(member, joinQualifier(wildcard, member));
      }
      return;
    }
    if (wildcard.endsWith("$")) {
      // Unknown object members: avoid guessing type imports from term-only wildcards.
      return;
    }
    builder.addWildcard(wildcard);
  }

  private static void parseImportText(
      ScalaTypeMapper.ImportScope.Builder builder,
      String text,
      String currentPackage,
      Map<String, Set<String>> objectTypeMembers) {
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
      String qualifier = qualifierFromTokens(tokens.subList(0, brace), currentPackage);
      if (qualifier == null || qualifier.isEmpty()) {
        return;
      }
      for (int i = brace + 1; i < close; i++) {
        String token = tokens.get(i);
        if (",".equals(token)) {
          continue;
        }
        if ("_".equals(token)) {
          addWildcardImport(builder, qualifier, objectTypeMembers);
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
      String qualifier = qualifierFromTokens(tokens.subList(0, tokens.size() - 1), currentPackage);
      if (qualifier != null && !qualifier.isEmpty()) {
        addWildcardImport(builder, qualifier, objectTypeMembers);
      }
      return;
    }
    List<String> idents = identifierTokens(tokens);
    if (idents.size() < 2) {
      return;
    }
    String name = idents.get(idents.size() - 1);
    String qualifier = qualifierFromIdents(idents.subList(0, idents.size() - 1), currentPackage);
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

  private static String qualifierFromTokens(List<String> tokens, String currentPackage) {
    List<String> idents = identifierTokens(tokens);
    return qualifierFromIdents(idents, currentPackage);
  }

  private static String qualifierFromIdents(List<String> idents, String currentPackage) {
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
    if (currentPackage != null && !currentPackage.isEmpty() && isClassLike(cleaned.get(0))) {
      List<String> prefixed = new ArrayList<>(Arrays.asList(currentPackage.split("\\.")));
      prefixed.addAll(cleaned);
      cleaned = prefixed;
    }
    return toBinaryQualifier(cleaned);
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

  private static String expandQualifiedAlias(
      String rhs,
      String currentPackage,
      ScalaTypeMapper.ImportScope scope,
      Map<ClassDef, ScalaTypeMapper.TypeAliasScope> aliasScopes,
      Map<String, ClassDef> objectsByBinary) {
    if (rhs == null || rhs.isEmpty()) {
      return rhs;
    }
    String raw = stripRootPrefix(rawTypeName(rhs));
    if (raw == null) {
      return rhs;
    }
    int slash = raw.lastIndexOf('/');
    if (slash < 0) {
      return rhs;
    }
    String qualifier = raw.substring(0, slash);
    String member = raw.substring(slash + 1);
    if (member.isEmpty()) {
      return rhs;
    }
    String qualifierBinary = resolveQualifierBinary(qualifier, currentPackage, scope);
    ClassDef obj = objectsByBinary.get(qualifierBinary);
    if (obj == null) {
      return rhs;
    }
    ScalaTypeMapper.TypeAliasScope objAliases =
        aliasScopes.getOrDefault(obj, ScalaTypeMapper.TypeAliasScope.empty());
    String alias = objAliases.aliases().get(member);
    return alias == null ? rhs : alias;
  }

  private static String resolveQualifierBinary(
      String qualifier, String currentPackage, ScalaTypeMapper.ImportScope scope) {
    if (qualifier == null || qualifier.isEmpty()) {
      return qualifier;
    }
    String cleaned = stripRootPrefix(qualifier);
    if (cleaned != null && cleaned.contains("/")) {
      return cleaned;
    }
    if (scope != null && !scope.isEmpty()) {
      String explicit = scope.explicit().get(qualifier);
      if (explicit != null) {
        return explicit;
      }
    }
    if (currentPackage != null && !currentPackage.isEmpty()) {
      return currentPackage.replace('.', '/') + "/" + qualifier;
    }
    return qualifier;
  }

  private static void addObjectAliasEntries(
      ScalaTypeMapper.TypeAliasScope.Builder builder,
      String currentPackage,
      ScalaTypeMapper.ImportScope scope,
      Map<ClassDef, ScalaTypeMapper.ImportScope> importScopes,
      Map<ClassDef, ScalaTypeMapper.TypeAliasScope> aliasScopes,
      Map<String, ClassDef> objectsByBinary) {
    if (objectsByBinary.isEmpty()) {
      return;
    }
    String pkgPrefix = "";
    if (currentPackage != null && !currentPackage.isEmpty()) {
      pkgPrefix = currentPackage.replace('.', '/') + "/";
    }
    for (Map.Entry<String, ClassDef> entry : objectsByBinary.entrySet()) {
      String binary = entry.getKey();
      ClassDef obj = entry.getValue();
      int slash = binary.lastIndexOf('/');
      String objPkg = slash >= 0 ? binary.substring(0, slash + 1) : "";
      if (!pkgPrefix.equals(objPkg)) {
        continue;
      }
      addObjectAliasEntries(builder, obj.name(), binary, obj, importScopes, aliasScopes, objectsByBinary);
    }
    if (scope != null && !scope.isEmpty()) {
      for (Map.Entry<String, String> entry : scope.explicit().entrySet()) {
        String simple = entry.getKey();
        String binary = entry.getValue();
        ClassDef obj = objectsByBinary.get(binary);
        if (obj == null) {
          continue;
        }
        addObjectAliasEntries(builder, simple, binary, obj, importScopes, aliasScopes, objectsByBinary);
      }
    }
  }

  private static void addObjectAliasEntries(
      ScalaTypeMapper.TypeAliasScope.Builder builder,
      String qualifier,
      String qualifierBinary,
      ClassDef obj,
      Map<ClassDef, ScalaTypeMapper.ImportScope> importScopes,
      Map<ClassDef, ScalaTypeMapper.TypeAliasScope> aliasScopes,
      Map<String, ClassDef> objectsByBinary) {
    ScalaTypeMapper.TypeAliasScope objAliases =
        aliasScopes.getOrDefault(obj, ScalaTypeMapper.TypeAliasScope.empty());
    if (objAliases.isEmpty()) {
      return;
    }
    ScalaTypeMapper.ImportScope objScope =
        importScopes.getOrDefault(obj, ScalaTypeMapper.ImportScope.empty());
    for (Map.Entry<String, String> entry : objAliases.aliases().entrySet()) {
      String name = entry.getKey();
      String rhs = entry.getValue();
      String expanded = expandQualifiedAlias(rhs, obj.packageName(), objScope, aliasScopes, objectsByBinary);
      builder.addAlias(qualifier + "/" + name, expanded);
      builder.addAlias(qualifierBinary + "/" + name, expanded);
    }
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

  private static ImmutableList<AnnotationInfo> classAnnotations(
      ClassDef cls,
      String pkg,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope) {
    ImmutableList<AnnotationInfo> extra = annotationsFromModifiers(cls.modifiers(), pkg, scope, aliasScope);
    if (extra.isEmpty()) {
      return scalaClassAnnotations();
    }
    ImmutableList.Builder<AnnotationInfo> out = ImmutableList.builder();
    out.addAll(scalaClassAnnotations());
    out.addAll(extra);
    return out.build();
  }

  private static ImmutableList<AnnotationInfo> annotationsFromModifiers(
      ImmutableList<String> modifiers,
      String pkg,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope) {
    if (modifiers.isEmpty()) {
      return ImmutableList.of();
    }
    ImmutableList.Builder<AnnotationInfo> out = ImmutableList.builder();
    Set<String> seen = new HashSet<>();
    for (String modifier : modifiers) {
      if (!modifier.startsWith("@")) {
        continue;
      }
      String name = modifier.substring(1);
      String desc = annotationDescriptor(name, pkg, scope, aliasScope);
      if (desc == null) {
        continue;
      }
      if (isScalaSignatureAnnotation(desc)) {
        continue;
      }
      String invisibleKey = desc + "#I";
      if (seen.add(invisibleKey)) {
        out.add(new AnnotationInfo(desc, AnnotationInfo.RuntimeVisibility.INVISIBLE, ImmutableMap.of()));
      }
      if (isVisibleAnnotation(desc)) {
        String visibleKey = desc + "#V";
        if (seen.add(visibleKey)) {
          out.add(new AnnotationInfo(desc, AnnotationInfo.RuntimeVisibility.VISIBLE, ImmutableMap.of()));
        }
      }
    }
    return out.build();
  }

  private static boolean isScalaSignatureAnnotation(String desc) {
    return "Lscala/reflect/ScalaSignature;".equals(desc)
        || "Lscala/reflect/ScalaLongSignature;".equals(desc);
  }

  private static @Nullable String annotationDescriptor(
      String name,
      String pkg,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope) {
    if (name == null || name.isEmpty()) {
      return null;
    }
    String cleaned = name;
    if (cleaned.startsWith("_root_.")) {
      cleaned = cleaned.substring("_root_.".length());
    } else if (cleaned.startsWith("_root_/")) {
      cleaned = cleaned.substring("_root_/".length());
    }
    String desc = ScalaTypeMapper.descriptorForParam(cleaned, pkg, ImmutableSet.of(), scope, aliasScope);
    if (desc == null) {
      return null;
    }
    if ("Ljava/lang/Object;".equals(desc)) {
      String raw = stripRootPrefix(cleaned.replace('.', '/'));
      if (!"java/lang/Object".equals(raw)) {
        return "L" + raw + ";";
      }
    }
    return desc;
  }

  private static boolean isVisibleAnnotation(String desc) {
    String binary = desc;
    if (binary.startsWith("L") && binary.endsWith(";")) {
      binary = binary.substring(1, binary.length() - 1);
    }
    String lower = binary.toLowerCase(Locale.ROOT);
    if (lower.startsWith("org/openjdk/jmh/annotations/")) {
      return true;
    }
    if (lower.startsWith("jdk/jfr/")) {
      return true;
    }
    if (lower.startsWith("org/apache/spark/annotation/")) {
      return true;
    }
    if (binary.endsWith("/ExpressionDescription")) {
      return true;
    }
    return "java/lang/Deprecated".equals(binary)
        || "scala/deprecated".equals(binary)
        || "scala/Deprecated".equals(binary);
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

  private static void addInterface(List<String> interfaces, String iface) {
    if (!interfaces.contains(iface)) {
      interfaces.add(iface);
    }
  }

  private ScalaLower() {}
}

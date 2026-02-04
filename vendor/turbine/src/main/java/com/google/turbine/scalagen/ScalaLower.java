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
import com.google.common.collect.ImmutableMap.Builder;
import com.google.turbine.bytecode.ClassFile;
import com.google.turbine.bytecode.ClassWriter;
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
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Lowers Scala outline trees to minimal classfiles. */
public final class ScalaLower {

  public static ImmutableMap<String, byte[]> lower(
      ImmutableList<ScalaTree.CompUnit> units, int majorVersion) {
    List<ClassDef> classDefs = new ArrayList<>();
    List<ClassDef> objectDefs = new ArrayList<>();
    List<ClassDef> packageObjects = new ArrayList<>();
    Map<ClassDef, ScalaTypeMapper.ImportScope> importScopes = new IdentityHashMap<>();
    Map<ClassDef, ScalaTypeMapper.TypeAliasScope> aliasScopes = new IdentityHashMap<>();
    for (ScalaTree.CompUnit unit : units) {
      ScalaTypeMapper.ImportScope unitScope = importScope(unit);
      for (ScalaTree.Stat stat : unit.stats()) {
        if (stat instanceof ClassDef cls) {
          ScalaTypeMapper.ImportScope localScope = importScope(cls.imports());
          ScalaTypeMapper.ImportScope scope = mergeImportScopes(unitScope, localScope);
          importScopes.put(cls, scope);
          aliasScopes.put(cls, typeAliasScope(cls));
          if (cls.isPackageObject()) {
            packageObjects.add(cls);
          } else if (cls.kind() == ClassDef.Kind.OBJECT) {
            objectDefs.add(cls);
          } else {
            classDefs.add(cls);
          }
        }
      }
    }

    Map<Key, ClassDef> objectsByKey = new HashMap<>();
    Set<Key> caseCompanions = new HashSet<>();
    for (ClassDef obj : objectDefs) {
      objectsByKey.put(new Key(obj.packageName(), obj.name()), obj);
    }

    Map<Key, List<ClassFile.MethodInfo>> companionExtras = new HashMap<>();
    Map<Key, Boolean> hasCtorDefaults = new HashMap<>();
    for (ClassDef cls : classDefs) {
      ScalaTypeMapper.ImportScope scope =
          importScopes.getOrDefault(cls, ScalaTypeMapper.ImportScope.empty());
      ScalaTypeMapper.TypeAliasScope aliases =
          aliasScopes.getOrDefault(cls, ScalaTypeMapper.TypeAliasScope.empty());
      List<ClassFile.MethodInfo> ctorDefaults =
          ctorDefaultGetters(cls, scope, aliases, /* staticContext= */ false);
      List<ClassFile.MethodInfo> caseCompanionMethods = ImmutableList.of();
      if (cls.isCase()) {
        caseCompanions.add(new Key(cls.packageName(), cls.name()));
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
          importScopes.put(synthetic, scope);
          aliasScopes.put(synthetic, ScalaTypeMapper.TypeAliasScope.empty());
        }
      }
    }

    Builder<String, byte[]> out = ImmutableMap.builder();

    for (ClassDef cls : classDefs) {
      ClassDef companion = objectsByKey.get(new Key(cls.packageName(), cls.name()));
      Key key = new Key(cls.packageName(), cls.name());
      boolean ctorDefaults = hasCtorDefaults.getOrDefault(key, false);
      ScalaTypeMapper.ImportScope scope =
          importScopes.getOrDefault(cls, ScalaTypeMapper.ImportScope.empty());
      ScalaTypeMapper.TypeAliasScope aliases =
          aliasScopes.getOrDefault(cls, ScalaTypeMapper.TypeAliasScope.empty());
      ScalaTypeMapper.TypeAliasScope companionAliases =
          companion == null
              ? ScalaTypeMapper.TypeAliasScope.empty()
              : aliasScopes.getOrDefault(companion, ScalaTypeMapper.TypeAliasScope.empty());
      out.putAll(
          generateClass(cls, companion, ctorDefaults, scope, aliases, companionAliases, majorVersion));
      if (cls.kind() == ClassDef.Kind.TRAIT) {
        out.putAll(generateTraitImplClass(cls, scope, aliases, majorVersion));
      }
    }

    for (ClassDef obj : objectDefs) {
      Key key = new Key(obj.packageName(), obj.name());
      List<ClassFile.MethodInfo> extras = companionExtras.getOrDefault(key, ImmutableList.of());
      boolean isCaseCompanion = caseCompanions.contains(key);
      ScalaTypeMapper.ImportScope scope =
          importScopes.getOrDefault(obj, ScalaTypeMapper.ImportScope.empty());
      ScalaTypeMapper.TypeAliasScope aliases =
          aliasScopes.getOrDefault(obj, ScalaTypeMapper.TypeAliasScope.empty());
      out.putAll(generateObject(obj, extras, scope, aliases, majorVersion, isCaseCompanion));
    }

    for (ClassDef pkgObj : packageObjects) {
      ScalaTypeMapper.ImportScope scope =
          importScopes.getOrDefault(pkgObj, ScalaTypeMapper.ImportScope.empty());
      ScalaTypeMapper.TypeAliasScope aliases =
          aliasScopes.getOrDefault(pkgObj, ScalaTypeMapper.TypeAliasScope.empty());
      out.putAll(generatePackageObject(pkgObj, scope, aliases, majorVersion));
    }

    return out.buildOrThrow();
  }

  private static ImmutableMap<String, byte[]> generateClass(
      ClassDef cls,
      ClassDef companion,
      boolean hasCtorDefaults,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      ScalaTypeMapper.TypeAliasScope companionAliases,
      int majorVersion) {
    String pkg = cls.packageName();
    String name = cls.name();
    String binaryName = binaryName(pkg, name);
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
      if (isTrait) {
        interfaces.add(eraseType(first, pkg, cls.typeParams(), scope, aliasScope));
      } else {
        superName = eraseType(first, pkg, cls.typeParams(), scope, aliasScope);
      }
      for (int i = 1; i < cls.parents().size(); i++) {
        interfaces.add(eraseType(cls.parents().get(i), pkg, cls.typeParams(), scope, aliasScope));
      }
    }
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
      methods.add(ctorMethod(cls, scope, aliasScope));
    }
    methods.addAll(memberMethods(cls, scope, aliasScope, /* staticContext= */ false));
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
      methods.addAll(caseClassInstanceMethods(cls, scope, aliasScope));
      methods.addAll(caseClassStaticMethods(cls, scope, aliasScope));
    }
    if (companion != null) {
      methods.addAll(
          forwarders(
              companion,
              scope,
              companionAliases,
              /* isTrait= */ isTrait));
    }
    methods = uniqueMethods(methods);

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
            /* fields= */ ImmutableList.of(),
            /* annotations= */ ImmutableList.of(),
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
        majorVersion,
        /* isCaseCompanion= */ false);
  }

  private static ImmutableMap<String, byte[]> generateObject(
      ClassDef obj,
      List<ClassFile.MethodInfo> extraMethods,
      ScalaTypeMapper.ImportScope scope,
      ScalaTypeMapper.TypeAliasScope aliasScope,
      int majorVersion,
      boolean isCaseCompanion) {
    String pkg = obj.packageName();
    String name = obj.name() + "$";
    String binaryName = binaryName(pkg, name);
    int access = TurbineFlag.ACC_PUBLIC | TurbineFlag.ACC_FINAL | TurbineFlag.ACC_SUPER;

    List<ClassFile.FieldInfo> fields = new ArrayList<>();
    fields.add(moduleField(binaryName));

    List<ClassFile.MethodInfo> methods = new ArrayList<>();
    methods.add(defaultConstructor(/* isPublic= */ false));
    methods.addAll(memberMethods(obj, scope, aliasScope, /* staticContext= */ false));
    methods.addAll(
        defaultGettersForDefs(
            obj.members(),
            obj.packageName(),
            ScalaTypeMapper.typeParamNames(obj.typeParams()),
            scope,
            aliasScope,
            /* staticContext= */ false,
            ClassDef.Kind.CLASS));
    methods.addAll(extraMethods);
    methods = uniqueMethods(methods);

    List<String> interfaces = new ArrayList<>();
    if (isCaseCompanion) {
      interfaces.add("java/io/Serializable");
    }

    ClassFile classFile =
        new ClassFile(
            access,
            majorVersion,
            binaryName,
            /* signature= */ null,
            "java/lang/Object",
            /* interfaces= */ interfaces,
            /* permits= */ ImmutableList.of(),
            methods,
            fields,
            /* annotations= */ ImmutableList.of(),
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
      int majorVersion) {
    String pkg = pkgObj.packageName();
    String fullPackage = pkg.isEmpty() ? pkgObj.name() : pkg + "." + pkgObj.name();

    ClassDef companion = pkgObj;

    String moduleBinary = binaryName(fullPackage, "package$");
    String companionBinary = binaryName(fullPackage, "package");

    List<ClassFile.FieldInfo> fields = new ArrayList<>();
    fields.add(moduleField(moduleBinary));

    List<ClassFile.MethodInfo> moduleMethods = new ArrayList<>();
    moduleMethods.add(defaultConstructor(/* isPublic= */ false));
    moduleMethods.addAll(memberMethods(pkgObj, scope, aliasScope, /* staticContext= */ false));
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
            /* annotations= */ ImmutableList.of(),
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
            /* annotations= */ ImmutableList.of(),
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
            /* annotations= */ ImmutableList.of(),
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
                  cls.kind()));
        }
      }
    }
    return methods;
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
    return ownerKind == ClassDef.Kind.TRAIT && def.modifiers().contains("abstract");
  }

  private static boolean isAbstractVal(ValDef val, ClassDef.Kind ownerKind) {
    if (ownerKind != ClassDef.Kind.TRAIT) {
      return false;
    }
    if (!val.hasDefault()) {
      return true;
    }
    return !val.hasExplicitType();
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
    if (modifiers.contains("final")) {
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
            syntheticDef(pkg, "toString", ImmutableList.of(), "String", cls.position()),
            pkg,
            typeParams,
            scope,
            aliasScope,
            /* staticContext= */ false,
            cls.kind()));
    return methods;
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

  private static ScalaTypeMapper.ImportScope importScope(ScalaTree.CompUnit unit) {
    ScalaTypeMapper.ImportScope.Builder builder = ScalaTypeMapper.ImportScope.builder();
    for (ScalaTree.Stat stat : unit.stats()) {
      if (stat instanceof ImportStat imp) {
        parseImportText(builder, imp.text());
      }
    }
    return builder.build();
  }

  private static ScalaTypeMapper.ImportScope importScope(ImmutableList<String> imports) {
    if (imports == null || imports.isEmpty()) {
      return ScalaTypeMapper.ImportScope.empty();
    }
    ScalaTypeMapper.ImportScope.Builder builder = ScalaTypeMapper.ImportScope.builder();
    for (String text : imports) {
      parseImportText(builder, text);
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

  private static void parseImportText(
      ScalaTypeMapper.ImportScope.Builder builder, String text) {
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
      String qualifier = qualifierFromTokens(tokens.subList(0, brace));
      if (qualifier == null || qualifier.isEmpty()) {
        return;
      }
      for (int i = brace + 1; i < close; i++) {
        String token = tokens.get(i);
        if (",".equals(token)) {
          continue;
        }
        if ("_".equals(token)) {
          builder.addWildcard(qualifier);
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
              builder.addExplicit(alias, qualifier + "/" + cleaned);
              i += 2;
              continue;
            }
          }
          builder.addExplicit(cleaned, qualifier + "/" + cleaned);
        }
      }
      return;
    }
    if (!tokens.isEmpty() && "_".equals(tokens.get(tokens.size() - 1))) {
      String qualifier = qualifierFromTokens(tokens.subList(0, tokens.size() - 1));
      if (qualifier != null && !qualifier.isEmpty()) {
        builder.addWildcard(qualifier);
      }
      return;
    }
    List<String> idents = identifierTokens(tokens);
    if (idents.size() < 2) {
      return;
    }
    String name = idents.get(idents.size() - 1);
    String qualifier = String.join("/", idents.subList(0, idents.size() - 1));
    if (qualifier.isEmpty()) {
      return;
    }
    builder.addExplicit(name, qualifier + "/" + name);
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

  private static String qualifierFromTokens(List<String> tokens) {
    List<String> idents = identifierTokens(tokens);
    if (idents.isEmpty()) {
      return null;
    }
    return String.join("/", idents);
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

  private static String stripBackticks(String token) {
    if (token.length() >= 2 && token.charAt(0) == '`' && token.charAt(token.length() - 1) == '`') {
      return token.substring(1, token.length() - 1);
    }
    return token;
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
        boolean isAbstract = isAbstractDef(def, ownerKind);
        int access = methodAccess(def.modifiers(), staticContext, isAbstract, ownerKind);
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

  private ScalaLower() {}
}

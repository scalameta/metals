/*
 * Copyright 2019 Google Inc. All Rights Reserved.
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

package com.google.turbine.processing;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.env.CompoundEnv;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.diag.TurbineError.ErrorKind;
import com.google.turbine.type.Type;
import com.google.turbine.type.Type.ClassTy;
import com.google.turbine.type.Type.TyKind;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A representation of the class hierarchy, with logic for performing search between subtypes and
 * their supertypes.
 */
public class ClassHierarchy {

  private final Map<ClassSymbol, HierarchyNode> cache = new HashMap<>();
  private Env<ClassSymbol, ? extends TypeBoundClass> env;

  ClassHierarchy(Env<ClassSymbol, ? extends TypeBoundClass> env) {
    this.env = env;
  }

  public void round(CompoundEnv<ClassSymbol, TypeBoundClass> env) {
    cache.clear();
    this.env = env;
  }

  /** A linked list between two types in the hierarchy. */
  private static class PathNode {

    /** The class for this node. */
    final ClassTy type;

    /** The node corresponding to a direct super-type of this class, or {@code null}. */
    final PathNode ancestor;

    PathNode(ClassTy type, PathNode ancestor) {
      this.type = type;
      this.ancestor = ancestor;
    }
  }

  /**
   * A node in the type hierarchy, corresponding to a class symbol U. For each type V in the
   * transitive supertype hierarchy of U, we save a mapping from the class symbol for V to the path
   * from U to V in the type hierarchy.
   */
  private class HierarchyNode {

    private final ClassSymbol sym;
    private final Map<ClassSymbol, PathNode> ancestors = new LinkedHashMap<>();

    HierarchyNode(ClassSymbol sym) {
      this.sym = sym;
    }

    /** Adds a child (direct supertype) of this node. */
    private void add(Type type) {
      if (type.tyKind() != TyKind.CLASS_TY) {
        // ignore any erroneous types that ended up in the hierarchy
        return;
      }
      ClassTy classTy = (ClassTy) type;
      HierarchyNode child = get(classTy.sym());
      // add a new edge to the direct supertype
      PathNode existing = ancestors.putIfAbsent(child.sym, new PathNode(classTy, null));
      if (existing != null) {
        // if this child has already been added don't re-process its ancestors
        return;
      }
      // copy and extend edges for the transitive supertypes
      for (Map.Entry<ClassSymbol, PathNode> n : child.ancestors.entrySet()) {
        ancestors.putIfAbsent(n.getKey(), new PathNode(classTy, n.getValue()));
      }
    }

    /** The supertype closure of this node. */
    private Set<ClassSymbol> closure() {
      return ancestors.keySet();
    }
  }

  private HierarchyNode compute(ClassSymbol sym) {
    HierarchyNode node = new HierarchyNode(sym);
    TypeBoundClass info = env.get(sym);
    if (info == null) {
      throw TurbineError.format(/* source= */ null, ErrorKind.SYMBOL_NOT_FOUND, sym);
    }
    if (info.superClassType() != null) {
      node.add(info.superClassType());
    }
    for (Type type : info.interfaceTypes()) {
      node.add(type);
    }
    return node;
  }

  private HierarchyNode get(ClassSymbol sym) {
    // dont use computeIfAbsent, to support re-entrant lookups
    HierarchyNode result = cache.get(sym);
    if (result != null) {
      return result;
    }
    result = compute(sym);
    cache.put(sym, result);
    return result;
  }

  /**
   * Returns a list of types on the path between the given type {@code t} and a transitive
   * superclass {@code s}, or an empty list if no such path exists.
   */
  ImmutableList<ClassTy> search(Type t, ClassSymbol s) {
    if (t.tyKind() != TyKind.CLASS_TY) {
      return ImmutableList.of();
    }
    ClassTy classTy = (ClassTy) t;
    if (classTy.sym().equals(s)) {
      return ImmutableList.of(classTy);
    }
    HierarchyNode node = get(classTy.sym());
    PathNode path = node.ancestors.get(s);
    if (path == null) {
      return ImmutableList.of();
    }
    ImmutableList.Builder<ClassTy> result = ImmutableList.builder();
    result.add(classTy);
    while (path != null) {
      result.add(path.type);
      path = path.ancestor;
    }
    return result.build().reverse();
  }

  /**
   * Returns all classes in the transitive supertype hierarchy of the given class, including the
   * class itself.
   *
   * <p>The iteration order of the results is undefined, and in particular no guarantees are made
   * about the ordering of sub-types and super-types.
   */
  public Iterable<ClassSymbol> transitiveSupertypes(ClassSymbol s) {
    return Iterables.concat(ImmutableList.of(s), get(s).closure());
  }
}

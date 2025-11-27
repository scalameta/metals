/*
 * Copyright 2016 Google Inc. All Rights Reserved.
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

package com.google.turbine.binder.bound;

import static com.google.common.base.MoreObjects.firstNonNull;

import com.google.common.collect.ImmutableSet;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.model.TurbineElementType;
import java.lang.annotation.RetentionPolicy;
import java.util.EnumSet;
import org.jspecify.annotations.Nullable;

/**
 * Annotation metadata, e.g. from {@link java.lang.annotation.Target}, {@link
 * java.lang.annotation.Retention}, and {@link java.lang.annotation.Repeatable}.
 */
public class AnnotationMetadata {

  public static final ImmutableSet<TurbineElementType> DEFAULT_TARGETS = getDefaultElements();

  private static ImmutableSet<TurbineElementType> getDefaultElements() {
    EnumSet<TurbineElementType> values = EnumSet.allOf(TurbineElementType.class);
    values.remove(TurbineElementType.TYPE_PARAMETER);
    values.remove(TurbineElementType.TYPE_USE);
    return ImmutableSet.copyOf(values);
  }

  private final RetentionPolicy retention;
  private final ImmutableSet<TurbineElementType> target;
  private final @Nullable ClassSymbol repeatable;

  public AnnotationMetadata(
      @Nullable RetentionPolicy retention,
      @Nullable ImmutableSet<TurbineElementType> annotationTarget,
      @Nullable ClassSymbol repeatable) {
    this.retention = firstNonNull(retention, RetentionPolicy.CLASS);
    this.target = firstNonNull(annotationTarget, DEFAULT_TARGETS);
    this.repeatable = repeatable;
  }

  /** The retention policy specified by the {@code @Retention} meta-annotation. */
  public RetentionPolicy retention() {
    return retention;
  }

  /** Target element types specified by the {@code @Target} meta-annotation. */
  public ImmutableSet<TurbineElementType> target() {
    return target;
  }

  /** The container annotation for {@code @Repeated} annotations. */
  public @Nullable ClassSymbol repeatable() {
    return repeatable;
  }
}

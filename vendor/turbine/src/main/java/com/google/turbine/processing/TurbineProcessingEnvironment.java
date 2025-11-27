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

import java.util.Locale;
import java.util.Map;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import org.jspecify.annotations.Nullable;

/** Turbine's {@link ProcessingEnvironment}. */
public class TurbineProcessingEnvironment implements ProcessingEnvironment {

  private final Filer filer;
  private final Types types;
  private final Map<String, String> processorOptions;
  private final Elements elements;
  private final Map<String, byte[]> statistics;
  private final SourceVersion sourceVersion;
  private final Messager messager;
  private final ClassLoader processorLoader;

  public TurbineProcessingEnvironment(
      Filer filer,
      Types types,
      Elements elements,
      Messager messager,
      Map<String, String> processorOptions,
      SourceVersion sourceVersion,
      @Nullable ClassLoader processorLoader,
      Map<String, byte[]> statistics) {
    this.filer = filer;
    this.types = types;
    this.processorOptions = processorOptions;
    this.sourceVersion = sourceVersion;
    this.elements = elements;
    this.statistics = statistics;
    this.messager = messager;
    this.processorLoader = processorLoader;
  }

  @Override
  public Map<String, String> getOptions() {
    return processorOptions;
  }

  @Override
  public Messager getMessager() {
    return messager;
  }

  @Override
  public Filer getFiler() {
    return filer;
  }

  @Override
  public Elements getElementUtils() {
    return elements;
  }

  @Override
  public Types getTypeUtils() {
    return types;
  }

  @Override
  public SourceVersion getSourceVersion() {
    return sourceVersion;
  }

  @Override
  public Locale getLocale() {
    return Locale.ENGLISH;
  }

  public ClassLoader processorLoader() {
    return processorLoader;
  }

  public void addStatistics(String key, byte[] extension) {
    byte[] existing = statistics.put(key, extension);
    if (existing != null) {
      throw new IllegalStateException("duplicate statistics reported for " + key);
    }
  }
}

/*
 * Copyright (c) Facebook, Inc. and its affiliates.
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

package com.facebook.buck.jvm.java.stepsbuilder.impl;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.jvm.core.BuildTargetValue;
import com.facebook.buck.jvm.java.BaseJavacToJarStepFactory;
import com.facebook.buck.jvm.java.CompileToJarStepFactory;
import com.facebook.buck.jvm.java.FilesystemParams;
import com.facebook.buck.jvm.java.JavacPipelineState;
import com.facebook.buck.jvm.java.stepsbuilder.JavaLibraryJarPipelineStepsBuilder;
import com.facebook.buck.jvm.java.stepsbuilder.JavaLibraryRules;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;

/**
 * Default implementation of {@link
 * com.facebook.buck.jvm.java.stepsbuilder.JavaLibraryJarPipelineStepsBuilder}
 */
class DefaultJavaLibraryJarPipelineStepsBuilder<T extends CompileToJarStepFactory.ExtraParams>
    extends DefaultJavaStepsBuilderBase<T> implements JavaLibraryJarPipelineStepsBuilder {

  DefaultJavaLibraryJarPipelineStepsBuilder(CompileToJarStepFactory<T> configuredCompiler) {
    super(configuredCompiler);
  }

  @Override
  public void addPipelinedBuildStepsForLibraryJar(
      BuildTargetValue libraryTarget,
      ImmutableList<String> postprocessClassesCommands,
      FilesystemParams filesystemParams,
      BuildableContext buildableContext,
      JavacPipelineState state,
      RelPath pathToClassHashes,
      ImmutableMap<RelPath, RelPath> resourcesMap,
      ImmutableMap<String, RelPath> cellToPathMappings,
      Optional<RelPath> pathToClasses) {
    Preconditions.checkArgument(postprocessClassesCommands.isEmpty());
    ((BaseJavacToJarStepFactory) configuredCompiler)
        .createPipelinedCompileToJarStep(
            filesystemParams,
            cellToPathMappings,
            libraryTarget,
            state,
            postprocessClassesCommands,
            stepsBuilder,
            buildableContext,
            resourcesMap);

    JavaLibraryRules.addAccumulateClassNamesStep(
        filesystemParams.getIgnoredPaths(), stepsBuilder, pathToClasses, pathToClassHashes);
  }
}
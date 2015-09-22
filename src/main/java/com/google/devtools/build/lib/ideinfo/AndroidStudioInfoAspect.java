// Copyright 2014 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.ideinfo;

import static com.google.common.collect.Iterables.transform;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteSource;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Root;
import com.google.devtools.build.lib.analysis.Aspect;
import com.google.devtools.build.lib.analysis.Aspect.Builder;
import com.google.devtools.build.lib.analysis.ConfiguredAspectFactory;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.actions.BinaryFileWriteAction;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.ideinfo.androidstudio.AndroidStudioIdeInfo.AndroidRuleIdeInfo;
import com.google.devtools.build.lib.ideinfo.androidstudio.AndroidStudioIdeInfo.AndroidSdkRuleInfo;
import com.google.devtools.build.lib.ideinfo.androidstudio.AndroidStudioIdeInfo.ArtifactLocation;
import com.google.devtools.build.lib.ideinfo.androidstudio.AndroidStudioIdeInfo.JavaRuleIdeInfo;
import com.google.devtools.build.lib.ideinfo.androidstudio.AndroidStudioIdeInfo.LibraryArtifact;
import com.google.devtools.build.lib.ideinfo.androidstudio.AndroidStudioIdeInfo.RuleIdeInfo;
import com.google.devtools.build.lib.ideinfo.androidstudio.AndroidStudioIdeInfo.RuleIdeInfo.Kind;
import com.google.devtools.build.lib.packages.AspectDefinition;
import com.google.devtools.build.lib.packages.AspectParameters;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.rules.android.AndroidCommon;
import com.google.devtools.build.lib.rules.android.AndroidIdeInfoProvider;
import com.google.devtools.build.lib.rules.android.AndroidIdeInfoProvider.SourceDirectory;
import com.google.devtools.build.lib.rules.android.AndroidSdkProvider;
import com.google.devtools.build.lib.rules.java.JavaExportsProvider;
import com.google.devtools.build.lib.rules.java.JavaRuleOutputJarsProvider;
import com.google.devtools.build.lib.rules.java.JavaSourceInfoProvider;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.protobuf.MessageLite;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;

import javax.annotation.Nullable;

/**
 * Generates ide-build information for Android Studio.
 */
public class AndroidStudioInfoAspect implements ConfiguredAspectFactory {
  public static final String NAME = "AndroidStudioInfoAspect";

  // Output groups.
  public static final String IDE_RESOLVE = "ide-resolve";
  public static final String IDE_BUILD = "ide-build";

  // File suffixes.
  public static final String ASWB_BUILD_SUFFIX = ".aswb-build";
  public static final Function<Label, String> LABEL_TO_STRING = new Function<Label, String>() {
    @Nullable
    @Override
    public String apply(Label label) {
      return label.toString();
    }
  };

  @Override
  public AspectDefinition getDefinition() {
    return new AspectDefinition.Builder(NAME)
        .attributeAspect("deps", AndroidStudioInfoAspect.class)
        .build();
  }

  @Override
  public Aspect create(ConfiguredTarget base, RuleContext ruleContext,
      AspectParameters parameters) {
    Aspect.Builder builder = new Builder(NAME);

    // Collect ide build files and calculate dependencies.
    NestedSetBuilder<Label> transitiveDependenciesBuilder = NestedSetBuilder.stableOrder();
    NestedSetBuilder<Label> dependenciesBuilder = NestedSetBuilder.stableOrder();
    NestedSetBuilder<SourceDirectory> transitiveResourcesBuilder = NestedSetBuilder.stableOrder();

    NestedSetBuilder<Artifact> ideBuildFilesBuilder = NestedSetBuilder.stableOrder();

    // todo(dslomov,tomlu): following current build info logic, this code enumerates dependencies
    // directly by iterating over deps attribute. The more robust way to do this might be
    // to iterate classpath as provided to build action.
    if (ruleContext.attributes().has("deps", BuildType.LABEL_LIST)) {
      Iterable<AndroidStudioInfoFilesProvider> androidStudioInfoFilesProviders =
          ruleContext.getPrerequisites("deps", Mode.TARGET, AndroidStudioInfoFilesProvider.class);
      for (AndroidStudioInfoFilesProvider depProvider : androidStudioInfoFilesProviders) {
        ideBuildFilesBuilder.addTransitive(depProvider.getIdeBuildFiles());
        transitiveDependenciesBuilder.addTransitive(depProvider.getTransitiveDependencies());
        transitiveResourcesBuilder.addTransitive(depProvider.getTransitiveResources());
      }
      List<? extends TransitiveInfoCollection> deps =
          ruleContext.getPrerequisites("deps", Mode.TARGET);
      for (TransitiveInfoCollection dep : deps) {
        dependenciesBuilder.add(dep.getLabel());
      }

      Iterable<JavaExportsProvider> javaExportsProviders = ruleContext
          .getPrerequisites("deps", Mode.TARGET, JavaExportsProvider.class);
      for (JavaExportsProvider javaExportsProvider : javaExportsProviders) {
        dependenciesBuilder.addTransitive(javaExportsProvider.getTransitiveExports());
      }
    }

    NestedSet<Label> directDependencies = dependenciesBuilder.build();
    transitiveDependenciesBuilder.addTransitive(directDependencies);
    NestedSet<Label> transitiveDependencies = transitiveDependenciesBuilder.build();

    RuleIdeInfo.Kind ruleKind = getRuleKind(ruleContext.getRule(), base);

    NestedSet<SourceDirectory> transitiveResources;
    if (ruleKind != RuleIdeInfo.Kind.UNRECOGNIZED) {
      Pair<Artifact, NestedSet<SourceDirectory>> ideBuildFile =
          createIdeBuildArtifact(
              base,
              ruleContext,
              ruleKind,
              directDependencies,
              transitiveDependencies,
              transitiveResourcesBuilder);
      ideBuildFilesBuilder.add(ideBuildFile.first);
      transitiveResources = ideBuildFile.second;
    } else {
      transitiveResources = transitiveResourcesBuilder.build();
    }

    NestedSet<Artifact> ideBuildFiles = ideBuildFilesBuilder.build();
    builder
        .addOutputGroup(IDE_BUILD, ideBuildFiles)
        .addProvider(
            AndroidStudioInfoFilesProvider.class,
            new AndroidStudioInfoFilesProvider(
                ideBuildFiles, transitiveDependencies, transitiveResources));

    return builder.build();
  }

  private static AndroidSdkRuleInfo makeAndroidSdkRuleInfo(RuleContext ruleContext,
      AndroidSdkProvider provider) {
    AndroidSdkRuleInfo.Builder sdkInfoBuilder = AndroidSdkRuleInfo.newBuilder();

    Path androidSdkDirectory = provider.getAndroidJar().getPath().getParentDirectory();
    sdkInfoBuilder.setAndroidSdkPath(androidSdkDirectory.toString());

    Root genfilesDirectory = ruleContext.getConfiguration().getGenfilesDirectory();
    sdkInfoBuilder.setGenfilesPath(genfilesDirectory.getPath().toString());

    Path binfilesPath = ruleContext.getConfiguration().getBinDirectory().getPath();
    sdkInfoBuilder.setBinPath(binfilesPath.toString());

    return sdkInfoBuilder.build();
  }

  private Pair<Artifact, NestedSet<SourceDirectory>> createIdeBuildArtifact(
      ConfiguredTarget base,
      RuleContext ruleContext,
      Kind ruleKind,
      NestedSet<Label> directDependencies,
      NestedSet<Label> transitiveDependencies,
      NestedSetBuilder<SourceDirectory> transitiveResourcesBuilder) {
    PathFragment ideBuildFilePath = getOutputFilePath(base, ruleContext);
    Root genfilesDirectory = ruleContext.getConfiguration().getGenfilesDirectory();
    Artifact ideBuildFile =
        ruleContext
            .getAnalysisEnvironment()
            .getDerivedArtifact(ideBuildFilePath, genfilesDirectory);

    RuleIdeInfo.Builder outputBuilder = RuleIdeInfo.newBuilder();

    outputBuilder.setLabel(base.getLabel().toString());

    outputBuilder.setBuildFile(
        ruleContext
            .getRule()
            .getPackage()
            .getBuildFile()
            .getPath()
            .toString());

    outputBuilder.setKind(ruleKind);

    outputBuilder.addAllDependencies(transform(directDependencies, LABEL_TO_STRING));
    outputBuilder.addAllTransitiveDependencies(transform(transitiveDependencies, LABEL_TO_STRING));

    NestedSet<SourceDirectory> transitiveResources = null;
    if (ruleKind == Kind.JAVA_LIBRARY
        || ruleKind == Kind.JAVA_IMPORT
        || ruleKind == Kind.JAVA_TEST
        || ruleKind == Kind.JAVA_BINARY) {
      outputBuilder.setJavaRuleIdeInfo(makeJavaRuleIdeInfo(base));
    } else if (ruleKind == Kind.ANDROID_LIBRARY || ruleKind == Kind.ANDROID_BINARY) {
      outputBuilder.setJavaRuleIdeInfo(makeJavaRuleIdeInfo(base));
      Pair<AndroidRuleIdeInfo, NestedSet<SourceDirectory>> androidRuleIdeInfo =
          makeAndroidRuleIdeInfo(ruleContext, base, transitiveResourcesBuilder);
      outputBuilder.setAndroidRuleIdeInfo(androidRuleIdeInfo.first);
      transitiveResources = androidRuleIdeInfo.second;
    } else if (ruleKind == Kind.ANDROID_SDK) {
      outputBuilder.setAndroidSdkRuleInfo(
          makeAndroidSdkRuleInfo(ruleContext, base.getProvider(AndroidSdkProvider.class)));
    }

    if (transitiveResources == null) {
      transitiveResources = transitiveResourcesBuilder.build();
    }

    final RuleIdeInfo ruleIdeInfo = outputBuilder.build();
    ruleContext.registerAction(
        makeProtoWriteAction(ruleContext.getActionOwner(), ruleIdeInfo, ideBuildFile));

    return Pair.of(ideBuildFile, transitiveResources);
  }

  private static Pair<AndroidRuleIdeInfo, NestedSet<SourceDirectory>> makeAndroidRuleIdeInfo(
      RuleContext ruleContext,
      ConfiguredTarget base,
      NestedSetBuilder<SourceDirectory> transitiveResourcesBuilder) {
    AndroidRuleIdeInfo.Builder builder = AndroidRuleIdeInfo.newBuilder();
    AndroidIdeInfoProvider provider = base.getProvider(AndroidIdeInfoProvider.class);
    if (provider.getSignedApk() != null) {
      builder.setApk(makeArtifactLocation(provider.getSignedApk()));
    }

    if (provider.getManifest() != null) {
      builder.setManifest(makeArtifactLocation(provider.getManifest()));
    }

    if (provider.getGeneratedManifest() != null) {
      builder.setGeneratedManifest(makeArtifactLocation(provider.getGeneratedManifest()));
    }

    for (Artifact artifact : provider.getApksUnderTest()) {
      builder.addDependencyApk(makeArtifactLocation(artifact));
    }
    for (SourceDirectory resourceDir : provider.getResourceDirs()) {
      ArtifactLocation artifactLocation = makeArtifactLocation(resourceDir);
      builder.addResources(artifactLocation);
      transitiveResourcesBuilder.add(resourceDir);
    }

    builder.setJavaPackage(AndroidCommon.getJavaPackage(ruleContext));

    NestedSet<SourceDirectory> transitiveResources = transitiveResourcesBuilder.build();
    for (SourceDirectory transitiveResource : transitiveResources) {
      builder.addTransitiveResources(makeArtifactLocation(transitiveResource));
    }

    return Pair.of(builder.build(), transitiveResources);
  }

  private static BinaryFileWriteAction makeProtoWriteAction(
      ActionOwner actionOwner, final MessageLite message, Artifact artifact) {
    return new BinaryFileWriteAction(
        actionOwner,
        artifact,
        new ByteSource() {
          @Override
          public InputStream openStream() throws IOException {
            return message.toByteString().newInput();
          }
        },
        /*makeExecutable =*/ false);
  }

  private static ArtifactLocation makeArtifactLocation(Artifact artifact) {
    return ArtifactLocation.newBuilder()
        .setRootPath(artifact.getRoot().getPath().toString())
        .setRelativePath(artifact.getRootRelativePathString())
        .build();
  }

  private static ArtifactLocation makeArtifactLocation(SourceDirectory resourceDir) {
    return ArtifactLocation.newBuilder()
        .setRootPath(resourceDir.getRootPath().toString())
        .setRelativePath(resourceDir.getRelativePath().toString())
        .build();
  }

  private static JavaRuleIdeInfo makeJavaRuleIdeInfo(ConfiguredTarget base) {
    JavaRuleIdeInfo.Builder builder = JavaRuleIdeInfo.newBuilder();
    JavaRuleOutputJarsProvider outputJarsProvider =
        base.getProvider(JavaRuleOutputJarsProvider.class);
    if (outputJarsProvider != null) {
      // java_library
      collectJarsFromOutputJarsProvider(builder, outputJarsProvider);
    } else {
      JavaSourceInfoProvider provider = base.getProvider(JavaSourceInfoProvider.class);
      if (provider != null) {
        // java_import
        collectJarsFromSourceInfoProvider(builder, provider);
      }
    }

    Collection<Artifact> sourceFiles = getSources(base);

    for (Artifact sourceFile : sourceFiles) {
      builder.addSources(makeArtifactLocation(sourceFile));
    }

    return builder.build();
  }

  private static void collectJarsFromSourceInfoProvider(
      JavaRuleIdeInfo.Builder builder, JavaSourceInfoProvider provider) {
    Collection<Artifact> sourceJarsForJarFiles = provider.getSourceJarsForJarFiles();
    // For java_import rule, we always have only one source jar specified.
    // The intent is that that source jar provides sources for all imported jars,
    // so we reflect that intent, adding that jar to all LibraryArtifacts we produce
    // for java_import rule. We should consider supporting
    //    library=<collection of jars>+<collection of srcjars>
    // mode in our AndroidStudio plugin (Android Studio itself supports that).
    Artifact sourceJar;
    if (sourceJarsForJarFiles.size() > 0) {
      sourceJar = sourceJarsForJarFiles.iterator().next();
    } else {
      sourceJar = null;
    }

    for (Artifact artifact : provider.getJarFiles()) {
      LibraryArtifact.Builder libraryBuilder = LibraryArtifact.newBuilder();
      libraryBuilder.setJar(makeArtifactLocation(artifact));
      if (sourceJar != null) {
        libraryBuilder.setSourceJar(makeArtifactLocation(sourceJar));
      }
      builder.addJars(libraryBuilder.build());
    }
  }

  private static void collectJarsFromOutputJarsProvider(
      JavaRuleIdeInfo.Builder builder, JavaRuleOutputJarsProvider outputJarsProvider) {
    LibraryArtifact.Builder jarsBuilder = LibraryArtifact.newBuilder();
    Artifact classJar = outputJarsProvider.getClassJar();
    if (classJar != null) {
      jarsBuilder.setJar(makeArtifactLocation(classJar));
    }
    Artifact srcJar = outputJarsProvider.getSrcJar();
    if (srcJar != null) {
      jarsBuilder.setSourceJar(makeArtifactLocation(srcJar));
    }
    if (jarsBuilder.hasJar() || jarsBuilder.hasSourceJar()) {
      builder.addJars(jarsBuilder.build());
    }


    LibraryArtifact.Builder genjarsBuilder = LibraryArtifact.newBuilder();

    Artifact genClassJar = outputJarsProvider.getGenClassJar();
    if (genClassJar != null) {
      genjarsBuilder.setJar(makeArtifactLocation(genClassJar));
    }
    Artifact gensrcJar = outputJarsProvider.getGensrcJar();
    if (gensrcJar != null) {
      genjarsBuilder.setSourceJar(makeArtifactLocation(gensrcJar));
    }
    if (genjarsBuilder.hasJar() || genjarsBuilder.hasSourceJar()) {
      builder.addGeneratedJars(genjarsBuilder.build());
    }
  }

  private static Collection<Artifact> getSources(ConfiguredTarget base) {
    // Calculate source files.
    JavaSourceInfoProvider sourceInfoProvider = base.getProvider(JavaSourceInfoProvider.class);
    return sourceInfoProvider != null
        ? sourceInfoProvider.getSourceFiles()
        : ImmutableList.<Artifact>of();
  }

  private PathFragment getOutputFilePath(ConfiguredTarget base, RuleContext ruleContext) {
    PathFragment packagePathFragment =
        ruleContext.getLabel().getPackageIdentifier().getPathFragment();
    String name = base.getLabel().getName();
    return new PathFragment(packagePathFragment, new PathFragment(name + ASWB_BUILD_SUFFIX));
  }

  private RuleIdeInfo.Kind getRuleKind(Rule rule, ConfiguredTarget base) {
    switch (rule.getRuleClassObject().getName()) {
      case "java_library":
        return Kind.JAVA_LIBRARY;
      case "java_import":
        return Kind.JAVA_IMPORT;
      case "java_test":
        return Kind.JAVA_TEST;
      case "java_binary":
        return Kind.JAVA_BINARY;
      case "android_library":
        return Kind.ANDROID_LIBRARY;
      case "android_binary":
        return Kind.ANDROID_BINARY;
      default:
        {
          if (base.getProvider(AndroidSdkProvider.class) != null) {
            return RuleIdeInfo.Kind.ANDROID_SDK;
          } else {
            return RuleIdeInfo.Kind.UNRECOGNIZED;
          }
        }
    }
  }
}
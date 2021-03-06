// Copyright 2014 The Bazel Authors. All rights reserved.
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
import static com.google.devtools.build.lib.packages.Attribute.ConfigurationTransition.HOST;
import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.BuildType.LABEL;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.io.ByteSource;
import com.google.devtools.build.lib.Constants;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ParameterFile.ParameterFileType;
import com.google.devtools.build.lib.actions.Root;
import com.google.devtools.build.lib.analysis.AnalysisUtils;
import com.google.devtools.build.lib.analysis.ConfiguredAspect;
import com.google.devtools.build.lib.analysis.ConfiguredAspect.Builder;
import com.google.devtools.build.lib.analysis.ConfiguredNativeAspectFactory;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.actions.BinaryFileWriteAction;
import com.google.devtools.build.lib.analysis.actions.CustomCommandLine;
import com.google.devtools.build.lib.analysis.actions.FileWriteAction;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.ideinfo.androidstudio.AndroidStudioIdeInfo.AndroidRuleIdeInfo;
import com.google.devtools.build.lib.ideinfo.androidstudio.AndroidStudioIdeInfo.ArtifactLocation;
import com.google.devtools.build.lib.ideinfo.androidstudio.AndroidStudioIdeInfo.CRuleIdeInfo;
import com.google.devtools.build.lib.ideinfo.androidstudio.AndroidStudioIdeInfo.CToolchainIdeInfo;
import com.google.devtools.build.lib.ideinfo.androidstudio.AndroidStudioIdeInfo.JavaRuleIdeInfo;
import com.google.devtools.build.lib.ideinfo.androidstudio.AndroidStudioIdeInfo.LibraryArtifact;
import com.google.devtools.build.lib.ideinfo.androidstudio.AndroidStudioIdeInfo.RuleIdeInfo;
import com.google.devtools.build.lib.ideinfo.androidstudio.AndroidStudioIdeInfo.RuleIdeInfo.Kind;
import com.google.devtools.build.lib.packages.AspectDefinition;
import com.google.devtools.build.lib.packages.AspectParameters;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.rules.android.AndroidIdeInfoProvider;
import com.google.devtools.build.lib.rules.android.AndroidIdeInfoProvider.SourceDirectory;
import com.google.devtools.build.lib.rules.android.AndroidSdkProvider;
import com.google.devtools.build.lib.rules.cpp.CppCompilationContext;
import com.google.devtools.build.lib.rules.cpp.CppConfiguration;
import com.google.devtools.build.lib.rules.java.JavaExportsProvider;
import com.google.devtools.build.lib.rules.java.JavaGenJarsProvider;
import com.google.devtools.build.lib.rules.java.JavaRuleOutputJarsProvider;
import com.google.devtools.build.lib.rules.java.JavaRuleOutputJarsProvider.OutputJar;
import com.google.devtools.build.lib.rules.java.JavaSourceInfoProvider;
import com.google.devtools.build.lib.syntax.Type;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.protobuf.MessageLite;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;


/**
 * Generates ide-build information for Android Studio.
 */
public class AndroidStudioInfoAspect implements ConfiguredNativeAspectFactory {
  public static final String NAME = "AndroidStudioInfoAspect";

  // Output groups.

  public static final String IDE_INFO = "ide-info";
  public static final String IDE_INFO_TEXT = "ide-info-text";
  public static final String IDE_RESOLVE = "ide-resolve";

  private static class PrerequisiteAttr {
    public final String name;
    public final Type<?> type;
    public PrerequisiteAttr(String name, Type<?> type) {
      this.name = name;
      this.type = type;
    }
  }
  public static final PrerequisiteAttr[] PREREQUISITE_ATTRS = {
      new PrerequisiteAttr("deps", BuildType.LABEL_LIST),
      new PrerequisiteAttr("exports", BuildType.LABEL_LIST),
      new PrerequisiteAttr("$robolectric", BuildType.LABEL_LIST), // From android_robolectric_test
      new PrerequisiteAttr("$junit", BuildType.LABEL), // From android_robolectric_test
      new PrerequisiteAttr("binary_under_test", BuildType.LABEL), // From android_test
      new PrerequisiteAttr("java_lib", BuildType.LABEL), // From proto_library
      new PrerequisiteAttr("$proto1_java_lib", BuildType.LABEL), // From proto_library
      new PrerequisiteAttr(":cc_toolchain", BuildType.LABEL) // from cc_* rules
  };

  // File suffixes.
  public static final String ASWB_BUILD_SUFFIX = ".aswb-build";
  public static final String ASWB_BUILD_TEXT_SUFFIX = ".aswb-build.txt";
  public static final Function<Label, String> LABEL_TO_STRING = new Function<Label, String>() {
    @Nullable
    @Override
    public String apply(Label label) {
      return label.toString();
    }
  };

  /** White-list for rules potentially having .java srcs */
  private static final Set<Kind> JAVA_SRC_RULES = ImmutableSet.of(
      Kind.JAVA_LIBRARY,
      Kind.JAVA_TEST,
      Kind.JAVA_BINARY,
      Kind.ANDROID_LIBRARY,
      Kind.ANDROID_BINARY,
      Kind.ANDROID_TEST,
      Kind.ANDROID_ROBOELECTRIC_TEST,
      Kind.JAVA_PLUGIN);

  // Rules that can output jars
  private static final Set<Kind> JAVA_OUTPUT_RULES = ImmutableSet.of(
      Kind.JAVA_LIBRARY,
      Kind.JAVA_IMPORT,
      Kind.JAVA_TEST,
      Kind.JAVA_BINARY,
      Kind.ANDROID_LIBRARY,
      Kind.ANDROID_BINARY,
      Kind.ANDROID_TEST,
      Kind.ANDROID_ROBOELECTRIC_TEST,
      Kind.PROTO_LIBRARY,
      Kind.JAVA_PLUGIN,
      Kind.JAVA_WRAP_CC
  );

  private static final Set<Kind> CC_RULES = ImmutableSet.of(
      Kind.CC_BINARY,
      Kind.CC_LIBRARY,
      Kind.CC_TEST,
      Kind.CC_INC_LIBRARY
  );

  private static final Set<Kind> ANDROID_RULES = ImmutableSet.of(
      Kind.ANDROID_LIBRARY,
      Kind.ANDROID_BINARY,
      Kind.ANDROID_TEST,
      Kind.ANDROID_RESOURCES
  );

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
      case "android_test":
        return Kind.ANDROID_TEST;
      case "android_robolectric_test":
        return Kind.ANDROID_ROBOELECTRIC_TEST;
      case "proto_library":
        return Kind.PROTO_LIBRARY;
      case "java_plugin":
        return Kind.JAVA_PLUGIN;
      case "android_resources":
        return Kind.ANDROID_RESOURCES;
      case "cc_library":
        return Kind.CC_LIBRARY;
      case "cc_binary":
        return Kind.CC_BINARY;
      case "cc_test":
        return Kind.CC_TEST;
      case "cc_inc_library":
        return Kind.CC_INC_LIBRARY;
      case "cc_toolchain":
        return Kind.CC_TOOLCHAIN;
      case "java_wrap_cc":
        return Kind.JAVA_WRAP_CC;
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

  @Override
  public AspectDefinition getDefinition(AspectParameters aspectParameters) {
    AspectDefinition.Builder builder = new AspectDefinition.Builder(NAME)
        .attributeAspect("runtime_deps", AndroidStudioInfoAspect.class)
        .attributeAspect("resources", AndroidStudioInfoAspect.class)
        .add(attr("$packageParser", LABEL).cfg(HOST).exec()
            .value(Label.parseAbsoluteUnchecked(
                Constants.TOOLS_REPOSITORY + "//tools/android:PackageParser")));

    for (PrerequisiteAttr prerequisiteAttr : PREREQUISITE_ATTRS) {
      builder.attributeAspect(prerequisiteAttr.name, AndroidStudioInfoAspect.class);
    }

    return builder.build();
  }

  @Override
  public ConfiguredAspect create(
      ConfiguredTarget base, RuleContext ruleContext, AspectParameters parameters) {
    ConfiguredAspect.Builder builder = new Builder(NAME, ruleContext);

    AndroidStudioInfoFilesProvider.Builder providerBuilder =
        new AndroidStudioInfoFilesProvider.Builder();

    RuleIdeInfo.Kind ruleKind = getRuleKind(ruleContext.getRule(), base);

    DependenciesResult dependenciesResult = processDependencies(
        base, ruleContext, providerBuilder, ruleKind);

    AndroidStudioInfoFilesProvider provider;
    if (ruleKind != RuleIdeInfo.Kind.UNRECOGNIZED) {
      provider =
          createIdeBuildArtifact(
              base,
              ruleContext,
              ruleKind,
              dependenciesResult,
              providerBuilder);
    } else {
      provider = providerBuilder.build();
    }

    builder
        .addOutputGroup(IDE_INFO, provider.getIdeInfoFiles())
        .addOutputGroup(IDE_INFO_TEXT, provider.getIdeInfoTextFiles())
        .addOutputGroup(IDE_RESOLVE, provider.getIdeResolveFiles())
        .addProvider(
            AndroidStudioInfoFilesProvider.class,
            provider);

    return builder.build();
  }

  private static class DependenciesResult {
    private DependenciesResult(Iterable<Label> deps,
        Iterable<Label> runtimeDeps, @Nullable Label resources) {
      this.deps = deps;
      this.runtimeDeps = runtimeDeps;
      this.resources = resources;
    }
    final Iterable<Label> deps;
    final Iterable<Label> runtimeDeps;
    @Nullable final Label resources;
  }

  private DependenciesResult processDependencies(
      ConfiguredTarget base, RuleContext ruleContext,
      AndroidStudioInfoFilesProvider.Builder providerBuilder, RuleIdeInfo.Kind ruleKind) {

    // Calculate direct dependencies
    ImmutableList.Builder<TransitiveInfoCollection> directDepsBuilder = ImmutableList.builder();
    for (PrerequisiteAttr prerequisiteAttr : PREREQUISITE_ATTRS) {
      if (ruleContext.attributes().has(prerequisiteAttr.name, prerequisiteAttr.type)) {
        directDepsBuilder.addAll(ruleContext.getPrerequisites(prerequisiteAttr.name, Mode.TARGET));
      }
    }
    List<TransitiveInfoCollection> directDeps = directDepsBuilder.build();

    // Add exports from direct dependencies
    NestedSetBuilder<Label> dependenciesBuilder = NestedSetBuilder.stableOrder();
    for (AndroidStudioInfoFilesProvider depProvider :
        AnalysisUtils.getProviders(directDeps, AndroidStudioInfoFilesProvider.class)) {
      dependenciesBuilder.addTransitive(depProvider.getExportedDeps());
    }
    for (TransitiveInfoCollection dep : directDeps) {
      dependenciesBuilder.add(dep.getLabel());
    }
    NestedSet<Label> dependencies = dependenciesBuilder.build();

    // Propagate my own exports
    JavaExportsProvider javaExportsProvider = base.getProvider(JavaExportsProvider.class);
    if (javaExportsProvider != null) {
      providerBuilder.exportedDepsBuilder()
          .addTransitive(javaExportsProvider.getTransitiveExports());
    }
    // android_library without sources exports all its deps
    if (ruleKind == Kind.ANDROID_LIBRARY) {
      JavaSourceInfoProvider sourceInfoProvider = base.getProvider(JavaSourceInfoProvider.class);
      boolean hasSources = sourceInfoProvider != null
          && !sourceInfoProvider.getSourceFiles().isEmpty();
      if (!hasSources) {
        for (TransitiveInfoCollection dep : directDeps) {
          providerBuilder.exportedDepsBuilder().add(dep.getLabel());
        }
      }
    }

    // runtime_deps
    List<? extends TransitiveInfoCollection> runtimeDeps = ImmutableList.of();
    NestedSetBuilder<Label> runtimeDepsBuilder = NestedSetBuilder.stableOrder();
    if (ruleContext.attributes().has("runtime_deps", BuildType.LABEL_LIST)) {
      runtimeDeps = ruleContext.getPrerequisites("runtime_deps", Mode.TARGET);
      for (TransitiveInfoCollection dep : runtimeDeps) {
        runtimeDepsBuilder.add(dep.getLabel());
      }
    }

    // resources
    @Nullable TransitiveInfoCollection resources =
        ruleContext.attributes().has("resources", BuildType.LABEL)
            ? ruleContext.getPrerequisite("resources", Mode.TARGET)
            : null;

    // Propagate providers from all prerequisites (deps + runtime_deps)
    ImmutableList.Builder<TransitiveInfoCollection> prerequisitesBuilder = ImmutableList.builder();
    prerequisitesBuilder.addAll(directDeps);
    prerequisitesBuilder.addAll(runtimeDeps);
    if (resources != null) {
      prerequisitesBuilder.add(resources);
    }

    List<TransitiveInfoCollection> prerequisites = prerequisitesBuilder.build();

    for (AndroidStudioInfoFilesProvider depProvider :
        AnalysisUtils.getProviders(prerequisites, AndroidStudioInfoFilesProvider.class)) {
      providerBuilder.ideInfoFilesBuilder().addTransitive(depProvider.getIdeInfoFiles());
      providerBuilder.ideInfoTextFilesBuilder().addTransitive(depProvider.getIdeInfoTextFiles());
      providerBuilder.ideResolveFilesBuilder().addTransitive(depProvider.getIdeResolveFiles());
    }


    return new DependenciesResult(
        dependencies,
        runtimeDepsBuilder.build(),
        resources != null ? resources.getLabel() : null);
  }

  private AndroidStudioInfoFilesProvider createIdeBuildArtifact(
      ConfiguredTarget base,
      RuleContext ruleContext,
      Kind ruleKind,
      DependenciesResult dependenciesResult,
      AndroidStudioInfoFilesProvider.Builder providerBuilder) {

    Artifact ideInfoFile = derivedArtifact(base, ruleContext, ASWB_BUILD_SUFFIX);
    Artifact ideInfoTextFile = derivedArtifact(base, ruleContext, ASWB_BUILD_TEXT_SUFFIX);
    Artifact packageManifest = createPackageManifest(base, ruleContext, ruleKind);
    providerBuilder.ideInfoFilesBuilder().add(ideInfoFile);
    providerBuilder.ideInfoTextFilesBuilder().add(ideInfoTextFile);
    if (packageManifest != null) {
      providerBuilder.ideInfoFilesBuilder().add(packageManifest);
    }
    NestedSetBuilder<Artifact> ideResolveArtifacts = providerBuilder.ideResolveFilesBuilder();

    RuleIdeInfo.Builder outputBuilder = RuleIdeInfo.newBuilder();

    outputBuilder.setLabel(base.getLabel().toString());

    outputBuilder.setBuildFile(
        ruleContext
            .getRule()
            .getPackage()
            .getBuildFile()
            .getPath()
            .toString());

    outputBuilder.setBuildFileArtifactLocation(
        makeArtifactLocation(ruleContext.getRule().getPackage()));

    outputBuilder.setKind(ruleKind);

    if (JAVA_OUTPUT_RULES.contains(ruleKind)) {
      JavaRuleIdeInfo javaRuleIdeInfo = makeJavaRuleIdeInfo(
          base, ruleContext, ideResolveArtifacts, packageManifest);
      outputBuilder.setJavaRuleIdeInfo(javaRuleIdeInfo);
    }
    if (CC_RULES.contains(ruleKind)) {
      CRuleIdeInfo cRuleIdeInfo = makeCRuleIdeInfo(base, ruleContext);
      outputBuilder.setCRuleIdeInfo(cRuleIdeInfo);
    }
    if (ruleKind == Kind.CC_TOOLCHAIN) {
      CToolchainIdeInfo cToolchainIdeInfo = makeCToolchainIdeInfo(base, ruleContext);
      if (cToolchainIdeInfo != null) {
        outputBuilder.setCToolchainIdeInfo(cToolchainIdeInfo);
      }
    }
    if (ANDROID_RULES.contains(ruleKind)) {
      outputBuilder.setAndroidRuleIdeInfo(makeAndroidRuleIdeInfo(base,
          dependenciesResult, ideResolveArtifacts));
    }

    AndroidStudioInfoFilesProvider provider = providerBuilder.build();

    outputBuilder.addAllDependencies(transform(dependenciesResult.deps, LABEL_TO_STRING));
    outputBuilder.addAllRuntimeDeps(transform(dependenciesResult.runtimeDeps, LABEL_TO_STRING));
    outputBuilder.addAllTags(base.getTarget().getAssociatedRule().getRuleTags());

    final RuleIdeInfo ruleIdeInfo = outputBuilder.build();

    ruleContext.registerAction(
        makeProtoWriteAction(ruleContext.getActionOwner(), ruleIdeInfo, ideInfoFile));
    ruleContext.registerAction(
        makeProtoTextWriteAction(ruleContext.getActionOwner(), ruleIdeInfo, ideInfoTextFile));
    if (packageManifest != null) {
      ruleContext.registerAction(
          makePackageManifestAction(ruleContext, packageManifest, getJavaSources(ruleContext))
      );
    }

    return provider;
  }

  @Nullable private static Artifact createPackageManifest(ConfiguredTarget base,
      RuleContext ruleContext, Kind ruleKind) {
    if (!JAVA_SRC_RULES.contains(ruleKind)) {
      return null;
    }
    Collection<Artifact> sourceFiles = getJavaSources(ruleContext);
    if (sourceFiles.isEmpty()) {
      return null;
    }
    return derivedArtifact(base, ruleContext, ".manifest");
  }

  private static Action[] makePackageManifestAction(
      RuleContext ruleContext,
      Artifact packageManifest,
      Collection<Artifact> sourceFiles) {

    return new SpawnAction.Builder()
        .addInputs(sourceFiles)
        .addOutput(packageManifest)
        .setExecutable(ruleContext.getExecutablePrerequisite("$packageParser", Mode.HOST))
        .setCommandLine(CustomCommandLine.builder()
            .addExecPath("--output_manifest", packageManifest)
            .addJoinStrings("--sources", ":", toSerializedArtifactLocations(sourceFiles))
            .build())
        .useParameterFile(ParameterFileType.SHELL_QUOTED)
        .setProgressMessage("Parsing java package strings for " + ruleContext.getRule())
        .setMnemonic("JavaPackageManifest")
        .build(ruleContext);
  }

  private static Iterable<String> toSerializedArtifactLocations(Iterable<Artifact> artifacts) {
    return Iterables.transform(
        Iterables.filter(artifacts, Artifact.MIDDLEMAN_FILTER),
        PACKAGE_PARSER_SERIALIZER);
  }

  private static final Function<Artifact, String> PACKAGE_PARSER_SERIALIZER =
      new Function<Artifact, String>() {
        @Override
        public String apply(Artifact artifact) {
          ArtifactLocation location = makeArtifactLocation(artifact);
          return Joiner.on(",").join(
              location.getRootExecutionPathFragment(),
              location.getRelativePath(),
              location.getRootPath()
          );
        }
      };

  private static Artifact derivedArtifact(ConfiguredTarget base, RuleContext ruleContext,
      String suffix) {
    BuildConfiguration configuration = ruleContext.getConfiguration();
    assert configuration != null;
    Root genfilesDirectory = configuration.getGenfilesDirectory();

    PathFragment derivedFilePath =
        getOutputFilePath(base, ruleContext, suffix);

    return ruleContext.getAnalysisEnvironment().getDerivedArtifact(
        derivedFilePath, genfilesDirectory);
  }

  private static AndroidRuleIdeInfo makeAndroidRuleIdeInfo(
      ConfiguredTarget base,
      DependenciesResult dependenciesResult,
      NestedSetBuilder<Artifact> ideResolveArtifacts) {
    AndroidRuleIdeInfo.Builder builder = AndroidRuleIdeInfo.newBuilder();
    AndroidIdeInfoProvider provider = base.getProvider(AndroidIdeInfoProvider.class);
    assert provider != null;
    if (provider.getSignedApk() != null) {
      builder.setApk(makeArtifactLocation(provider.getSignedApk()));
    }

    Artifact manifest = provider.getManifest();
    if (manifest != null) {
      builder.setManifest(makeArtifactLocation(manifest));
      addResolveArtifact(ideResolveArtifacts, manifest);
    }

    for (Artifact artifact : provider.getApksUnderTest()) {
      builder.addDependencyApk(makeArtifactLocation(artifact));
    }
    for (SourceDirectory resourceDir : provider.getResourceDirs()) {
      ArtifactLocation artifactLocation = makeArtifactLocation(resourceDir);
      builder.addResources(artifactLocation);
    }

    if (provider.getJavaPackage() != null) {
      builder.setJavaPackage(provider.getJavaPackage());
    }

    boolean hasIdlSources = !provider.getIdlSrcs().isEmpty();
    builder.setHasIdlSources(hasIdlSources);
    if (hasIdlSources) {
      LibraryArtifact idlLibraryArtifact = makeLibraryArtifact(ideResolveArtifacts,
          provider.getIdlClassJar(), null, provider.getIdlSourceJar());
      if (idlLibraryArtifact != null) {
        builder.setIdlJar(idlLibraryArtifact);
      }
    }

    builder.setGenerateResourceClass(provider.definesAndroidResources());

    if (dependenciesResult.resources != null) {
      builder.setLegacyResources(dependenciesResult.resources.toString());
    }

    return builder.build();
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

  private static FileWriteAction makeProtoTextWriteAction(
      ActionOwner actionOwner, final MessageLite message, Artifact artifact) {
    return new FileWriteAction(
        actionOwner,
        artifact,
        message.toString(),
        /*makeExecutable =*/ false);
  }

  private static ArtifactLocation makeArtifactLocation(Artifact artifact) {
    return makeArtifactLocation(artifact.getRoot(), artifact.getRootRelativePath());
  }

  private static ArtifactLocation makeArtifactLocation(Package pkg) {
    Root root = Root.asSourceRoot(pkg.getSourceRoot());
    PathFragment relativePath = pkg.getBuildFile().getPath().relativeTo(root.getPath());
    return makeArtifactLocation(root, relativePath);
  }

  private static ArtifactLocation makeArtifactLocation(Root root, PathFragment relativePath) {
    return ArtifactLocation.newBuilder()
        .setRootPath(root.getPath().toString())
        .setRootExecutionPathFragment(root.getExecPath().toString())
        .setRelativePath(relativePath.toString())
        .setIsSource(root.isSourceRoot())
        .build();
  }

  private static ArtifactLocation makeArtifactLocation(SourceDirectory resourceDir) {
    return ArtifactLocation.newBuilder()
        .setRootPath(resourceDir.getRootPath().toString())
        .setRootExecutionPathFragment(resourceDir.getRootExecutionPathFragment().toString())
        .setRelativePath(resourceDir.getRelativePath().toString())
        .setIsSource(resourceDir.isSource())
        .build();
  }

  private static JavaRuleIdeInfo makeJavaRuleIdeInfo(
      ConfiguredTarget base,
      RuleContext ruleContext,
      NestedSetBuilder<Artifact> ideResolveArtifacts,
      @Nullable Artifact packageManifest) {
    JavaRuleIdeInfo.Builder builder = JavaRuleIdeInfo.newBuilder();
    JavaRuleOutputJarsProvider outputJarsProvider =
        base.getProvider(JavaRuleOutputJarsProvider.class);
    if (outputJarsProvider != null) {
      // java_library
      collectJarsFromOutputJarsProvider(builder, ideResolveArtifacts, outputJarsProvider);

      Artifact jdeps = outputJarsProvider.getJdeps();
      if (jdeps != null) {
        builder.setJdeps(makeArtifactLocation(jdeps));
      }
    }

    JavaGenJarsProvider genJarsProvider =
        base.getProvider(JavaGenJarsProvider.class);
    if (genJarsProvider != null) {
      collectGenJars(builder, ideResolveArtifacts, genJarsProvider);
    }

    Collection<Artifact> sourceFiles = getSources(ruleContext);

    for (Artifact sourceFile : sourceFiles) {
      builder.addSources(makeArtifactLocation(sourceFile));
    }

    if (packageManifest != null) {
      builder.setPackageManifest(makeArtifactLocation(packageManifest));
    }

    return builder.build();
  }

  private static CRuleIdeInfo makeCRuleIdeInfo(ConfiguredTarget base, RuleContext ruleContext) {
    CRuleIdeInfo.Builder builder = CRuleIdeInfo.newBuilder();

    Collection<Artifact> sourceFiles = getSources(ruleContext);
    for (Artifact sourceFile : sourceFiles) {
      builder.addSource(makeArtifactLocation(sourceFile));
    }

    Collection<Artifact> exportedHeaderFiles = getExportedHeaders(ruleContext);
    for (Artifact exportedHeaderFile : exportedHeaderFiles) {
      builder.addExportedHeader(makeArtifactLocation(exportedHeaderFile));
    }

    builder.addAllRuleInclude(getIncludes(ruleContext));
    builder.addAllRuleDefine(getDefines(ruleContext));
    builder.addAllRuleCopt(getCopts(ruleContext));

    CppCompilationContext cppCompilationContext = base.getProvider(CppCompilationContext.class);
    if (cppCompilationContext != null) {
      // Get information about from the transitive closure
      ImmutableList<PathFragment> transitiveIncludeDirectories =
          cppCompilationContext.getIncludeDirs();
      for (PathFragment pathFragment : transitiveIncludeDirectories) {
        builder.addTransitiveIncludeDirectory(pathFragment.getSafePathString());
      }
      ImmutableList<PathFragment> transitiveQuoteIncludeDirectories =
          cppCompilationContext.getQuoteIncludeDirs();
      for (PathFragment pathFragment : transitiveQuoteIncludeDirectories) {
        builder.addTransitiveQuoteIncludeDirectory(pathFragment.getSafePathString());
      }
      ImmutableList<String> transitiveDefines = cppCompilationContext.getDefines();
      for (String transitiveDefine : transitiveDefines) {
        builder.addTransitiveDefine(transitiveDefine);
      }
      ImmutableList<PathFragment> transitiveSystemIncludeDirectories =
          cppCompilationContext.getSystemIncludeDirs();
      for (PathFragment pathFragment : transitiveSystemIncludeDirectories) {
        builder.addTransitiveSystemIncludeDirectory(pathFragment.getSafePathString());
      }
    }

    return builder.build();
  }

  @Nullable
  private static CToolchainIdeInfo makeCToolchainIdeInfo(ConfiguredTarget base,
      RuleContext ruleContext) {
    BuildConfiguration configuration = base.getConfiguration();
    if (configuration != null) {
      CppConfiguration cppConfiguration = configuration.getFragment(CppConfiguration.class);
      if (cppConfiguration != null) {
        CToolchainIdeInfo.Builder builder = CToolchainIdeInfo.newBuilder();
        ImmutableSet<String> features = ruleContext.getFeatures();
        builder.setTargetName(cppConfiguration.getTargetGnuSystemName());

        builder.addAllBaseCompilerOption(cppConfiguration.getCompilerOptions(features));
        builder.addAllCOption(cppConfiguration.getCOptions());
        builder.addAllCppOption(cppConfiguration.getCxxOptions(features));
        builder.addAllLinkOption(cppConfiguration.getLinkOptions());

        // This includes options such as system includes from toolchains.
        builder.addAllUnfilteredCompilerOption(
            cppConfiguration.getUnfilteredCompilerOptions(features));

        builder.setPreprocessorExecutable(
            cppConfiguration.getCpreprocessorExecutable().getSafePathString());
        builder.setCppExecutable(cppConfiguration.getCppExecutable().getSafePathString());

        List<PathFragment> builtInIncludeDirectories = cppConfiguration
            .getBuiltInIncludeDirectories();
        for (PathFragment builtInIncludeDirectory : builtInIncludeDirectories) {
          builder.addBuiltInIncludeDirectory(builtInIncludeDirectory.getSafePathString());
        }
        return builder.build();
      }
    }

    return null;
  }

  private static void collectJarsFromOutputJarsProvider(
      JavaRuleIdeInfo.Builder builder,
      NestedSetBuilder<Artifact> ideResolveArtifacts,
      JavaRuleOutputJarsProvider outputJarsProvider) {
    for (OutputJar outputJar : outputJarsProvider.getOutputJars()) {
      LibraryArtifact libraryArtifact = makeLibraryArtifact(ideResolveArtifacts,
          outputJar.getClassJar(), outputJar.getIJar(), outputJar.getSrcJar());

      if (libraryArtifact != null) {
        builder.addJars(libraryArtifact);
      }
    }
  }

  @Nullable
  private static LibraryArtifact makeLibraryArtifact(
      NestedSetBuilder<Artifact> ideResolveArtifacts,
      @Nullable Artifact classJar,
      @Nullable Artifact iJar,
      @Nullable Artifact sourceJar
      ) {
    // We don't want to add anything that doesn't have a class jar
    if (classJar == null) {
      return null;
    }
    LibraryArtifact.Builder jarsBuilder = LibraryArtifact.newBuilder();
    jarsBuilder.setJar(makeArtifactLocation(classJar));
    addResolveArtifact(ideResolveArtifacts, classJar);

    if (iJar != null) {
      jarsBuilder.setInterfaceJar(makeArtifactLocation(iJar));
      addResolveArtifact(ideResolveArtifacts, iJar);
    }
    if (sourceJar != null) {
      jarsBuilder.setSourceJar(makeArtifactLocation(sourceJar));
      addResolveArtifact(ideResolveArtifacts, sourceJar);
    }

    return jarsBuilder.build();
  }

  private static void collectGenJars(
      JavaRuleIdeInfo.Builder builder,
      NestedSetBuilder<Artifact> ideResolveArtifacts,
      JavaGenJarsProvider genJarsProvider) {
    LibraryArtifact.Builder genjarsBuilder = LibraryArtifact.newBuilder();

    if (genJarsProvider.usesAnnotationProcessing()) {
      Artifact genClassJar = genJarsProvider.getGenClassJar();
      if (genClassJar != null) {
        genjarsBuilder.setJar(makeArtifactLocation(genClassJar));
        addResolveArtifact(ideResolveArtifacts, genClassJar);
      }
      Artifact gensrcJar = genJarsProvider.getGenSourceJar();
      if (gensrcJar != null) {
        genjarsBuilder.setSourceJar(makeArtifactLocation(gensrcJar));
        addResolveArtifact(ideResolveArtifacts, gensrcJar);
      }
      if (genjarsBuilder.hasJar()) {
        builder.addGeneratedJars(genjarsBuilder.build());
      }
    }
  }

  private static Collection<Artifact> getJavaSources(RuleContext ruleContext) {
    Collection<Artifact> srcs = getSources(ruleContext);
    List<Artifact> javaSrcs = Lists.newArrayList();
    for (Artifact src : srcs) {
      if (src.getRootRelativePathString().endsWith(".java")) {
        javaSrcs.add(src);
      }
    }
    return javaSrcs;
  }

  private static Collection<Artifact> getSources(RuleContext ruleContext) {
    return ruleContext.attributes().has("srcs", BuildType.LABEL_LIST)
        ? ruleContext.getPrerequisiteArtifacts("srcs", Mode.TARGET).list()
        : ImmutableList.<Artifact>of();
  }

  private static Collection<Artifact> getExportedHeaders(RuleContext ruleContext) {
    return ruleContext.attributes().has("hdrs", BuildType.LABEL_LIST)
        ? ruleContext.getPrerequisiteArtifacts("hdrs", Mode.TARGET).list()
        : ImmutableList.<Artifact>of();
  }

  private static Collection<String> getIncludes(RuleContext ruleContext) {
    return ruleContext.attributes().has("includes", Type.STRING_LIST)
        ? ruleContext.attributes().get("includes", Type.STRING_LIST)
        : ImmutableList.<String>of();
  }

  private static Collection<String> getDefines(RuleContext ruleContext) {
    return ruleContext.attributes().has("defines", Type.STRING_LIST)
        ? ruleContext.attributes().get("defines", Type.STRING_LIST)
        : ImmutableList.<String>of();
  }

  private static Collection<String> getCopts(RuleContext ruleContext) {
    return ruleContext.attributes().has("copts", Type.STRING_LIST)
        ? ruleContext.attributes().get("copts", Type.STRING_LIST)
        : ImmutableList.<String>of();
  }

  private static PathFragment getOutputFilePath(ConfiguredTarget base, RuleContext ruleContext,
      String suffix) {
    PathFragment packagePathFragment =
        ruleContext.getLabel().getPackageIdentifier().getPathFragment();
    String name = base.getLabel().getName();
    return new PathFragment(packagePathFragment, new PathFragment(name + suffix));
  }


  private static void addResolveArtifact(NestedSetBuilder<Artifact> ideResolveArtifacts,
      Artifact artifact) {
    if (!artifact.isSourceArtifact()) {
      ideResolveArtifacts.add(artifact);
    }
  }
}

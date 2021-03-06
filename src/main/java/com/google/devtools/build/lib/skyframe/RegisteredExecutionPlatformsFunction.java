// Copyright 2018 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.skyframe;

import static com.google.common.base.Predicates.equalTo;
import static com.google.common.base.Predicates.not;
import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.PlatformConfiguration;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.analysis.platform.PlatformProviderUtils;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.cmdline.TargetParsingException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.pkgcache.FilteringPolicy;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetFunction.ConfiguredValueCreationException;
import com.google.devtools.build.lib.skyframe.PlatformLookupUtil.InvalidPlatformException;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.ValueOrException;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/** {@link SkyFunction} that returns all registered execution platforms available. */
public class RegisteredExecutionPlatformsFunction implements SkyFunction {

  @Nullable
  @Override
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws SkyFunctionException, InterruptedException {

    BuildConfigurationValue buildConfigurationValue =
        (BuildConfigurationValue)
            env.getValue(((RegisteredExecutionPlatformsValue.Key) skyKey).getConfigurationKey());
    if (env.valuesMissing()) {
      return null;
    }
    BuildConfiguration configuration = buildConfigurationValue.getConfiguration();

    // Get the execution platforms from the configuration.
    PlatformConfiguration platformConfiguration =
        configuration.getFragment(PlatformConfiguration.class);

    // Get the registered execution platforms from the WORKSPACE.
    ImmutableListMultimap<RepositoryName, String> workspaceExecutionPlatforms =
        getWorkspaceExecutionPlatforms(env);
    if (workspaceExecutionPlatforms == null) {
      return null;
    }

    ImmutableListMultimap<RepositoryName, String> targetPatterns =
        mergeExecutionPlatforms(workspaceExecutionPlatforms, platformConfiguration);

    // Expand target patterns.
    ImmutableList<Label> platformLabels;
    try {
      platformLabels =
          TargetPatternUtil.expandTargetPatterns(env, targetPatterns, HasPlatformInfo.create());
      if (env.valuesMissing()) {
        return null;
      }
    } catch (TargetPatternUtil.InvalidTargetPatternException e) {
      throw new RegisteredExecutionPlatformsFunctionException(
          new InvalidExecutionPlatformLabelException(e), Transience.PERSISTENT);
    }

    // Load the configured target for each, and get the declared execution platforms providers.
    ImmutableList<ConfiguredTargetKey> registeredExecutionPlatformKeys =
        configureRegisteredExecutionPlatforms(
            env, configuration, configuration.trimConfigurationsRetroactively(), platformLabels);
    if (env.valuesMissing()) {
      return null;
    }

    return RegisteredExecutionPlatformsValue.create(registeredExecutionPlatformKeys);
  }

  /**
   * Loads the external package and then returns the registered execution platform labels.
   *
   * @param env the environment to use for lookups
   */
  @Nullable
  @VisibleForTesting
  public static ImmutableListMultimap<RepositoryName, String> getWorkspaceExecutionPlatforms(
      Environment env) throws InterruptedException {
    PackageValue externalPackageValue =
        (PackageValue) env.getValue(PackageValue.key(LabelConstants.EXTERNAL_PACKAGE_IDENTIFIER));
    if (externalPackageValue == null) {
      return null;
    }

    Package externalPackage = externalPackageValue.getPackage();
    return externalPackage.getRegisteredExecutionPlatforms();
  }

  private ImmutableListMultimap<RepositoryName, String> mergeExecutionPlatforms(
      ImmutableListMultimap<RepositoryName, String> workspaceExecutionPlatforms,
      @Nullable PlatformConfiguration platformConfiguration) {

    ImmutableListMultimap.Builder<RepositoryName, String> builder = ImmutableListMultimap.builder();
    // This ensures that execution platforms specified via a flag are considered first
    if (platformConfiguration != null) {
      builder.putAll(RepositoryName.MAIN, platformConfiguration.getExtraExecutionPlatforms());
    }
    builder.putAll(workspaceExecutionPlatforms);
    return builder.build();
  }

  private ImmutableList<ConfiguredTargetKey> configureRegisteredExecutionPlatforms(
      Environment env,
      BuildConfiguration configuration,
      boolean sanityCheckConfiguration,
      List<Label> labels)
      throws InterruptedException, RegisteredExecutionPlatformsFunctionException {

    ImmutableList<ConfiguredTargetKey> keys =
        labels.stream()
            .map(label -> ConfiguredTargetKey.of(label, configuration))
            .collect(toImmutableList());

    Map<SkyKey, ValueOrException<ConfiguredValueCreationException>> values =
        env.getValuesOrThrow(keys, ConfiguredValueCreationException.class);
    ImmutableList.Builder<ConfiguredTargetKey> validPlatformKeys = new ImmutableList.Builder<>();
    boolean valuesMissing = false;
    for (ConfiguredTargetKey platformKey : keys) {
      Label platformLabel = platformKey.getLabel();
      try {
        ValueOrException<ConfiguredValueCreationException> valueOrException =
            values.get(platformKey);
        if (valueOrException.get() == null) {
          valuesMissing = true;
          continue;
        }
        ConfiguredTarget target =
            ((ConfiguredTargetValue) valueOrException.get()).getConfiguredTarget();
        // This check is necessary because trimming for other rules assumes that platform resolution
        // uses the platform fragment and _only_ the platform fragment. Without this check, it's
        // possible another fragment could slip in without us realizing, and thus break this
        // assumption.
        if (sanityCheckConfiguration
            && target.getConfigurationKey().getFragments().stream()
                .anyMatch(not(equalTo(PlatformConfiguration.class)))) {
          // Only the PlatformConfiguration fragment may be present on a platform rule in
          // retroactive trimming mode.
          throw new RegisteredExecutionPlatformsFunctionException(
              new InvalidPlatformException(
                  target.getLabel(),
                  "has fragments other than PlatformConfiguration, "
                      + "which is forbidden in retroactive trimming mode"),
              Transience.PERSISTENT);
        }
        PlatformInfo platformInfo = PlatformProviderUtils.platform(target);
        if (platformInfo == null) {
          throw new RegisteredExecutionPlatformsFunctionException(
              new InvalidPlatformException(platformLabel), Transience.PERSISTENT);
        }
        validPlatformKeys.add(platformKey);
      } catch (ConfiguredValueCreationException e) {
        throw new RegisteredExecutionPlatformsFunctionException(
            new InvalidPlatformException(platformLabel, e), Transience.PERSISTENT);
      }
    }

    if (valuesMissing) {
      return null;
    }
    return validPlatformKeys.build();
  }

  @Nullable
  @Override
  public String extractTag(SkyKey skyKey) {
    return null;
  }

  /**
   * Used to indicate that the given {@link Label} represents a {@link ConfiguredTarget} which is
   * not a valid {@link PlatformInfo} provider.
   */
  static final class InvalidExecutionPlatformLabelException extends Exception {

    public InvalidExecutionPlatformLabelException(
        TargetPatternUtil.InvalidTargetPatternException e) {
      this(e.getInvalidPattern(), e.getTpe());
    }

    public InvalidExecutionPlatformLabelException(String invalidPattern, TargetParsingException e) {
      super(
          String.format(
              "invalid registered execution platform '%s': %s", invalidPattern, e.getMessage()),
          e);
    }
  }

  /**
   * Used to declare all the exception types that can be wrapped in the exception thrown by {@link
   * #compute}.
   */
  private static class RegisteredExecutionPlatformsFunctionException extends SkyFunctionException {

    private RegisteredExecutionPlatformsFunctionException(
        InvalidExecutionPlatformLabelException cause, Transience transience) {
      super(cause, transience);
    }

    private RegisteredExecutionPlatformsFunctionException(
        InvalidPlatformException cause, Transience transience) {
      super(cause, transience);
    }
  }

  @AutoValue
  @AutoCodec
  abstract static class HasPlatformInfo extends FilteringPolicy {

    @Override
    public boolean shouldRetain(Target target, boolean explicit) {
      if (explicit) {
        return true;
      }

      // If the rule requires platforms, it can't be used as a platform.
      RuleClass ruleClass = target.getAssociatedRule().getRuleClassObject();
      if (ruleClass == null) {
        return false;
      }

      if (ruleClass.supportsPlatforms()) {
        return false;
      }

      return ruleClass.getAdvertisedProviders().advertises(PlatformInfo.class);
    }

    @AutoCodec.Instantiator
    static HasPlatformInfo create() {
      return new AutoValue_RegisteredExecutionPlatformsFunction_HasPlatformInfo();
    }
  }
}

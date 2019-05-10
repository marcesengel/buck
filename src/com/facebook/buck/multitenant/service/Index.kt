/*
 * Copyright 2019-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.multitenant.service

import com.facebook.buck.core.model.UnconfiguredBuildTarget
import com.facebook.buck.multitenant.collect.DefaultGenerationMap
import com.facebook.buck.multitenant.fs.FsAgnosticPath
import com.google.common.collect.ImmutableSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.annotation.concurrent.GuardedBy
import kotlin.concurrent.withLock

class Index(val buildTargetParser: (target: String) -> UnconfiguredBuildTarget) {
    /**
     * To save space, we pass around ints instead of references to BuildTargets.
     * AppendOnlyBidirectionalCache does its own synchronization, so it does not need to be guarded
     * by rwLock.
     */
    private val buildTargetCache = AppendOnlyBidirectionalCache<UnconfiguredBuildTarget>()

    /**
     * id of the current generation. should only be read and set by [addCommitData] as clients
     * should get generation ids via [commitToGeneration]/[getGeneration].
     */
    private val generation = AtomicInteger()

    /** Stores commit to generation mappings created by [addCommitData]. */
    private val commitToGeneration = ConcurrentHashMap<Commit, Int>()

    /**
     * Access to all of the fields after this one must be guarded by the rwLock.
     * We use a fair lock to prioritize writer threads.
     */
    private val rwLock = ReentrantReadWriteLock(/*fair*/ true)

    /**
     * The key is the path for the directory relative to the Buck root that contains the build file
     * for the corresponding build package.
     */
    @GuardedBy("rwLock")
    private val buildPackageMap = DefaultGenerationMap<FsAgnosticPath, Set<String>, FsAgnosticPath> { it }

    /**
     * Map that captures the value of a build rule at a specific generation, indexed by BuildTarget.
     *
     * We also specify the key as the keyInfo so that we get it back when we use
     * `getAllInfoValuePairsForGeneration()`.
     */
    @GuardedBy("rwLock")
    private val ruleMap = DefaultGenerationMap<BuildTargetId, InternalRawBuildRule, BuildTargetId> { it }

    /**
     * If you need to look up multiple target nodes for the same commit, prefer [getTargetNodes].
     *
     * @return the corresponding [RawBuildRule] at the specified commit, if it exists;
     *     otherwise, return `null`.
     */
    fun getTargetNode(generation: Generation, target: UnconfiguredBuildTarget): RawBuildRule? {
        return getTargetNodes(generation, listOf(target))[0]
    }

    /**
     * @return a list whose entries correspond to the input list of `targets` where each element in
     *     the output is the corresponding target node for the build target at the commit or `null`
     *     if no rule existed for that target at that commit.
     */
    fun getTargetNodes(generation: Generation, targets: List<UnconfiguredBuildTarget>): List<RawBuildRule?> {
        val targetIds = targets.map { buildTargetCache.get(it) }

        // internalRules is a List rather than a Sequence because sequences are lazy and we need to
        // ensure all reads to ruleMap are done while the lock is held.
        val internalRules = rwLock.readLock().withLock {
            targetIds.map { ruleMap.getVersion(it, generation) }.toList()
        }
        // We can release the lock because now we only need access to buildTargetCache, which does
        // not need to be guarded by rwLock.
        return internalRules.map {
            if (it != null) {
                val deps = it.deps.asSequence().map { buildTargetCache.getByIndex(it) }.toSet()
                RawBuildRule(it.targetNode, deps)
            } else {
                null
            }
        }
    }

    /**
     * @return the transitive deps of the specified target (does not include target)
     */
    fun getTransitiveDeps(generation: Generation, target: UnconfiguredBuildTarget): Set<UnconfiguredBuildTarget> {
        val rootBuildTargetId = buildTargetCache.get(target)
        val toVisit = LinkedHashSet<Int>()
        toVisit.add(rootBuildTargetId)
        val visited = mutableSetOf<Int>()

        rwLock.readLock().withLock {
            while (toVisit.isNotEmpty()) {
                val targetId = getFirst(toVisit)
                val node = ruleMap.getVersion(targetId, generation)
                visited.add(targetId)

                if (node == null) {
                    continue
                }

                for (dep in node.deps) {
                    if (!toVisit.contains(dep) && !visited.contains(dep)) {
                        toVisit.add(dep)
                    }
                }
            }
        }

        visited.remove(rootBuildTargetId)
        return visited.asSequence().map { buildTargetCache.getByIndex(it) }.toSet()
    }

    fun getFwdDeps(generation: Generation, targets: Iterable<UnconfiguredBuildTarget>, out: ImmutableSet.Builder<UnconfiguredBuildTarget>) {
        // Compute the list of target ids before taking the lock.
        val targetIds = targets.map { buildTargetCache.get(it) }
        rwLock.readLock().withLock {
            for (targetId in targetIds) {
                val node = ruleMap.getVersion(targetId, generation) ?: continue
                for (dep in node.deps) {
                    out.add(buildTargetCache.getByIndex(dep))
                }
            }
        }
    }

    /**
     * @param generation at which to enumerate all build targets
     */
    fun getTargets(generation: Generation): List<UnconfiguredBuildTarget> {
        val pairs = rwLock.readLock().withLock {
            ruleMap.getEntries(generation)
        }

        // Note that we release the read lock before making a bunch of requests to the
        // buildTargetCache. As this is going to do a LOT of lookups to the buildTargetCache, we
        // should probably see whether we can do some sort of "multi-get" operation that requires
        // less locking, or potentially change the locking strategy for AppendOnlyBidirectionalCache
        // completely so that it is not thread-safe internally, but is guarded by its own lock.
        return pairs.map { buildTargetCache.getByIndex(it.first) }.toList()
    }

    /**
     * Used to match a ":" build target pattern wildcard.
     *
     * @param generation at which to enumerate all build targets under `basePath`
     * @param basePath under which to look. If the query is for `//:`, then `basePath` would be
     *     the empty string. If the query is for `//foo/bar:`, then `basePath` would be
     *     `foo/bar`.
     */
    fun getTargetsInBasePath(generation: Generation, basePath: FsAgnosticPath): List<UnconfiguredBuildTarget> {
        val targetNames = rwLock.readLock().withLock {
            buildPackageMap.getVersion(basePath, generation) ?: return listOf()
        }

        return targetNames.asSequence().map {
            createBuildTarget(basePath, it)
        }.toList()
    }

    /**
     * Used to match a "/..." build target pattern wildcard.
     *
     * @param generation at which to enumerate all build targets under `basePath`
     * @param basePath under which to look. If the query is for `//...`, then `basePath` would be
     *     the empty string. If the query is for `//foo/bar/...`, then `basePath` would be
     *     `foo/bar`.
     */
    fun getTargetsUnderBasePath(generation: Generation, basePath: FsAgnosticPath): List<UnconfiguredBuildTarget> {
        if (basePath.isEmpty()) {
            return getTargets(generation)
        }

        val entries = rwLock.readLock().withLock {
            buildPackageMap.getEntries(generation) { it.startsWith(basePath) }
        }

        return entries.flatMap {
            val basePath = it.first
            val names = it.second
            names.map {
                createBuildTarget(basePath, it)
            }.asSequence()
        }.toList()
    }

    /**
     * @return the generation that corresponds to the specified commit or `null` if no such
     *     generation is available
     */
    fun getGeneration(commit: Commit): Int? {
        return commitToGeneration[commit]
    }

    /**
     * Currently, the caller is responsible for ensuring that addCommitData() is invoked
     * serially (never concurrently) for each commit in a chain of version control history.
     *
     * The expectation is that the caller will use something like `buck audit rules` based on the
     * changes in the commit to produce the Changes object to pass to this method.
     */
    fun addCommitData(commit: Commit, changes: Changes) {
        // Although the first portion of this method requires read-only access to all of the
        // data structures, we want to be sure that only one caller is invoking addCommitData() at a
        // time.

        // First, determine if any of the changes from the commits require new values to be added
        // to the generation map.
        val currentGeneration = generation.get()
        val deltas = determineDeltas(toInternalChanges(changes), currentGeneration)

        // If there are no updates to any of the generation maps, add a new entry for the current
        // commit using the existing generation in the commitToGeneration map.
        if (deltas.isEmpty()) {
            val oldValue = commitToGeneration.putIfAbsent(commit, currentGeneration)
            require(oldValue == null) { "Should not have existing value for $commit" }
            return
        }

        val nextGeneration = currentGeneration + 1

        // If any generation map needs to be updated, grab the write lock, bump the generation for
        // all of the maps, insert all of the new values into the maps, and as a final step, add a
        // new entry to commitToGeneration with the new generation value.
        rwLock.writeLock().withLock {
            for (delta in deltas.buildPackageDeltas) {
                when (delta) {
                    is BuildPackageDelta.Updated -> {
                        buildPackageMap.addVersion(delta.directory, delta.rules, nextGeneration)
                    }
                    is BuildPackageDelta.Removed -> {
                        buildPackageMap.addVersion(delta.directory, null, nextGeneration)
                    }
                }
            }

            for (delta in deltas.ruleDeltas) {
                val buildTarget: UnconfiguredBuildTarget
                val newNodeAndDeps: InternalRawBuildRule?
                when (delta) {
                    is RuleDelta.Updated -> {
                        buildTarget = delta.rule.targetNode.buildTarget
                        newNodeAndDeps = delta.rule
                    }
                    is RuleDelta.Removed -> {
                        buildTarget = delta.buildTarget
                        newNodeAndDeps = null
                    }
                }
                ruleMap.addVersion(buildTargetCache.get(buildTarget), newNodeAndDeps, nextGeneration)
            }
        }

        val oldValue = commitToGeneration.putIfAbsent(commit, nextGeneration)
        require(oldValue == null) { "Should not have existing value for $commit" }
        generation.set(nextGeneration)
    }

    private fun createBuildTarget(buildFileDirectory: FsAgnosticPath, name: String): UnconfiguredBuildTarget {
        return buildTargetParser(String.format("//%s:%s", buildFileDirectory, name))
    }

    private fun determineDeltas(changes: InternalChanges, generation: Generation): Deltas {
        val buildPackageDeltas = mutableListOf<BuildPackageDelta>()
        val ruleDeltas = mutableListOf<RuleDelta>()

        rwLock.readLock().withLock {
            for (added in changes.addedBuildPackages) {
                val oldRules = buildPackageMap.getVersion(added.buildFileDirectory, generation)
                if (oldRules != null) {
                    throw IllegalArgumentException("Build package to add already existed at ${added
                            .buildFileDirectory} for generation $generation")
                }

                val ruleNames = getRuleNames(added.rules)
                buildPackageDeltas.add(BuildPackageDelta.Updated(added.buildFileDirectory, ruleNames))
                for (rule in added.rules) {
                    ruleDeltas.add(RuleDelta.Updated(rule))
                }
            }

            for (removed in changes.removedBuildPackages) {
                val oldRules = requireNotNull(buildPackageMap.getVersion(removed, generation)) {
                    "Build package to remove did not exist at $removed for generation $generation"
                }

                buildPackageDeltas.add(BuildPackageDelta.Removed(removed))
                for (ruleName in oldRules) {
                    val buildTarget = createBuildTarget(removed, ruleName)
                    ruleDeltas.add(RuleDelta.Removed(buildTarget))
                }
            }

            for (modified in changes.modifiedBuildPackages) {
                val oldRuleNames = requireNotNull(buildPackageMap.getVersion(modified
                        .buildFileDirectory,
                        generation)) {
                    "No version found for build file in ${modified.buildFileDirectory} for " +
                            "generation $generation"
                }

                val oldRules = oldRuleNames.asSequence().map { oldRuleName: String ->
                    val buildTarget = createBuildTarget(modified.buildFileDirectory, oldRuleName)
                    requireNotNull(ruleMap.getVersion(buildTargetCache.get(buildTarget),
                            generation)) {
                        "Missing deps for $buildTarget at generation $generation"
                    }
                }.toSet()

                val newRules = modified.rules
                // Compare oldRules and newRules to see whether the build package actually changed.
                // Keep track of the individual rule changes so we need not recompute them later.
                val ruleChanges = diffRules(oldRules, newRules)
                if (ruleChanges.isNotEmpty()) {
                    buildPackageDeltas.add(BuildPackageDelta.Updated(modified
                            .buildFileDirectory, getRuleNames(newRules)))
                    ruleDeltas.addAll(ruleChanges)
                }
            }
        }

        return Deltas(buildPackageDeltas, ruleDeltas)
    }

    private fun toInternalChanges(changes: Changes): InternalChanges {
        return InternalChanges(changes.addedBuildPackages.map { toInternalBuildPackage(it) }.toList(),
                changes.modifiedBuildPackages.map { toInternalBuildPackage(it) }.toList(),
                changes.removedBuildPackages
        )
    }

    private fun toInternalBuildPackage(buildPackage: BuildPackage): InternalBuildPackage {
        return InternalBuildPackage(buildPackage.buildFileDirectory, buildPackage.rules.map { toInternalRawBuildRule(it) }.toSet())
    }

    private fun toInternalRawBuildRule(rawBuildRule: RawBuildRule): InternalRawBuildRule {
        return InternalRawBuildRule(rawBuildRule.targetNode, toBuildTargetSet(rawBuildRule.deps))
    }

    private fun toBuildTargetSet(targets: Set<UnconfiguredBuildTarget>): BuildTargetSet {
        val ids = targets.map { buildTargetCache.get(it) }.toIntArray()
        ids.sort()
        return ids
    }
}

internal data class Deltas(val buildPackageDeltas: List<BuildPackageDelta>,
                           val ruleDeltas: List<RuleDelta>) {
    fun isEmpty(): Boolean {
        return buildPackageDeltas.isEmpty() && ruleDeltas.isEmpty()
    }
}

private fun getRuleNames(rules: Set<InternalRawBuildRule>): Set<String> {
    return rules.asSequence().map { it.targetNode.buildTarget.name }.toSet()
}

/**
 * @param set a non-empty set
 */
private fun <T> getFirst(set: LinkedHashSet<T>): T {
    // There are other ways to do this that seem like they might be cheaper:
    // https://stackoverflow.com/questions/5792596/removing-the-first-object-from-a-set.
    val iterator = set.iterator()
    val value = iterator.next()
    iterator.remove()
    return value
}

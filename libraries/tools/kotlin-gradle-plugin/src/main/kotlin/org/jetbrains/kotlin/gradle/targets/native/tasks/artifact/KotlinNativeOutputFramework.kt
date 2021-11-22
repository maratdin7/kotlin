/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.targets.native.tasks.artifact

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.tasks.TaskProvider
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.jetbrains.kotlin.gradle.plugin.mpp.BitcodeEmbeddingMode
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeOutputKind
import org.jetbrains.kotlin.gradle.plugin.mpp.enabledOnCurrentHost
import org.jetbrains.kotlin.gradle.tasks.dependsOn
import org.jetbrains.kotlin.gradle.tasks.registerTask
import org.jetbrains.kotlin.gradle.utils.lowerCamelCaseName
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.jetbrains.kotlin.konan.target.presetName
import org.jetbrains.kotlin.konan.util.visibleName
import java.io.File

class Framework : KotlinNativeLibraryArtifact() {
    lateinit var target: KonanTarget
    var embedBitcode: BitcodeEmbeddingMode? = null

    private val kind = NativeOutputKind.FRAMEWORK

    override fun validate(project: Project, name: String): Boolean {
        val logger = project.logger
        if (!super.validate(project, name)) return false
        if (!this::target.isInitialized) {
            logger.error("Native library '${name}' wasn't configured because it requires target")
            return false
        }
        if (!kind.availableFor(target)) {
            logger.error("Native library '${name}' wasn't configured because ${kind.description} is not available for ${target.visibleName}")
            return false
        }

        return true
    }

    override fun registerAssembleTask(project: Project, name: String) {
        val resultTask = project.registerTask<Task>(lowerCamelCaseName("assemble", kind.taskNameClassifier, name)) { task ->
            task.group = BasePlugin.BUILD_GROUP
            task.description = "Assemble ${kind.description} '$name'."
            task.enabled = target.enabledOnCurrentHost
        }

        val librariesConfigurationName = project.registerLibsDependencies(target, name, exportDeps)
        val exportConfigurationName = project.registerExportDependencies(target, name, exportDeps)
        modes.forEach { buildType ->
            val targetTask = registerLinkFrameworkTask(
                project,
                name,
                target,
                buildType,
                librariesConfigurationName,
                exportConfigurationName,
                embedBitcode
            )
            resultTask.dependsOn(targetTask)
        }
    }
}

internal fun KotlinNativeLibraryArtifact.registerLinkFrameworkTask(
    project: Project,
    name: String,
    target: KonanTarget,
    buildType: NativeBuildType,
    librariesConfigurationName: String,
    exportConfigurationName: String,
    embedBitcode: BitcodeEmbeddingMode?,
    outDirName: String = "out"
): TaskProvider<KotlinNativeLinkArtifactTask> {
    val kind = NativeOutputKind.FRAMEWORK
    val destinationDir = project.buildDir.resolve("$outDirName/${kind.visibleName}/${target.visibleName}/${buildType.visibleName}")
    val resultTask = project.registerTask<KotlinNativeLinkArtifactTask>(
        lowerCamelCaseName("assemble", buildType.visibleName, kind.taskNameClassifier, name, target.presetName),
        listOf(target, kind.compilerOutputKind)
    ) { task ->
        task.description = "Assemble ${kind.description} '$name' for a target '${target.name}'."
        task.enabled = target.enabledOnCurrentHost && kind.availableFor(target)
        task.baseName = name
        task.destinationDir = destinationDir
        task.optimized = buildType.optimized
        task.debuggable = buildType.debuggable
        task.linkerOptions = linkerOptions
        task.binaryOptions = binaryOptions
        task.isStaticFramework = isStatic
        task.embedBitcode = embedBitcode ?: buildType.embedBitcode(target)
        task.librariesConfiguration = librariesConfigurationName
        task.exportLibrariesConfiguration = exportConfigurationName
        task.kotlinOptions(kotlinOptionsFn)
    }
    project.tasks.named(LifecycleBasePlugin.ASSEMBLE_TASK_NAME).dependsOn(resultTask)
    return resultTask
}
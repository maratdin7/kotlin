/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.konan.blackboxtest

import com.intellij.testFramework.TestDataFile
import org.jetbrains.kotlin.konan.blackboxtest.support.NativeBlackBoxTestSupport
import org.jetbrains.kotlin.konan.blackboxtest.support.TestRun
import org.jetbrains.kotlin.konan.blackboxtest.support.TestRunProvider
import org.jetbrains.kotlin.konan.blackboxtest.support.TestRunTreeNode
import org.jetbrains.kotlin.konan.blackboxtest.support.util.getAbsoluteFile
import org.junit.jupiter.api.DynamicContainer.dynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(NativeBlackBoxTestSupport::class)
abstract class AbstractNativeBlackBoxTest {
    internal lateinit var testRunProvider: TestRunProvider

    /**
     * Run JUnit test.
     *
     * This function should be called from a method annotated with [org.junit.jupiter.api.Test].
     */
    fun runTest(@TestDataFile testDataFilePath: String) {
        val testDataFile = getAbsoluteFile(testDataFilePath)
        val testRun = testRunProvider.getSingleTestRun(testDataFile)
        runTest(testRun)
    }

    /**
     * Run JUnit dynamic test.
     *
     * This function should be called from a method annotated with [org.junit.jupiter.api.TestFactory].
     */
    fun dynamicTest(@TestDataFile testDataFilePath: String): Collection<DynamicNode> {
        val testDataFile = getAbsoluteFile(testDataFilePath)
        val rootTestRunNode = testRunProvider.getTestRuns(testDataFile)
        return buildJUnitDynamicNodes(rootTestRunNode)
    }

    private fun buildJUnitDynamicNodes(testRunNode: TestRunTreeNode): Collection<DynamicNode> = buildList {
        testRunNode.testRuns.mapTo(this) { testRun ->
            dynamicTest(testRun.displayName) { runTest(testRun) }
        }

        testRunNode.children.mapTo(this) { (prefix, childTestRunNode) ->
            dynamicContainer(prefix, buildJUnitDynamicNodes(childTestRunNode))
        }
    }

    private fun runTest(testRun: TestRun) {
        val testRunner = testRunProvider.createRunner(testRun)
        testRunner.run()
    }
}

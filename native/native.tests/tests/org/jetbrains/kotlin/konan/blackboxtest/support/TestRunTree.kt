/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.konan.blackboxtest.support

internal interface TestRunTreeNode {
    val testRuns: List<TestRun>
    val children: Map<String, TestRunTreeNode>

    companion object {
        fun singleton(testRun: TestRun) = object : TestRunTreeNode {
            override val testRuns = listOf(testRun)
            override val children get() = emptyMap<String, TestRunTreeNode>()
        }
    }
}

internal fun Iterable<TestFunction>.buildTestRunTree(buildTestRun: (TestFunction) -> TestRun): TestRunTreeNode {
    TODO()
}

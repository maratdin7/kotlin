/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.konan.blackboxtest.support.util

import org.jetbrains.kotlin.konan.blackboxtest.support.PackageFQN

internal interface TreeNode<T> {
    val packageSegment: String
    val items: Collection<T>
    val children: Collection<TreeNode<T>>

    companion object {
        fun <T> oneLevel(vararg items: T) = oneLevel(listOf(*items))

        fun <T> oneLevel(items: Iterable<T>) = object : TreeNode<T> {
            override val packageSegment get() = ""
            override val items = items.toList()
            override val children get() = emptyList<TreeNode<T>>()
        }
    }
}

internal fun <T, R> Collection<T>.buildTree(extractPackageName: (T) -> PackageFQN, transform: (T) -> R): TreeNode<R> {
    val groupedItems: Map<PackageFQN, List<R>> = groupBy(extractPackageName).mapValues { (_, items) -> items.map(transform) }

    // Fast pass.
    when (groupedItems.size) {
        0 -> return TreeNode.oneLevel()
        1 -> return TreeNode.oneLevel(groupedItems.values.first())
    }

    // Long pass.
    val root = TreeBuilder<R>("")

    // Populate the tree.
    groupedItems.forEach { (packageFQN, items) ->
        var node = root
        packageFQN.split('.').forEach { packageSegment ->
            node = node.childrenMap.computeIfAbsent(packageSegment) { TreeBuilder(packageSegment) }
        }
        node.items += items
    }

    // Compress the tree.
    root.compress()

    return root
}

private class TreeBuilder<T>(override var packageSegment: String) : TreeNode<T> {
    override val items = mutableListOf<T>()
    val childrenMap = hashMapOf<String, TreeBuilder<T>>()
    override val children: Collection<TreeBuilder<T>> get() = childrenMap.values
}

private fun <T> TreeBuilder<T>.compress() {
    while (items.isEmpty() && childrenMap.size == 1) {
        val childNode = childrenMap.values.first()

        items += childNode.items

        childrenMap.clear()
        childrenMap += childNode.childrenMap

        packageSegment = joinPackageNames(packageSegment, childNode.packageSegment)
    }

    childrenMap.values.forEach { it.compress() }
}

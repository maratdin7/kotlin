/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.konan.blackboxtest.support.util

import org.jetbrains.kotlin.konan.blackboxtest.support.PackageFQN

internal interface TreeNode<T> {
    val items: List<T>
    val children: Map<PackageFQN, TreeNode<T>>

    companion object {
        fun <T> oneLevel(vararg items: T) = oneLevel(listOf(*items))

        fun <T> oneLevel(items: Iterable<T>) = object : TreeNode<T> {
            override val items = items.toList()
            override val children get() = emptyMap<PackageFQN, TreeNode<T>>()
        }
    }
}

internal fun <T, R> Collection<T>.buildTree(extractPackageFQN: (T) -> PackageFQN, transform: (T) -> R): TreeNode<R> {
    val groupedItems: Map<PackageFQN, List<R>> = groupBy(extractPackageFQN).mapValues { (_, items) -> items.map(transform) }

    // Fast pass.
    when (groupedItems.size) {
        0 -> return TreeNode.oneLevel()
        1 -> return TreeNode.oneLevel(groupedItems.values.first())
    }

    // Long pass.
    val root = TreeBuilder<R>()

    // Populate the tree.
    groupedItems.forEach { (packageFQN, items) ->
        var node = root
        packageFQN.split('.').forEach { packageName ->
            node = node.children.computeIfAbsent(packageName) { TreeBuilder() }
        }
        node.items += items
    }

    // Compress the tree.
    return root.skipMeaninglessNodes().apply { compress() }
}

private class TreeBuilder<T> : TreeNode<T> {
    override val items = mutableListOf<T>()
    override val children = hashMapOf<PackageFQN, TreeBuilder<T>>()
}

private tailrec fun <T> TreeBuilder<T>.skipMeaninglessNodes(): TreeBuilder<T> =
    if (items.isNotEmpty() || children.size != 1)
        this
    else
        children.values.first().skipMeaninglessNodes()

private fun <T> TreeBuilder<T>.compress() {
    while (items.isEmpty() && children.size == 1) {
        val (childPackageName, childNode) = children.entries.first()

        items += childNode.items

        children.clear()
        childNode.children.forEach { (grandChildPackageName, grandChildNode) ->
            children[joinPackageNames(childPackageName, grandChildPackageName)] = grandChildNode
        }
    }

    children.values.forEach { it.compress() }
}

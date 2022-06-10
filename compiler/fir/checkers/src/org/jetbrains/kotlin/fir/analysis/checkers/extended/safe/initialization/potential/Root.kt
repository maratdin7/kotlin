/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers.extended.safe.initialization.potential

import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.references.FirSuperReference
import org.jetbrains.kotlin.fir.references.FirThisReference
import org.jetbrains.kotlin.fir.types.coneType

sealed class Root(firElement: FirElement) : Potential(firElement), Potential.Propagatable {

    data class This(val firThisReference: FirThisReference, val firClass: FirClass) : Root(firThisReference) {
        override fun viewChange(root: Potential) = root

        override fun toString(): String {
            return "this@${firClass.symbol.toLookupTag()}"
        }
    }

    data class Super(val firSuperReference: FirSuperReference, val firClass: FirClass) : Root(firSuperReference) {
        override fun viewChange(root: Potential) = this

        override fun toString(): String {
            return "super@${firSuperReference.superTypeRef.coneType}"
        }
    }

    data class Cold(val firDeclaration: FirDeclaration) : Root(firDeclaration) {
        override fun viewChange(root: Potential) = this

        override fun toString(): String {
            return "cold(${firDeclaration.symbol})"
        }
    }
}
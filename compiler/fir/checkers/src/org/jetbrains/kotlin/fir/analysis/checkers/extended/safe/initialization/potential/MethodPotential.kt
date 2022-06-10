/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers.extended.safe.initialization.potential

import org.jetbrains.kotlin.fir.declarations.FirFunction

data class MethodPotential(override val potential: Potential, val method: FirFunction) : WithPrefix(potential, method) {

    override fun createPotentialForPotential(pot: Potential) = MethodPotential(pot, method)

    override fun toString(): String {
        return "$potential.${method.symbol.callableId.callableName}"
    }
}
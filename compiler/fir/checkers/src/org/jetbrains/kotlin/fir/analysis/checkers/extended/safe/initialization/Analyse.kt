/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers.extended.safe.initialization

import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.extended.safe.initialization.Checker.StateOfClass
import org.jetbrains.kotlin.fir.analysis.checkers.extended.safe.initialization.Checker.effectChecking
import org.jetbrains.kotlin.fir.analysis.checkers.extended.safe.initialization.Potential.Root
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.expressions.impl.FirElseIfTrueCondition
import org.jetbrains.kotlin.fir.expressions.impl.FirNoReceiverExpression
import org.jetbrains.kotlin.fir.symbols.SymbolInternals

object ClassAnalyser {

    fun StateOfClass.classTyping(firClass1: FirClass): EffectsAndPotentials {
        // TODO: resolveSuperClasses
        return StateOfClass(firClass1).allEffectsAndPotentials()
    }

    fun StateOfClass.fieldTyping(firProperty: FirProperty): EffectsAndPotentials =
        caches[firProperty] ?: emptyEffsAndPots

    fun StateOfClass.methodTyping(firFunction: FirFunction): EffectsAndPotentials =
        firFunction.body?.let(::analyser) ?: throw IllegalArgumentException()

    fun StateOfClass.analyseDeclaration(firDeclaration: FirDeclaration): EffectsAndPotentials =
        when (firDeclaration) {
            is FirRegularClass -> classTyping(firDeclaration)
//                is FirConstructor -> TODO()
            is FirSimpleFunction -> methodTyping(firDeclaration)
            is FirProperty -> fieldTyping(firDeclaration)
            is FirField -> TODO()
            else -> emptyEffsAndPots
        }

    fun StateOfClass.allEffectsAndPotentials(): EffectsAndPotentials =
        firClass.declarations.fold(emptyEffsAndPots) { prev, dec ->
            val effsAndPots = analyseDeclaration(dec)
            prev + effsAndPots
        }
}

fun FirClass.findDec(firDeclaration: FirDeclaration?): Boolean = declarations.contains(firDeclaration)

@OptIn(SymbolInternals::class)
fun StateOfClass.analyser(firElement: FirElement): EffectsAndPotentials =
    when (firElement) {
        is FirBlock -> firElement.statements.fold(emptyEffsAndPots) { sum, firStatement ->
            sum + analyser(firStatement)
        }
        is FirTypeOperatorCall -> firElement.arguments.fold(emptyEffsAndPots) { sum, operator ->
            sum + analyser(operator)
        }
        is FirFunctionCall -> {
            val receiver = firElement.getReceiver()
            val prefEffsAndPots = receiver?.let(::analyser) ?: emptyEffsAndPots

            val dec = firElement.calleeReference.toResolvedCallableSymbol()?.fir

            val effsAndPotsOfMethod = if (firClass.findDec(dec)) {
                when (dec) {
                    is FirAnonymousFunction -> TODO()
                    is FirConstructor -> TODO()
                    is FirErrorFunction -> TODO()
                    is FirPropertyAccessor -> select(prefEffsAndPots.potentials, dec.propertySymbol?.fir!!)
                    is FirSimpleFunction -> call(prefEffsAndPots.potentials, dec)
                    is FirBackingField -> TODO()
                    is FirEnumEntry -> TODO()
                    is FirErrorProperty -> TODO()
                    is FirField -> TODO()
                    is FirProperty -> TODO()
                    is FirValueParameter -> TODO()
                    null -> TODO()
                }
            } else emptyEffsAndPots

            val effsAndPotsOfArgs = firElement.arguments.fold(emptyEffsAndPots) { sum, argDec ->
                val (effs, pots) = analyser(argDec)
                sum + effs + promote(pots)
                // TODO: explicit receiver promotion
            }
            effsAndPotsOfMethod + effsAndPotsOfArgs + prefEffsAndPots
        }
        is FirPropertyAccessExpression -> {
            val receiver = firElement.getReceiver()

            val firProperty = firElement.calleeReference.toResolvedCallableSymbol()?.fir as FirVariable

            val (prefEffs, prefPots) = receiver?.let(::analyser) ?: emptyEffsAndPots      // Φ, Π

            val effsAndPots =
                if (allProperties.contains(firProperty)) select(prefPots, firProperty)
                else emptyEffsAndPots                                               // Φ', Π'
            effsAndPots + prefEffs                                                              // Φ ∪ Φ', Π'
        }
        is FirReturnExpression -> analyser(firElement.result)
        is FirThisReceiverExpression -> {
            val firClass = firElement.calleeReference.boundSymbol?.fir as FirClass
            resolveThis(firClass, EffectsAndPotentials(Root.This(firElement.calleeReference)), firClass)
        }
        is FirConstExpression<*> -> emptyEffsAndPots  // ???
        is FirWhenBranch -> firElement.run {
            val localSize = localInitedProperties.size
            val effsAndPots = analyser(condition) + analyser(result)

            var i = 0
            localInitedProperties.removeIf { i++ >= localSize }

            effsAndPots

        }
        is FirWhenExpression -> firElement.run {
            val effsAndPots = branches.fold(emptyEffsAndPots) { sum, branch -> sum + analyser(branch) }
            val sub = (subject ?: subjectVariable)?.let(::analyser) ?: emptyEffsAndPots

            val (initedFirProperties, isPrimeInitialization) = initializationOrder.getOrElse(firElement) { return sub + effsAndPots }

            if (isPrimeInitialization) {
                alreadyInitializedVariable.addAll(initedFirProperties)
//                initedFirProperties.forEach {
//                    caches[it] = notFinalAssignments[it] ?: throw java.lang.IllegalArgumentException()
//                    notFinalAssignments.remove(it)
//                }
                localInitedProperties.removeIf { initedFirProperties.contains(it) }
            } else
                localInitedProperties.addAll(initedFirProperties)

            notFinalAssignments.keys.removeIf {
                !(localInitedProperties.contains(it) || alreadyInitializedVariable.contains(it))
            }

            sub + effsAndPots
        }
        is FirVariableAssignment -> {
            val (effs, pots) = analyser(firElement.rValue)
            errors.addAll(effs.flatMap { effectChecking(it) })

            when (val firDeclaration = firElement.lValue.toResolvedCallableSymbol()?.fir) {
                is FirProperty -> {
                    val prevEffsAndPots = notFinalAssignments.getOrDefault(firDeclaration, emptyEffsAndPots)

                    localInitedProperties.add(firDeclaration)

                    val effsAndPots = prevEffsAndPots + pots
                    notFinalAssignments[firDeclaration] = effsAndPots //
                    caches[firDeclaration] = effsAndPots
                }
                is FirVariable -> {}
                else -> throw IllegalArgumentException()
            }
            emptyEffsAndPots
        }
        is FirElseIfTrueCondition -> emptyEffsAndPots
        else -> throw IllegalArgumentException()
    }

private fun FirQualifiedAccess.getReceiver(): FirExpression? = when {
    explicitReceiver != null -> explicitReceiver
    dispatchReceiver !is FirNoReceiverExpression -> dispatchReceiver
    extensionReceiver !is FirNoReceiverExpression -> extensionReceiver
    else -> null
}


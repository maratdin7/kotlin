/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers.extended.safe.initialization

import org.jetbrains.kotlin.contracts.description.isDefinitelyVisited
import org.jetbrains.kotlin.fir.analysis.cfa.util.PropertyInitializationInfo
import org.jetbrains.kotlin.fir.analysis.cfa.util.PropertyInitializationInfoCollector
import org.jetbrains.kotlin.fir.analysis.checkers.extended.safe.initialization.Potential.*
import org.jetbrains.kotlin.fir.analysis.checkers.extended.safe.initialization._Effect.*
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.utils.anonymousInitializers
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.references.FirThisReference
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.NormalPath
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.WhenExitNode
import org.jetbrains.kotlin.fir.resolve.dfa.controlFlowGraph
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol

class CheckingEffects {
}


fun resolveThis(
    clazz: FirClass,
    effsAndPots: EffectsAndPotentials,
    innerClass: FirClass,
): EffectsAndPotentials {
    if (clazz === innerClass) return effsAndPots

    val outerSelection = outerSelection(effsAndPots.potentials, innerClass)
    val outerClass = TODO() // outerClass for innerClass
    return resolveThis(clazz, outerSelection, outerClass)
}

@OptIn(SymbolInternals::class)
fun resolve(thisReference: FirThisReference, firDeclaration: FirDeclaration): FirClass =
    resolve(thisReference.boundSymbol?.fir as FirClass, firDeclaration)

fun resolve(clazz: FirClass, firDeclaration: FirDeclaration): FirClass = clazz // maybe delete

object Checker {

    data class StateOfClass(val firClass: FirClass) {
        fun FirVariable.isPropertyInitialized(): Boolean =
            alreadyInitializedVariable.contains(this) || localInitedProperties.contains(this)

        val alreadyInitializedVariable = mutableSetOf<FirVariable>()

        data class InitializationPointInfo(val firVariables: Set<FirVariable>, val isPrimeInitialization: Boolean)

        val initializationOrder = mutableMapOf<FirExpression, InitializationPointInfo>()


        val localInitedProperties = LinkedHashSet<FirVariable>()
        val notFinalAssignments = mutableMapOf<FirProperty, EffectsAndPotentials>()
        val caches = mutableMapOf<FirDeclaration, EffectsAndPotentials>()

        val allProperties = firClass.declarations.filterIsInstance<FirProperty>()
        val errors = mutableListOf<Error<*>>()

        @OptIn(SymbolInternals::class)
        fun FirAnonymousInitializer.initBlockAnalyser(propertySymbols: Set<FirPropertySymbol>) {
            val graph = controlFlowGraphReference?.controlFlowGraph ?: return
            val initLevel = graph.exitNode.level
            val data = PropertyInitializationInfoCollector(propertySymbols).getData(graph)

            for (entry in data.filterKeys { it is WhenExitNode }) {
                val propertyInitializationInfo = entry.value[NormalPath] ?: PropertyInitializationInfo.EMPTY              // NormalPath ?
                val assignmentPropertiesSymbols = propertyInitializationInfo.filterValues { it.isDefinitelyVisited() }

                val assignmentProperties = assignmentPropertiesSymbols.keys.map { it.fir }.toSet()
                val whenExitNode = entry.key
                initializationOrder[whenExitNode.fir as FirExpression] =
                    InitializationPointInfo(assignmentProperties, whenExitNode.level == initLevel)
            }
        }

        init {
            val inits = firClass.anonymousInitializers
            val p = allProperties.mapTo(mutableSetOf()) { it.symbol }

            for (init in inits)
                init.initBlockAnalyser(p)
        }
    }

    fun StateOfClass.checkClass(): Errors {
        errors + firClass.declarations.flatMap { dec ->
            when (dec) {
                is FirConstructor -> {
                    if (dec.isPrimary)
                        alreadyInitializedVariable + dec.valueParameters
                    checkBody(dec)
                }
                is FirAnonymousInitializer -> {
                    val (effs, _) = dec.body?.let(::analyser) ?: return@flatMap emptyList()
                    effs.flatMap { effectChecking(it) }
                }
                is FirRegularClass -> {
                    val state = StateOfClass(dec)
                    val errors = state.checkClass()
                    errors
                }
                is FirPropertyAccessor -> TODO()
                //                is FirSimpleFunction -> checkBody(dec)
                is FirField -> TODO()
                is FirProperty -> {
                    val (effs, _) = dec.initializer?.let(::analyser) ?: return emptyList()
                    val errors = effs.flatMap { effectChecking(it) }
                    alreadyInitializedVariable.add(dec)
                    errors
                }
                else -> return@flatMap emptyList()
            }
        }
        return errors
    }

    fun StateOfClass.checkBody(dec: FirFunction): Errors {
        val (effs, _) = dec.body?.let(::analyser) ?: return emptyList()
        return effs.flatMap { effectChecking(it) }
    }

    fun StateOfClass.potentialPropagation(potential: Potential): EffectsAndPotentials {
        return when (potential) {
            is FieldPotential -> {
                val (pot, field) = potential
                when (pot) {
                    is Root.This -> {                                  // P-Acc1
                        val clazz = resolve(pot.firThisReference, field)
                        val potentials = pot.potentialsOf(this, field)
                        EffectsAndPotentials(potentials = potentials.viewChange(pot))
                    }
                    is Root.Warm -> {                                         // P-Acc2
                        val clazz = resolve(pot.clazz, field)
                        val potentials = pot.potentialsOf(this, field)
                        EffectsAndPotentials(potentials = potentials.viewChange(pot))
                    }
                    is Root.Cold -> EffectsAndPotentials(Promote(pot)) // or exception or empty list
                    is FunPotential -> throw IllegalArgumentException()
                    else -> {                                                       // P-Acc3
                        val (effects, potentials) = potentialPropagation(pot)
                        val sel = select(potentials, field)
                        EffectsAndPotentials(effects + sel.effects, sel.potentials)
                    }
                }
            }
            is MethodPotential -> {
                val (pot, method) = potential
                when (pot) {
                    is Root.This -> {                                     // P-Inv1
                        val clazz = resolve(pot.firThisReference, method)
                        val potentials = pot.potentialsOf(this, method)
                        EffectsAndPotentials(emptyList(), potentials)
                    }
                    is Root.Warm -> {                                     // P-Inv2
                        val clazz = resolve(pot.clazz, method)
                        val potentials = pot.potentialsOf(this, method)
                        EffectsAndPotentials(emptyList(), potentials.viewChange(pot))
                    }
                    is Root.Cold -> EffectsAndPotentials(Promote(pot))
                    is FunPotential -> {
                        TODO()
                    }
                    else -> {                                                       // P-Inv3
                        val (effects, potentials) = potentialPropagation(pot)
                        val call = call(potentials, method)
                        EffectsAndPotentials(effects + call.effects, call.potentials)
                    }
                }
            }
            is OuterPotential -> {
                val (pot, outer) = potential
                when (pot) {
                    is Root.This -> emptyEffsAndPots                // P-Out1
                    is Root.Warm -> {                                     // P-Out2
                        TODO()// просто вверх по цепочке наследования
                    }
                    is Root.Cold -> EffectsAndPotentials(Promote(pot))  // or exception or empty list
                    is FunPotential -> throw IllegalArgumentException()
                    else -> {                                                       // P-Out3
                        val (effects, potentials) = potentialPropagation(pot)
                        val out = outerSelection(potentials, outer)
                        EffectsAndPotentials(effects + out.effects, out.potentials)
                    }
                }
            }
            is FunPotential -> TODO() // invoke?
            is Root.Cold, is Root.This, is Root.Warm -> EffectsAndPotentials(potential)
        }
    }

    fun StateOfClass.effectChecking(effect: Effect): Errors {
        val errors = when (effect) {
            is FieldAccess -> {
                val (pot, field) = effect
                when (pot) {
                    is Root.This -> {                                     // C-Acc1
                        if (field.isPropertyInitialized())
                            emptyList()
                        else listOf(Error.AccessError(effect))
                    }
                    is Root.Warm -> emptyList()                              // C-Acc2
                    is FunPotential -> throw Exception()                  // impossible
                    is Root.Cold -> listOf(Error.AccessError(effect))           // illegal
                    else ->                                                         // C-Acc3
                        ruleAcc3(potentialPropagation(pot)) { p -> FieldAccess(p, field) }
                }
            }
            is MethodAccess -> {
                val (pot, method) = effect
                when (pot) {
                    is Root.This -> {                                     // C-Inv1
                        val clazz = resolve(pot.firThisReference, method)
                        pot.effectsOf(this, method).flatMap { effectChecking(it) }
                    }
                    is Root.Warm -> {                                     // C-Inv2
                        val clazz = resolve(pot.clazz, method)
                        pot.effectsOf(this, method).flatMap { eff ->
                            viewChange(eff, pot)
                            effectChecking(eff)
                        }
                    }
                    is FunPotential -> emptyList() // invoke
                    is Root.Cold -> listOf(Error.InvokeError(effect))              // illegal
                    else ->                                                         // C-Inv3
                        ruleAcc3(potentialPropagation(pot)) { p -> MethodAccess(p, method) }
                }
            }
            is Init -> {                                                     // C-Init
                val (pot, clazz) = effect
                pot.effectsOf(this, clazz).flatMap { eff ->
                    viewChange(eff, pot)
                    effectChecking(eff)
                }
                // ???
            }
            is Promote -> {
                val pot = effect.potential
                when (pot) {
                    is Root.Warm -> {                                     // C-Up1
                        pot.clazz.declarations.map {
                            when (it) {
//                                is FirAnonymousInitializer -> TODO()
                                is FirRegularClass -> TODO()
//                                is FirConstructor -> TODO()
                                is FirSimpleFunction -> TODO()
                                is FirField, is FirProperty -> TODO()
                                else -> throw IllegalArgumentException()
                            }
                        }

                    }
                    is Root.This -> listOf(Error.PromoteError(effect))
                    is FunPotential -> ruleAcc3(pot.effectsAndPotentials, ::Promote)
                    is Root.Cold -> listOf(Error.PromoteError(effect))
                    else -> {
                        ruleAcc3(potentialPropagation(pot), ::Promote)   // C-Up2

                    }
                }
            }
        }
        for (error in errors) error.trace.add(effect)

        return errors
    }

    private fun StateOfClass.ruleAcc3(effectsAndPotentials: EffectsAndPotentials, producerOfEffects: (Potential) -> Effect): Errors =
        effectsAndPotentials.run {
            val errors = potentials.map { effectChecking(producerOfEffects(it)) } // call / select
            val effectErrors = effects.map { effectChecking(it) }
            (errors + effectErrors).flatten()
        }
}

typealias Errors = List<Error<*>>

sealed class Error<T : Effect>(val effect: T) {
    val trace = mutableListOf<Effect>()

    class AccessError(effect: FieldAccess) : Error<FieldAccess>(effect) {
        override fun toString(): String {
            return "AccessError(property=${effect.field})"
        }
    }

    class InvokeError(effect: MethodAccess) : Error<MethodAccess>(effect) {
        override fun toString(): String {
            return "InvokeError(method=${effect.method})"
        }
    }

    class PromoteError(effect: Promote) : Error<Promote>(effect) {
        override fun toString(): String {
            return "PromoteError(potential=${effect.potential})"
        }
    }
}

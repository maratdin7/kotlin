/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers.extended.safe.initialization

import org.jetbrains.kotlin.contracts.description.isDefinitelyVisited
import org.jetbrains.kotlin.fir.analysis.cfa.util.PropertyInitializationInfo
import org.jetbrains.kotlin.fir.analysis.cfa.util.PropertyInitializationInfoCollector
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.extended.safe.initialization.Effect.*
import org.jetbrains.kotlin.fir.analysis.checkers.extended.safe.initialization.EffectsAndPotentials.Companion.toEffectsAndPotentials
import org.jetbrains.kotlin.fir.analysis.checkers.extended.safe.initialization.potential.*
import org.jetbrains.kotlin.fir.analysis.checkers.overriddenFunctions
import org.jetbrains.kotlin.fir.analysis.checkers.overriddenProperties
import org.jetbrains.kotlin.fir.analysis.checkers.toRegularClassSymbol
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.utils.anonymousInitializers
import org.jetbrains.kotlin.fir.declarations.utils.isOverride
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.NormalPath
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.WhenExitNode
import org.jetbrains.kotlin.fir.resolve.dfa.controlFlowGraph
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.FirUserTypeRef
import org.jetbrains.kotlin.utils.addToStdlib.filterIsInstanceWithChecker

class CheckingEffects {
}

object Checker {

    fun resolveThis(
        clazz: FirClass,
        effsAndPots: EffectsAndPotentials,
        stateOfClass: StateOfClass,
    ): EffectsAndPotentials {
        val innerClass = stateOfClass.firClass
        if (clazz === innerClass || stateOfClass.superClasses.contains(clazz))
            return effsAndPots

        val outerSelection = outerSelection(effsAndPots.potentials, innerClass)
        // val outerClass =  // outerClass for innerClass
        return stateOfClass.outerClassState?.let { resolveThis(clazz, outerSelection, it) } ?: TODO()
    }

    @OptIn(SymbolInternals::class)
    fun resolve(dec: FirCallableDeclaration): StateOfClass =
        dec.dispatchReceiverType?.toRegularClassSymbol(dec.moduleData.session)?.fir?.let(cache::get) ?: TODO()

    val cache = mutableMapOf<FirClass, StateOfClass>()

    @OptIn(SymbolInternals::class)
    data class StateOfClass(val firClass: FirClass, val context: CheckerContext, val outerClassState: StateOfClass? = null) {
        fun FirVariable.isPropertyInitialized(): Boolean =
            alreadyInitializedVariable.contains(this) || localInitedProperties.contains(this)

        val alreadyInitializedVariable = mutableSetOf<FirVariable>()

        data class InitializationPointInfo(val firVariables: Set<FirVariable>, val isPrimeInitialization: Boolean)

        val initializationOrder = mutableMapOf<FirExpression, InitializationPointInfo>()
        val effectsInProcess = mutableListOf<Effect>()

        val localInitedProperties = LinkedHashSet<FirVariable>()
        val notFinalAssignments = mutableMapOf<FirProperty, EffectsAndPotentials>()
        val caches = mutableMapOf<FirDeclaration, EffectsAndPotentials>()

        val overriddenMembers = overriddenMembers()

        private fun overriddenMembers(): Map<FirMemberDeclaration, FirMemberDeclaration> {
            val members = firClass.declarations.filterIsInstanceWithChecker(FirMemberDeclaration::isOverride)
            val map = mutableMapOf<FirMemberDeclaration, FirMemberDeclaration>()
            members.forEach { member ->
                val getOverriddenMembers = when (member) {
                    is FirSimpleFunction -> member::overriddenFunctions
                    is FirProperty -> member::overriddenProperties
                    else -> throw IllegalArgumentException()
                }
                getOverriddenMembers(firClass.symbol, context).associateByTo(map, FirCallableSymbol<*>::fir) { member }
                map[member] = member
            }
            return map
        }

        val superClasses =
            firClass.superTypeRefs.filterIsInstanceWithChecker<FirResolvedTypeRef> { it.delegatedTypeRef is FirUserTypeRef }
                .mapNotNull { it.toRegularClassSymbol(context.session)?.fir }
        val declarations = (superClasses + firClass).flatMap { clazz -> clazz.declarations }

        val allProperties = declarations.filterIsInstance<FirProperty>()

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

            cache[firClass] = this
        }

        fun checkClass(): Errors {
            val classErrors = declarations.flatMap { dec ->
                when (dec) {
                    is FirConstructor -> {
                        if (dec.isPrimary)
                            alreadyInitializedVariable + dec.valueParameters
                    }
                }
                val effsAndPots = analyseDeclaration(dec)
                val errors = effsAndPots.effects.flatMap { it.check(this) }
                if (dec is FirProperty && dec.initializer != null) {
                    alreadyInitializedVariable.add(dec)
                }
//                caches.putIfAbsent(dec, effsAndPots)
                errors
            }
            errors.addAll(classErrors)
            return errors
        }

        fun potentialPropagation(potential: Potential): EffectsAndPotentials {
            return when (potential) {
                is FieldPotential -> {
                    val (pot, field) = potential
                    when (pot) {
                        is Root.This -> {                                  // P-Acc1
                            val state = resolve(field)
                            val potentials = pot.potentialsOf(state, field)
                            potentials.viewChange(pot).toEffectsAndPotentials()
                        }
                        is Warm -> {                                         // P-Acc2
                            val state = resolve(field)
                            val potentials = pot.potentialsOf(state, field)
                            potentials.viewChange(pot).toEffectsAndPotentials()
                        }
                        is Root.Cold -> EffectsAndPotentials(Promote(pot)) // or exception or empty list
                        is Root.Super -> {
                            val state = cache[pot.firClass] ?: TODO()
                            val potentials = pot.potentialsOf(state, field)
                            potentials.viewChange(pot).toEffectsAndPotentials()
                        }
                        is FunPotential -> throw IllegalArgumentException()
                        else -> {                                                       // P-Acc3
                            val (effects, potentials) = potentialPropagation(pot)
                            val select = select(potentials, field)
                            select + effects
                        }
                    }
                }
                is MethodPotential -> {
                    val (pot, method) = potential
                    when (pot) {
                        is Root.This -> {                                     // P-Inv1
                            val state = resolve(method)
                            val potentials = pot.potentialsOf(state, method)
                            potentials.toEffectsAndPotentials()
                        }
                        is Warm -> {                                     // P-Inv2
                            val state = resolve(method)
                            val potentials = pot.potentialsOf(state, method)  // find real state
                            potentials.viewChange(pot).toEffectsAndPotentials()
                        }
                        is Root.Cold -> EffectsAndPotentials(Promote(pot))
                        is Root.Super -> {
                            val state = cache[pot.firClass] ?: TODO()
                            val potentials = pot.potentialsOf(state, method)
                            potentials.toEffectsAndPotentials()
                        }
                        is FunPotential -> TODO()
                        else -> {                                                       // P-Inv3
                            val (effects, potentials) = potentialPropagation(pot)
                            val call = call(potentials, method)
                            call + effects
                        }
                    }
                }
                is OuterPotential -> {
                    val (pot, outer) = potential
                    when (pot) {
                        is Root.This -> emptyEffsAndPots                // P-Out1
                        is Warm -> {                               // P-Out2
                            val (firClass, outerPot) = pot
                            return EffectsAndPotentials(outerPot)
                            // TODO:
                            //  if (firClass != this.firClass) rec: findParent(firClass)
                            //  просто вверх по цепочке наследования если inner от кого-то наследуется
                        }
                        is Root.Cold -> EffectsAndPotentials(Promote(pot))  // or exception or empty list
                        is Root.Super -> TODO()
                        is FunPotential -> throw IllegalArgumentException()
                        else -> {                                                       // P-Out3
                            val (effects, potentials) = potentialPropagation(pot)
                            val (_, outPots) = outerSelection(potentials, outer)
                            EffectsAndPotentials(effects, outPots)
                        }
                    }
                }
                is FunPotential -> TODO() // invoke?
                is Root, is Warm -> EffectsAndPotentials(potential)
            }
        }
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

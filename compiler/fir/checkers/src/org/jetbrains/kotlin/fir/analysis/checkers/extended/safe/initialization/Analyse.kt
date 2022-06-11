/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers.extended.safe.initialization

import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.extended.safe.initialization.Checker.StateOfClass
import org.jetbrains.kotlin.fir.analysis.checkers.extended.safe.initialization.Checker.resolveThis
import org.jetbrains.kotlin.fir.analysis.checkers.extended.safe.initialization.EffectsAndPotentials.Companion.toEffectsAndPotentials
import org.jetbrains.kotlin.fir.analysis.checkers.extended.safe.initialization.potential.LambdaPotential
import org.jetbrains.kotlin.fir.analysis.checkers.extended.safe.initialization.potential.Root
import org.jetbrains.kotlin.fir.analysis.checkers.extended.safe.initialization.potential.Super
import org.jetbrains.kotlin.fir.containingClass
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.references.FirSuperReference
import org.jetbrains.kotlin.fir.references.FirThisReference
import org.jetbrains.kotlin.fir.resolve.toFirRegularClass
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.*
import org.jetbrains.kotlin.fir.visitors.FirDefaultVisitor

object ClassAnalyser {

    fun StateOfClass.classTyping(firClass1: FirClass): EffectsAndPotentials {
        // TODO: resolveSuperClasses
        val state = StateOfClass(firClass1, context, this)
        return state.firClass.declarations.fold(emptyEffsAndPots) { sum, d -> sum + state.analyseDeclaration(d) }
    }

    fun StateOfClass.fieldTyping(firProperty: FirProperty): EffectsAndPotentials =
        analyseDeclaration(firProperty)

    fun StateOfClass.methodTyping(firFunction: FirFunction): EffectsAndPotentials =
        firFunction.body?.let(::analyser) ?: emptyEffsAndPots

    fun StateOfClass.analyseDeclaration1(firDeclaration: FirDeclaration): EffectsAndPotentials {
        return caches.getOrPut(firDeclaration) {
            when (firDeclaration) {
                is FirRegularClass -> classTyping(firDeclaration)
                //                is FirConstructor(a: FirElement) {
                //                TODO()}
                is FirSimpleFunction -> methodTyping(firDeclaration)
                is FirProperty -> fieldTyping(firDeclaration)
                is FirField -> {
                    TODO()
                }
                else -> emptyEffsAndPots
            }
        }
    }
}

object Analyser {

    object ReferenceVisitor : FirDefaultVisitor<EffectsAndPotentials, Pair<StateOfClass, Potentials>>() {
        override fun visitElement(element: FirElement, data: Pair<StateOfClass, Potentials>): EffectsAndPotentials = emptyEffsAndPots

        override fun visitSuperReference(superReference: FirSuperReference, data: Pair<StateOfClass, Potentials>): EffectsAndPotentials {
            val (stateOfClass, pots) = data
            return pots.map { pot -> Super(superReference, stateOfClass.firClass, pot) }.toEffectsAndPotentials()
        }

        @OptIn(SymbolInternals::class)
        override fun visitThisReference(thisReference: FirThisReference, data: Pair<StateOfClass, Potentials>): EffectsAndPotentials =
            (thisReference.boundSymbol as? FirClassSymbol<*>)?.let { EffectsAndPotentials(Root.This(thisReference, it.fir)) }
                ?: emptyEffsAndPots

        @OptIn(SymbolInternals::class)
        override fun visitResolvedNamedReference(
            resolvedNamedReference: FirResolvedNamedReference,
            data: Pair<StateOfClass, Potentials>
        ): EffectsAndPotentials {
            val (stateOfClass, prefPots) = data
            return when (val symbol = resolvedNamedReference.resolvedSymbol) {
                is FirVariableSymbol<*> -> stateOfClass.select(prefPots, symbol.fir)
                is FirConstructorSymbol -> symbol.getClassFromConstructor()?.let { init(prefPots, it) } ?: emptyEffsAndPots
                is FirAnonymousFunctionSymbol -> TODO()
                is FirFunctionSymbol<*> -> stateOfClass.call(prefPots, symbol.fir)
                else -> emptyEffsAndPots
            }
        }
    }

    class ExpressionVisitor(private val stateOfClass: StateOfClass) : FirDefaultVisitor<EffectsAndPotentials, Nothing?>() {
        override fun visitElement(element: FirElement, data: Nothing?): EffectsAndPotentials = emptyEffsAndPots

        private fun FirElement.accept(): EffectsAndPotentials = accept(this@ExpressionVisitor, null)

        private fun analyseArgumentList(argumentList: FirArgumentList): EffectsAndPotentials =
            argumentList.arguments.fold(emptyEffsAndPots) { sum, argDec ->
                val (effs, pots) = argDec.accept()
                sum + effs + promote(pots)
            }

        private fun analyseQualifiedAccess(firQualifiedAccess: FirQualifiedAccess): EffectsAndPotentials = firQualifiedAccess.run {
            setOfNotNull(
                explicitReceiver, dispatchReceiver, extensionReceiver
            ).fold(emptyEffsAndPots) { sum, receiver ->
                val recEffsAndPots = receiver.accept().let {
                    if (receiver != extensionReceiver) it
                    else (promote(it.potentials) + it.effects).toEffectsAndPotentials()
                }
                sum + recEffsAndPots
            }
        }

        override fun visitTypeOperatorCall(typeOperatorCall: FirTypeOperatorCall, data: Nothing?): EffectsAndPotentials {
            TODO()
        }

        override fun visitQualifiedAccessExpression(
            qualifiedAccessExpression: FirQualifiedAccessExpression,
            data: Nothing?
        ): EffectsAndPotentials {
            val (prefEffs, prefPots) = visitQualifiedAccess(qualifiedAccessExpression, null)                        // Φ, Π
            val effsAndPots = qualifiedAccessExpression.calleeReference.accept(ReferenceVisitor, stateOfClass to prefPots)
            return effsAndPots + prefEffs
        }

        override fun visitFunctionCall(functionCall: FirFunctionCall, data: Nothing?): EffectsAndPotentials {
            val effsAndPotsOfMethod = visitQualifiedAccessExpression(functionCall, null)

            val (effsOfArgs, _) = functionCall.argumentList.accept()
            // TODO: explicit receiver promotion
            return effsAndPotsOfMethod + effsOfArgs
        }

        override fun visitAnonymousFunctionExpression(
            anonymousFunctionExpression: FirAnonymousFunctionExpression,
            data: Nothing?
        ): EffectsAndPotentials {

            val anonymousFunction = anonymousFunctionExpression.anonymousFunction
            val effectsAndPotentials = anonymousFunction.body?.accept() ?: emptyEffsAndPots
            val lambdaPot = LambdaPotential(effectsAndPotentials, anonymousFunction)
            return EffectsAndPotentials(lambdaPot)
        }

        @OptIn(SymbolInternals::class)
        override fun visitThisReceiverExpression(thisReceiverExpression: FirThisReceiverExpression, data: Nothing?): EffectsAndPotentials {
            val firThisReference = thisReceiverExpression.calleeReference
            val firClass = firThisReference.boundSymbol?.fir as FirClass
            val effectsAndPotentials = firThisReference.accept(ReferenceVisitor, stateOfClass to emptyList())
            return if (stateOfClass.superClasses.contains(firClass) || firClass === stateOfClass.firClass)
                effectsAndPotentials else resolveThis(firClass, effectsAndPotentials, stateOfClass)
        }

        override fun visitWhenExpression(whenExpression: FirWhenExpression, data: Nothing?): EffectsAndPotentials {
            return whenExpression.run {
                val effsAndPots = branches.fold(emptyEffsAndPots) { sum, branch -> sum + branch.accept() }
                val sub = (subject ?: subjectVariable)?.accept() ?: emptyEffsAndPots

                val (initedFirProperties, isPrimeInitialization) = stateOfClass.initializationOrder.getOrElse(whenExpression) { return sub + effsAndPots }

                if (isPrimeInitialization) {
                    stateOfClass.alreadyInitializedVariable.addAll(initedFirProperties)
//                initedFirProperties.forEach {
//                    caches[it] = notFinalAssignments[it] ?: throw java.lang.IllegalArgumentException()
//                    notFinalAssignments.remove(it)
//                }
                    stateOfClass.localInitedProperties.removeIf(initedFirProperties::contains)
                } else
                    stateOfClass.localInitedProperties.addAll(initedFirProperties)

                stateOfClass.notFinalAssignments.keys.removeIf {
                    !(stateOfClass.localInitedProperties.contains(it) || stateOfClass.alreadyInitializedVariable.contains(it))
                }

                sub + effsAndPots
            }
        }

        override fun visitWhenBranch(whenBranch: FirWhenBranch, data: Nothing?): EffectsAndPotentials {
            return whenBranch.run {
                val localSize = stateOfClass.localInitedProperties.size
                val effsAndPots = condition.accept().effects + result.accept()

                var i = 0
                stateOfClass.localInitedProperties.removeIf { i++ >= localSize }

                effsAndPots
            }
        }

        override fun visitLoop(loop: FirLoop, data: Nothing?): EffectsAndPotentials =
            loop.run { condition.accept() + block.accept() }

        override fun visitBinaryLogicExpression(binaryLogicExpression: FirBinaryLogicExpression, data: Nothing?): EffectsAndPotentials {
            TODO()
        }

        override fun visitStringConcatenationCall(
            stringConcatenationCall: FirStringConcatenationCall,
            data: Nothing?
        ): EffectsAndPotentials {
            TODO()
        }

        override fun visitCheckNotNullCall(checkNotNullCall: FirCheckNotNullCall, data: Nothing?): EffectsAndPotentials {
            TODO()
        }

        override fun visitElvisExpression(elvisExpression: FirElvisExpression, data: Nothing?): EffectsAndPotentials {
            TODO()
        }

        override fun visitSafeCallExpression(safeCallExpression: FirSafeCallExpression, data: Nothing?): EffectsAndPotentials {
            TODO()
        }

        override fun visitTryExpression(tryExpression: FirTryExpression, data: Nothing?): EffectsAndPotentials {
            TODO()
        }

        override fun visitClassReferenceExpression(
            classReferenceExpression: FirClassReferenceExpression,
            data: Nothing?
        ): EffectsAndPotentials {
            TODO()
        }

        override fun visitEqualityOperatorCall(equalityOperatorCall: FirEqualityOperatorCall, data: Nothing?): EffectsAndPotentials {
            TODO()
        }

        @OptIn(SymbolInternals::class)
        override fun visitVariableAssignment(variableAssignment: FirVariableAssignment, data: Nothing?): EffectsAndPotentials {
            val (effs, pots) = variableAssignment.rValue.accept()
            stateOfClass.errors.addAll(effs.flatMap { it.check(stateOfClass) })

            when (val firDeclaration = variableAssignment.lValue.toResolvedCallableSymbol()?.fir) {
                is FirProperty -> {
                    val prevEffsAndPots = stateOfClass.notFinalAssignments.getOrDefault(firDeclaration, emptyEffsAndPots)

                    stateOfClass.localInitedProperties.add(firDeclaration)

                    val effsAndPots = prevEffsAndPots + pots
                    stateOfClass.notFinalAssignments[firDeclaration] = effsAndPots //
                    stateOfClass.caches[firDeclaration] = effsAndPots
                }
                is FirVariable -> {}
                else -> throw IllegalArgumentException()
            }
            return emptyEffsAndPots
        }

        override fun visitReturnExpression(returnExpression: FirReturnExpression, data: Nothing?): EffectsAndPotentials {
            val effsAndPots = returnExpression.result.accept()
            return effsAndPots
        }

        override fun visitBlock(block: FirBlock, data: Nothing?): EffectsAndPotentials =
            block.statements.fold(emptyEffsAndPots) { sum, firStatement -> sum + firStatement.accept() }

        override fun visitDelegatedConstructorCall(
            delegatedConstructorCall: FirDelegatedConstructorCall,
            data: Nothing?
        ): EffectsAndPotentials {
            TODO()
        }

        override fun visitCall(call: FirCall, data: Nothing?): EffectsAndPotentials =
            call.argumentList.accept()

        override fun visitQualifiedAccess(qualifiedAccess: FirQualifiedAccess, data: Nothing?): EffectsAndPotentials =
            analyseQualifiedAccess(qualifiedAccess)

        override fun visitArgumentList(argumentList: FirArgumentList, data: Nothing?): EffectsAndPotentials =
            analyseArgumentList(argumentList)
    }
}

fun StateOfClass.analyser(firElement: FirElement): EffectsAndPotentials {
    val visitor = Analyser.ExpressionVisitor(this)
    return firElement.accept(visitor, null)
}

@OptIn(LookupTagInternals::class)
private fun FirConstructorSymbol.getClassFromConstructor() =
    containingClass()?.toFirRegularClass(moduleData.session)

fun StateOfClass.analyseDeclaration(dec: FirDeclaration): EffectsAndPotentials {
    val effsAndPots = when (dec) {
        is FirConstructor -> dec.body?.let(::analyser) ?: emptyEffsAndPots
        is FirAnonymousInitializer -> dec.body?.let(::analyser) ?: emptyEffsAndPots
        is FirRegularClass -> {
            val state = StateOfClass(dec, context, this)
            this.errors.addAll(state.checkClass())
            state.firClass.declarations.fold(emptyEffsAndPots) { sum, d -> sum + state.analyseDeclaration(d) }
        }
        is FirPropertyAccessor -> TODO()
        //                is FirSimpleFunction -> checkBody(dec)
        is FirField -> TODO()
        is FirProperty -> dec.initializer?.let(::analyser) ?: emptyEffsAndPots
        else -> emptyEffsAndPots
    }
    return effsAndPots
}


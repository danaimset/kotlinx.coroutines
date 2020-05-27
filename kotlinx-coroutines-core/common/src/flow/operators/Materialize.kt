/*
 * Copyright 2016-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

@file:JvmMultifileClass
@file:JvmName("FlowKt")
@file:Suppress("EXPERIMENTAL_FEATURE_WARNING")

package kotlinx.coroutines.flow

import kotlinx.coroutines.*
import kotlin.jvm.*

@ExperimentalCoroutinesApi
public fun <T> Flow<T>.materializeCompletion(): Flow<Completing<T>> = this
    .map { value -> Completing.emission(value) }
    .catch { cause ->
        emit(Completing.completion(cause))
        throw cause
    }
    .onCompletion { cause ->
        if (cause == null) emit(Completing.completion())
    }

@ExperimentalCoroutinesApi
public fun <T> Flow<Completing<T>>.dematerializeCompletion(): Flow<T> = this
    .transformWhile { completing ->
        completing.fold(
            onEmission = { value ->
                emit(value)
                true
            },
            onCompletion = { cause ->
                if (cause != null) throw cause
                false
            }
        )
    }


@ExperimentalCoroutinesApi
public inline class Completing<out T>(private val value: Any?) {
    public companion object {
        public fun <T> emission(value: T): Completing<T> = TODO()
        public fun completion(cause: Throwable? = null): Completing<Nothing> = TODO()
    }
    public val isEmission: Boolean
        get() = TODO()
    public val isCompletion: Boolean
        get() = TODO()
}

@ExperimentalCoroutinesApi
public inline fun <R, T> Completing<T>.fold(
    onEmission: (value: T) -> R,
    onCompletion: (cause: Throwable?) -> R
): R = TODO()

@ExperimentalCoroutinesApi
public inline fun <R, T : R> Completing<T>.getValueOrElse(onCompletion: (cause: Throwable?) -> R): R = TODO()

// todo: a separate feature
public inline fun <T, R> Flow<T>.transformWhile(
    @BuilderInference crossinline transform: suspend FlowCollector<R>.(value: T) -> Boolean
): Flow<R> = flow { // Note: safe flow is used here, because collector is exposed to transform on each operation
    collect { value ->
        // kludge, without it Unit will be returned and TCE won't kick in, KT-28938
        transform(value)
        // todo: check result
    }
}


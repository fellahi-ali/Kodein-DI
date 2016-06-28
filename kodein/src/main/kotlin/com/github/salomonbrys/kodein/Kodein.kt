package com.github.salomonbrys.kodein

import com.github.salomonbrys.kodein.internal.KodeinContainer
import java.lang.reflect.Type
import java.util.*

/**
 * KOtlin DEpendency INjection.
 *
 * To construct a Kodein instance, simply use it's block constructor and define your bindings in it :
 *
 * ```kotlin
 * val kodein = Kodein {
 *     bind<Type1>() with factory { arg: Arg -> ** provide a Type1 function arg ** }
 *     bind<Type2>() with provide { ** provide a Type1 ** }
 * }
 * ```
 *
 * See the file scopes.kt for other scopes.
 */
class Kodein internal constructor(internal val _container: KodeinContainer) {

    data class Bind(
            val type: Type,
            val tag: Any?
    ) {
        override fun toString() = "bind<${type.dispName}>(${ if (tag != null) "\"$tag\"" else "" })"
    }

    data class Key(
            val bind: Bind,
            val argType: Type
    ) {
        override fun toString() = buildString {
            if (bind.tag != null) append("\"${bind.tag}\": ")
            if (argType != Unit.javaClass) append("(${argType.dispName})") else append("()")
            append("-> ${bind.type.dispName}")
        }
    }

    /**
     * A module is used the same way as in the Kodein constructor :
     *
     * ```kotlin
     * val module = Kodein.Module {
     *     bind<Type2>() with provide { ** provide a Type1 ** }
     * }
     * ```
     */
    class Module(val allowSilentOverride: Boolean = false, val init: Builder.() -> Unit)

    internal enum class OverrideMode {
        ALLOW_SILENT {
            override val allow: Boolean get() = true
            override fun must(overrides: Boolean?) = overrides
            override fun allow(allowOverride: Boolean) = allowOverride
        },
        ALLOW_EXPLICIT {
            override val allow: Boolean get() = true
            override fun must(overrides: Boolean?) = overrides ?: false
            override fun allow(allowOverride: Boolean) = allowOverride
        },
        FORBID {
            override val allow: Boolean get() = false
            override fun must(overrides: Boolean?) = if (overrides != null && overrides) throw OverridingException("Overriding has been forbidden") else false
            override fun allow(allowOverride: Boolean) = if (allowOverride) throw OverridingException("Overriding has been forbidden") else false
        };

        abstract val allow: Boolean
        abstract fun must(overrides: Boolean?): Boolean?
        abstract fun allow(allowOverride: Boolean): Boolean

        companion object {
            fun get(allow: Boolean, silent: Boolean): OverrideMode {
                if (!allow)
                    return FORBID
                if (silent)
                    return ALLOW_SILENT
                return ALLOW_EXPLICIT
            }
        }
    }

    /**
     * Allows for the DSL inside the block argument of the constructor of `Kodein` and `Kodein.Module`
     */
    class Builder internal constructor(private val _overrideMode: OverrideMode, internal val _builder: KodeinContainer.Builder, internal val _callbacks: MutableList<Kodein.() -> Unit>, init: Builder.() -> Unit) {

        init { init() }

        inner class TypeBinder<in T : Any> internal constructor(private val _bind: Bind, overrides: Boolean?) {
            private val _mustOverride = _overrideMode.must(overrides)
            infix fun <R : T, A> with(factory: Factory<A, R>) = _builder.bind(Key(_bind, factory.argType), factory, _mustOverride)
        }

        inner class DirectBinder internal constructor(private val _tag: Any?, overrides: Boolean?) {
            private val _mustOverride = _overrideMode.must(overrides)
            infix inline fun <A, reified T : Any> from(factory: Factory<A, T>) = _with(typeToken<T>().type, factory)
            fun <A> _with(type: Type, factory: Factory<A, *>) = _builder.bind(Key(Bind(type, _tag), factory.argType), factory, _mustOverride)
        }

        inner class ConstantBinder internal constructor(private val _tag: Any, overrides: Boolean?) {
            private val _mustOverride = _overrideMode.must(overrides)
            infix fun with(value: Any) = _builder.bind(Key(Bind(value.javaClass, _tag), Unit.javaClass), instance(value), _mustOverride)
        }

        fun bind(type: Type, tag: Any? = null, overrides: Boolean? = null): TypeBinder<Any> = TypeBinder(Bind(type, tag), overrides)

        fun <T : Any> bind(type: TypeToken<T>, tag: Any? = null, overrides: Boolean? = null): TypeBinder<T> = TypeBinder(Bind(type.type, tag), overrides)

        fun <T : Any> bind(type: Class<T>, tag: Any? = null, overrides: Boolean? = null): TypeBinder<T> = TypeBinder(Bind(type, tag), overrides)

        inline fun <reified T : Any> bind(tag: Any? = null, overrides: Boolean? = null) = bind(typeToken<T>(), tag, overrides)

        fun bind(tag: Any? = null, overrides: Boolean? = null) = DirectBinder(tag, overrides)

        fun constant(tag: Any, overrides: Boolean? = null): ConstantBinder = ConstantBinder(tag, overrides)

        fun import(module: Module, allowOverride: Boolean = false) {
            Builder(OverrideMode.get(_overrideMode.allow(allowOverride), module.allowSilentOverride), _builder, _callbacks, module.init)
        }

        fun extend(kodein: Kodein, allowOverride: Boolean = false) {
            _builder.extend(kodein._container, _overrideMode.allow(allowOverride))
        }

        fun onReady(f: Kodein.() -> Unit) {
            _callbacks += f
        }
    }

    private constructor(builder: Builder) : this(KodeinContainer(builder._builder)) {
        builder._callbacks.forEach { it() }
    }

    constructor(allowSilentOverride: Boolean = false, init: Kodein.Builder.() -> Unit) : this(Builder(OverrideMode.get(true, allowSilentOverride), KodeinContainer.Builder(), ArrayList(), init))

    /**
     * This is for debug. It allows to print all binded keys.
     */
    val registeredBindings: Map<Kodein.Bind, String> get() = _container.registeredBindings

    val bindingsDescription: String get() = _container.bindingsDescription

    /**
     * Exception thrown when there is a dependency loop.
     */
    class DependencyLoopException internal constructor(message: String) : RuntimeException(message)

    /**
     * Exception thrown when asked for a dependency that cannot be found
     */
    class NotFoundException(message: String) : RuntimeException(message)

    /**
     * Exception thrown when there is an overriding error
     */
    class OverridingException(message: String) : RuntimeException(message)

    /**
     * Gets a factory for the given argument type, return type and tag.
     */
    inline fun <reified A, reified T : Any> factory(tag: Any? = null): (A) -> T = typed.factory(typeToken<A>(), typeToken<T>(), tag)

    /**
     * Gets a factory for the given argument type, return type and tag, or null if non is found.
     */
    inline fun <reified A, reified T : Any> factoryOrNull(tag: Any? = null): ((A) -> T)? = typed.factoryOrNull(typeToken<A>(), typeToken<T>(), tag)

    /**
     * Gets a provider for the given type and tag.
     *
     * Whether a provider will re-create a new instance at each call or not depends on the binding scope.
     */
    inline fun <reified T : Any> provider(tag: Any? = null): () -> T = typed.provider(typeToken<T>(), tag)

    /**
     * Gets a provider for the given type and tag, or null if none is found.
     *
     * Whether a provider will re-create a new instance at each call or not depends on the binding scope.
     */
    @Suppress("UNCHECKED_CAST")
    inline fun <reified T : Any> providerOrNull(tag: Any? = null): (() -> T)? = typed.providerOrNull(typeToken<T>(), tag)

    /**
     * Gets an instance for the given type and tag.
     *
     * Whether the returned object is a new instance at each call or not depends on the binding scope.
     */
    inline fun <reified T : Any> instance(tag: Any? = null): T = typed.instance(typeToken<T>(), tag)

    /**
     * Gets an instance for the given type and tag, or null if none is found.
     *
     * Whether the returned object is a new instance at each call or not depends on the binding scope.
     */
    inline fun <reified T : Any> instanceOrNull(tag: Any? = null): T? = typed.instanceOrNull(typeToken<T>(), tag)

    inner class CurriedFactory<A>(val arg: A, val argType: TypeToken<A>) {
        // https://youtrack.jetbrains.com/issue/KT-12126
//        val _container: KodeinContainer get() = this@Kodein._container
        val typed: TKodein get() = this@Kodein.typed

        inline fun <reified T : Any> provider(tag: Any? = null): (() -> T) = typed.factory(argType, typeToken<T>(), tag).toProvider(arg)

        inline fun <reified T : Any> providerOrNull(tag: Any? = null): (() -> T)? = typed.factoryOrNull(argType, typeToken<T>(), tag)?.toProvider(arg)

        inline fun <reified T : Any> instance(tag: Any? = null): T = typed.factory(argType, typeToken<T>(), tag).invoke(arg)

        inline fun <reified T : Any> instanceOrNull(tag: Any? = null): T? = typed.factoryOrNull(argType, typeToken<T>(), tag)?.invoke(arg)
    }

    inline fun <reified A> with(arg: A) = CurriedFactory(arg, typeToken<A>())

    inline fun <reified A, reified T : Any> providerFromFactory(arg: A, tag: Any? = null): () -> T = factory<A, T>(tag).toProvider(arg)

    inline fun <reified A, reified T : Any> providerFromFactoryOrNull(arg: A, tag: Any? = null): (() -> T)? = factoryOrNull<A, T>(tag)?.toProvider(arg)

    inline fun <reified A, reified T : Any> instanceFromFactory(arg: A, tag: Any? = null): T = factory<A, T>(tag).invoke(arg)

    inline fun <reified A, reified T : Any> instanceFromFactoryOrNull(arg: A, tag: Any? = null): T? = factoryOrNull<A, T>(tag)?.invoke(arg)


    val typed = TKodein(_container)

    companion object {
        // The companion object is empty but it exists to allow external libraries to extend it.
    }
}

fun <A, T : Any> ((A) -> T).toProvider(arg: A): () -> T = { invoke(arg) }

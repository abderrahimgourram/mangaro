package tachiyomi.core.common.util.lang

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Think twice before using this. This is a delicate API. It is easy to accidentally create resource or memory leaks when GlobalScope is used.
 *
 * **Possible replacements**
 * - suspend function
 * - custom scope like view or presenter scope
 */
@DelicateCoroutinesApi
fun launchUI(block: suspend CoroutineScope.() -> Unit): Job =
    GlobalScope.launch(Dispatchers.Main + LocalAccountScope.context() + LocalAccountScope.StorageElement(LocalAccountScope.fileOwner()), CoroutineStart.DEFAULT, block)

/**
 * Think twice before using this. This is a delicate API. It is easy to accidentally create resource or memory leaks when GlobalScope is used.
 *
 * **Possible replacements**
 * - suspend function
 * - custom scope like view or presenter scope
 */
@DelicateCoroutinesApi
fun launchIO(block: suspend CoroutineScope.() -> Unit): Job =
    GlobalScope.launch(Dispatchers.IO + LocalAccountScope.context() + LocalAccountScope.StorageElement(LocalAccountScope.fileOwner()), CoroutineStart.DEFAULT, block)

/**
 * Think twice before using this. This is a delicate API. It is easy to accidentally create resource or memory leaks when GlobalScope is used.
 *
 * **Possible replacements**
 * - suspend function
 * - custom scope like view or presenter scope
 */
@DelicateCoroutinesApi
fun launchNow(block: suspend CoroutineScope.() -> Unit): Job =
    GlobalScope.launch(Dispatchers.Main + LocalAccountScope.context() + LocalAccountScope.StorageElement(LocalAccountScope.fileOwner()), CoroutineStart.UNDISPATCHED, block)

fun CoroutineScope.launchUI(block: suspend CoroutineScope.() -> Unit): Job =
    launch(Dispatchers.Main + (coroutineContext[LocalAccountScope.Element] ?: LocalAccountScope.context()) + (coroutineContext[LocalAccountScope.StorageElement] ?: LocalAccountScope.StorageElement(coroutineContext[LocalAccountScope.Element]?.token?.fileOwner ?: LocalAccountScope.fileOwner())), block = block)

fun CoroutineScope.launchIO(block: suspend CoroutineScope.() -> Unit): Job =
    launch(Dispatchers.IO + (coroutineContext[LocalAccountScope.Element] ?: LocalAccountScope.context()) + (coroutineContext[LocalAccountScope.StorageElement] ?: LocalAccountScope.StorageElement(coroutineContext[LocalAccountScope.Element]?.token?.fileOwner ?: LocalAccountScope.fileOwner())), block = block)

fun CoroutineScope.launchNonCancellable(block: suspend CoroutineScope.() -> Unit): Job =
    launchIO { withContext(NonCancellable, block) }

suspend fun <T> withUIContext(block: suspend CoroutineScope.() -> T) = withContext(
    Dispatchers.Main,
    block,
)

suspend fun <T> withIOContext(block: suspend CoroutineScope.() -> T) = withContext(
    Dispatchers.IO,
    block,
)

suspend fun <T> withNonCancellableContext(block: suspend CoroutineScope.() -> T) =
    withContext(NonCancellable, block)

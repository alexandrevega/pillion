package app.pillion.core

import kotlinx.coroutines.CoroutineDispatcher

// Dispatchers.IO is JVM/Android-only (not visible from commonMain), but the frame reader does blocking
// socket reads that must stay off the Default compute pool. expect/actual keeps Android on real IO.
internal expect val ioDispatcher: CoroutineDispatcher

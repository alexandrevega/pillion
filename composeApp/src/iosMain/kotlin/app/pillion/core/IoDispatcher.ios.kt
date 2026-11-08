package app.pillion.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// Native has no dedicated IO pool exposed here; Default is multi-threaded and fine for one reader.
internal actual val ioDispatcher: CoroutineDispatcher = Dispatchers.Default

/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package app.momoding.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.rememberLifecycleOwner
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavMetadataKey
import androidx.navigation3.runtime.get
import androidx.navigation3.runtime.metadata
import androidx.navigation3.scene.OverlayScene
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope

internal val LocalMomodingBottomSheetContentKey = compositionLocalOf<Any?> { null }

internal class MomodingBottomSheetDismissRegistry {
    private data class Handler(
        val routeIdentity: Any,
        val owner: Any,
        val enabled: Boolean,
        val request: () -> Unit,
    )

    private val handlers = mutableStateMapOf<Any, Handler>()
    private val activeContentKey = mutableStateOf<Any?>(null)

    fun register(
        key: Any,
        routeIdentity: Any,
        owner: Any,
        enabled: Boolean,
        request: () -> Unit,
    ) {
        handlers[key] = Handler(routeIdentity, owner, enabled, request)
        activeContentKey.value = key
    }

    fun unregister(key: Any, owner: Any) {
        if (handlers[key]?.owner !== owner) return
        handlers.remove(key)
        if (activeContentKey.value == key) activeContentKey.value = null
    }

    fun canDismiss(key: Any): Boolean =
        activeContentKey.value == key && handlers[key]?.enabled == true

    fun requestDismiss(key: Any): Boolean {
        if (activeContentKey.value != key) return false
        val handler = handlers[key]?.takeIf { it.enabled } ?: return false
        handler.request()
        return true
    }

    fun requestDismissForRoute(routeIdentity: Any): Boolean {
        val key = activeContentKey.value ?: return false
        val handler = handlers[key]?.takeIf {
            it.routeIdentity == routeIdentity && it.enabled
        } ?: return false
        handler.request()
        return true
    }
}

/** Project-scoped copy of the official Navigation3 modal-bottom-sheet recipe. */
@OptIn(ExperimentalMaterial3Api::class)
internal data class MomodingBottomSheetScene<T : Any>(
    override val key: T,
    override val previousEntries: List<NavEntry<T>>,
    override val overlaidEntries: List<NavEntry<T>>,
    private val entry: NavEntry<T>,
    private val properties: ModalBottomSheetProperties,
    private val dismissRegistry: MomodingBottomSheetDismissRegistry,
) : OverlayScene<T> {
    override val entries: List<NavEntry<T>> = listOf(entry)
    override val content: @Composable (() -> Unit) = {
        val lifecycleOwner = rememberLifecycleOwner()
        val contentKey = entry.contentKey
        val sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { target ->
                if (target == SheetValue.Hidden) {
                    dismissRegistry.requestDismiss(contentKey)
                    false
                } else {
                    true
                }
            },
        )
        ModalBottomSheet(
            onDismissRequest = { dismissRegistry.requestDismiss(contentKey) },
            sheetState = sheetState,
            properties = properties,
            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            scrimColor = Color.Black.copy(alpha = 0.37f),
            dragHandle = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(width = 34.dp, height = 4.dp)
                            .background(
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                RoundedCornerShape(2.dp),
                            ),
                    )
                }
            },
        ) {
            CompositionLocalProvider(
                LocalLifecycleOwner provides lifecycleOwner,
                LocalMomodingBottomSheetContentKey provides contentKey,
            ) {
                entry.Content()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
internal class MomodingBottomSheetSceneStrategy<T : Any>(
    private val dismissRegistry: MomodingBottomSheetDismissRegistry,
) : SceneStrategy<T> {
    override fun SceneStrategyScope<T>.calculateScene(entries: List<NavEntry<T>>): Scene<T>? {
        val lastEntry = entries.lastOrNull() ?: return null
        val properties = lastEntry.metadata[BottomSheetKey] ?: return null
        @Suppress("UNCHECKED_CAST")
        return MomodingBottomSheetScene(
            key = lastEntry.contentKey as T,
            previousEntries = entries.dropLast(1),
            overlaidEntries = entries.dropLast(1),
            entry = lastEntry,
            properties = properties,
            dismissRegistry = dismissRegistry,
        )
    }

    companion object {
        fun bottomSheet(
            properties: ModalBottomSheetProperties = ModalBottomSheetProperties(),
        ) = metadata { put(BottomSheetKey, properties) }

        object BottomSheetKey : NavMetadataKey<ModalBottomSheetProperties>
    }
}

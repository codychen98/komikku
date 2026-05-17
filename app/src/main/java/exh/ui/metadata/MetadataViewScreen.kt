package exh.ui.metadata

import androidx.annotation.ColorInt
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.presentation.util.Screen
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.plus

class MetadataViewScreen(
    private val mangaId: Long,
    private val sourceId: Long,
    // KMK -->
    @ColorInt private val seedColor: Int?,
    // KMK <--
) : Screen() {
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { MetadataViewScreenModel(mangaId, sourceId) }
        val navigator = LocalNavigator.currentOrThrow

        val state by screenModel.state.collectAsState()

        @Composable
        fun content() = Scaffold(
            topBar = {
                AppBar(
                    title = screenModel.manga.collectAsState().value?.title,
                    navigateUp = navigator::pop,
                    scrollBehavior = null,
                )
            },
        ) { paddingValues ->
            when (
                @Suppress("NAME_SHADOWING")
                val state = state
            ) {
                MetadataViewState.Loading -> LoadingScreen()
                MetadataViewState.MetadataNotFound -> EmptyScreen(MR.strings.no_results_found)
                MetadataViewState.SourceNotFound -> EmptyScreen(MR.strings.source_empty_screen)
                is MetadataViewState.Success -> {
                    val context = LocalContext.current
                    val items = remember(state.meta) { state.meta.getExtraInfoPairs(context) }
                    MetadataInfoList(
                        items = items,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                paddingValues +
                                    WindowInsets.navigationBars.asPaddingValues() +
                                    topSmallPaddingValues,
                            ),
                    )
                }
            }
        }

        // KMK -->
        TachiyomiTheme(
            seedColor = seedColor?.let { Color(seedColor) }.takeIf { screenModel.themeCoverBased },
        ) {
            // KMK <--
            content()
        }
    }
}

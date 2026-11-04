package com.photoxt.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.photoxt.core.designsystem.PxtCollapsingHeader
import com.photoxt.core.designsystem.PxtTheme
import com.photoxt.core.ui.EmptyState

/** App shell: bottom bar on phones, rail from 600 dp wide (spec §4.1), one back stack per tab. */
@Composable
fun PhotoXtApp(modifier: Modifier = Modifier) {
    val navigation = rememberNavigationState()
    NavigationSuiteScaffold(
        modifier = modifier,
        navigationSuiteItems = {
            TopLevelTab.entries.forEach { tab ->
                val selected = tab == navigation.currentTab
                item(
                    selected = selected,
                    onClick = { navigation.select(tab) },
                    icon = {
                        Icon(
                            painter = painterResource(if (selected) tab.selectedIcon else tab.icon),
                            contentDescription = null
                        )
                    },
                    label = { Text(stringResource(tab.label)) }
                )
            }
        }
    ) {
        NavDisplay(
            entries = navigation.entries(
                entryProvider {
                    TopLevelTab.entries.forEach { tab -> entry(tab) { TabScreen(tab) } }
                }
            ),
            onBack = navigation::goBack
        )
    }
}

/** Phase 0 tab content: the One UI header over an empty state. Later phases replace the body. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabScreen(tab: TopLevelTab, modifier: Modifier = Modifier) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        rememberTopAppBarState()
    )
    Column(modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection)) {
        PxtCollapsingHeader(
            title = stringResource(tab.title),
            scrollBehavior = scrollBehavior,
            modifier = Modifier.testTag(HEADER_TEST_TAG)
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item {
                EmptyState(
                    icon = painterResource(tab.icon),
                    title = stringResource(tab.emptyTitle),
                    body = stringResource(tab.emptyBody),
                    modifier = Modifier.fillParentMaxSize()
                )
            }
        }
    }
}

const val HEADER_TEST_TAG = "header"

@PreviewLightDark
@Composable
private fun TabScreenPreview() {
    PxtTheme {
        Surface {
            TabScreen(TopLevelTab.Pictures)
        }
    }
}

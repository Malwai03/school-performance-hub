package com.schoolapp.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.schoolapp.core.ui.theme.WarningAmber

/**
 * Pull down to download from the school server ([onRefresh]); [notice] says when what is shown
 * may not be the server's latest. The content needs a scrollable child for the pull to start.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncRefreshBox(
    syncing: Boolean,
    notice: String?,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Column(modifier) {
        if (notice != null) {
            Text(
                notice,
                style = MaterialTheme.typography.bodySmall,
                color = WarningAmber,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        PullToRefreshBox(
            isRefreshing = syncing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize(), content = content)
        }
    }
}

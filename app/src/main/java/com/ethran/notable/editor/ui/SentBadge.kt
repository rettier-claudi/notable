package com.ethran.notable.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ethran.notable.R
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.editor.EditorViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Lock

/**
 * Top-right label on a page that was sent: "Sent · locked" on a locked quick page, "Sent" on a
 * notebook that has not been changed since. Purely informational -- it takes no touches, so
 * gestures underneath keep working.
 */
@Composable
fun SentBadge(viewModel: EditorViewModel) {
    val state by viewModel.toolbarState.collectAsStateWithLifecycle()
    if (!state.isPageLocked && !state.isSentMarked) return
    val toolbarOnTop = state.isToolbarOpen &&
        GlobalAppSettings.current.toolbarPosition == AppSettings.Position.Top
    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = if (toolbarOnTop) 48.dp else 8.dp, end = 16.dp)
                .background(Color.White)
                .border(1.dp, Color.Black, RectangleShape)
                .padding(horizontal = 6.dp, vertical = 3.dp)
        ) {
            Icon(
                imageVector = if (state.isPageLocked) FeatherIcons.Lock else FeatherIcons.Check,
                contentDescription = null,
                tint = Color.Black,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = stringResource(
                    if (state.isPageLocked) R.string.sent_badge_locked else R.string.sent_badge_marked
                ),
                color = Color.Black,
                fontSize = 13.sp,
            )
        }
    }
}

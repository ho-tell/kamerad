package at.tellioglu.kamerad.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import at.tellioglu.kamerad.R

/**
 * The "back" button the Karoo shows in the lower left corner of its own screens: grey-blue,
 * flush with the left screen edge, rounded on the right, with a black arrow (measured from a Karoo screenshot).
 */
@Composable
fun KarooBackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(bottom = 10.dp)
            // Wider than what is visible: the left part is pushed beyond the screen edge
            .offset(x = (-12).dp)
            .size(width = 64.dp, height = 51.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(Color(0xFFA0B4BE))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_arrow_back),
            contentDescription = "Back",
            tint = Color.Black,
            // Centre the arrow in the visible part of the button
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

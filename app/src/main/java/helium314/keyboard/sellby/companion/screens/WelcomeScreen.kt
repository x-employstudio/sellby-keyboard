// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import helium314.keyboard.latin.R
import helium314.keyboard.sellby.companion.theme.SellbyColors
import kotlin.math.max

/** Ported from welcome_page.dart: "Hallo Seller!" splash with mascot image + body copy (final
 *  marketing copy, not a Lorem Ipsum placeholder) and a "Mulai Aplikasi" pill button. */
@Composable
fun WelcomeScreen(onStart: () -> Unit) {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    Box(Modifier.fillMaxSize().background(SellbyColors.TealDark)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp)) {
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "Hallo Seller!",
                    color = SellbyColors.White,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    "Siap jadi smart and fastest\nSeller in the world?",
                    color = SellbyColors.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp,
                )
                Spacer(Modifier.height(5.dp))
                Image(
                    painter = painterResource(R.drawable.sellby_companion_welcome_seller),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.height(max(screenHeight.value * 0.28f, 140f).dp).fillMaxWidth(),
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    "Sellby Keyboard bikin jualan jadi lebih sat-set\n" +
                        "Biar kamu fokus jualan, bukan sibuk ngurusin chat",
                    color = SellbyColors.White,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp,
                )
            }
            Box(
                Modifier.fillMaxWidth().padding(bottom = 28.dp, top = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Button(
                    onClick = onStart,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SellbyColors.White,
                        contentColor = SellbyColors.TealDark,
                    ),
                    shape = RoundedCornerShape(30.dp),
                    contentPadding = PaddingValues(vertical = 14.dp),
                    modifier = Modifier.fillMaxWidth(0.65f),
                ) {
                    Text("Mulai Aplikasi", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

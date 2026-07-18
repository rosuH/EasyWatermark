package me.rosuh.easywatermark.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.cmp_spike_hello
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * S-i18n-0 packaging spike for official Compose Multiplatform resources.
 *
 * Product screens still use Android [androidx.compose.ui.res.stringResource] / bags — this is a
 * Temporary witness + unit-test entry for CMP-9547 packaging proof only. *
 * Locale qualifier selection is proven by host/tests that set the platform locale (or Compose
 * resource environment) before calling [resolveSpikeHello] / [CmpSpikeHelloLabel]. The public
 * [ResourceEnvironment] constructor is library-internal, so forced-locale tests use system locale
 * changes on Desktop JVM (see [CmpSpikeResourcesTest]).
 */
object CmpSpikeResources {
    const val KEY = "cmp_spike_hello"
    const val EXPECTED_EN = "CMP spike hello"
    const val EXPECTED_ZH_CN = "CMP 探针你好"

    val stringResource: StringResource
        get() = Res.string.cmp_spike_hello

    /**
 * Real multiplatform resource read (suspend [getString]) using the system resource
 * Environment. Used by unit tests and host witnesses so packaging into APK/framework is * exercised — not a bag and not Android R.string.
     */
    suspend fun resolveSpikeHello(): String = getString(Res.string.cmp_spike_hello)
}

/**
 * Tiny on-screen witness. Hosts may show this only in DEBUG / headless paths.
 */
@Composable
fun CmpSpikeHelloLabel(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(Res.string.cmp_spike_hello),
        modifier = modifier
            .padding(8.dp)
            .testTag(CmpSpikeResources.KEY),
        fontSize = 12.sp,
    )
}

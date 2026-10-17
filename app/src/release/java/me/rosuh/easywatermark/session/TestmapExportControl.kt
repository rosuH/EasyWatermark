package me.rosuh.easywatermark.session

import android.content.Context

/** Production builds never inspect Testmap control files. */
@Suppress("UNUSED_PARAMETER")
internal fun withTestmapExportControl(context: Context, delegate: ExportPipelinePort): ExportPipelinePort = delegate

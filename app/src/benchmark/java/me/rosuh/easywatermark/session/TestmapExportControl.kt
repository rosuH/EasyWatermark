package me.rosuh.easywatermark.session

import android.content.Context

/** Measurements use the production port, without debug controls or file access. */
@Suppress("UNUSED_PARAMETER")
internal fun withTestmapExportControl(context: Context, delegate: ExportPipelinePort): ExportPipelinePort = delegate

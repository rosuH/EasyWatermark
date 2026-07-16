package me.rosuh.easywatermark.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter

/**
 * S-i18n-3: action icons for all platforms come from composeResources via [SharedProductDrawables].
 * Legacy hand-drawn ImageVectors removed.
 */
@Composable
fun rememberSharedAboutPainter(): Painter = SharedProductDrawables.aboutPainter()

@Composable
fun rememberSharedBackPainter(): Painter = SharedProductDrawables.backPainter()

@Composable
fun rememberSharedAddImagePainter(): Painter = SharedProductDrawables.pickerImagePainter()

@Composable
fun rememberSharedSavePainter(): Painter = SharedProductDrawables.savePainter()

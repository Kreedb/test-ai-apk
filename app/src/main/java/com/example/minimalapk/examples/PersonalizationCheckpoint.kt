package com.example.minimalapk.examples

import android.content.Context
import java.io.File

/** Checkpoint written by the on-device trainer; the camera plugin only reads it. */
internal fun personalizationCheckpoint(context: Context): File =
    File(File(context.filesDir, "examples"), "personalization-head.bin")

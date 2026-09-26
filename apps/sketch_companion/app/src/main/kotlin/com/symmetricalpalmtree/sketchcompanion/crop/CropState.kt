package com.symmetricalpalmtree.sketchcompanion.crop

import kotlinx.serialization.Serializable

/**
 * Where the 3:4 frame sits on the oriented source photo, independent of any screen: [zoom] is 1 at
 * "cover" (the un-rotated photo just fills the frame) up to [CropMath.MAX_ZOOM]; ([cx], [cy]) is
 * the source point under the frame's centre as fractions 0…1 of the source's width and height;
 * [angle] is the photo's turn about that centre in degrees, clockwise on screen.
 */
@Serializable
data class CropState(val zoom: Float = 1f, val cx: Float = 0.5f, val cy: Float = 0.5f, val angle: Float = 0f)

package dev.immichwall.crop

import dev.immichwall.crop.CropTarget.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CropTargetTest {
    @Test fun `nothing seen gives no box`() = assertNull(CropTarget.unionBox(emptyList()))

    @Test fun `one surface is its own box`() = assertEquals(Size(1080, 2400), CropTarget.unionBox(listOf(Size(1080, 2400))))

    @Test fun `tall cover and wide inner panels give widest by tallest`() {
        val seen = listOf(Size(1080, 2520), Size(2256, 2504))
        assertEquals(Size(2256, 2520), CropTarget.unionBox(seen))
    }

    @Test fun `landscape of the inner panel widens the box`() {
        val seen = listOf(Size(1080, 2520), Size(2256, 2504), Size(2504, 2256))
        assertEquals(Size(2504, 2520), CropTarget.unionBox(seen))
    }

    @Test fun `a centred focus centres the photo`() =
        assertEquals(-444f, CropTarget.offset(canvas = 1080f, scaled = 1968f, focus = 0.5f))

    @Test fun `an off-centre focus slides the photo to keep it in view`() {
        // Faces at 20% of the width: the left edge is as far as the photo can slide.
        assertEquals(0f, CropTarget.offset(1080f, 1968f, 0.2f))
        // Faces at 40%: 0.4 * 1968 = 787 sits at the middle (540) of the surface.
        assertEquals(540f - 787.2f, CropTarget.offset(1080f, 1968f, 0.4f), 0.01f)
        // Faces at the far right: the right edge is the limit.
        assertEquals(1080f - 1968f, CropTarget.offset(1080f, 1968f, 1f))
    }

    @Test fun `a photo that exactly fits does not move`() =
        assertEquals(0f, CropTarget.offset(1080f, 1080f, 0.1f))

    @Test fun `remember ignores duplicates and nonsense and caps the list`() {
        var seen = emptyList<Size>()
        seen = CropTarget.remember(seen, 1080, 2520)
        seen = CropTarget.remember(seen, 1080, 2520)
        seen = CropTarget.remember(seen, 0, 2520)
        assertEquals(listOf(Size(1080, 2520)), seen)
        for (i in 1..10) seen = CropTarget.remember(seen, 100 + i, 200)
        assertEquals(CropTarget.MAX_SURFACES, seen.size)
        assertEquals(Size(110, 200), seen.last())
    }

    @Test fun `a first report after a reset does not shrink a known box`() {
        // The list was emptied (or never filled) while the crop is already the two-panel
        // box; the cover display reports first.
        val seen = CropTarget.rememberReported(emptyList(), cropWidth = 2256, cropHeight = 2520, width = 1080, height = 2520)
        assertEquals(Size(2256, 2520), CropTarget.unionBox(seen))
        assertEquals(true, Size(1080, 2520) in seen)
    }

    @Test fun `with no stored crop the first report is the box`() {
        val seen = CropTarget.rememberReported(emptyList(), cropWidth = 0, cropHeight = 0, width = 1080, height = 2520)
        assertEquals(listOf(Size(1080, 2520)), seen)
        assertEquals(Size(1080, 2520), CropTarget.unionBox(seen))
    }

    @Test fun `a stored crop is not added once shapes are remembered`() {
        val seen = CropTarget.rememberReported(listOf(Size(1080, 2520)), cropWidth = 2504, cropHeight = 2520, width = 2256, height = 2504)
        assertEquals(listOf(Size(1080, 2520), Size(2256, 2504)), seen)
    }

    @Test fun `encode and decode round-trip and tolerate junk`() {
        val seen = listOf(Size(1080, 2520), Size(1968, 2184))
        assertEquals(seen, CropTarget.decode(CropTarget.encode(seen)))
        assertEquals(emptyList(), CropTarget.decode(""))
        assertEquals(listOf(Size(5, 6)), CropTarget.decode("axb,5x6,7x,-1x4,1x2x3"))
    }
}

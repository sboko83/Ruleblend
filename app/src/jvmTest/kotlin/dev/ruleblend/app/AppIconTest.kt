package dev.ruleblend.app

import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

class AppIconTest {
    @Test
    fun `app icon has transparent corners and an opaque centre`() {
        val image = ImageIO.read(requireNotNull(javaClass.getResourceAsStream("/icons/AppIcon.png")))

        assertEquals(0, image.getRGB(0, 0).ushr(24), "top-left corner must be transparent")
        assertEquals(0, image.getRGB(image.width - 1, image.height - 1).ushr(24), "bottom-right corner must be transparent")
        assertEquals(255, image.getRGB(image.width / 2, image.height / 2).ushr(24), "icon centre must stay opaque")
    }
}

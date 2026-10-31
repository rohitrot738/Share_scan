package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ProductPhotosTest {
    private val json = """
        {"v":1,"stocks":{
          "MRF":{"logo":"product-photos/MRF/logo.webp","banner":"product-photos/MRF/banner.webp",
                 "p":{"tyre":"product-photos/MRF/tyre.webp","rockets":"product-photos/MRF/rockets.webp","toys":"https://x/toys.jpg"},
                 "c":{"product-photos/MRF/tyre.webp":"A. Person · CC BY-SA 4.0 · Wikimedia Commons","product-photos/MRF/logo.webp":""}},
          "EMPTYCO":{"p":{}},
          "SNEAKY":{"logo":"product-photos/../../secret.webp","banner":"product-photos/SNEAKY/banner.png"},
          "M&M":{"logo":"product-photos/M_M/logo.webp"}}}
    """.trimIndent()

    @Test
    fun picturesAreTheAppsOwnFilesForProductsItKnows() {
        val photos = ProductPhotos.parse(json)
        assertEquals(2, photos.size)
        val mrf = photos.of("MRF")!!
        assertEquals("product-photos/MRF/logo.webp", mrf.logo)
        assertEquals("product-photos/MRF/banner.webp", mrf.banner)
        assertEquals(mapOf("tyre" to "product-photos/MRF/tyre.webp"), mrf.photos)
        assertEquals(mapOf("product-photos/MRF/tyre.webp" to "A. Person · CC BY-SA 4.0 · Wikimedia Commons"), mrf.credits)
        assertEquals("product-photos/M_M/logo.webp", photos.of("M&M")!!.logo)
        assertNull(photos.of("EMPTYCO"))
        assertNull(photos.of("SNEAKY"))
        assertNull(photos.of("TCS"))
    }

    @Test
    fun brokenOrUnexpectedInputIsEmpty() {
        assertSame(ProductPhotos.EMPTY, ProductPhotos.parse(null))
        assertSame(ProductPhotos.EMPTY, ProductPhotos.parse("{"))
        assertSame(ProductPhotos.EMPTY, ProductPhotos.parse("""{"v":3,"stocks":{}}"""))
    }
}

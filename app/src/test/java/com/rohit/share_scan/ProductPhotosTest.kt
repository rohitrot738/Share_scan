package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ProductPhotosTest {
    private val json = """
        {"v":1,"stocks":{
          "MRF":{"logo":"https://commons.wikimedia.org/wiki/Special:FilePath/MRF%20logo.svg?width=256",
                 "banner":"http://www.mrftyres.com/img/banner.jpg",
                 "p":{"tyre":"https://upload.wikimedia.org/a.jpg","rockets":"https://upload.wikimedia.org/b.jpg","toys":"ftp://x"},
                 "c":{"https://upload.wikimedia.org/a.jpg":"A. Person · CC BY-SA 4.0 · Wikimedia Commons","https://x/y.jpg":""}},
          "EMPTYCO":{"p":{}},
          "ICONCO":{"logo":"https://iconco.in/apple-touch-icon.png"}}}
    """.trimIndent()

    @Test
    fun picturesAreReadWithSecureAddressesAndKnownProductsOnly() {
        val photos = ProductPhotos.parse(json)
        assertEquals(2, photos.size)
        val mrf = photos.of("MRF")!!
        assertEquals("https://www.mrftyres.com/img/banner.jpg", mrf.banner)
        assertEquals(mapOf("tyre" to "https://upload.wikimedia.org/a.jpg"), mrf.photos)
        assertEquals(mapOf("https://upload.wikimedia.org/a.jpg" to "A. Person · CC BY-SA 4.0 · Wikimedia Commons"), mrf.credits)
        assertEquals("https://iconco.in/apple-touch-icon.png", photos.of("ICONCO")!!.logo)
        assertNull(photos.of("EMPTYCO"))
        assertNull(photos.of("TCS"))
    }

    @Test
    fun brokenOrUnexpectedInputIsEmpty() {
        assertSame(ProductPhotos.EMPTY, ProductPhotos.parse(null))
        assertSame(ProductPhotos.EMPTY, ProductPhotos.parse("{"))
        assertSame(ProductPhotos.EMPTY, ProductPhotos.parse("""{"v":3,"stocks":{}}"""))
    }
}

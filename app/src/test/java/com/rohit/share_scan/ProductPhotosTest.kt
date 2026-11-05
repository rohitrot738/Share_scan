package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    @Test
    fun brandsComeGroupedByWhatTheyAre() {
        val brands = """
            {"v":1,"stocks":{
              "ITC":{"src":"itcportal.com","g":[
                {"k":"food","b":[["Aashirvaad","brand-photos/ITC/aashirvaad.webp"],["Bingo!","brand-photos/ITC/bingo.webp"]]},
                {"k":"rockets","b":[["Zoom","brand-photos/ITC/zoom.webp"]]},
                {"k":"paper","b":[["Classmate","brand-photos/../secret.webp"],["",""]]},
                {"k":"household","b":[]}]},
              "NEWCO":{"g":[{"k":"food","b":[["Snacko","brand-photos/NEWCO/snacko.webp"]]}]}}}
        """.trimIndent()
        val photos = ProductPhotos.parse(json, brands)
        val itc = photos.of("ITC")!!
        assertEquals("itcportal.com", itc.brandSource)
        // An unknown product and a group without brands are left out.
        assertEquals(listOf("food", "paper"), itc.brands.map { it.product })
        assertEquals(
            listOf(ProductPhotos.Brand("Aashirvaad", "brand-photos/ITC/aashirvaad.webp"), ProductPhotos.Brand("Bingo!", "brand-photos/ITC/bingo.webp")),
            itc.brands[0].brands,
        )
        // A picture outside the app's brand folder is left out and the name stays; a brand without a name is left out.
        assertEquals(listOf(ProductPhotos.Brand("Classmate", null)), itc.brands[1].brands)
        // A company with brands alone has its entry; one with pictures alone has no brands.
        assertEquals("Snacko", photos.of("NEWCO")!!.brands.single().brands.single().name)
        assertNull(photos.of("NEWCO")!!.brandSource)
        assertTrue(photos.of("MRF")!!.brands.isEmpty())
        assertEquals(4, photos.size)
        // A broken brands file leaves the pictures as they are.
        assertEquals(2, ProductPhotos.parse(json, "{").size)
        assertEquals(2, ProductPhotos.parse(json, null).size)
    }

    /** The brands the app carries: every picture it names ships with it. */
    @Test
    fun everyCarriedBrandPictureShips() {
        val assets = listOf("src/main/assets", "app/src/main/assets").map(::File).first { it.exists() }
        val photos = ProductPhotos.parse(File(assets, "product-photos.json").readText(), File(assets, "brands.json").readText())
        val itc = photos.of("ITC")!!
        assertEquals(listOf("food", "personalcare", "paper", "household"), itc.brands.map { it.product })
        assertEquals(44, itc.brands.sumOf { it.brands.size })
        val pictures = itc.brands.flatMap { group -> group.brands.map { it.picture } }
        assertTrue(pictures.all { it != null })
        assertEquals(emptyList<String>(), pictures.filterNotNull().filterNot { File(assets, it).isFile })
        // The logo the app had for ITC stays.
        assertEquals("product-photos/ITC/logo.webp", itc.logo)
    }
}

package com.rohit.share_scan

/**
 * The colours one theme paints its Milky Way with (see [GalaxySkyView]). Colours are linear 0..1 RGB.
 *
 * @property spaceTop deep space at the top; it must match the theme's opaque status-bar strip (its colorBackground)
 * @property spaceBottom deep space at the bottom
 * @property arm the band's light out along its arms
 * @property core the band's light at its core
 * @property brightness how bright the band and its core are
 * @property gas the faint gas glow scattered along the band: RGB, then strength
 * @property nebulae nebulae, seven numbers each: u, v and size in band units (u along the band from the core, v
 *   across it), RGB, then strength
 * @property aurora how strong the aurora curtains low in the sky are; 0 for none
 */
class SkyPalette(
    val spaceTop: FloatArray,
    val spaceBottom: FloatArray,
    val arm: FloatArray,
    val core: FloatArray,
    val brightness: Float,
    val gas: FloatArray,
    val nebulae: FloatArray,
    val aurora: Float = 0f,
) {
    companion object {
        /** A Milky Way on a dark night: cool grey-blue arms, a cream and amber core, red hydrogen, a blue reflection nebula. */
        val GALAXY = SkyPalette(
            spaceTop = rgb(2, 1, 8),
            spaceBottom = rgb(3, 6, 20),
            arm = floatArrayOf(0.80f, 0.85f, 1.00f),
            core = floatArrayOf(1.00f, 0.83f, 0.50f),
            brightness = 1f,
            gas = floatArrayOf(1.0f, 0.30f, 0.45f, 0.16f),
            nebulae = floatArrayOf(
                0.10f, 0.15f, 0.045f, 1.0f, 0.30f, 0.45f, 0.55f, // hydrogen, pink-red, below the core
                0.17f, 0.08f, 0.022f, 1.0f, 0.35f, 0.55f, 0.45f, // a small one beside it
                0.32f, -0.20f, 0.065f, 0.42f, 0.58f, 1.0f, 0.40f, // blue reflection nebula above the band
                0.38f, -0.14f, 0.03f, 1.0f, 0.78f, 0.45f, 0.30f, // its warm star
                -0.95f, -0.02f, 0.07f, 1.0f, 0.28f, 0.42f, 0.40f, // hydrogen further down the band
                -0.50f, 0.10f, 0.04f, 0.20f, 0.85f, 0.80f, 0.25f, // teal
                0.62f, 0.05f, 0.05f, 1.0f, 0.32f, 0.50f, 0.30f, // hydrogen towards the top
            ),
        )

        /** Rose and magenta: violet-pink arms, a rose-gold core and big magenta, pink and violet gas clouds. */
        val NEBULA = SkyPalette(
            spaceTop = rgb(7, 2, 10),
            spaceBottom = rgb(16, 4, 22),
            arm = floatArrayOf(1.00f, 0.64f, 0.98f),
            core = floatArrayOf(1.00f, 0.68f, 0.70f),
            brightness = 0.95f,
            gas = floatArrayOf(1.0f, 0.28f, 0.62f, 0.42f),
            nebulae = floatArrayOf(
                0.10f, 0.15f, 0.08f, 1.0f, 0.25f, 0.75f, 1.10f, // magenta, below the core
                0.32f, -0.20f, 0.09f, 0.80f, 0.35f, 1.0f, 0.85f, // violet, above the band
                -0.60f, 0.05f, 0.10f, 1.0f, 0.30f, 0.55f, 0.90f, // rose, further down the band
                0.62f, 0.05f, 0.07f, 1.0f, 0.42f, 0.80f, 0.65f, // pink, towards the top
                -0.20f, -0.25f, 0.06f, 0.65f, 0.40f, 1.0f, 0.50f, // purple, beside the band
                -1.00f, 0.12f, 0.08f, 1.0f, 0.35f, 0.70f, 0.60f, // pink, at the lower end
            ),
        )

        /** Polar night: a cool cyan-white Milky Way, green and teal gas, and aurora curtains low in the sky. */
        val AURORA = SkyPalette(
            spaceTop = rgb(1, 5, 8),
            spaceBottom = rgb(2, 12, 16),
            arm = floatArrayOf(0.70f, 0.92f, 1.00f),
            core = floatArrayOf(0.88f, 1.00f, 0.86f),
            brightness = 0.85f,
            gas = floatArrayOf(0.25f, 1.0f, 0.70f, 0.12f),
            nebulae = floatArrayOf(
                0.10f, 0.15f, 0.06f, 0.20f, 1.0f, 0.75f, 0.45f, // green, below the core
                0.32f, -0.20f, 0.07f, 0.35f, 0.75f, 1.0f, 0.40f, // teal, above the band
                -0.60f, 0.05f, 0.08f, 0.25f, 1.0f, 0.65f, 0.35f, // green, further down the band
                0.62f, 0.05f, 0.05f, 0.40f, 0.85f, 1.0f, 0.30f, // cyan, towards the top
            ),
            aurora = 0.7f,
        )

        /** A clear midnight: near-black navy, a quieter blue-white Milky Way and faint blue nebulae. */
        val MIDNIGHT = SkyPalette(
            spaceTop = rgb(0, 0, 0),
            spaceBottom = rgb(2, 5, 16),
            arm = floatArrayOf(0.50f, 0.70f, 1.00f),
            core = floatArrayOf(0.72f, 0.82f, 1.00f),
            brightness = 0.72f,
            gas = floatArrayOf(0.55f, 0.55f, 1.0f, 0.06f),
            nebulae = floatArrayOf(
                0.32f, -0.20f, 0.065f, 0.42f, 0.58f, 1.0f, 0.40f, // blue reflection nebula above the band
                -0.50f, 0.10f, 0.05f, 0.30f, 0.70f, 1.0f, 0.25f, // pale blue, down the band
                0.10f, 0.15f, 0.04f, 0.55f, 0.50f, 1.0f, 0.22f, // periwinkle, below the core
            ),
        )

        private fun rgb(r: Int, g: Int, b: Int) = floatArrayOf(r / 255f, g / 255f, b / 255f)
    }
}

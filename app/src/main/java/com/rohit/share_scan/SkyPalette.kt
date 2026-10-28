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

        /** Dusk: a plum sky warming to ember low down, a gold core, orange and red clouds below it and magenta above. */
        val SUNSET = SkyPalette(
            spaceTop = rgb(11, 3, 8),
            spaceBottom = rgb(44, 15, 6),
            arm = floatArrayOf(1.00f, 0.68f, 0.42f),
            core = floatArrayOf(1.00f, 0.80f, 0.38f),
            brightness = 0.95f,
            gas = floatArrayOf(1.0f, 0.42f, 0.15f, 0.40f),
            nebulae = floatArrayOf(
                0.05f, 0.16f, 0.10f, 1.0f, 0.45f, 0.08f, 1.00f, // orange, below the core
                0.32f, -0.20f, 0.09f, 0.85f, 0.22f, 0.75f, 0.80f, // magenta, above the band
                0.62f, 0.05f, 0.08f, 0.70f, 0.30f, 1.0f, 0.70f, // violet, towards the top
                -0.55f, 0.08f, 0.10f, 1.0f, 0.22f, 0.18f, 0.90f, // red, further down the band
                -0.95f, -0.05f, 0.09f, 1.0f, 0.40f, 0.10f, 0.80f, // orange, at the lower end
                -0.20f, 0.24f, 0.06f, 1.0f, 0.68f, 0.20f, 0.55f, // gold, beside the band
            ),
        )

        /** The deep sea: deep-blue space, a cool blue-white Milky Way, and cyan, teal and azure nebulae. */
        val OCEAN = SkyPalette(
            spaceTop = rgb(1, 6, 20),
            spaceBottom = rgb(2, 18, 44),
            arm = floatArrayOf(0.52f, 0.78f, 1.00f),
            core = floatArrayOf(0.72f, 0.95f, 1.00f),
            brightness = 0.85f,
            gas = floatArrayOf(0.12f, 0.72f, 1.0f, 0.28f),
            nebulae = floatArrayOf(
                0.08f, 0.16f, 0.09f, 0.05f, 0.85f, 1.0f, 0.95f, // cyan, below the core
                0.34f, -0.20f, 0.09f, 0.20f, 0.50f, 1.0f, 0.75f, // azure, above the band
                -0.60f, 0.06f, 0.10f, 0.08f, 1.0f, 0.85f, 0.65f, // teal, further down the band
                0.62f, 0.04f, 0.07f, 0.30f, 0.82f, 1.0f, 0.55f, // pale cyan, towards the top
                -1.00f, -0.04f, 0.08f, 0.25f, 0.45f, 1.0f, 0.60f, // deep blue, at the lower end
            ),
        )

        /** Emerald and jade: a green-black sky, a jade Milky Way with a pale green core and emerald gas. */
        val EMERALD = SkyPalette(
            spaceTop = rgb(1, 8, 4),
            spaceBottom = rgb(3, 22, 13),
            arm = floatArrayOf(0.55f, 1.00f, 0.76f),
            core = floatArrayOf(0.72f, 1.00f, 0.70f),
            brightness = 0.88f,
            gas = floatArrayOf(0.10f, 1.0f, 0.48f, 0.38f),
            nebulae = floatArrayOf(
                0.10f, 0.15f, 0.09f, 0.06f, 1.0f, 0.45f, 1.00f, // emerald, below the core
                0.30f, -0.22f, 0.08f, 0.15f, 0.95f, 0.72f, 0.75f, // jade, above the band
                -0.55f, 0.06f, 0.10f, 0.08f, 1.0f, 0.40f, 0.85f, // emerald, further down the band
                0.65f, 0.04f, 0.06f, 0.55f, 1.0f, 0.22f, 0.50f, // lime, towards the top
                -0.20f, -0.25f, 0.06f, 0.08f, 0.85f, 0.85f, 0.45f, // teal, beside the band
            ),
        )

        /** Mars at night: a dusty red-brown sky, a rust and salmon Milky Way and big blood-red clouds. */
        val MARS = SkyPalette(
            spaceTop = rgb(12, 3, 2),
            spaceBottom = rgb(40, 8, 5),
            arm = floatArrayOf(1.00f, 0.44f, 0.32f),
            core = floatArrayOf(1.00f, 0.60f, 0.42f),
            brightness = 0.80f,
            gas = floatArrayOf(1.0f, 0.18f, 0.10f, 0.45f),
            nebulae = floatArrayOf(
                0.12f, 0.14f, 0.09f, 1.0f, 0.12f, 0.08f, 1.10f, // blood red, below the core
                0.35f, -0.18f, 0.08f, 1.0f, 0.35f, 0.18f, 0.70f, // rust, above the band
                -0.60f, 0.05f, 0.11f, 0.95f, 0.10f, 0.16f, 0.95f, // crimson, further down the band
                0.62f, 0.06f, 0.07f, 1.0f, 0.42f, 0.30f, 0.60f, // coral, towards the top
                -1.00f, 0.10f, 0.09f, 1.0f, 0.18f, 0.08f, 0.75f, // red, at the lower end
                -0.25f, -0.22f, 0.06f, 0.90f, 0.30f, 0.15f, 0.45f, // rust, beside the band
            ),
        )

        /** Royal gold: deep purple space, lavender arms round a big golden core, and violet and gold nebulae. */
        val ROYAL_GOLD = SkyPalette(
            spaceTop = rgb(8, 3, 15),
            spaceBottom = rgb(22, 8, 40),
            arm = floatArrayOf(0.82f, 0.70f, 1.00f),
            core = floatArrayOf(1.00f, 0.76f, 0.28f),
            brightness = 1f,
            gas = floatArrayOf(0.62f, 0.30f, 1.0f, 0.30f),
            nebulae = floatArrayOf(
                0.08f, 0.15f, 0.09f, 1.0f, 0.68f, 0.12f, 1.00f, // gold, below the core
                0.32f, -0.20f, 0.09f, 0.60f, 0.28f, 1.0f, 0.85f, // violet, above the band
                -0.60f, 0.06f, 0.10f, 0.78f, 0.25f, 0.95f, 0.75f, // purple, further down the band
                0.62f, 0.05f, 0.07f, 1.0f, 0.55f, 0.15f, 0.65f, // amber, towards the top
                -1.00f, -0.03f, 0.08f, 0.55f, 0.30f, 1.0f, 0.60f, // violet, at the lower end
            ),
        )

        /** Frost: a pale steel-blue sky, a bright blue-white Milky Way, ice-blue nebulae and a faint aurora. */
        val FROST = SkyPalette(
            spaceTop = rgb(5, 10, 16),
            spaceBottom = rgb(20, 36, 54),
            arm = floatArrayOf(0.72f, 0.88f, 1.00f),
            core = floatArrayOf(0.92f, 0.97f, 1.00f),
            brightness = 1f,
            gas = floatArrayOf(0.55f, 0.85f, 1.0f, 0.22f),
            nebulae = floatArrayOf(
                0.32f, -0.20f, 0.09f, 0.45f, 0.78f, 1.0f, 0.75f, // ice-blue reflection nebula, above the band
                0.10f, 0.15f, 0.06f, 0.65f, 0.85f, 1.0f, 0.50f, // pale blue, below the core
                -0.55f, 0.08f, 0.09f, 0.50f, 0.92f, 1.0f, 0.55f, // pale cyan, further down the band
                0.62f, 0.05f, 0.06f, 0.70f, 0.70f, 1.0f, 0.40f, // lavender, towards the top
            ),
            aurora = 0.3f,
        )

        /** Space Glass's colours: its deep navy, a cool blue-white band, and cyan, emerald and indigo gas. */
        val SPACE_GLASS = SkyPalette(
            spaceTop = rgb(11, 15, 25),
            spaceBottom = rgb(6, 26, 34),
            arm = floatArrayOf(0.55f, 0.80f, 1.00f),
            core = floatArrayOf(0.75f, 0.95f, 1.00f),
            brightness = 0.85f,
            gas = floatArrayOf(0.20f, 0.75f, 0.95f, 0.25f),
            nebulae = floatArrayOf(
                0.08f, 0.16f, 0.09f, 0.22f, 0.74f, 0.97f, 0.85f, // cyan, below the core
                0.34f, -0.20f, 0.09f, 0.06f, 0.73f, 0.51f, 0.75f, // emerald, above the band
                -0.60f, 0.06f, 0.10f, 0.39f, 0.40f, 0.95f, 0.55f, // indigo, further down the band
                0.62f, 0.04f, 0.07f, 0.22f, 0.74f, 0.97f, 0.45f, // pale cyan, towards the top
            ),
        )

        private fun rgb(r: Int, g: Int, b: Int) = floatArrayOf(r / 255f, g / 255f, b / 255f)
    }
}

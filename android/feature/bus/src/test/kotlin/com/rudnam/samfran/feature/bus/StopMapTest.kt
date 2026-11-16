package com.rudnam.samfran.feature.bus

import org.junit.Assert.assertTrue
import org.junit.Test

class StopMapTest {
    @Test
    fun `google's map goes in an iframe, since it won't draw as a whole page`() {
        val page = stopMapPage("https://www.google.com/maps?q=34.97,138.38&z=17&hl=ja&output=embed")
        assertTrue(page, page.contains("""<iframe src="https://www.google.com/maps?q=34.97,138.38&amp;z=17&amp;hl=ja&amp;output=embed""""))
    }
}

package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class StockNewsTest {
    private fun ist(text: String): Long = LocalDateTime.parse(text).atZone(MarketClock.INDIA).toInstant().toEpochMilli()

    @Test
    fun readsNseAnnouncements() {
        val json = """[
          {"an_dt":"19-Oct-2026 11:30:07","sort_date":"2026-10-19 11:30:07","symbol":"TCS","seq_id":"1001",
           "desc":"Updates","sm_name":"Tata Consultancy Services Limited",
           "attchmntText":"Tata Consultancy Services Limited has informed the Exchange regarding 'Press Release - TCS BaNCS for Treasury marks its entry into Sri Lanka with Indian Bank'.",
           "attchmntFile":"https://nsearchives.nseindia.com/corporate/TCS_PR.pdf"},
          {"an_dt":"24-Oct-2026 01:33:09","sort_date":"2026-10-24 01:33:09","symbol":"RCOM","seq_id":"1002",
           "desc":"Action(s) taken or orders passed","sm_name":"Reliance Communications Limited",
           "attchmntText":"Reliance Communications Limited has informed the Exchange about Action(s) taken or orders passed",
           "attchmntFile":"https://nsearchives.nseindia.com/corporate/RCOM.pdf"},
          {"an_dt":"23-Oct-2026 23:53:32","symbol":"SHRIPISTON","seq_id":"1003","desc":"Shareholders meeting",
           "attchmntText":"SPR Auto Technologies Limited has submitted the Exchange a copy Srutinizers report of  Postal Ballot.",
           "attchmntFile":""}
        ]"""
        val items = StockNews.nse(json)
        assertEquals(3, items.size)
        val tcs = items[0]
        assertEquals("TCS", tcs.symbol)
        assertEquals("Press Release - TCS BaNCS for Treasury marks its entry into Sri Lanka with Indian Bank", tcs.title)
        assertEquals(ist("2026-10-19T11:30:07"), tcs.at)
        assertEquals("NSE", tcs.source)
        assertEquals("nse:1001", tcs.id)
        assertTrue(tcs.official)
        assertEquals(NewsKind.LEGAL, items[1].kind)
        assertEquals("Action(s) taken or orders passed", items[1].title)
        // No sort_date: the shown date is read instead.
        assertEquals(ist("2026-10-23T23:53:32"), items[2].at)
        assertEquals(NewsKind.ROUTINE, items[2].kind)
        assertEquals(emptyList<NewsItem>(), StockNews.nse("<html>blocked</html>"))
    }

    @Test
    fun readsBseAnnouncements() {
        val json = """{"Table":[{"NEWSID":"c37d","SCRIP_CD":532540,"NEWSSUB":"Announcement under Regulation 30 (LODR)-Updates on Acquisition",
          "HEADLINE":"Tata Consultancy Services Limited has informed the Exchange about completion of acquisition of BBY Services India LLP.",
          "NEWS_DT":"2026-10-15T13:28:35.827","CATEGORYNAME":"Company Update","SUBCATNAME":"Acquisition",
          "ATTACHMENTNAME":"ad952e13.pdf"}]}"""
        val item = StockNews.bse(json, "TCS").single()
        assertEquals("Completion of acquisition of BBY Services India LLP", item.title)
        assertEquals(NewsKind.DEAL, item.kind)
        assertEquals("https://www.bseindia.com/xml-data/corpfiling/AttachLive/ad952e13.pdf", item.url)
        assertEquals(ist("2026-10-15T13:28:35.827"), item.at)
        assertEquals("bse:c37d", item.id)
        assertEquals(emptyList<NewsItem>(), StockNews.bse("{}", "TCS"))
    }

    @Test
    fun readsGoogleNewsAndKeepsTheCompanysOwn() {
        val xml = """<rss><channel>
          <item><title>Steel Strips Wheels Approves Rs 1,400 Crore Plant in Chhatrapati Sambhajinagar - Autocar Professional</title>
            <link>https://news.google.com/rss/articles/AAA</link><pubDate>Fri, 23 Oct 2026 11:27:17 GMT</pubDate>
            <source url="https://www.autocarpro.in">Autocar Professional</source></item>
          <item><title>Sensex jumps 492 points; metals &amp; autos lead - Mint</title>
            <link>https://news.google.com/rss/articles/BBB</link><pubDate>Fri, 23 Oct 2026 10:00:00 GMT</pubDate>
            <source url="https://www.livemint.com">Mint</source></item>
          <item><title>SSWL shares surge most in over a month after profit surges 209% in Q2 - Upstox</title>
            <link>https://news.google.com/rss/articles/CCC</link><pubDate>Fri, 23 Oct 2026 07:06:21 GMT</pubDate>
            <source url="https://upstox.com">Upstox</source></item>
        </channel></rss>"""
        val items = StockNews.googleNews(xml, "SSWL", listOf("Steel Strips Wheels", "SSWL"))
        assertEquals(listOf("Autocar Professional", "Upstox"), items.map { it.source })
        assertEquals("Steel Strips Wheels Approves Rs 1,400 Crore Plant in Chhatrapati Sambhajinagar", items[0].title)
        assertEquals(NewsKind.EXPANSION, items[0].kind)
        assertEquals(NewsKind.RESULTS, items[1].kind)
        assertEquals(ist("2026-10-23T16:57:17"), items[0].at)
        assertTrue(items.none { it.official })
    }

    @Test
    fun sortsFilingsAndArticlesOut() {
        fun kind(text: String) = StockNews.classify(text)
        assertEquals(NewsKind.MEETING, kind("Board Meeting Intimation. Intimation of board meeting to consider financial results for the quarter"))
        assertEquals(NewsKind.RESULTS, kind("Outcome of Board Meeting. Financial results for the quarter ended September 30, 2026"))
        assertEquals(NewsKind.ORDER, kind("Bagging/Receiving of orders/contracts. Receipt of order from NHAI"))
        assertEquals(NewsKind.ORDER, kind("KEC International bags orders worth Rs 1,100 crore"))
        assertEquals(NewsKind.DIVIDEND, kind("Corporate Action-Board approves Dividend"))
        assertEquals(NewsKind.FUNDRAISE, kind("Board approves raising of funds through QIP"))
        assertEquals(NewsKind.MANAGEMENT, kind("Resignation of Chief Financial Officer"))
        assertEquals(NewsKind.RATING, kind("Credit Rating. CRISIL reaffirms the rating"))
        assertEquals(NewsKind.INVESTOR, kind("Analysts/Institutional Investor Meet/Con. Call Updates. Schedule of meet"))
        assertEquals(NewsKind.ROUTINE, kind("Copy of Newspaper Publication"))
        assertEquals(NewsKind.ROUTINE, kind("Certificate under SEBI (Depositories and Participants) Regulations, 2018"))
        assertEquals(NewsKind.OTHER, kind("The company has taken steps in order to improve its processes"))
        // A lender's debt coming due is routine, record date or not; a bare record date is no dividend.
        assertEquals(NewsKind.ROUTINE, kind("Record Date. Intimation of Record Date for redemption of Commercial Paper (CP) issued by the Company"))
        assertEquals(NewsKind.ROUTINE, kind("Updates. Certificate for Payment of Interest (BSE Listed)"))
        assertEquals(NewsKind.ROUTINE, kind("Capri Global Capital (NSE:CGCL): Why Has a January 2027 CP Redemption Record Date Been Set?"))
        assertEquals(NewsKind.ROUTINE, kind("General Updates. Redemption of Securities"))
        assertEquals(NewsKind.ROUTINE, kind("Record Date. Record date for payment of interest and redemption of NCDs"))
        assertEquals(NewsKind.ROUTINE, kind("Monitoring Agency Report"))
        assertEquals(NewsKind.OTHER, kind("Record Date. Intimation of record date"))
        assertEquals(NewsKind.DIVIDEND, kind("Record Date. Record date for the purpose of interim dividend"))
        assertEquals(NewsKind.FUNDRAISE, kind("Allotment of Securities. Allotment of NCDs on private placement basis"))
        assertEquals(
            NewsKind.ROUTINE,
            kind("Allotment of Securities. The approval of allotment of 25,000 equity shares on exercise of options under the Employees Stock Option Scheme 2017"),
        )
        assertTrue(NewsKind.RESULTS.big && !NewsKind.ROUTINE.big && !NewsKind.MEETING.big)
    }

    @Test
    fun mergesNewestFirstWithoutRepeats() {
        fun item(id: String, at: Long, title: String, official: Boolean) =
            NewsItem("X", at, title, "S", "u", NewsKind.OTHER, official, id)
        val merged = StockNews.merge(
            listOf(item("nse:1", 10, "Board Meeting Intimation", true), item("nse:2", 30, "Board Meeting Intimation", true)),
            listOf(item("g:1", 20, "X wins big order!", false), item("g:2", 25, "X wins big order", false), item("g:1", 20, "X wins big order!", false)),
        )
        // Two filings under the same words both stay; the same headline from two papers stays once.
        assertEquals(listOf("nse:2", "g:2", "nse:1"), merged.map { it.id })
    }

    @Test
    fun marksCardsAndPicksAlerts() {
        val now = ist("2026-10-23T20:00:00")
        fun item(symbol: String, kind: NewsKind, at: String, id: String, official: Boolean = true) =
            NewsItem(symbol, ist(at), "t$id", "NSE", "u", kind, official, id)
        val items = listOf(
            item("SSWL", NewsKind.EXPANSION, "2026-10-23T16:50:00", "1"),
            item("SSWL", NewsKind.RESULTS, "2026-10-23T12:30:00", "2"),
            item("SSWL", NewsKind.ROUTINE, "2026-10-23T19:00:00", "3"),
            item("TCS", NewsKind.INVESTOR, "2026-10-23T18:00:00", "4"),
            item("INFY", NewsKind.ORDER, "2026-10-21T10:00:00", "5"),
            item("CYIENT", NewsKind.RESULTS, "2026-10-23T10:00:00", "6", official = false),
        )
        val badges = StockNews.badges(items, now)
        // Routine filings, analyst meets, old filings and newspapers do not mark a card; results outrank a new plant.
        assertEquals(setOf("SSWL"), badges.keys)
        assertEquals(NewsBadge(NewsKind.RESULTS, 2, ist("2026-10-23T16:50:00")), badges["SSWL"])
        val alerts = StockNews.alerts(items, watchlist = setOf("SSWL", "INFY", "CYIENT"), seen = setOf("2"), now = now)
        assertEquals(listOf("1"), alerts.map { it.id })
    }

    @Test
    fun tellsPreOpenAndLiveNews() {
        // Friday 23 Oct 2026: the session runs 9:15 to 15:30 IST.
        val fridayNoon = ist("2026-10-23T11:27:00")
        val fridayEvening = ist("2026-10-23T17:12:00")
        assertEquals(StockNews.Timing.LIVE, StockNews.timing(fridayNoon, now = ist("2026-10-23T14:00:00")))
        assertEquals(StockNews.Timing.PRE_OPEN, StockNews.timing(fridayEvening, now = ist("2026-10-24T10:00:00")))
        // Monday morning before the open: Saturday's filing is news Monday opens on; Friday noon's is old.
        assertEquals(StockNews.Timing.PRE_OPEN, StockNews.timing(ist("2026-10-24T12:00:00"), now = ist("2026-10-26T08:50:00")))
        assertNull(StockNews.timing(fridayNoon, now = ist("2026-10-26T08:50:00")))
        assertNull(StockNews.timing(fridayNoon, now = ist("2026-10-24T10:00:00")))
        assertEquals(ist("2026-10-23T15:30:00"), StockNews.latestClose(MarketClock.now(ist("2026-10-26T08:50:00"))))
    }
}

package org.evvdroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PunctuationAndFixesTest {

	@Test
	fun testSliderValuesUnbroken() {
		// Slider values and percentages must never be corrupted or broken
		assertEquals("50%", TextFixes.apply("50%"))
		assertEquals("0%", TextFixes.apply("0%"))
		assertEquals("100%", TextFixes.apply("100%"))
		assertEquals("50 percent", TextFixes.apply("50 percent"))
		assertEquals("50%, Volume slider", TextFixes.apply("50%, Volume slider"))
		assertEquals("50% seek bar", TextFixes.apply("50% seek bar"))
	}

	@Test
	fun testZeroWidthLeadingMarksCleaned() {
		// Paperback and Android unlabelled buttons with zero-width spaces
		assertEquals("Play", TextFixes.apply("\u200B, Play"))
		assertEquals("Play", TextFixes.apply("\u200B,Play"))
		assertEquals("Play", TextFixes.apply("\u200B., Play"))
		assertEquals("Play", TextFixes.apply("\u200B. , Play"))
		assertEquals("Play", TextFixes.apply("\uFEFF, Play"))
		assertEquals("Play", TextFixes.apply("\u200E, Play"))
		assertEquals("button", TextFixes.apply(", button"))
		assertEquals("button", TextFixes.apply("  , button"))
		assertEquals("button", TextFixes.apply(". button"))
		assertEquals("play button", TextFixes.apply(",play button"))
		assertEquals("", TextFixes.apply("\u200B,   "))
	}

	@Test
	fun testPeriodBeforeCommaCollapses() {
		// TalkBack view concatenation: "Submit., button" or "Submit. , button"
		assertEquals("Submit, button", TextFixes.apply("Submit., button"))
		assertEquals("Submit, button", TextFixes.apply("Submit. , button"))
		assertEquals("Submit, button", TextFixes.apply("Submit.   , button"))
		assertEquals("Submit, button", TextFixes.apply("Submit . , button"))
	}

	@Test
	fun testCommaBeforePeriodCollapses() {
		assertEquals("Submit. button", TextFixes.apply("Submit,. button"))
		assertEquals("Submit. button", TextFixes.apply("Submit, . button"))
		assertEquals("Submit. button", TextFixes.apply("Submit , . button"))
	}

	@Test
	fun testClauseAfterSentenceMarks() {
		assertEquals("Really? yes", TextFixes.apply("Really?, yes"))
		assertEquals("Really? yes", TextFixes.apply("Really? , yes"))
		assertEquals("Done! next", TextFixes.apply("Done!, next"))
		assertEquals("Done! next", TextFixes.apply("Done! , next"))
	}

	@Test
	fun testRepeatedPunctuationDeduped() {
		assertEquals("Hello. world", TextFixes.apply("Hello.. world"))
		assertEquals("Hello. world", TextFixes.apply("Hello... world"))
		assertEquals("Hello, world", TextFixes.apply("Hello,, world"))
		assertEquals("Hello, world", TextFixes.apply("Hello,,, world"))
	}

	@Test
	fun testSpaceBeforeMarkPreservedProperly() {
		assertEquals("word.", TextFixes.apply("word ."))
		assertEquals("word,", TextFixes.apply("word ,"))
		assertEquals("word, next", TextFixes.apply("word. , next"))
	}

	@Test
	fun testIsolatedPunctuationCleaned() {
		assertEquals("", TextFixes.apply(" , "))
		assertEquals("", TextFixes.apply(" . "))
		assertEquals("", TextFixes.apply(","))
		assertEquals("", TextFixes.apply("."))
		assertEquals("", TextFixes.apply(" .. "))
		assertEquals("", TextFixes.apply(" ,, "))
	}

	@Test
	fun testGroupedThousandsFixes() {
		assertEquals("1000000", TextFixes.apply("1,000,000"))
		assertEquals("10005", TextFixes.apply("1,000,5"))
		assertEquals("1000000 items", TextFixes.apply("1,000,000 items"))
		assertEquals("2000000000", TextFixes.apply("2,000,000,000"))
		assertEquals("12,345", TextFixes.apply("12,345"))
	}

	@Test
	fun testPunctuationSomeLevelDoesNotVocalizeCommaOrPeriod() {
		val input = "Hello, world. This is a test."
		val processed = Punctuation.apply(input, Punctuation.LEVEL_SOME, 0x00010000)
		assertFalse("Comma should not be pronounced at LEVEL_SOME", processed.contains("comma", ignoreCase = true))
		assertFalse("Period should not be pronounced at LEVEL_SOME", processed.contains("period", ignoreCase = true))
		assertFalse("Dot should not be pronounced at LEVEL_SOME", processed.contains("dot", ignoreCase = true))
	}

	@Test
	fun testPunctuationSomeLevelVocalizesBullet() {
		val input = "• First item"
		val processedEn = Punctuation.apply(input, Punctuation.LEVEL_SOME, 0x00010000)
		assertTrue("Bullet should be pronounced at LEVEL_SOME in English", processedEn.contains("bullet", ignoreCase = true))

		val processedEs = Punctuation.apply(input, Punctuation.LEVEL_SOME, 0x00020000)
		assertTrue("Bullet should be pronounced at LEVEL_SOME in Spanish", processedEs.contains("viñeta", ignoreCase = true))

		val processedFr = Punctuation.apply(input, Punctuation.LEVEL_SOME, 0x00030000)
		assertTrue("Bullet should be pronounced at LEVEL_SOME in French", processedFr.contains("puce", ignoreCase = true))

		val processedDe = Punctuation.apply(input, Punctuation.LEVEL_SOME, 0x00040000)
		assertTrue("Bullet should be pronounced at LEVEL_SOME in German", processedDe.contains("aufzählung", ignoreCase = true))
	}

	@Test
	fun testWesternTextFlattenBulletDoesNotBecomePeriod() {
		val flattenedBullet = WesternText.flatten("• item") { false }
		assertFalse("Bullet must not be turned into period", flattenedBullet.contains('.'))

		val flattenedMiddleDot = WesternText.flatten("A · B") { false }
		assertFalse("Middle dot must not be turned into period", flattenedMiddleDot.contains('.'))
	}

	@Test
	fun testSomeVsMostPunctuationClassification() {
		val text = "test: item; word_a and ^x and ~y and \\z"

		// At LEVEL_SOME, none of :, ;, _, ^, ~, \ should be vocalized
		val someResult = Punctuation.apply(text, Punctuation.LEVEL_SOME, 0x00010000)
		assertFalse(someResult.contains("colon", ignoreCase = true))
		assertFalse(someResult.contains("semi", ignoreCase = true))
		assertFalse(someResult.contains("underscore", ignoreCase = true))
		assertFalse(someResult.contains("caret", ignoreCase = true))
		assertFalse(someResult.contains("tilda", ignoreCase = true))
		assertFalse(someResult.contains("tilde", ignoreCase = true))
		assertFalse(someResult.contains("backslash", ignoreCase = true))

		// At LEVEL_MOST, all of them should be vocalized
		val mostResult = Punctuation.apply(text, Punctuation.LEVEL_MOST, 0x00010000)
		assertTrue(mostResult.contains("colon", ignoreCase = true))
		assertTrue(mostResult.contains("semi", ignoreCase = true))
		assertTrue(mostResult.contains("underscore", ignoreCase = true))
		assertTrue(mostResult.contains("caret", ignoreCase = true))
		assertTrue(mostResult.contains("tilda", ignoreCase = true))
		assertTrue(mostResult.contains("backslash", ignoreCase = true))
	}

	@Test
	fun testNvdaPunctuationAllLevel() {
		val text = "Really? Yes! It is $5, right? ... Wait."
		val processed = Punctuation.apply(text, Punctuation.LEVEL_ALL, 0x00010000)
		assertTrue("Question mark should be pronounced as question", processed.contains("question", ignoreCase = true))
		assertTrue("Exclamation mark should be pronounced as bang", processed.contains("bang", ignoreCase = true))
		assertTrue("Dollar should be pronounced as dollar", processed.contains("dollar", ignoreCase = true))
		assertTrue("Comma should be pronounced as comma", processed.contains("comma", ignoreCase = true))
		assertTrue("Ellipsis should be pronounced as dot dot dot", processed.contains("dot dot dot", ignoreCase = true))
		assertTrue("Period should be pronounced as dot", processed.contains("dot", ignoreCase = true))
	}

	@Test
	fun testNvdaPunctuationMostParens() {
		val text = "(test) and [bracket]"
		val processed = Punctuation.apply(text, Punctuation.LEVEL_MOST, 0x00010000)
		assertTrue("Open paren should be paren on", processed.contains("paren on", ignoreCase = true))
		assertTrue("Close paren should be paren off", processed.contains("paren off", ignoreCase = true))
		assertTrue("Open bracket should be left bracket", processed.contains("left bracket", ignoreCase = true))
		assertTrue("Close bracket should be right bracket", processed.contains("right bracket", ignoreCase = true))
	}
}


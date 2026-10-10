package org.evvdroid

/**
 * Text the engine reads wrong, put right before it sees it.
 *
 * Eloquence gives up on a word it cannot parse and spells it out instead, one
 * letter at a time. That is why "teamtalk5" comes back as t e a m t a l k 5:
 * the trailing digit makes the whole token unparseable, so the whole token gets
 * spelled. A space in front of the digit is enough to fix it.
 *
 * Most of these are davidacm's, from the NVDA IBMTTS driver's ibm_global_fixes.
 * Every one was measured against the engine first, because a rule that reads
 * well can still cost more than it saves. Two of the driver's were left out:
 * the pair that collapses double spaces around brackets, which exist because
 * NVDA's own symbol processing puts them there and TalkBack's does not.
 *
 * This runs before Pauses and Prosody, and has to. Those add annotations like
 * `` `p1 ``, and a rule here that puts a space between a letter and a digit
 * would turn that into `` `p 1 ``, which the engine speaks rather than obeys.
 */
object TextFixes {

	val ZERO_WIDTH = Regex("""[\u200B-\u200D\uFEFF\u200E\u200F\u202A-\u202E\u2060\u2066-\u2069\u00AD]""")
	val NON_BREAKING_SPACES = Regex("""[\u00A0\u2007\u202F\u205F\u3000]""")

	/**
	 * [text] with the words the engine mishandles rewritten. Nothing here
	 * changes what is said, only how it is written down for the engine.
	 */
	fun apply(text: String): String {
		if (text.isEmpty()) return text
		var out = ZERO_WIDTH.replace(text, "")
		out = NON_BREAKING_SPACES.replace(out, " ")
		out = BEFORE_A_DIGIT.replace(out, "$1 $2")
		out = BEFORE_AN_OPENER.replace(out, "$1 $2")
		out = LOOSE_S_SUFFIX.replace(out, "$1$2")
		out = PERIOD_BEFORE_COMMA.replace(out, ",")
		out = COMMA_BEFORE_PERIOD.replace(out, ".")
		out = CLAUSE_AFTER_SENTENCE_MARK.replace(out, "$1")
		out = REPEATED_PERIODS.replace(out, ".")
		out = REPEATED_COMMAS.replace(out, ",")
		out = SPACE_BEFORE_A_MARK.replace(out, "$1$2")
		out = LEADING_MARKS.replace(out, "")
		out = ISOLATED_PUNCT.replace(out, "")
		out = GROUPED_THOUSANDS.replace(out) { it.value.replace(",", "") }
		return out.trim()
	}

	/** A letter against a digit, which is the whole of the teamtalk5 problem.
	 *  Where the engine already coped the space costs nothing: mp3, x86, v2,
	 *  NVDA5 and EURUSD5 all came back byte for byte the same with it and
	 *  without, so this needs no test for which words are worth splitting.
	 *  Digits against letters are left alone: 1st is right already and gets
	 *  worse when split. */
	private val BEFORE_A_DIGIT = Regex("""([A-Za-z])(\d)""")

	/** The marks the engine starts spelling in front of. Closing brackets are
	 *  deliberately absent: "abc)" already costs no more than "abc", while
	 *  "abc )" costs twice as much. */
	private val OPENERS = """~#${'$'}%^*({|\[<""" + "\u2022"

	private val BEFORE_AN_OPENER = Regex("([A-Za-z]+)([" + Regex.escape(OPENERS) + "])")

	/** "books (s)" back to "books(s)", which is a third shorter. */
	private val LOOSE_S_SUFFIX = Regex("""([A-Za-z]+)\s+(\(s\))""")

	/**
	 * A period before a comma (e.g. "Submit., button" or "Submit. , button" or "Submit . , button"),
	 * common when screen readers append a control role to a label that ends
	 * in a full stop. Eloquence cannot parse adjacent punctuation marks and
	 * spells them out as "period comma" or "dot comma". Collapsing them into
	 * a single comma preserves the cadence pause without speaking the mark names.
	 */
	private val PERIOD_BEFORE_COMMA = Regex("""\s*\.\s*,""")

	/** A comma before a period (e.g. "Submit,. button") collapsed to a single period. */
	private val COMMA_BEFORE_PERIOD = Regex("""\s*,\s*\.""")

	/** A question or exclamation mark before a comma (e.g. "Really?, button"). */
	private val CLAUSE_AFTER_SENTENCE_MARK = Regex("""\s*([!?])\s*,""")

	/** Repeated periods (e.g. ".." or trailing dots) reduced to a single dot to avoid "dot dot". */
	private val REPEATED_PERIODS = Regex("""\.{2,}""")

	/** Repeated commas reduced to a single comma. */
	private val REPEATED_COMMAS = Regex(""",{2,}""")

	/** The engine does not tolerate a space in front of a punctuation mark. The
	 *  lookahead keeps decimals and thousands separators out of it. Preceding token
	 *  must not end in a punctuation mark to prevent creating adjacent marks. */
	private val SPACE_BEFORE_A_MARK = Regex("""([A-Za-z0-9]+|[^\s\w:.!;,?]+)\s+([:.!;,?](?![A-Za-z]|\d))""")

	/** Leading cadence marks (e.g. ", Play", "., button", " . Next") at the start
	 *  of an utterance, common with TalkBack view concatenation on unlabelled views.
	 *  Eloquence speaks the mark aloud if it has no preceding word. */
	private val LEADING_MARKS = Regex("""^[\s,.:;]+""")

	/** An utterance containing only punctuation and whitespace (e.g. " . " or " , "). */
	private val ISOLATED_PUNCT = Regex("""^[\s,.:;]+$""")

	/** A number grouped with commas around a round thousand, which the engine
	 *  reads as "one comma hundred". Matches all digit chunk lengths following
	 *  davidacm's IBMTTS driver rules. */
	private val GROUPED_THOUSANDS = Regex("""\b\d{1,3},000(?:,\d{1,3})+\b""")
}

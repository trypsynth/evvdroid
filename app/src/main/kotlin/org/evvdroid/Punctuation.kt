package org.evvdroid

import android.text.Spanned
import android.text.style.TtsSpan

/**
 * Punctuation pronunciation processing for Eloquence TTS matching NVDA's symbol dictionary.
 *
 * Supports 4 verbosity levels matching NVDA:
 * - LEVEL_NONE (0): Punctuation symbols are not spoken (they only provide standard pauses).
 * - LEVEL_SOME (1): Essential symbols are spoken (#, %, &, *, +, =, /, <, >, @, •).
 * - LEVEL_MOST (2): Level SOME plus brackets, braces, quotes, dashes, phrase punctuation (: ;),
 *                   and code symbols (_, ^, ~, \, |, `).
 * - LEVEL_ALL  (3): Every punctuation mark is spoken, including sentence punctuation (. , ? !),
 *                   ticks ('), currency ($), and ellipses (...).
 */
object Punctuation {

	const val LEVEL_NONE = 0
	const val LEVEL_SOME = 1
	const val LEVEL_MOST = 2
	const val LEVEL_ALL = 3

	/**
	 * Expands any TtsSpan objects (such as those attached by TalkBack's punctuation reading control)
	 * into their spoken text equivalents. Returns null if no relevant TtsSpans are present.
	 */
	fun expandTtsSpans(charSequence: CharSequence?): String? {
		if (charSequence == null || charSequence !is Spanned) return null
		val spans = charSequence.getSpans(0, charSequence.length, TtsSpan::class.java)
		if (spans.isNullOrEmpty()) return null

		val spanList = spans.mapNotNull { span ->
			val text = span.args?.getString(TtsSpan.ARG_TEXT)
				?: span.args?.getString(TtsSpan.ARG_VERBATIM)
			if (!text.isNullOrEmpty()) {
				val start = charSequence.getSpanStart(span)
				val end = charSequence.getSpanEnd(span)
				if (start in 0..end && end <= charSequence.length) {
					Triple(start, end, text)
				} else null
			} else null
		}.sortedBy { it.first }

		if (spanList.isEmpty()) return null

		val sb = StringBuilder()
		var cursor = 0
		for ((start, end, replacement) in spanList) {
			if (start < cursor) continue
			sb.append(charSequence.subSequence(cursor, start))
			val original = charSequence.subSequence(start, end).toString()
			if (original.length == 1 && original[0] in ".,:;?!") {
				sb.append(" ").append(replacement).append(original[0]).append(" ")
			} else {
				sb.append(" ").append(replacement).append(" ")
			}
			cursor = end
		}
		if (cursor < charSequence.length) {
			sb.append(charSequence.subSequence(cursor, charSequence.length))
		}
		return collapseSpaces(sb.toString())
	}

	fun apply(text: String, level: Int, language: Int? = null): String {
		if (text.isEmpty() || level <= LEVEL_NONE) return text
		val langCode = language?.let { Eci.localeOf(it)?.first } ?: "eng"
		val map = getSymbolMap(langCode)

		var out = text

		// 1. Process LEVEL_SOME symbols
		for (ch in SOME_SYMBOLS) {
			if (out.indexOf(ch) >= 0) {
				val name = map[ch] ?: continue
				out = out.replace(ch.toString(), " $name ")
			}
		}

		if (level < LEVEL_MOST) {
			return collapseSpaces(out)
		}

		// 2. Process LEVEL_MOST symbols
		for (ch in MOST_SYMBOLS) {
			if (out.indexOf(ch) >= 0) {
				val name = map[ch] ?: continue
				out = when (ch) {
					// Phrase endings preserve punctuation mark to retain cadence pause in Eloquence
					':' -> out.replace(Regex(""":(?!\d)"""), " $name: ")
					';' -> out.replace(";", " $name; ")
					else -> out.replace(ch.toString(), " $name ")
				}
			}
		}

		if (level < LEVEL_ALL) {
			return collapseSpaces(out)
		}

		// 3. Process LEVEL_ALL symbols (sentence & clause punctuation, currency, ticks, ellipses)
		// Ellipses and multiple dots first
		val ellipsisName = map['…'] ?: "dot dot dot"
		out = out.replace(Regex("""\.{4,}"""), " multiple dots. ")
		out = out.replace(Regex("""\.{3}|…"""), " $ellipsisName. ")

		for (ch in ALL_SYMBOLS) {
			if (out.indexOf(ch) >= 0) {
				val name = map[ch] ?: continue
				// Preserved marks keep punctuation mark for Eloquence cadence and pitch inflections
				out = when (ch) {
					'.' -> out.replace(Regex("""(?<=[^\s.])\.(?=[\"'”’)\s]|$)"""), " $name. ")
					',' -> out.replace(Regex(""",(?!\d)"""), " $name, ")
					'?' -> out.replace("?", " $name? ")
					'!' -> out.replace("!", " $name! ")
					else -> out.replace(ch.toString(), " $name ")
				}
			}
		}

		return collapseSpaces(out)
	}

	private fun collapseSpaces(text: String): String =
		text.replace(Regex("""[ \t]+"""), " ")

	private val SOME_SYMBOLS = charArrayOf(
		'#', '%', '&', '*', '+', '=', '/', '<', '>', '@', '•'
	)

	private val MOST_SYMBOLS = charArrayOf(
		'"', '(', ')', '[', ']', '{', '}', '_', '^', '~', '\\', '|', '`',
		'-', '—', '–', '“', '”', '‘', '’', '·', ':', ';'
	)

	private val ALL_SYMBOLS = charArrayOf(
		'.', ',', '?', '!', '\'', '$'
	)

	private fun getSymbolMap(langCode: String): Map<Char, String> = when (langCode) {
		"spa" -> SPANISH_MAP
		"fra" -> FRENCH_MAP
		"deu" -> GERMAN_MAP
		"ita" -> ITALIAN_MAP
		"pol" -> POLISH_MAP
		else -> ENGLISH_MAP
	}

	/** NVDA English symbol dictionary, including user custom overrides for parens and underscore. */
	private val ENGLISH_MAP: Map<Char, String> = mapOf(
		'@' to "at",
		'#' to "number",
		'$' to "dollar",
		'%' to "percent",
		'&' to "and",
		'*' to "star",
		'+' to "plus",
		'=' to "equals",
		'/' to "slash",
		'<' to "less",
		'>' to "greater",
		'•' to "bullet",
		'(' to "paren on",
		')' to "paren off",
		'[' to "left bracket",
		']' to "right bracket",
		'{' to "left brace",
		'}' to "right brace",
		'"' to "quote",
		'“' to "left quote",
		'”' to "right quote",
		'‘' to "left tick",
		'’' to "right tick",
		'-' to "dash",
		'—' to "em dash",
		'–' to "en dash",
		':' to "colon",
		';' to "semi",
		'_' to "underscore",
		'^' to "caret",
		'~' to "tilda",
		'\\' to "backslash",
		'|' to "bar",
		'`' to "graav",
		'·' to "middle dot",
		'.' to "dot",
		',' to "comma",
		'?' to "question",
		'!' to "bang",
		'\'' to "tick",
		'…' to "dot dot dot"
	)

	/** NVDA Spanish symbol dictionary from locale/es/symbols.dic */
	private val SPANISH_MAP: Map<Char, String> = ENGLISH_MAP + mapOf(
		'@' to "arroba",
		'#' to "signo de número",
		'$' to "dólares",
		'%' to "porciento",
		'&' to "et",
		'*' to "asterisco",
		'+' to "más",
		'=' to "igual",
		'/' to "barra",
		'<' to "menor que",
		'>' to "mayor que",
		'•' to "viñeta",
		'(' to "abrir paréntesis",
		')' to "cerrar paréntesis",
		'[' to "abrir corchete",
		']' to "cerrar corchete",
		'{' to "abrir llave",
		'}' to "cerrar llave",
		'"' to "comillas",
		'“' to "abrir comillas",
		'”' to "cerrar comillas",
		'‘' to "abrir apóstrofo",
		'’' to "cerrar apóstrofo",
		'-' to "guion",
		'—' to "raya",
		'–' to "guion corto",
		':' to "dos puntos",
		';' to "punto y coma",
		'_' to "subrayado",
		'^' to "acento circunflejo",
		'~' to "tilde",
		'\\' to "barra inversa",
		'|' to "barra vertical",
		'`' to "acento grave",
		'·' to "punto centrado",
		'.' to "punto",
		',' to "coma",
		'?' to "cerrar interrogación",
		'!' to "cerrar exclamación",
		'\'' to "apóstrofo",
		'…' to "elipsis"
	)

	/** NVDA French symbol dictionary from locale/fr/symbols.dic */
	private val FRENCH_MAP: Map<Char, String> = ENGLISH_MAP + mapOf(
		'@' to "arobase",
		'#' to "dièse",
		'$' to "dollar",
		'%' to "pour cent",
		'&' to "et commercial",
		'*' to "astérisque",
		'+' to "pluss",
		'=' to "égal",
		'/' to "barre oblique",
		'<' to "inférieur",
		'>' to "supérieur",
		'•' to "puce",
		'(' to "parenthèse gauche",
		')' to "parenthèse droite",
		'[' to "crochet gauche",
		']' to "crochet droit",
		'{' to "accolade gauche",
		'}' to "accolade droite",
		'"' to "guillemet",
		'“' to "double guillemet ouvrant",
		'”' to "double guillemet fermant",
		'‘' to "apostrophe gauche",
		'’' to "apostrophe droite",
		'-' to "tiret",
		'—' to "tiret cadratin",
		'–' to "tiret demicadratin",
		':' to "deux points",
		';' to "point virgule",
		'_' to "souligné",
		'^' to "accent circonflexe",
		'~' to "tilde",
		'\\' to "barre oblique inversée",
		'|' to "barre verticale",
		'`' to "accent grave",
		'·' to "point médian",
		'.' to "point",
		',' to "virgule",
		'?' to "point d'interrogation",
		'!' to "point d'exclamation",
		'\'' to "apostrophe",
		'…' to "points de suspension"
	)

	/** NVDA German symbol dictionary from locale/de/symbols.dic */
	private val GERMAN_MAP: Map<Char, String> = ENGLISH_MAP + mapOf(
		'@' to "ät",
		'#' to "Nummernzeichen",
		'$' to "Dollar",
		'%' to "Prozent",
		'&' to "und",
		'*' to "Stern",
		'+' to "plus",
		'=' to "gleich",
		'/' to "Schrägstrich",
		'<' to "kleiner als",
		'>' to "größer als",
		'•' to "Aufzählungszeichen",
		'(' to "Runde Klammer auf",
		')' to "Runde Klammer zu",
		'[' to "Eckige Klammer auf",
		']' to "Eckige Klammer zu",
		'{' to "Geschweifte Klammer auf",
		'}' to "Geschweifte Klammer zu",
		'"' to "Anführungszeichen",
		'“' to "Anführungszeichen links",
		'”' to "Anführungszeichen rechts",
		'‘' to "Apostroph links",
		'’' to "Apostroph rechts",
		'-' to "Strich",
		'—' to "Gedankenstrich",
		'–' to "Bindestrich",
		':' to "Doppelpunkt",
		';' to "Semikolon",
		'_' to "Unterstrich",
		'^' to "Dach",
		'~' to "Tilde",
		'\\' to "Bäcksläsch",
		'|' to "Senkrechter Strich",
		'`' to "Gravis",
		'·' to "Mittelpunkt",
		'.' to "Punkt",
		',' to "Komma",
		'?' to "Fragezeichen",
		'!' to "Ausrufezeichen",
		'\'' to "Apostroph",
		'…' to "Punkt Punkt Punkt"
	)

	/** NVDA Italian symbol dictionary from locale/it/symbols.dic */
	private val ITALIAN_MAP: Map<Char, String> = ENGLISH_MAP + mapOf(
		'@' to "chiocciola",
		'#' to "cancelletto",
		'$' to "dollari",
		'%' to "percento",
		'&' to "and",
		'*' to "asterisco",
		'+' to "più",
		'=' to "uguale",
		'/' to "barra",
		'<' to "minore di",
		'>' to "maggiore di",
		'•' to "puntato",
		'(' to "aperta parentesi",
		')' to "chiusa parentesi",
		'[' to "aperta quadra",
		']' to "chiusa quadra",
		'{' to "aperta graffa",
		'}' to "chiusa graffa",
		'"' to "virgolette",
		'“' to "aperte virgolette",
		'”' to "chiuse virgolette",
		'‘' to "aperto apostrofo",
		'’' to "chiuso apostrofo",
		'-' to "trattino",
		'—' to "trattino lungo",
		'–' to "Trattino corto",
		':' to "due punti",
		';' to "punto e virgola",
		'_' to "sottolineato",
		'^' to "accento circonflesso",
		'~' to "tilde",
		'\\' to "controbarra",
		'|' to "barra verticale",
		'`' to "accento grave",
		'·' to "punto medio",
		'.' to "punto",
		',' to "virgola",
		'?' to "punto di domanda",
		'!' to "punto esclamativo",
		'\'' to "apostrofo",
		'…' to "punto punto punto"
	)

	/** NVDA Polish symbol dictionary from locale/pl/symbols.dic */
	private val POLISH_MAP: Map<Char, String> = ENGLISH_MAP + mapOf(
		'@' to "małpa",
		'#' to "hasz",
		'$' to "dolar",
		'%' to "procent",
		'&' to "and",
		'*' to "gwiazdka",
		'+' to "plus",
		'=' to "równe",
		'/' to "slesz",
		'<' to "mniejsze",
		'>' to "większe",
		'•' to "punktor",
		'(' to "lewy nawias",
		')' to "prawy nawias",
		'[' to "lewy kwadratowy",
		']' to "prawy kwadratowy",
		'{' to "lewy klamrowy",
		'}' to "prawy klamrowy",
		'"' to "cudzysłów",
		'“' to "lewy cudzysłów",
		'”' to "prawy cudzysłów",
		'‘' to "lewy apostrof",
		'’' to "prawy apostrof",
		'-' to "minus",
		'—' to "myślnik",
		'–' to "półpauza",
		':' to "dwukropek",
		';' to "średnik",
		'_' to "podkreślacz",
		'^' to "daszek",
		'~' to "tylda",
		'\\' to "bekslesz",
		'|' to "kreska pionowa",
		'`' to "akcent",
		'·' to "kropka środkowa",
		'.' to "kropka",
		',' to "przecinek",
		'?' to "pytajnik",
		'!' to "wykrzyknik",
		'\'' to "apostrof",
		'…' to "wielokropek"
	)
}

package com.tsuty.remoteagent.ui.transcript

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory

/**
 * Colors the code of a fenced block with the lexer of the language it names.
 *
 * The IDE Markdown preview highlights a code fence with the very same machinery — the lexer the
 * editor uses plus the colors of the current editor scheme — so this is what makes a fence in the
 * transcript read like the same code read in an editor tab. Nothing is looked up here that a
 * browser could do: the tokens leave as class names ([CATEGORIES]), and their colors travel as the
 * CSS custom properties `transcript.css` styles them with (see [ThemeTokens]).
 *
 * A language nobody registered a file type for (and a document rendered without an application at
 * all, which is where the tests run) simply stays plain: [highlight] returns `null` and the fence
 * keeps the escaped text it had.
 */
object CodeHighlighter {

    /**
     * The token classes of the style sheet, each with the attribute keys its color is read from.
     *
     * A language contributes its own keys, but they fall back onto these platform ones, so the
     * mapping is the fallback chain of a key rather than a list of language specific names (see
     * [category]). The order inside a category only decides which color is asked for first.
     */
    val CATEGORIES: List<Pair<String, List<TextAttributesKey>>> = listOf(
        "keyword" to listOf(DefaultLanguageHighlighterColors.KEYWORD),
        "string" to listOf(DefaultLanguageHighlighterColors.STRING),
        "number" to listOf(DefaultLanguageHighlighterColors.NUMBER),
        "comment" to listOf(
            DefaultLanguageHighlighterColors.LINE_COMMENT,
            DefaultLanguageHighlighterColors.BLOCK_COMMENT,
        ),
        "doc" to listOf(DefaultLanguageHighlighterColors.DOC_COMMENT),
        "function" to listOf(
            DefaultLanguageHighlighterColors.FUNCTION_DECLARATION,
            DefaultLanguageHighlighterColors.FUNCTION_CALL,
        ),
        "type" to listOf(
            DefaultLanguageHighlighterColors.CLASS_NAME,
            DefaultLanguageHighlighterColors.CLASS_REFERENCE,
        ),
        "constant" to listOf(DefaultLanguageHighlighterColors.CONSTANT),
        "field" to listOf(DefaultLanguageHighlighterColors.INSTANCE_FIELD),
        "metadata" to listOf(DefaultLanguageHighlighterColors.METADATA),
        "tag" to listOf(DefaultLanguageHighlighterColors.MARKUP_TAG),
        "attribute" to listOf(DefaultLanguageHighlighterColors.MARKUP_ATTRIBUTE),
        "operator" to listOf(DefaultLanguageHighlighterColors.OPERATION_SIGN),
        "predefined" to listOf(DefaultLanguageHighlighterColors.PREDEFINED_SYMBOL),
    )

    /** [code] with its tokens wrapped in spans, or `null` when the language cannot be highlighted. */
    fun highlight(code: String, language: String?): String? {
        if (code.isBlank() || language.isNullOrBlank()) return null
        val application = ApplicationManager.getApplication() ?: return null
        if (application.isDisposed) return null
        val highlighter = highlighterFor(language) ?: return null
        // A lexer of some other plugin is run over text nobody validated: a fence it chokes on is
        // read as plain code, it does not take the message it was written in down with it.
        return runCatching { spans(highlighter, code) }
            .onFailure { log.debug("Cannot highlight a '$language' fence", it) }
            .getOrNull()
    }

    private fun spans(highlighter: SyntaxHighlighter, code: String): String? {
        val lexer = highlighter.highlightingLexer
        lexer.start(code)
        if (lexer.tokenType == null) return null
        val out = StringBuilder(code.length + 64)
        while (lexer.tokenType != null) {
            val text = code.substring(lexer.tokenStart, lexer.tokenEnd)
            val category = lexer.tokenType?.let { category(highlighter.getTokenHighlights(it)) }
            if (category == null || text.isBlank()) {
                out.append(TranscriptHtml.escape(text))
            } else {
                out.append("""<span class="hl-$category">""")
                out.append(TranscriptHtml.escape(text))
                out.append("</span>")
            }
            lexer.advance()
        }
        return out.toString()
    }

    /**
     * The class of a token.
     *
     * `getTokenHighlights` returns the keys from the most general to the most specific one, and a
     * language specific key names its platform equivalent as its fallback: walking the chain of
     * the most specific key first is therefore what picks the closest match.
     */
    private fun category(keys: Array<TextAttributesKey>): String? {
        keys.reversed().forEach { key ->
            var current: TextAttributesKey? = key
            while (current != null) {
                val name = current.externalName
                CATEGORIES.firstOrNull { (_, mapped) -> mapped.any { it.externalName == name } }
                    ?.let { return it.first }
                current = current.fallbackAttributeKey
            }
        }
        return null
    }

    private fun highlighterFor(language: String): SyntaxHighlighter? {
        val fileType = fileType(language) as? LanguageFileType ?: return null
        return SyntaxHighlighterFactory.getSyntaxHighlighter(fileType.language, null, null)
    }

    /**
     * The file type a fence info names.
     *
     * The info line of a fence is whatever the agent wrote (```` ```bash ````, ```` ```sh ````,
     * ```` ```console ````), while the IDE knows extensions: [ALIASES] bridges the common names,
     * and anything else is tried as an extension and as a file name (`Dockerfile`, `Makefile`).
     */
    private fun fileType(language: String): FileType? {
        val name = language.trim().lowercase().substringBefore(' ').substringBefore(':')
        if (name.isEmpty()) return null
        val alias = ALIASES[name] ?: name
        val manager = FileTypeManager.getInstance()
        manager.getFileTypeByExtension(alias).takeIf { it.isKnown() }?.let { return it }
        return manager.getFileTypeByFileName(alias).takeIf { it.isKnown() }
    }

    private fun FileType.isKnown(): Boolean = this != FileTypes.UNKNOWN && this != FileTypes.PLAIN_TEXT

    private val log = Logger.getInstance(CodeHighlighter::class.java)

    /** Fence info as agents write it, mapped onto the extension the IDE registers a language for. */
    private val ALIASES = mapOf(
        "kotlin" to "kt",
        "java" to "java",
        "javascript" to "js",
        "node" to "js",
        "typescript" to "ts",
        "jsx" to "jsx",
        "tsx" to "tsx",
        "python" to "py",
        "python3" to "py",
        "ruby" to "rb",
        "rust" to "rs",
        "golang" to "go",
        "csharp" to "cs",
        "c#" to "cs",
        "c++" to "cpp",
        "objective-c" to "m",
        "shell" to "sh",
        "bash" to "sh",
        "zsh" to "sh",
        "console" to "sh",
        "shell-session" to "sh",
        "terminal" to "sh",
        "yml" to "yaml",
        "markdown" to "md",
        "html" to "html",
        "htm" to "html",
        "dockerfile" to "Dockerfile",
        "makefile" to "Makefile",
        "gradle" to "gradle",
        "kts" to "kts",
        "text" to "txt",
        "plain" to "txt",
    )
}

package com.forai.codepad

import android.graphics.Color

class EditorTheme(
    val name: String,
    val isLight: Boolean,
    val bg: Int,
    val fg: Int,
    val gutterBg: Int,
    val gutterFg: Int,
    val currentLine: Int,
    val selection: Int,
    val cursor: Int,
    val bar: Int,
    val barFg: Int,
    val accent: Int,
    val comment: Int,
    val string: Int,
    val number: Int,
    val keyword: Int,
    val type: Int,
    val function: Int,
    val attr: Int,
    val tag: Int,
    val annot: Int,
    val findMatch: Int
) {
    fun colorFor(kind: Int): Int = when (kind) {
        Kind.COMMENT -> comment
        Kind.STRING -> string
        Kind.NUMBER -> number
        Kind.KEYWORD -> keyword
        Kind.TYPE -> type
        Kind.FUNCTION -> function
        Kind.ATTR -> attr
        Kind.TAG -> tag
        Kind.ANNOT -> annot
        else -> fg
    }
}

object Themes {
    private fun c(s: String): Int = Color.parseColor(s)

    val all: List<EditorTheme> = listOf(
        EditorTheme(
            "Dracula", false,
            c("#282A36"), c("#F8F8F2"), c("#21222C"), c("#6272A4"), c("#303341"),
            c("#55587A"), c("#F8F8F0"), c("#21222C"), c("#F8F8F2"), c("#BD93F9"),
            c("#6272A4"), c("#F1FA8C"), c("#BD93F9"), c("#FF79C6"), c("#8BE9FD"),
            c("#50FA7B"), c("#FFB86C"), c("#FF79C6"), c("#50FA7B"), c("#66FFB86C")
        ),
        EditorTheme(
            "Monokai", false,
            c("#272822"), c("#F8F8F2"), c("#1E1F1C"), c("#75715E"), c("#3E3D32"),
            c("#575646"), c("#F8F8F0"), c("#1E1F1C"), c("#F8F8F2"), c("#A6E22E"),
            c("#75715E"), c("#E6DB74"), c("#AE81FF"), c("#F92672"), c("#66D9EF"),
            c("#A6E22E"), c("#A6E22E"), c("#F92672"), c("#FD971F"), c("#66E6DB74")
        ),
        EditorTheme(
            "Solarized Dark", false,
            c("#002B36"), c("#839496"), c("#073642"), c("#586E75"), c("#073642"),
            c("#0B5163"), c("#93A1A1"), c("#073642"), c("#93A1A1"), c("#268BD2"),
            c("#586E75"), c("#2AA198"), c("#D33682"), c("#859900"), c("#B58900"),
            c("#268BD2"), c("#93A1A1"), c("#268BD2"), c("#CB4B16"), c("#66B58900")
        ),
        EditorTheme(
            "AMOLED Black", false,
            c("#000000"), c("#E6E6E6"), c("#000000"), c("#5C6370"), c("#141414"),
            c("#264F78"), c("#FFFFFF"), c("#0A0A0A"), c("#E6E6E6"), c("#61AFEF"),
            c("#5C6370"), c("#98C379"), c("#D19A66"), c("#C678DD"), c("#E5C07B"),
            c("#61AFEF"), c("#D19A66"), c("#E06C75"), c("#E5C07B"), c("#66E5C07B")
        ),
        EditorTheme(
            "GitHub Light", true,
            c("#FFFFFF"), c("#24292F"), c("#F6F8FA"), c("#8C959F"), c("#EEF3FB"),
            c("#B6D6FD"), c("#24292F"), c("#F6F8FA"), c("#24292F"), c("#0969DA"),
            c("#6E7781"), c("#0A3069"), c("#0550AE"), c("#CF222E"), c("#953800"),
            c("#8250DF"), c("#0550AE"), c("#116329"), c("#953800"), c("#66FFD33D")
        ),
        EditorTheme(
            "Solarized Light", true,
            c("#FDF6E3"), c("#657B83"), c("#EEE8D5"), c("#93A1A1"), c("#EEE8D5"),
            c("#DDD6BE"), c("#586E75"), c("#EEE8D5"), c("#586E75"), c("#268BD2"),
            c("#93A1A1"), c("#2AA198"), c("#D33682"), c("#859900"), c("#B58900"),
            c("#268BD2"), c("#657B83"), c("#268BD2"), c("#CB4B16"), c("#66B58900")
        )
    )

    fun byName(n: String?): EditorTheme? = all.firstOrNull { it.name == n }
}

package com.rudnam.samfran.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.rudnam.samfran.core.data.Outcome
import java.util.Locale

/**
 * The server's messages (refusals) are written in English. In Japanese, the
 * known ones are translated, as the website does (serverText in
 * web/src/i18n.tsx; this table is jaServer in web/src/locales/ja.ts, and
 * ServerTextTest keeps the two the same). A pattern's {name} matches
 * anything, and {msg} is itself translated. English ones start with a
 * capital, as sentences.
 */
object ServerText {
    fun translate(message: String, locale: Locale): String {
        if (locale.language != "ja") return message.replaceFirstChar { it.titlecase(locale) }
        JA[message]?.let { return it }
        for (p in patterns) {
            val m = p.regex.matchEntire(message) ?: continue
            return Regex("\\{(\\w+)\\}").replace(p.translated) { r ->
                val name = r.groupValues[1]
                val value = m.groupValues.getOrElse(p.names.indexOf(name) + 1) { "" }
                if (name == "msg") translate(value, locale) else value
            }
        }
        return message
    }

    private class Pattern(val regex: Regex, val names: List<String>, val translated: String)

    private val patterns: List<Pattern> by lazy {
        JA.filterKeys { '{' in it }.map { (en, ja) ->
            val names = mutableListOf<String>()
            val source = en.split(Regex("(?=\\{\\w+\\})|(?<=\\})")).joinToString("") { part ->
                if (Regex("^\\{\\w+\\}$").matches(part)) {
                    names += part.drop(1).dropLast(1)
                    "(.+?)"
                } else {
                    Regex.escape(part)
                }
            }
            Pattern(Regex(source), names, ja)
        }
    }

    internal val JA: Map<String, String> = mapOf(
        "sign in required" to "ログインしてください",
        "session expired; sign in again" to "セッションの有効期限が切れました。もう一度ログインしてください",
        "too many attempts; wait a minute and try again" to "試行回数が多すぎます。1分ほど待ってからもう一度お試しください",
        "enter a valid email address" to "有効なメールアドレスを入力してください",
        "sign-up is closed on this server" to "このサーバーでは新規登録を受け付けていません",
        "password must be {min} to {max} characters" to "パスワードは{min}〜{max}文字にしてください",
        "an account with this email already exists" to "このメールアドレスのアカウントはすでにあります",
        "email or password is incorrect" to "メールアドレスまたはパスワードが正しくありません",
        "a name must be at most {max} characters" to "名前は{max}文字以内にしてください",
        "this email already has an account of its own" to "このメールアドレスはすでに別のアカウントで使われています",
        "this email is already invited by someone else" to "このメールアドレスはすでにほかの人から招待されています",
        "no such invitation" to "招待が見つかりません",
        "no such restaurant" to "お店が見つかりません",
        "no such visit" to "訪問の記録が見つかりません",
        "a name must be 1 to {max} characters" to "店名は1〜{max}文字にしてください",
        "an area must be at most {max} characters" to "エリアは{max}文字以内にしてください",
        "a cuisine must be at most {max} characters" to "料理は{max}文字以内にしてください",
        "notes must be at most {max} characters" to "メモは{max}文字以内にしてください",
        "a map link must be a web address starting with https://" to "地図のリンクは https:// で始まるアドレスにしてください",
        "enter the date you went, no later than today" to "行った日付（今日まで）を入力してください",
        "a visit's note must be at most {max} characters" to "訪問のメモは{max}文字以内にしてください",
        "a rating must be 1 to 5 stars" to "評価は星1〜5つにしてください",
        "that isn't a Google Maps link" to "Googleマップのリンクではありません",
        "reading photos isn't set up on this server; enter the shifts by hand" to "このサーバーでは写真の読み込みが設定されていません。手で入力してください",
        "first set the name as it's printed on the sheet" to "先に勤務表に書かれている名前を設定してください",
        "send a photo of the sheet (JPEG, PNG or WebP, up to 8 MB)" to "勤務表の写真（JPEG・PNG・WebP、8MBまで）を送ってください",
        "the photo couldn't be read; try a clearer one, or enter the shifts by hand" to "写真を読めませんでした。もっと鮮明な写真を使うか、手で入力してください",
        "the photo couldn't be read just now; try again, or enter the shifts by hand" to "今は写真を読めませんでした。もう一度試すか、手で入力してください",
        "couldn't find {name} on the sheet; check the name in settings" to "勤務表に{name}が見つかりませんでした。設定の名前を確認してください",
        "couldn't tell which month the sheet is for" to "何月の勤務表か分かりませんでした",
        "month must be YYYY-MM" to "月は YYYY-MM の形式で指定してください",
        "every day must be in {month}" to "すべての日付を{month}の中にしてください",
        "a shift code must be at most {max} characters" to "シフト記号は{max}文字以内にしてください",
        "a name must be at most 40 characters, and the name on the sheet 80" to "名前は40文字以内、勤務表上の名前は80文字以内にしてください",
        "each shift letter needs a length of more than 0 and at most 24 hours" to "シフトの英字ごとに、0より長く24時間以内の長さを指定してください",
        "unknown time zone" to "不明なタイムゾーンです",
        "choose where the stop is" to "バス停の場所を選んでください",
        "a stop's name must be at most 80 characters" to "バス停の名前は80文字以内にしてください",
        "ring between 200 m and 5 km before the stop" to "バス停の200m〜5km手前に設定してください",
        "not found" to "見つかりません",
        "that sign-in took too long; try again" to "ログインに時間がかかりすぎました。もう一度お試しください",
        "the demo can't do this; create an account to use it" to "デモではこの操作はできません。使うにはアカウントを作成してください",
        "the email could not be sent; try again in a few minutes" to "メールを送信できませんでした。数分後にお試しください",
        "the password is incorrect" to "パスワードが違います",
        "this link has expired or was already used; ask for a new one" to "このリンクは期限切れか、すでに使われています。新しいリンクを依頼してください",
        "this server doesn't send email" to "このサーバーはメールを送信しません",
        "type your email address to confirm" to "確認のためメールアドレスを入力してください",
        "your email is already confirmed" to "メールアドレスはすでに確認済みです",
        "the stop search isn’t answering; try again in a moment" to "バス停の検索が応答しません。少し待ってからもう一度お試しください",
    )
}

/** A server message in the app's language. */
@Composable
fun serverText(message: String): String = ServerText.translate(message, currentLocale)

/** What to say about a call that didn't work: the server's reason, or that it can't be reached. Null when it worked. */
@Composable
fun <T> Outcome<T>.problem(): String? = when (this) {
    is Outcome.Done -> null
    Outcome.Offline -> stringResource(R.string.offline)
    Outcome.SignedOut -> null
    is Outcome.Refused -> if (message.isEmpty()) stringResource(R.string.went_wrong) else serverText(message)
}

package io.github.silleruss.drivy

import androidx.annotation.DrawableRes

/** A 3×3 atlas: columns are growth stages; rows are idle, blink and greeting. */
data class CompanionForm(@param:DrawableRes val sprite: Int, val name: String)

enum class Companion(val displayName: String, val concept: String, val forms: List<CompanionForm>) {
    MOKA("모카", "숲에서 태어난 호기심 많은 풀잎 친구.\n잎사귀와 작은 꽃을 살랑이며 인사해요.", listOf(
        CompanionForm(R.drawable.moka_atlas, "새싹 모카"),
        CompanionForm(R.drawable.moka_atlas, "풀잎 모카"),
        CompanionForm(R.drawable.moka_atlas, "숲의 모카"))),
    NURI("누리", "맑은 물을 따라 어디든 떠나는 장난기 많은 친구.\n물결처럼 가볍게 두둥실 떠다녀요.", listOf(
        CompanionForm(R.drawable.nuri_atlas, "물방울 누리"),
        CompanionForm(R.drawable.nuri_atlas, "물결 누리"),
        CompanionForm(R.drawable.nuri_atlas, "푸른 누리"))),
    PIKO("피코", "언제나 새로운 것을 찾아 떠나는 호기심 많은 친구.\n작은 불꽃을 품고 신나게 뛰어놀아요.", listOf(
        CompanionForm(R.drawable.piko_atlas, "불씨 피코"),
        CompanionForm(R.drawable.piko_atlas, "불꽃 피코"),
        CompanionForm(R.drawable.piko_atlas, "타오른 피코"))),
    NERU("네루", "달빛을 따라 조용히 다니는 신비로운 친구.\n별을 품은 긴 귀를 늘어뜨리고 꾸벅꾸벅 졸아요.", listOf(
        CompanionForm(R.drawable.neru_atlas, "별빛 네루"),
        CompanionForm(R.drawable.neru_atlas, "달빛 네루"),
        CompanionForm(R.drawable.neru_atlas, "꿈꾸는 네루"))),
    TORI("토리", "사막을 누비며 동행을 지키는 사막여우 친구.\n성장할수록 차분하고 든든해져요.", listOf(
        CompanionForm(R.drawable.tori_atlas, "꼬마 토리"),
        CompanionForm(R.drawable.tori_atlas, "단단한 토리"),
        CompanionForm(R.drawable.tori_atlas, "수호자 토리"))),
    RAPI("라피", "언제나 꽃을 찾아 다정하고 따뜻한 사랑스러운 친구.\n분홍 꽃잎과 함께 긴 귀를 살랑여요.", listOf(
        CompanionForm(R.drawable.rapi_atlas, "꽃봉오리 라피"),
        CompanionForm(R.drawable.rapi_atlas, "꽃잎 라피"),
        CompanionForm(R.drawable.rapi_atlas, "만개한 라피")));

    companion object {
        fun fromSavedName(name: String?) = when (name) {
            "BORI" -> PIKO
            "NABI" -> NERU
            else -> entries.firstOrNull { it.name == name }
        }
    }
}

/*
 * SPDX-FileCopyrightText: 2026 kenway214
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.lockscreen

import com.android.settings.R

object ClockUtils {

    val CLOCK_LAYOUTS = intArrayOf(
        R.layout.keyguard_clock_default,
        R.layout.keyguard_clock_oos, // 1
        R.layout.keyguard_clock_oos2, // 2
        R.layout.keyguard_clock_center, // 3
        R.layout.keyguard_clock_simple, // 4
        R.layout.keyguard_clock_miui, // 5
        R.layout.keyguard_clock_ide, // 6
        R.layout.keyguard_clock_moto, // 7
        R.layout.keyguard_clock_stylish, // 8
        R.layout.keyguard_clock_stylish2, // 9
        R.layout.keyguard_clock_stylish3, // 10
        R.layout.keyguard_clock_stylish4, // 11
        R.layout.keyguard_clock_stylish5, // 12
        R.layout.keyguard_clock_stylish6, // 13
        R.layout.keyguard_clock_stylish7, // 14
        R.layout.keyguard_clock_stylish8, // 15
        R.layout.keyguard_clock_stylish9, // 16
        R.layout.keyguard_clock_stylish10, // 17
        R.layout.keyguard_clock_word, // 18
        R.layout.keyguard_clock_life, // 19
        R.layout.keyguard_clock_a9, // 20
        R.layout.keyguard_clock_nos1, // 21
        R.layout.keyguard_clock_nos2, // 22
        R.layout.keyguard_clock_num, // 23
        R.layout.keyguard_clock_accent, // 24
        R.layout.keyguard_clock_analog, // 25
        R.layout.keyguard_clock_block, // 26
        R.layout.keyguard_clock_bubble, // 27
        R.layout.keyguard_clock_label, // 28
        R.layout.keyguard_clock_ios, // 29
        R.layout.keyguard_clock_taden, // 30
        R.layout.keyguard_clock_mont, // 31
        R.layout.keyguard_clock_encode, // 32
        R.layout.keyguard_clock_nos3, // 33
        R.layout.keyguard_anci_clock_outline, // 34
        R.layout.keyguard_anci_clock_ovalium, // 35
        R.layout.keyguard_anci_clock_rectangle, // 36
        R.layout.keyguard_anci_clock_wallet, // 37
        R.layout.keyguard_anci_clockdate_clavicula, // 38
        R.layout.keyguard_anci_clockdate_kln, // 39
        R.layout.keyguard_anci_clockdate_miring, // 40
        R.layout.keyguard_anci_clockdate_scapula, // 41
        R.layout.keyguard_anci_clockdate_sternum, // 42
        R.layout.keyguard_sparkCircle, // 43
        R.layout.keyguard_sparkList, // 44
        R.layout.keyguard_clock_miui2, // 45
        R.layout.keyguard_clock_ios2, // 46
        R.layout.keyguard_clock_ios3, // 47
        R.layout.keyguard_clock_ios4, // 48
        R.layout.keyguard_clock_ios5, // 49
        R.layout.keyguard_clock_ios6, // 50
        R.layout.keyguard_clock_ios7, // 51
        R.layout.keyguard_clock_ios8, // 52
        R.layout.keyguard_clock_ios9, // 53
        R.layout.keyguard_clock_ios10, // 54
        R.layout.keyguard_clock_ios11, // 55
        R.layout.keyguard_clock_ios12, // 56
        R.layout.keyguard_clock_ios13, // 57
        R.layout.keyguard_clock_ios14, // 58
        R.layout.keyguard_clock_ios15, // 59
        R.layout.keyguard_clock_ios16, // 60
        R.layout.keyguard_clock_ios17, // 61
        R.layout.keyguard_clock_ios18, // 62
        R.layout.keyguard_clock_ios19, // 63
        R.layout.keyguard_clock_big1, // 64
        R.layout.keyguard_clock_big2, // 65
        R.layout.keyguard_clock_big3, // 66
        R.layout.keyguard_clock_big4, // 67
        R.layout.keyguard_clock_sweet, // 68
        R.layout.keyguard_clock_pixel, // 69
        R.layout.keyguard_clock_samurai, // 70
        R.layout.keyguard_clock_gateway, // 71
        R.layout.keyguard_clock_tall, // 72
        R.layout.keyguard_clock_gobold, // 73
        R.layout.keyguard_clock_gobold2, // 74
        R.layout.keyguard_clock_delirium, // 75
        R.layout.keyguard_clock_deliriumdual, // 76
        R.layout.keyguard_clock_skewrom, // 77
        R.layout.keyguard_clock_skewrom2, // 78
        R.layout.keyguard_clock_taller, // 79
        R.layout.keyguard_clock_taller2, // 80
        R.layout.keyguard_clock_taller3, // 81
        R.layout.keyguard_clock_modak, // 82
        R.layout.keyguard_clock_galada, // 83
        R.layout.keyguard_clock_cos1, // 84
        R.layout.keyguard_clock_cos2, // 85
        R.layout.keyguard_clock_neu, // 86
        R.layout.keyguard_clock_neu2, // 87
        R.layout.keyguard_clock_neu3, // 88
        R.layout.keyguard_clock_badeen, // 89
        R.layout.keyguard_clock_space_age, // 90
        R.layout.keyguard_clock_polyline, // 91
        R.layout.keyguard_clock_oppo, // 92
    )

    fun getClockNames(): Array<String> {
        return arrayOf(
            "Default Clock",
            "OnePlus Clock",
            "OnePlus Clock 2",
            "Center Clock",
            "Simple Clock",
            "MIUI Clock",
            "IDE Clock",
            "Moto Clock",
            "Stylish Clock",
            "Stylish Clock 2",
            "Stylish Clock 3",
            "Stylish Clock 4",
            "Stylish Clock 5",
            "Stylish Clock 6",
            "Stylish Clock 7",
            "Stylish Clock 8",
            "Stylish Clock 9",
            "Stylish Clock 10",
            "Text Clock",
            "LifeStyle Clock",
            "Android 9 Vibe",
            "NothingOS 1 Clock",
            "NothingOS 2 Clock",
            "Stacked Clock",
            "X Factor",
            "Simple Analog",
            "Block",
            "Bubble",
            "Label Clock",
            "iOS Clock",
            "Taden Clock",
            "Mont Clock",
            "Encode Clock",
            "NOS Clock 3",
            "Anci Outline",
            "Anci Ovalium",
            "Anci Rectangle",
            "Anci Wallet",
            "Anci Clavicula",
            "Anci KLN",
            "Anci Miring",
            "Anci Scapula",
            "Anci Sternum",
            "Spark Circle",
            "Spark List",
            "HyperOS Clock",
            "iOS 2",
            "iOS 3",
            "iOS 4",
            "iOS 5",
            "iOS 6",
            "iOS 7",
            "iOS 8",
            "iOS 9",
            "iOS 10",
            "iOS 11",
            "iOS 12",
            "iOS 13",
            "iOS 14",
            "iOS 15",
            "iOS 16",
            "iOS 17",
            "iOS 18",
            "iOS 19",
            "Big 1",
            "Big 2",
            "Big 3",
            "Big 4",
            "Sweet",
            "Pixel",
            "Samurai",
            "Gateway",
            "Tall clock",
            "GoBold monet",
            "GoBold",
            "Delirium",
            "Delirium Dual",
            "Skewrom",
            "Skewrom 2",
            "Taller",
            "Taller 2",
            "Taller 3",
            "Modak",
            "Galada",
            "COS 1",
            "COS 2",
            "Neu",
            "Neu2",
            "Neu3",
            "Badeen",
            "Space age",
            "Poly line",
            "Big clock"
        )
    }
}

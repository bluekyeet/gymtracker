package com.gymtracker.shared.domain

object Units {
    const val KG_TO_LB = 2.2046226218

    fun kgToLb(kg: Double): Double = kg * KG_TO_LB
    fun lbToKg(lb: Double): Double = lb / KG_TO_LB
}

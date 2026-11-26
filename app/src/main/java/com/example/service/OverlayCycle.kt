/*
 * CoreGovernor - custom CPU/GPU throttling governor
 * Copyright (C) 2026 Azrael
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */

package com.example.service

import com.example.model.GovernorConfig

/**
 * v3.8.1 Quick Settings tile logic (pure, no Android dependencies).
 *
 * One tap moves the overlay to the next step: Off -> Compact -> Expanded -> Detailed -> Off.
 * The order of the three styles is the same as tapping the overlay itself.
 */
object OverlayCycle {
    enum class Step { OFF, COMPACT, EXPANDED, DETAILED }

    /** The step the overlay is in for this config. */
    fun current(cfg: GovernorConfig): Step = when {
        !cfg.overlayEnabled -> Step.OFF
        cfg.overlayDetailed -> Step.DETAILED
        cfg.overlayExpanded -> Step.EXPANDED
        else -> Step.COMPACT
    }

    fun next(step: Step): Step = when (step) {
        Step.OFF -> Step.COMPACT
        Step.COMPACT -> Step.EXPANDED
        Step.EXPANDED -> Step.DETAILED
        Step.DETAILED -> Step.OFF
    }

    /** Config with the overlay put into [step]. Off only switches it off, so every size and position is kept. */
    fun apply(cfg: GovernorConfig, step: Step): GovernorConfig = when (step) {
        Step.OFF -> cfg.copy(overlayEnabled = false)
        Step.COMPACT -> cfg.copy(overlayEnabled = true, overlayExpanded = false, overlayDetailed = false)
        Step.EXPANDED -> cfg.copy(overlayEnabled = true, overlayExpanded = true, overlayDetailed = false)
        Step.DETAILED -> cfg.copy(overlayEnabled = true, overlayExpanded = false, overlayDetailed = true)
    }

    /** One tap: the config after moving to the next step. */
    fun advance(cfg: GovernorConfig): GovernorConfig = apply(cfg, next(current(cfg)))
}

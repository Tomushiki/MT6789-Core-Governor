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

package com.example.governor

import com.example.model.AutoTuneCandidateSpec
import com.example.model.AutoTuneMode
import com.example.model.AutoTuneParam
import com.example.model.AutoTuneTrialRecord
import com.example.model.AutoTuneTrialStatus
import com.example.model.EffectiveGameConfig
import com.example.model.GovernorConfig
import com.example.model.PerGameAutoTuneState
import com.example.model.PerGameProfile
import com.example.model.boostStrengthFor
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

data class AutoTuneParamBound(
    val param: AutoTuneParam,
    val minVal: Float,
    val maxVal: Float,
    val step: Float,
    val defaultDirection: Int,
    val decimals: Int,
    val unit: String
)

object AutoTuneLogic {

    /** Expected-benefit parameter order and hard safety bounds table. */
    val PARAM_TABLE: List<AutoTuneParamBound> = listOf(
        AutoTuneParamBound(AutoTuneParam.POWER_LIMIT_WATTS, minVal = 3.0f, maxVal = 10.0f, step = 0.5f, defaultDirection = -1, decimals = 1, unit = " W"),
        AutoTuneParamBound(AutoTuneParam.TEMP_START_C, minVal = 36.0f, maxVal = 52.0f, step = 1.5f, defaultDirection = -1, decimals = 1, unit = " C"),
        AutoTuneParamBound(AutoTuneParam.HEADROOM_PCT, minVal = 6.0f, maxVal = 35.0f, step = 4.0f, defaultDirection = 1, decimals = 0, unit = "%"),
        AutoTuneParamBound(AutoTuneParam.GPU_HEADROOM_PCT, minVal = 6.0f, maxVal = 35.0f, step = 4.0f, defaultDirection = 1, decimals = 0, unit = "%"),
        AutoTuneParamBound(AutoTuneParam.RAMP_DOWN_SPEED, minVal = 0.02f, maxVal = 0.30f, step = 0.04f, defaultDirection = -1, decimals = 2, unit = ""),
        AutoTuneParamBound(AutoTuneParam.GPU_RAMP_DOWN_SPEED, minVal = 0.02f, maxVal = 0.30f, step = 0.04f, defaultDirection = -1, decimals = 2, unit = ""),
        AutoTuneParamBound(AutoTuneParam.RAMP_UP_SPEED, minVal = 0.50f, maxVal = 0.98f, step = 0.08f, defaultDirection = 1, decimals = 2, unit = ""),
        AutoTuneParamBound(AutoTuneParam.GPU_RAMP_UP_SPEED, minVal = 0.50f, maxVal = 0.98f, step = 0.08f, defaultDirection = 1, decimals = 2, unit = "")
    )

    private val BOUNDS_BY_PARAM = PARAM_TABLE.associateBy { it.param }

    fun boundFor(param: AutoTuneParam): AutoTuneParamBound = BOUNDS_BY_PARAM.getValue(param)

    fun formatValue(param: AutoTuneParam, value: Float): String {
        val b = boundFor(param)
        return String.format(Locale.US, "%.${b.decimals}f%s", value, b.unit)
    }

    /**
     * Resolves the effective settings for [packageName] when it is in the foreground in Game mode.
     * Clamps power limit and thermal throttle limits so a per-game profile can never exceed the user's
     * global limits. Safe to cache per `(config, packageName)`.
     */
    fun resolveEffectiveForGame(cfg: GovernorConfig, packageName: String): EffectiveGameConfig {
        val ov = cfg.perGameProfiles[packageName]
        val at = cfg.autoTuneByPkg[packageName]
        val gm = cfg.gameMode
        val tt = cfg.tempThrottle
        val pt = cfg.powerThrottle

        val effGameMode = if (ov == null) {
            gm
        } else {
            gm.copy(
                headroomPct = (ov.headroomPct ?: gm.headroomPct).coerceIn(0f, 50f),
                rampUpSpeed = (ov.rampUpSpeed ?: gm.rampUpSpeed).coerceIn(0.05f, 1f),
                rampDownSpeed = (ov.rampDownSpeed ?: gm.rampDownSpeed).coerceIn(0.01f, 0.95f),
                gpuHeadroomPct = (ov.gpuHeadroomPct ?: gm.gpuHeadroomPct).coerceIn(0f, 50f),
                gpuRampUpSpeed = (ov.gpuRampUpSpeed ?: gm.gpuRampUpSpeed).coerceIn(0.05f, 1f),
                gpuRampDownSpeed = (ov.gpuRampDownSpeed ?: gm.gpuRampDownSpeed).coerceIn(0.01f, 0.95f),
                cpuRangesKhz = ov.cpuRangesKhz ?: gm.cpuRangesKhz,
                gpuRangeKhz = ov.gpuRangeKhz ?: gm.gpuRangeKhz
            )
        }

        val effTempStart = ov?.tempStartC?.let { minOf(it, tt.startTempC) } ?: tt.startTempC
        val effTempFull = (ov?.tempFullC?.let { minOf(it, tt.fullTempC) } ?: tt.fullTempC)
            .coerceAtLeast(effTempStart + 2f)
        val effTemp = if (ov == null) tt else tt.copy(startTempC = effTempStart, fullTempC = effTempFull)

        val effPowerLimit = ov?.powerLimitWatts?.let {
            minOf(it, pt.limitWatts).coerceIn(3.0f, 10.0f)
        } ?: pt.limitWatts
        val effPower = if (ov == null) pt else pt.copy(limitWatts = effPowerLimit)

        val effBoost = ov?.afterburnStrength ?: cfg.boostStrengthFor(packageName)

        val atActive = at != null && at.enabled && !at.paused
        val trialId = if (atActive) at?.pendingTrialId ?: 0 else 0
        return EffectiveGameConfig(
            packageName = packageName,
            gameMode = effGameMode,
            tempThrottle = effTemp,
            powerThrottle = effPower,
            afterburnStrength = effBoost,
            hasPerGameOverride = ov != null && !ov.isEmpty,
            autoTuneActive = atActive,
            autoTuneTrialId = trialId
        )
    }

    fun currentParamValue(param: AutoTuneParam, profile: PerGameProfile, cfg: GovernorConfig): Float {
        return when (param) {
            AutoTuneParam.POWER_LIMIT_WATTS -> profile.powerLimitWatts ?: cfg.powerThrottle.limitWatts
            AutoTuneParam.TEMP_START_C -> profile.tempStartC ?: cfg.tempThrottle.startTempC
            AutoTuneParam.HEADROOM_PCT -> profile.headroomPct ?: cfg.gameMode.headroomPct
            AutoTuneParam.GPU_HEADROOM_PCT -> profile.gpuHeadroomPct ?: cfg.gameMode.gpuHeadroomPct
            AutoTuneParam.RAMP_DOWN_SPEED -> profile.rampDownSpeed ?: cfg.gameMode.rampDownSpeed
            AutoTuneParam.GPU_RAMP_DOWN_SPEED -> profile.gpuRampDownSpeed ?: cfg.gameMode.gpuRampDownSpeed
            AutoTuneParam.RAMP_UP_SPEED -> profile.rampUpSpeed ?: cfg.gameMode.rampUpSpeed
            AutoTuneParam.GPU_RAMP_UP_SPEED -> profile.gpuRampUpSpeed ?: cfg.gameMode.gpuRampUpSpeed
        }
    }

    fun maxAllowedFor(param: AutoTuneParam, cfg: GovernorConfig): Float {
        val b = boundFor(param)
        return when (param) {
            AutoTuneParam.POWER_LIMIT_WATTS -> minOf(b.maxVal, cfg.powerThrottle.limitWatts).coerceAtLeast(b.minVal)
            AutoTuneParam.TEMP_START_C -> minOf(b.maxVal, cfg.tempThrottle.startTempC).coerceAtLeast(b.minVal)
            else -> b.maxVal
        }
    }

    fun withParam(profile: PerGameProfile, param: AutoTuneParam, value: Float, cfg: GovernorConfig): PerGameProfile {
        val b = boundFor(param)
        val clamped = value.coerceIn(b.minVal, maxAllowedFor(param, cfg))
        val rounded = (clamped * 100f).roundToInt() / 100f
        return when (param) {
            AutoTuneParam.POWER_LIMIT_WATTS -> profile.copy(powerLimitWatts = rounded)
            AutoTuneParam.TEMP_START_C -> profile.copy(tempStartC = rounded)
            AutoTuneParam.HEADROOM_PCT -> profile.copy(headroomPct = rounded)
            AutoTuneParam.GPU_HEADROOM_PCT -> profile.copy(gpuHeadroomPct = rounded)
            AutoTuneParam.RAMP_DOWN_SPEED -> profile.copy(rampDownSpeed = rounded)
            AutoTuneParam.GPU_RAMP_DOWN_SPEED -> profile.copy(gpuRampDownSpeed = rounded)
            AutoTuneParam.RAMP_UP_SPEED -> profile.copy(rampUpSpeed = rounded)
            AutoTuneParam.GPU_RAMP_UP_SPEED -> profile.copy(gpuRampUpSpeed = rounded)
        }
    }

    fun candidateKey(param: AutoTuneParam, value: Float): String =
        "${param.name}:${String.format(Locale.US, "%.2f", value)}"

    private fun buildCandidate(
        param: AutoTuneParam,
        direction: Int,
        baseProfile: PerGameProfile,
        cfg: GovernorConfig,
        state: PerGameAutoTuneState
    ): AutoTuneCandidateSpec? {
        val b = boundFor(param)
        val cur = currentParamValue(param, baseProfile, cfg)
        val raw = cur + direction * b.step
        val maxA = maxAllowedFor(param, cfg)
        val target = ((raw.coerceIn(b.minVal, maxA)) * 100f).roundToInt() / 100f
        if (abs(target - cur) < b.step * 0.25f) return null
        val key = candidateKey(param, target)
        if (key in state.badCandidateKeys || key in state.triedCandidateKeys) return null
        return AutoTuneCandidateSpec(param = param, direction = direction, value = target, key = key)
    }

    /** Picks the next untried, safe, unlocked candidate along the ordered parameter list. */
    fun pickNextCandidate(
        state: PerGameAutoTuneState,
        cfg: GovernorConfig,
        startCursor: Int = state.paramCursor,
        preferredDirection: Int? = null
    ): Pair<AutoTuneCandidateSpec?, Int> {
        val unlocked = PARAM_TABLE.map { it.param }.filter { it !in state.lockedParams }
        if (unlocked.isEmpty()) return null to 0

        val total = PARAM_TABLE.size
        for (offset in 0 until total) {
            val idx = (startCursor + offset) % total
            val bound = PARAM_TABLE[idx]
            if (bound.param in state.lockedParams) continue
            val dirs = if (offset == 0 && preferredDirection != null) {
                listOf(preferredDirection, -preferredDirection)
            } else {
                listOf(bound.defaultDirection, -bound.defaultDirection)
            }
            for (d in dirs) {
                val cand = buildCandidate(bound.param, d, state.bestProfile, cfg, state)
                if (cand != null) return cand to idx
            }
        }
        return null to startCursor
    }

    /**
     * Called ONLY at game session start so settings never change mid-session.
     * In AUTO_APPLY mode, activates the next pending candidate into `perGameProfiles[pkg]` when ready.
     */
    fun prepareSessionStart(cfg: GovernorConfig, pkg: String): GovernorConfig {
        if (pkg.isBlank()) return cfg
        val st = cfg.autoTuneByPkg[pkg] ?: return cfg
        if (!st.enabled || st.paused || st.converged || !st.baselineCaptured) return cfg
        if (st.mode != AutoTuneMode.AUTO_APPLY) return cfg

        if (st.pendingCandidate != null && st.candidateProfile != null) {
            val curProf = cfg.perGameProfiles[pkg]
            if (curProf == st.candidateProfile) return cfg
            val nextProfiles = cfg.perGameProfiles.toMutableMap()
            if (st.candidateProfile.isEmpty) nextProfiles.remove(pkg) else nextProfiles[pkg] = st.candidateProfile
            return cfg.copy(perGameProfiles = nextProfiles)
        }

        val (cand, nextCursor) = pickNextCandidate(st, cfg)
        if (cand == null) {
            val updatedSt = st.copy(converged = true, pendingCandidate = null, candidateProfile = null)
            return cfg.copy(autoTuneByPkg = cfg.autoTuneByPkg + (pkg to updatedSt))
        }

        val candProfile = withParam(st.bestProfile, cand.param, cand.value, cfg)
        val trialId = st.nextTrialId
        val updatedSt = st.copy(
            paramCursor = nextCursor,
            pendingCandidate = cand,
            candidateProfile = candProfile,
            pendingTrialId = trialId,
            nextTrialId = trialId + 1
        )
        val nextProfiles = cfg.perGameProfiles.toMutableMap()
        if (candProfile.isEmpty) nextProfiles.remove(pkg) else nextProfiles[pkg] = candProfile
        return cfg.copy(
            perGameProfiles = nextProfiles,
            autoTuneByPkg = cfg.autoTuneByPkg + (pkg to updatedSt)
        )
    }

    /**
     * Evaluates a completed game session and updates the per-game auto-tune state + profile.
     * Enforces minimum duration, sample count, non-charging rule, no mid-session edits, hard safety bounds,
     * and margin-based hill climbing.
     */
    fun evaluateCompletedSession(
        cfg: GovernorConfig,
        session: SessionInfo,
        nowMs: Long = session.endMs
    ): GovernorConfig {
        val pkg = session.packageName
        val st = cfg.autoTuneByPkg[pkg] ?: return cfg
        if (!st.enabled || st.paused) return cfg

        val sum = session.summary
        val score = SessionScoring.compositeScore(sum, cfg.scoreWeights)
        val minDurMs = st.minSessionMinutes.coerceIn(1, 30) * 60_000L

        val ignoreReasonCode: String? = when {
            sum.includesCharging -> "ignored_charging"
            sum.sampleCount < MetricsRecorder.MIN_SESSION_SAMPLES -> "ignored_short_samples"
            sum.durationMs < minDurMs -> "ignored_short_duration"
            session.midSessionChanges > 0 -> "ignored_mid_changes"
            !score.isFinite() -> "ignored_no_fps"
            else -> null
        }

        val activeCand = st.pendingCandidate
        if (ignoreReasonCode != null) {
            val rec = AutoTuneTrialRecord(
                trialId = st.nextTrialId,
                timestampMs = nowMs,
                sessionFileName = session.fileName,
                param = activeCand?.param,
                oldValue = activeCand?.let { formatValue(it.param, currentParamValue(it.param, st.bestProfile, cfg)) }.orEmpty(),
                newValue = activeCand?.let { formatValue(it.param, it.value) }.orEmpty(),
                score = score,
                baselineScore = st.baselineScore,
                bestScoreBefore = st.bestScore,
                avgFps = sum.avgFps,
                p5Fps = sum.p5Fps,
                avgWatts = sum.avgWatts,
                maxTempC = sum.maxTempC,
                status = AutoTuneTrialStatus.IGNORED,
                reason = ignoreReasonCode,
                reasonCode = ignoreReasonCode,
                candidateKey = activeCand?.key.orEmpty()
            )
            val updatedSt = st.copy(
                nextTrialId = st.nextTrialId + 1,
                trials = (st.trials + rec).takeLast(60)
            )
            return cfg.copy(autoTuneByPkg = cfg.autoTuneByPkg + (pkg to updatedSt))
        }

        // First clean valid session establishes the baseline.
        if (!st.baselineCaptured) {
            val baseProfile = cfg.perGameProfiles[pkg] ?: PerGameProfile()
            val trialId = st.nextTrialId
            val rec = AutoTuneTrialRecord(
                trialId = trialId,
                timestampMs = nowMs,
                sessionFileName = session.fileName,
                score = score,
                baselineScore = score,
                bestScoreBefore = score,
                avgFps = sum.avgFps,
                p5Fps = sum.p5Fps,
                avgWatts = sum.avgWatts,
                maxTempC = sum.maxTempC,
                status = AutoTuneTrialStatus.BASELINE,
                reason = "baseline_recorded",
                reasonCode = "baseline_recorded"
            )
            val seededState = st.copy(
                baselineCaptured = true,
                baselineProfile = baseProfile,
                userOriginalProfile = st.userOriginalProfile ?: cfg.perGameProfiles[pkg],
                bestProfile = baseProfile,
                baselineScore = score,
                baselineP5Fps = sum.p5Fps,
                bestScore = score,
                nextTrialId = trialId + 1,
                trials = (st.trials + rec).takeLast(60)
            )
            val (firstCand, cursor) = pickNextCandidate(seededState, cfg, 0)
            if (firstCand == null) {
                val convState = seededState.copy(converged = true)
                return cfg.copy(autoTuneByPkg = cfg.autoTuneByPkg + (pkg to convState))
            }
            val finalState = if (seededState.mode == AutoTuneMode.SUGGEST_ONLY) {
                val sugRec = AutoTuneTrialRecord(
                    trialId = seededState.nextTrialId,
                    timestampMs = nowMs,
                    param = firstCand.param,
                    oldValue = formatValue(firstCand.param, currentParamValue(firstCand.param, baseProfile, cfg)),
                    newValue = formatValue(firstCand.param, firstCand.value),
                    status = AutoTuneTrialStatus.PENDING,
                    reason = "suggest_try_next",
                    reasonCode = "suggest_try_next",
                    candidateKey = firstCand.key
                )
                seededState.copy(paramCursor = cursor, pendingSuggestion = sugRec)
            } else {
                seededState.copy(paramCursor = cursor)
            }
            return cfg.copy(autoTuneByPkg = cfg.autoTuneByPkg + (pkg to finalState))
        }

        // If no candidate was pending (e.g., Suggest-only session without tapped Apply, or already converged), ignore scoring mutation or keep best.
        if (activeCand == null || st.candidateProfile == null) {
            return cfg
        }

        val durSec = (sum.durationMs / 1000f).coerceAtLeast(1f)
        val effLimitTemp = minOf(
            cfg.tempThrottle.fullTempC,
            (cfg.perGameProfiles[pkg]?.tempFullC ?: cfg.tempThrottle.fullTempC)
        )
        val tempBreach = sum.maxTempC > effLimitTemp ||
            (cfg.tempThrottle.enabled && sum.maxTempC > cfg.tempThrottle.startTempC + 2f && sum.thermalThrottleSec > durSec * 0.25f)
        val heavyPowerThrottle = cfg.powerThrottle.enabled && sum.powerThrottleSec > durSec * 0.45f
        val p5Collapse = st.baselineP5Fps.isFinite() && st.baselineP5Fps > 0f &&
            sum.p5Fps.isFinite() && sum.p5Fps < st.baselineP5Fps * 0.85f

        val safetyFailCode: String? = when {
            tempBreach -> "safety_thermal_limit"
            heavyPowerThrottle -> "safety_power_throttle"
            p5Collapse -> "safety_p5_collapse"
            else -> null
        }

        val oldValStr = formatValue(activeCand.param, currentParamValue(activeCand.param, st.bestProfile, cfg))
        val newValStr = formatValue(activeCand.param, activeCand.value)
        val trialId = if (st.pendingTrialId > 0) st.pendingTrialId else st.nextTrialId
        val nextId = maxOf(st.nextTrialId, trialId + 1)

        if (safetyFailCode != null) {
            val rec = AutoTuneTrialRecord(
                trialId = trialId,
                timestampMs = nowMs,
                sessionFileName = session.fileName,
                param = activeCand.param,
                oldValue = oldValStr,
                newValue = newValStr,
                score = score,
                baselineScore = st.baselineScore,
                bestScoreBefore = st.bestScore,
                avgFps = sum.avgFps,
                p5Fps = sum.p5Fps,
                avgWatts = sum.avgWatts,
                maxTempC = sum.maxTempC,
                status = AutoTuneTrialStatus.REJECTED_SAFETY,
                reason = safetyFailCode,
                reasonCode = safetyFailCode,
                candidateKey = activeCand.key
            )
            return finishCandidateDecision(
                cfg = cfg,
                pkg = pkg,
                st = st,
                activeCand = activeCand,
                improved = false,
                newBestScore = st.bestScore,
                newBestProfile = st.bestProfile,
                record = rec,
                nextTrialId = nextId,
                blacklistBad = true,
                nowMs = nowMs
            )
        }

        val threshold = if (st.bestScore.isFinite() && st.bestScore > 0f) {
            st.bestScore * (1f + st.rewardMarginPct / 100f)
        } else 0f
        val improved = score >= threshold

        val rec = AutoTuneTrialRecord(
            trialId = trialId,
            timestampMs = nowMs,
            sessionFileName = session.fileName,
            param = activeCand.param,
            oldValue = oldValStr,
            newValue = newValStr,
            score = score,
            baselineScore = st.baselineScore,
            bestScoreBefore = st.bestScore,
            avgFps = sum.avgFps,
            p5Fps = sum.p5Fps,
            avgWatts = sum.avgWatts,
            maxTempC = sum.maxTempC,
            status = if (improved) AutoTuneTrialStatus.KEPT else AutoTuneTrialStatus.REVERTED,
            reason = if (improved) "beat_best_margin" else "below_margin",
            reasonCode = if (improved) "beat_best_margin" else "below_margin",
            candidateKey = activeCand.key
        )

        return finishCandidateDecision(
            cfg = cfg,
            pkg = pkg,
            st = st,
            activeCand = activeCand,
            improved = improved,
            newBestScore = if (improved) score else st.bestScore,
            newBestProfile = if (improved) st.candidateProfile else st.bestProfile,
            record = rec,
            nextTrialId = nextId,
            blacklistBad = false,
            nowMs = nowMs
        )
    }

    private fun finishCandidateDecision(
        cfg: GovernorConfig,
        pkg: String,
        st: PerGameAutoTuneState,
        activeCand: AutoTuneCandidateSpec,
        improved: Boolean,
        newBestScore: Float,
        newBestProfile: PerGameProfile,
        record: AutoTuneTrialRecord,
        nextTrialId: Int,
        blacklistBad: Boolean,
        nowMs: Long
    ): GovernorConfig {
        val nextTried = st.triedCandidateKeys + activeCand.key
        val nextBad = if (blacklistBad) st.badCandidateKeys + activeCand.key else st.badCandidateKeys

        // Revert or promote perGameProfiles[pkg] cleanly immediately upon session end so next launch is safe.
        val updatedProfiles = cfg.perGameProfiles.toMutableMap()
        if (newBestProfile.isEmpty) updatedProfiles.remove(pkg) else updatedProfiles[pkg] = newBestProfile

        val unlockedCount = PARAM_TABLE.count { it.param !in st.lockedParams }.coerceAtLeast(1)
        val afterOutcome = st.copy(
            bestProfile = newBestProfile,
            bestScore = newBestScore,
            candidateProfile = null,
            pendingCandidate = null,
            pendingTrialId = 0,
            pendingSuggestion = null,
            nextTrialId = nextTrialId,
            triedCandidateKeys = nextTried,
            badCandidateKeys = nextBad,
            trials = (st.trials + record).takeLast(60)
        )

        // Choose where to probe next:
        // If improved: step forward to next parameter (or keep climbing); if failed: try opposite direction on same param first.
        val nextCandResult = if (improved) {
            val nextIdx = (st.paramCursor + 1) % PARAM_TABLE.size
            pickNextCandidate(afterOutcome, cfg, nextIdx)
        } else {
            // Try opposite direction on same parameter first if unlocked and untried.
            val sameParam = PARAM_TABLE[st.paramCursor % PARAM_TABLE.size].param
            val oppDir = -activeCand.direction
            val oppCand = if (sameParam !in afterOutcome.lockedParams) {
                buildCandidate(sameParam, oppDir, newBestProfile, cfg, afterOutcome)
            } else null
            if (oppCand != null) {
                oppCand to (st.paramCursor % PARAM_TABLE.size)
            } else {
                val nextIdx = (st.paramCursor + 1) % PARAM_TABLE.size
                pickNextCandidate(afterOutcome, cfg, nextIdx)
            }
        }

        val nextNoImprove = if (improved) 0 else st.consecutiveNoImproveParams + (if (nextCandResult.second != st.paramCursor) 1 else 0)
        val hasCandidate = nextCandResult.first != null && nextNoImprove < unlockedCount
        val converged = !hasCandidate

        val nextSuggestion = if (!converged && afterOutcome.mode == AutoTuneMode.SUGGEST_ONLY && nextCandResult.first != null) {
            val nc = nextCandResult.first!!
            AutoTuneTrialRecord(
                trialId = nextTrialId,
                timestampMs = nowMs,
                param = nc.param,
                oldValue = formatValue(nc.param, currentParamValue(nc.param, newBestProfile, cfg)),
                newValue = formatValue(nc.param, nc.value),
                status = AutoTuneTrialStatus.PENDING,
                reason = "suggest_try_next",
                reasonCode = "suggest_try_next",
                candidateKey = nc.key
            )
        } else null

        val finalState = afterOutcome.copy(
            paramCursor = nextCandResult.second,
            consecutiveNoImproveParams = nextNoImprove,
            converged = converged,
            pendingSuggestion = nextSuggestion
        )
        return cfg.copy(
            perGameProfiles = updatedProfiles,
            autoTuneByPkg = cfg.autoTuneByPkg + (pkg to finalState)
        )
    }

    /** Called when user taps Apply on a pending suggestion in Suggest-only mode. */
    fun applySuggestion(cfg: GovernorConfig, pkg: String): GovernorConfig {
        val st = cfg.autoTuneByPkg[pkg] ?: return cfg
        val sug = st.pendingSuggestion ?: return cfg
        val param = sug.param ?: return cfg
        val bound = boundFor(param)
        val rawVal = sug.newValue.removeSuffix(bound.unit).trim().toFloatOrNull() ?: return cfg
        val cand = AutoTuneCandidateSpec(
            param = param,
            direction = if (rawVal >= currentParamValue(param, st.bestProfile, cfg)) 1 else -1,
            value = rawVal,
            key = sug.candidateKey.ifBlank { candidateKey(param, rawVal) }
        )
        val candProfile = withParam(st.bestProfile, param, rawVal, cfg)
        val trialId = st.nextTrialId
        val nextProfiles = cfg.perGameProfiles.toMutableMap()
        if (candProfile.isEmpty) nextProfiles.remove(pkg) else nextProfiles[pkg] = candProfile
        val updatedSt = st.copy(
            pendingCandidate = cand,
            candidateProfile = candProfile,
            pendingTrialId = trialId,
            nextTrialId = trialId + 1,
            pendingSuggestion = null
        )
        return cfg.copy(
            perGameProfiles = nextProfiles,
            autoTuneByPkg = cfg.autoTuneByPkg + (pkg to updatedSt)
        )
    }

    fun setEnabled(cfg: GovernorConfig, pkg: String, enabled: Boolean): GovernorConfig {
        if (pkg.isBlank()) return cfg
        val cur = cfg.autoTuneByPkg[pkg] ?: PerGameAutoTuneState()
        val orig = if (enabled && cur.userOriginalProfile == null) cfg.perGameProfiles[pkg] else cur.userOriginalProfile
        val nextSt = cur.copy(enabled = enabled, userOriginalProfile = orig)
        return cfg.copy(autoTuneByPkg = cfg.autoTuneByPkg + (pkg to nextSt))
    }

    fun setMode(cfg: GovernorConfig, pkg: String, mode: AutoTuneMode): GovernorConfig {
        if (pkg.isBlank()) return cfg
        val cur = cfg.autoTuneByPkg[pkg] ?: PerGameAutoTuneState()
        var nextSt = cur.copy(mode = mode)
        if (mode == AutoTuneMode.SUGGEST_ONLY && cur.baselineCaptured && !cur.converged && cur.pendingSuggestion == null && cur.pendingCandidate == null) {
            val (nc, cursor) = pickNextCandidate(nextSt, cfg)
            if (nc != null) {
                nextSt = nextSt.copy(
                    paramCursor = cursor,
                    pendingSuggestion = AutoTuneTrialRecord(
                        trialId = nextSt.nextTrialId,
                        timestampMs = System.currentTimeMillis(),
                        param = nc.param,
                        oldValue = formatValue(nc.param, currentParamValue(nc.param, nextSt.bestProfile, cfg)),
                        newValue = formatValue(nc.param, nc.value),
                        status = AutoTuneTrialStatus.PENDING,
                        reason = "suggest_try_next",
                        reasonCode = "suggest_try_next",
                        candidateKey = nc.key
                    )
                )
            }
        }
        return cfg.copy(autoTuneByPkg = cfg.autoTuneByPkg + (pkg to nextSt))
    }

    fun setPaused(cfg: GovernorConfig, pkg: String, paused: Boolean): GovernorConfig {
        val cur = cfg.autoTuneByPkg[pkg] ?: return cfg
        return cfg.copy(autoTuneByPkg = cfg.autoTuneByPkg + (pkg to cur.copy(paused = paused)))
    }

    fun setMinSessionMinutes(cfg: GovernorConfig, pkg: String, minutes: Int): GovernorConfig {
        val cur = cfg.autoTuneByPkg[pkg] ?: PerGameAutoTuneState()
        return cfg.copy(autoTuneByPkg = cfg.autoTuneByPkg + (pkg to cur.copy(minSessionMinutes = minutes.coerceIn(1, 30))))
    }

    fun toggleLockParam(cfg: GovernorConfig, pkg: String, param: AutoTuneParam): GovernorConfig {
        val cur = cfg.autoTuneByPkg[pkg] ?: PerGameAutoTuneState()
        val nextLocked = if (param in cur.lockedParams) cur.lockedParams - param else cur.lockedParams + param
        return cfg.copy(autoTuneByPkg = cfg.autoTuneByPkg + (pkg to cur.copy(lockedParams = nextLocked)))
    }

    fun resetToBaseline(cfg: GovernorConfig, pkg: String): GovernorConfig {
        val cur = cfg.autoTuneByPkg[pkg] ?: return cfg
        val nextProfiles = cfg.perGameProfiles.toMutableMap()
        if (cur.baselineProfile.isEmpty) nextProfiles.remove(pkg) else nextProfiles[pkg] = cur.baselineProfile
        val nextSt = cur.copy(
            bestProfile = cur.baselineProfile,
            bestScore = cur.baselineScore,
            candidateProfile = null,
            pendingCandidate = null,
            pendingTrialId = 0,
            pendingSuggestion = null,
            converged = false,
            consecutiveNoImproveParams = 0
        )
        return cfg.copy(
            perGameProfiles = nextProfiles,
            autoTuneByPkg = cfg.autoTuneByPkg + (pkg to nextSt)
        )
    }

    fun revertToUserSettings(cfg: GovernorConfig, pkg: String): GovernorConfig {
        val cur = cfg.autoTuneByPkg[pkg] ?: return cfg
        val orig = cur.userOriginalProfile
        val nextProfiles = cfg.perGameProfiles.toMutableMap()
        if (orig == null || orig.isEmpty) nextProfiles.remove(pkg) else nextProfiles[pkg] = orig
        val nextSt = cur.copy(
            enabled = false,
            paused = false,
            candidateProfile = null,
            pendingCandidate = null,
            pendingTrialId = 0,
            pendingSuggestion = null
        )
        return cfg.copy(
            perGameProfiles = nextProfiles,
            autoTuneByPkg = cfg.autoTuneByPkg + (pkg to nextSt)
        )
    }

    fun retune(cfg: GovernorConfig, pkg: String): GovernorConfig {
        val cur = cfg.autoTuneByPkg[pkg] ?: PerGameAutoTuneState(enabled = true)
        val nextSt = cur.copy(
            enabled = true,
            paused = false,
            converged = false,
            consecutiveNoImproveParams = 0,
            paramCursor = 0,
            candidateProfile = null,
            pendingCandidate = null,
            pendingTrialId = 0,
            pendingSuggestion = null,
            triedCandidateKeys = emptySet()
        )
        return cfg.copy(autoTuneByPkg = cfg.autoTuneByPkg + (pkg to nextSt))
    }
}

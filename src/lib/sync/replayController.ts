/**
 * Contrôleur central qui dérive {rps, batchSize, batchPauseMs} depuis :
 *   - le mode du state machine (NORMAL/DEGRADED/RECOVERY)
 *   - les stats de la sliding window
 *   - la pression de la file (queueSize / SOFT_LIMIT)
 *
 * Logique déterministe — pas de heuristique stochastique, pas de ML.
 * Pure function `decide()` + helper opt-in pour smoothing inter-tick.
 *
 * Smooth transitions : le décideur produit une CONSIGNE (target). Le caller
 * applique un step-limited update (±1 RPS par tick, ±5 batch par tick) pour
 * éviter les sauts brusques.
 *
 * Bornes :
 *   minRPS = 2, maxRPS = 15
 *   minBatch = 1, maxBatch = 30
 *   minPauseMs = 25, maxPauseMs = 500
 */

import type { ReplayMode } from "./stateMachine";
import type { WindowStats } from "./metricsWindow";

export interface ControllerParams {
  rps: number;
  batchSize: number;
  batchPauseMs: number;
}

export interface ControllerInput {
  mode: ReplayMode;
  stats: WindowStats;
  queueSize: number;
  softLimit: number;   // ex 3000
  hardLimit: number;   // ex 10000
}

export const REPLAY_BOUNDS = {
  minRps: 2,
  maxRps: 15,
  minBatch: 1,
  maxBatch: 30,
  minPauseMs: 25,
  maxPauseMs: 500,
} as const;

/** Décide la consigne {rps, batch, pause} en fonction de l'état et des stats. */
export function decide(input: ControllerInput): ControllerParams {
  const { mode, stats, queueSize, softLimit } = input;

  // En DEGRADED : conservateur. Charge minimale, batch unitaire, pauses longues.
  if (mode === "DEGRADED") {
    return { rps: REPLAY_BOUNDS.minRps, batchSize: 1, batchPauseMs: REPLAY_BOUNDS.maxPauseMs };
  }

  // En RECOVERY : ramp-up prudent. Taux intermédiaire, petits batches.
  if (mode === "RECOVERY") {
    return { rps: 3, batchSize: 5, batchPauseMs: 250 };
  }

  // NORMAL : adapte selon latence + queue pressure.
  let rps = 5;
  let batchSize = 15;
  let batchPauseMs = 50;

  // Boost throughput si santé excellente ET file conséquente
  const goodHealth = stats.successRate >= 0.95 && stats.p95LatencyMs < 500;
  const lowHealth = stats.p95LatencyMs > 1500 || stats.failureRate > 0.1;

  if (goodHealth) {
    if (queueSize > softLimit) {
      // Pression forte : on push à max
      rps = REPLAY_BOUNDS.maxRps;
      batchSize = REPLAY_BOUNDS.maxBatch;
      batchPauseMs = REPLAY_BOUNDS.minPauseMs;
    } else if (queueSize > softLimit / 3) {
      // Pression modérée : taux élevé
      rps = 10;
      batchSize = 25;
      batchPauseMs = 30;
    } else {
      // Pression faible : nominal +
      rps = 7;
      batchSize = 20;
      batchPauseMs = 40;
    }
  } else if (lowHealth) {
    // Santé limite — on freine sans entrer en DEGRADED (c'est le rôle du SM)
    rps = 3;
    batchSize = 5;
    batchPauseMs = 200;
  }
  // Sinon : valeurs nominales (5, 15, 50)

  return {
    rps: clamp(rps, REPLAY_BOUNDS.minRps, REPLAY_BOUNDS.maxRps),
    batchSize: clamp(batchSize, REPLAY_BOUNDS.minBatch, REPLAY_BOUNDS.maxBatch),
    batchPauseMs: clamp(batchPauseMs, REPLAY_BOUNDS.minPauseMs, REPLAY_BOUNDS.maxPauseMs),
  };
}

/** Smooth transition : limite la variation par tick pour éviter les jumps. */
export function smooth(prev: ControllerParams, target: ControllerParams): ControllerParams {
  return {
    rps: stepToward(prev.rps, target.rps, 1),
    batchSize: stepToward(prev.batchSize, target.batchSize, 5),
    batchPauseMs: stepToward(prev.batchPauseMs, target.batchPauseMs, 50),
  };
}

function stepToward(current: number, target: number, maxStep: number): number {
  if (target === current) return current;
  const diff = target - current;
  if (Math.abs(diff) <= maxStep) return target;
  return current + Math.sign(diff) * maxStep;
}

function clamp(v: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, v));
}

export const DEFAULT_PARAMS: ControllerParams = {
  rps: 5,
  batchSize: 15,
  batchPauseMs: 50,
};

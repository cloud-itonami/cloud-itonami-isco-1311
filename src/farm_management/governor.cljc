(ns farm-management.governor
  "FarmGovernor — the independent safety/traceability layer for
  the ISCO-08 1311 agricultural and forestry production manager actor.
  Wired as its own `:govern` node in `farm-management.actor`'s
  StateGraph, downstream of `:advise` — the Advisor has no notion of
  site provenance or anomaly/supply risk, so this MUST be a separate
  system able to reject a proposal (itonami actor pattern, per
  ADR-2607011000 / CLAUDE.md Actors section).

  `check` is a pure function of (request, context, proposal, store) ->
  verdict; it never mutates the store. The StateGraph's `:decide` node
  routes on the verdict:
    :hard? true                → :hold  (irreversible, no write)
    :escalate? true            → :request-approval (interrupt-before)
    otherwise                  → :commit

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. site provenance      — the request's site must be registered
                              and verified.
    2. no-actuation         — proposal :effect must be :propose.
  ESCALATION invariants (:escalate? true, ALWAYS human sign-off, per
  the README robotics-premise: crop/forest anomalies and significant
  supply decisions always require human sign-off):
    3. :op :flag-crop-anomaly (always escalates).
    4. :op :order-supplies with cost >= `supply-cost-threshold`.
    5. low confidence (< `confidence-floor`)."
  (:require [farm-management.store :as store]))

(def confidence-floor 0.6)
(def supply-cost-threshold 5000)
(def ^:private escalating-ops #{:flag-crop-anomaly})

(defn- hard-violations [{:keys [proposal]} site-record]
  (cond-> []
    (or (nil? site-record) (not (:verified? site-record)))
    (conj {:rule :no-site :detail "unregistered or unverified farm site"})

    (not= :propose (:effect proposal))
    (conj {:rule :no-actuation :detail "effect は :propose のみ許可（直接書込禁止）"})))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a
  `store` implementing `farm-management.store/Store`. Returns
  `{:ok? bool :violations [...] :confidence n :hard? bool :escalate? bool}`."
  [request context proposal store]
  (let [site-record (store/farm-site store (:site-id request))
        hard (hard-violations {:proposal proposal} site-record)
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        cost (get proposal :cost 0)
        high-cost-supply? (and (= :order-supplies (:op proposal))
                               (>= cost supply-cost-threshold))
        risky-op? (contains? escalating-ops (:op proposal))]
    {:ok? (and (not hard?) (not low?) (not risky-op?) (not high-cost-supply?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? risky-op? high-cost-supply?))}))

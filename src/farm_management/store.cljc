(ns farm-management.store
  "SSoT for the ISCO-08 1311 agricultural and forestry production
  manager actor. Store is a protocol injected into the
  `farm-management.actor` StateGraph — `MemStore` is the default,
  deterministic, zero-dep backend; a Datomic/kotoba-server-backed
  implementation can be swapped in without touching the actor or
  governor (itonami actor pattern, per ADR-2607011000 / CLAUDE.md
  Actors section).

  Domain:

    farm-site — a registered agricultural/forestry operation
                (:site-id, :name, :location, :verified?)
    record     — a committed operating record under a farm site
                 (harvest schedule, yield report, supply order,
                 anomaly report) — written ONLY via commit-record!,
                 never mutated in place
    ledger     — an append-only audit trail of every proposal/verdict/
                 disposition, regardless of outcome (commit or hold)")

(defprotocol Store
  (farm-site [s site-id])
  (records-of [s site-id])
  (ledger [s])
  (register-site! [s site])
  (commit-record! [s record])
  (append-ledger! [s fact]))

(defrecord MemStore [a]
  Store
  (farm-site [_ site-id] (get-in @a [:sites site-id]))
  (records-of [_ site-id] (filter #(= site-id (:site-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-site! [s site]
    (swap! a assoc-in [:sites (:site-id site)] site) s)
  (commit-record! [s record]
    (swap! a update :records (fnil conj []) record) s)
  (append-ledger! [s fact]
    (swap! a update :ledger (fnil conj []) fact) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge {:sites {} :records [] :ledger []} seed)))))
